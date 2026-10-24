package dev.autopulse.maintenance;

import dev.autopulse.shared.ApiFailure;
import dev.autopulse.trip.TripCursor;
import dev.autopulse.vehicle.VehicleRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static dev.autopulse.maintenance.MaintenanceInput.*;

@Service
public class MaintenanceService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MaintenanceInput input;
    private final VehicleRegistry registry;
    private final OilRule oil;
    private final TransactionTemplate tx,read;
    private final Clock clock;
    private final TripCursor cursors;
    @Autowired public MaintenanceService(JdbcTemplate jdbc,ObjectMapper mapper,MaintenanceInput input,VehicleRegistry registry,OilRule oil,PlatformTransactionManager transactions) {
        this(jdbc,mapper,input,registry,oil,transactions,Clock.systemUTC());
    }
    public MaintenanceService(JdbcTemplate jdbc,ObjectMapper mapper,MaintenanceInput input,VehicleRegistry registry,OilRule oil,PlatformTransactionManager transactions,Clock clock) {
        this.jdbc=jdbc;this.mapper=mapper;this.input=input;this.registry=registry;this.oil=oil;this.clock=clock;cursors=new TripCursor(clock);
        tx=new TransactionTemplate(transactions);tx.setTimeout(10);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read=new TransactionTemplate(transactions);read.setTimeout(10);read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    UUID vehicle(String publicId) { return registry.vehicle(publicId).orElseThrow(()->new ApiFailure(404,"UNKNOWN_VEHICLE","Vehicle not registered",List.of())); }
    static Timestamp stamp(Instant at) { return Timestamp.from(at); }
    Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    void lock(UUID vehicle) {
        jdbc.update("insert into maintenance_vehicle(vehicle_id) values(?) on conflict do nothing",vehicle);
        jdbc.queryForObject("select vehicle_id from maintenance_vehicle where vehicle_id=? for update",UUID.class,vehicle);
    }
    private JsonNode command(String publicId,String operation,ObjectNode p,Function<UUID,JsonNode> write) {
        UUID request=input.request(p);input.revision(p);
        return tx.execute(t->{
            UUID vehicle=vehicle(publicId);lock(vehicle);
            var previous=jdbc.queryForList("select operation,payload,receipt from maintenance_command where vehicle_id=? and request_id=?",vehicle,request);
            if(!previous.isEmpty()) {
                var old=previous.getFirst();if(!old.get("operation").equals(operation)||!mapper.readTree(old.get("payload").toString()).equals(p)) throw conflict("IDENTITY_CONFLICT");
                return mapper.readTree(old.get("receipt").toString());
            }
            JsonNode receipt=write.apply(vehicle);
            jdbc.update("insert into maintenance_command(vehicle_id,request_id,operation,payload,receipt) values(?,?,?,cast(? as jsonb),cast(? as jsonb))",vehicle,request,operation,p.toString(),receipt.toString());
            jdbc.update("update maintenance_vehicle set generation=generation+1 where vehicle_id=?",vehicle);
            return receipt;
        });
    }
    public JsonNode profile(String publicId) { return read.execute(t->{ UUID v=vehicle(publicId);var p=profile(v);var result=mapper.createObjectNode();result.put("revision",profileRevision(v));result.set("profile",p==null?mapper.nullNode():p);result.set("oilAssessment",assessment(v,now()));return result; }); }
    JsonNode profile(UUID vehicle) {
        var p=jdbc.queryForList("select payload from maintenance_profile where vehicle_id=? order by revision desc limit 1",vehicle);
        return p.isEmpty()?null:mapper.readTree(p.getFirst().get("payload").toString());
    }
    long profileRevision(UUID vehicle) { return jdbc.queryForObject("select coalesce(max(revision),0) from maintenance_profile where vehicle_id=?",Long.class,vehicle); }
    public JsonNode saveProfile(String publicId,ObjectNode p) {
        input.shape(p,"requestId expectedRevision applicability usageSchedule distanceUnit timeZone normalOilSpecificationConfirmed");
        input.choice(p,"applicability","UNCONFIRMED","US_2021_VENUE_G16");input.choice(p,"usageSchedule","UNSET","NORMAL","SEVERE");input.choice(p,"distanceUnit","MI","KM");
        String zone=input.text(p,"timeZone");if(zone.length()>80||!ZoneId.getAvailableZoneIds().contains(zone)||!p.get("normalOilSpecificationConfirmed").isBoolean()) throw invalid();
        return command(publicId,"PROFILE",p,v->{
            long revision=profileRevision(v);if(revision!=input.revision(p)) throw conflict("REVISION_CONFLICT");
            var stored=p.deepCopy();stored.remove(List.of("requestId","expectedRevision"));stored.put("revision",revision+1);
            jdbc.update("insert into maintenance_profile(vehicle_id,revision,recorded_at,payload) values(?,?,?,cast(? as jsonb))",v,revision+1,stamp(now()),stored.toString());
            enqueue(v,"PROFILE",null);return receipt(v,revision+1);
        });
    }
    public JsonNode saveEntry(String publicId,UUID id,ObjectNode p) {
        input.shape(p,"requestId expectedRevision kind serviceType observedAt odometerValue odometerUnit notes correctionReason");
        String kind=input.choice(p,"kind","ODOMETER","SERVICE");
        String type=input.bounded(p,"serviceType",32,true);if(kind.equals("SERVICE")?!"OIL_FILTER".equals(type):type!=null) throw invalid();
        Instant observed=input.time(p,"observedAt");BigDecimal km=input.odometer(p);input.bounded(p,"notes",500,true);
        String correction=input.bounded(p,"correctionReason",200,true);if(id==null?correction!=null:correction==null) throw invalid();
        return command(publicId,"ENTRY:"+(id==null?"NEW":id),p,v->{
            if(observed.isAfter(now())) throw invalid();
            Map<String,Object> previous=id==null?null:entry(v,id);
            long revision=previous==null?0:((Number)previous.get("revision")).longValue();if(revision!=input.revision(p)) throw conflict("REVISION_CONFLICT");
            if(previous!=null&&!previous.get("kind").equals(kind)) throw invalid();
            UUID entryId=id==null?UUID.randomUUID():id;
            // Adjacent indexed maxima/minima validate the entire resulting chronology under the vehicle lock.
            var lower=jdbc.queryForList("select odometer_km from maintenance_entry where vehicle_id=? and id<>? and observed_at<=? order by observed_at desc,odometer_km desc limit 1",v,entryId,stamp(observed));
            var upper=jdbc.queryForList("select odometer_km from maintenance_entry where vehicle_id=? and id<>? and observed_at>=? order by observed_at,odometer_km limit 1",v,entryId,stamp(observed));
            if(!lower.isEmpty()&&km.compareTo((BigDecimal)lower.getFirst().get("odometer_km"))<0
                    ||!upper.isEmpty()&&km.compareTo((BigDecimal)upper.getFirst().get("odometer_km"))>0) throw conflict("ODOMETER_HISTORY_CONFLICT");
            var stored=p.deepCopy();stored.remove(List.of("requestId","expectedRevision"));stored.put("entryId",entryId.toString()).put("revision",revision+1).put("observedAt",observed.toString());
            if(previous==null) jdbc.update("insert into maintenance_entry(id,vehicle_id,revision,kind,service_type,observed_at,odometer_km,payload) values(?,?,?,?,?,?,?,cast(? as jsonb))",entryId,v,revision+1,kind,type,stamp(observed),km,stored.toString());
            else jdbc.update("update maintenance_entry set revision=?,service_type=?,observed_at=?,odometer_km=?,payload=cast(? as jsonb) where id=?",revision+1,type,stamp(observed),km,stored.toString(),entryId);
            jdbc.update("insert into maintenance_entry_revision(entry_id,vehicle_id,revision,recorded_at,payload) values(?,?,?,?,cast(? as jsonb))",entryId,v,revision+1,stamp(now()),stored.toString());
            enqueue(v,previous!=null?"CORRECTION":kind.equals("SERVICE")?"SERVICE":"ODOMETER",entryId);
            return receipt(entryId,revision+1);
        });
    }
    private ObjectNode receipt(UUID id,long revision) { return mapper.createObjectNode().put("id",id.toString()).put("revision",revision).put("recordedAt",now().toString()); }
    private void enqueue(UUID vehicle,String reason,UUID entry) {
        var e=mapper.createObjectNode().put("reason",reason);if(entry==null)e.putNull("entryId");else e.put("entryId",entry.toString());
        // Freeze accepted manual inputs; worker downtime must not replace an earlier basis with a later service.
        e.set("inputs",assessment(vehicle,now()));
        jdbc.update("insert into maintenance_work(vehicle_id,kind,evidence) values(?,'MANUAL',cast(? as jsonb))",vehicle,e.toString());
        jdbc.update("update maintenance_vehicle set next_evaluation_at=? where vehicle_id=?",stamp(now().plusSeconds(60)),vehicle);
    }
    Map<String,Object> entry(UUID v,UUID id) {
        var rows=jdbc.queryForList("select * from maintenance_entry where vehicle_id=? and id=?",v,id);
        if(rows.isEmpty()) throw new ApiFailure(404,"UNKNOWN_MAINTENANCE_RECORD","Record not found",List.of());return rows.getFirst();
    }
    JsonNode assessment(UUID vehicle,Instant at) {
        var service=jdbc.queryForList("select payload from maintenance_entry where vehicle_id=? and kind='SERVICE' and service_type='OIL_FILTER' and observed_at<=? order by observed_at desc,id desc limit 1",vehicle,stamp(at));
        var current=jdbc.queryForList("select payload from maintenance_entry where vehicle_id=? and observed_at<=? order by observed_at desc,odometer_km desc,id desc limit 1",vehicle,stamp(at));
        return oil.evaluate(profile(vehicle),service.isEmpty()?null:mapper.readTree(service.getFirst().get("payload").toString()),current.isEmpty()?null:mapper.readTree(current.getFirst().get("payload").toString()),at);
    }
    JsonNode assessmentFromInputs(JsonNode inputs,Instant at) {
        var profile=inputs.get("profile");var service=inputs.get("service");var current=inputs.get("currentOdometer");
        return oil.evaluate(profile.isNull()?null:profile,service.isNull()?null:service,current.isNull()?null:current,at);
    }
    public JsonNode detail(String publicId,UUID id,boolean recommendation) {
        return read.execute(t->{UUID v=vehicle(publicId);var result=mapper.createObjectNode();
            if(recommendation) result.set("item",recommendation(v,id));else result.set("item",mapper.readTree(entry(v,id).get("payload").toString()));
            String table=recommendation?"maintenance_recommendation_revision":"maintenance_entry_revision",column=recommendation?"recommendation_id":"entry_id";
            var history=jdbc.queryForList("select * from "+table+" where vehicle_id=? and "+column+"=? order by revision desc limit 101",v,id);
            var array=result.putArray("recentRevisions");history.stream().limit(100).forEach(r->{
                var node=mapper.createObjectNode().put("revision",((Number)r.get("revision")).longValue()).put("recordedAt",((Timestamp)r.get("recorded_at")).toInstant().toString());
                node.set("evidence",mapper.readTree(r.get(recommendation?"evidence":"payload").toString()));
                if(recommendation)node.put("state",r.get("state").toString()).put("reason",r.get("reason").toString());array.add(node);
            });return result.put("hasEarlierRevisions",history.size()>100);
        });
    }
    ObjectNode recommendation(UUID v,UUID id) {
        var rows=jdbc.queryForList("select * from maintenance_recommendation where vehicle_id=? and id=?",v,id);
        if(rows.isEmpty()) throw new ApiFailure(404,"UNKNOWN_RECOMMENDATION","Recommendation not found",List.of());return recommendation(rows.getFirst());
    }
    ObjectNode recommendation(Map<String,Object> r) {
        var n=mapper.createObjectNode().put("id",r.get("id").toString()).put("kind",r.get("kind").toString()).put("state",r.get("state").toString())
                .put("ruleVersion",r.get("rule_version").toString()).put("revision",((Number)r.get("revision")).longValue()).put("generatedAt",((Timestamp)r.get("generated_at")).toInstant().toString());
        var frozen=mapper.readTree(r.get("evidence").toString());n.set("evidence",frozen);
        UUID vehicle=(UUID)r.get("vehicle_id");String kind=r.get("kind").toString();var observation=mapper.createObjectNode().put("freshness","UNKNOWN");
        if(kind.equals("TEMPERATURE")) {
            var scopes=jdbc.queryForList("select captured_at,state from maintenance_thermal_scope where vehicle_id=? and device_id=? and source='SIMULATED'",vehicle,UUID.fromString(frozen.get("deviceId").asText()));
            if(!scopes.isEmpty()) {var scope=scopes.getFirst();var state=mapper.readTree(scope.get("state").toString());Instant capture=((Timestamp)scope.get("captured_at")).toInstant();
                observation.put("capturedAt",capture.toString()).put("source","SIMULATED").put("synthetic",true).put("activeCondition",state.get("active").asBoolean());
                observation.put("freshness",state.path("uncertain").asBoolean()?"UNKNOWN":capture.isBefore(now().minusSeconds(300))?"STALE":"CURRENT");
            }
        } else if(kind.equals("DIAGNOSTIC")) {
            var scopes=jdbc.queryForList("select s.captured_at,s.outcome,s.mil_outcome,s.mil_value,s.source,r.received_at from diagnostic_event e join diagnostic_scope s on s.id=e.scope_id join diagnostic_snapshot r on r.id=s.snapshot_id where e.vehicle_id=? and e.id=?",vehicle,UUID.fromString(frozen.get("eventId").asText()));
            if(!scopes.isEmpty()) {var scope=scopes.getFirst();Instant capture=((Timestamp)scope.get("captured_at")).toInstant(),receipt=((Timestamp)scope.get("received_at")).toInstant();
                String outcome=scope.get("outcome").toString();observation.put("capturedAt",capture.toString()).put("source",scope.get("source").toString()).put("readOutcome",outcome).put("milOutcome",scope.get("mil_outcome").toString());
                if(scope.get("mil_value")==null)observation.putNull("milIlluminated");else observation.put("milIlluminated",(Boolean)scope.get("mil_value"));
                observation.put("freshness",capture.isAfter(receipt)?"CLOCK_WARNING":!outcome.equals("COMPLETE")?"UNKNOWN":capture.isBefore(now().minusSeconds(300))?"STALE":"CURRENT");
            }
        } else observation.set("oilAssessment",assessment(vehicle,now()));
        n.set("currentObservation",observation);return n;
    }
    public JsonNode action(String publicId,UUID id,ObjectNode p) {
        input.shape(p,"requestId expectedRevision state");String state=input.choice(p,"state","ACKNOWLEDGED","DISMISSED","COMPLETED");
        return command(publicId,"ACTION:"+id,p,v->{
            var old=recommendation(v,id);if(old.get("revision").asLong()!=input.revision(p)) throw conflict("REVISION_CONFLICT");
            String before=old.get("state").asText();if(!Set.of("OPEN","ACKNOWLEDGED").contains(before)||before.equals(state)
                    ||state.equals("COMPLETED")&&old.get("kind").asText().equals("OIL_FILTER")) throw conflict("TRANSITION_NOT_ALLOWED");
            transition(v,id,state,"USER_WORKFLOW_ONLY",old.get("evidence"),now());return receipt(id,old.get("revision").asLong()+1);
        });
    }
    void transition(UUID vehicle,UUID id,String state,String reason,JsonNode evidence,Instant at) {
        long revision=jdbc.queryForObject("select revision from maintenance_recommendation where vehicle_id=? and id=?",Long.class,vehicle,id)+1;
        jdbc.update("update maintenance_recommendation set revision=?,state=?,evidence=cast(? as jsonb) where vehicle_id=? and id=?",revision,state,evidence.toString(),vehicle,id);
        jdbc.update("insert into maintenance_recommendation_revision(recommendation_id,vehicle_id,revision,state,recorded_at,reason,evidence) values(?,?,?,?,?,?,cast(? as jsonb))",id,vehicle,revision,state,stamp(at),reason,evidence.toString());
    }
    public JsonNode list(String publicId,boolean recommendations,int limit,String cursor) {
        if(limit<1||limit>100) throw invalid();
        return read.execute(t->{UUID v=vehicle(publicId);long generation=generation(v);String type=recommendations?"recommendations":"entries";
            UUID binding=UUID.nameUUIDFromBytes(("maintenance-v1:"+v+":"+type).getBytes(StandardCharsets.UTF_8));
            var pos=cursor==null?null:cursors.decode(cursor,binding,limit);if(pos!=null&&pos.generation()!=generation) throw conflict("PAGE_CHANGED");
            String time=recommendations?"generated_at":"observed_at",table=recommendations?"maintenance_recommendation":"maintenance_entry";
            var args=new ArrayList<Object>();args.add(v);String where=" where vehicle_id=?";
            if(pos!=null) { where+=" and ("+time+",id)<(?,?)";args.add(stamp(pos.startedAt()));args.add(pos.tripId()); }args.add(limit+1);
            var rows=jdbc.queryForList("select * from "+table+where+" order by "+time+" desc,id desc limit ?",args.toArray());
            var result=mapper.createObjectNode().put("generation",Long.toString(generation));var items=result.putArray("items");
            rows.stream().limit(limit).forEach(r->items.add(recommendations?recommendation(r):mapper.readTree(r.get("payload").toString())));
            if(rows.size()>limit) {var last=rows.get(limit-1);result.put("nextCursor",cursors.encode(binding,limit,new TripCursor.Position(generation,((Timestamp)last.get(time)).toInstant(),(UUID)last.get("id"))));}else result.putNull("nextCursor");
            result.put("processingState",processing(v));if(recommendations)result.set("oilAssessment",assessment(v,now()));return result;
        });
    }
    long generation(UUID v) { return jdbc.queryForObject("select coalesce((select generation from maintenance_vehicle where vehicle_id=?),0)",Long.class,v); }
    String processing(UUID v) { return jdbc.queryForObject("select case when exists(select 1 from maintenance_work where vehicle_id=? and status='REVIEW') then 'REVIEW' when exists(select 1 from maintenance_work where vehicle_id=? and status='PENDING') then 'PENDING' else 'CURRENT' end",String.class,v,v); }
}
