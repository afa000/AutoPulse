package dev.autopulse.diagnostic;

import dev.autopulse.shared.ApiFailure;
import dev.autopulse.trip.TripCursor;
import dev.autopulse.vehicle.VehicleRegistry;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;

import static dev.autopulse.diagnostic.DiagnosticProjector.*;

@Service
public class DiagnosticQueries {
    public record Evidence(UUID firstSnapshotId,UUID lastPositiveSnapshotId,UUID lastAppliedSnapshotId) {}
    public record Mil(String outcome,Boolean illuminated) {}
    public record Transition(String kind,UUID snapshotId,Instant capturedAt) {}
    public record Event(UUID eventId,String vehicleId,String deviceId,String source,String ecuId,String category,String code,
                        String observationState,long cycleNumber,long positiveObservationCount,Instant firstObservedAt,Instant lastObservedAt,
                        Instant lastAppliedAt,String currentReadOutcome,String freshness,Mil mil,Evidence evidence,
                        DiagnosticCatalog.Definition definition,List<Transition> recentTransitions,Boolean hasEarlierTransitions) {}
    public record Latest(UUID snapshotId,Instant receivedAt,JsonNode snapshot,String freshness) {}
    public record Page(List<Event> items,String nextCursor,String generation,String processingState,Latest latestSnapshot) {}
    public record Detail(Event event,String processingState) {}
    private final JdbcTemplate jdbc;
    private final VehicleRegistry registry;
    private final DiagnosticCatalog catalog;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TripCursor cursors;
    private final TransactionTemplate tx;
    private static final String SELECT="""
        select e.*,s.device_id,s.source,s.ecu_id,s.outcome,s.mil_outcome,s.mil_value,
          s.captured_at as applied_at,s.snapshot_id as applied_snapshot,r.received_at as applied_receipt,d.public_id as device_public
        from diagnostic_event e join diagnostic_scope s on s.id=e.scope_id join device d on d.id=s.device_id
        join diagnostic_snapshot r on r.id=s.snapshot_id
        """;
    @Autowired public DiagnosticQueries(JdbcTemplate jdbc,VehicleRegistry registry,DiagnosticCatalog catalog,ObjectMapper mapper,PlatformTransactionManager transactions) {
        this(jdbc,registry,catalog,mapper,transactions,Clock.systemUTC());
    }
    public DiagnosticQueries(JdbcTemplate jdbc,VehicleRegistry registry,DiagnosticCatalog catalog,ObjectMapper mapper,PlatformTransactionManager transactions,Clock clock) {
        this.jdbc=jdbc;this.registry=registry;this.catalog=catalog;this.mapper=mapper;this.clock=clock;this.cursors=new TripCursor(clock);
        tx=new TransactionTemplate(transactions);tx.setReadOnly(true);tx.setTimeout(10);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Page list(String publicVehicle,int limit,String category,String state,String cursor) {
        if(limit<1 || limit>100 || category!=null && !DiagnosticValidator.CATEGORIES.contains(category)
                || state!=null && !Set.of("PRESENT","NOT_OBSERVED").contains(state)) throw invalid();
        return tx.execute(t->{
            UUID vehicle=vehicle(publicVehicle); long generation=generation(vehicle); Instant now=clock.instant();
            UUID binding=UUID.nameUUIDFromBytes(("diagnostic-v1:"+vehicle+":"+category+":"+state).getBytes(StandardCharsets.UTF_8));
            var position=cursor==null?null:cursors.decode(cursor,binding,limit);
            if(position!=null && position.generation()!=generation) throw new ApiFailure(409,"PAGE_CHANGED","Diagnostic history changed; restart paging",List.of());
            String sql=SELECT+" where e.vehicle_id=?"; var args=new ArrayList<Object>();args.add(vehicle);
            if(category!=null) { sql+=" and e.category=?";args.add(category); }
            if(state!=null) { sql+=" and e.state=?";args.add(state); }
            if(position!=null) { sql+=" and (e.last_observed_at,e.id)<(?,?)";args.add(stamp(position.startedAt()));args.add(position.tripId()); }
            sql+=" order by e.last_observed_at desc,e.id desc limit ?"; args.add(limit+1);
            var rows=jdbc.queryForList(sql,args.toArray());var items=rows.stream().limit(limit).map(r->event(r,publicVehicle,now,null,null)).toList();
            String next=null;
            if(rows.size()>limit) { var last=items.getLast();next=cursors.encode(binding,limit,new TripCursor.Position(generation,last.lastObservedAt(),last.eventId())); }
            return new Page(items,next,Long.toString(generation),processing(vehicle),latest(vehicle,now));
        });
    }
    public Detail detail(String publicVehicle,UUID id) {
        return tx.execute(t->{
            UUID vehicle=vehicle(publicVehicle);var rows=jdbc.queryForList(SELECT+" where e.vehicle_id=? and e.id=?",vehicle,id);
            if(rows.isEmpty()) throw DiagnosticStore.failure(404,"UNKNOWN_DIAGNOSTIC_EVENT");
            var transitions=jdbc.query("select kind,snapshot_id,captured_at from diagnostic_transition where event_id=? order by id desc limit 101",(r,n)->new Transition(r.getString(1),r.getObject(2,UUID.class),r.getTimestamp(3).toInstant()),id);
            return new Detail(event(rows.getFirst(),publicVehicle,clock.instant(),transitions.stream().limit(100).toList(),transitions.size()>100),processing(vehicle));
        });
    }
    private Event event(Map<String,Object> r,String vehicle,Instant now,List<Transition> recent,Boolean earlier) {
        String code=r.get("code").toString(),outcome=r.get("outcome").toString();
        return new Event((UUID)r.get("id"),vehicle,r.get("device_public").toString(),r.get("source").toString(),r.get("ecu_id").toString(),r.get("category").toString(),code,r.get("state").toString(),
                ((Number)r.get("cycle_number")).longValue(),((Number)r.get("positive_count")).longValue(),time(r,"first_observed_at"),time(r,"last_observed_at"),time(r,"applied_at"),outcome,
                freshness(time(r,"applied_at"),time(r,"applied_receipt"),outcome,now),new Mil(r.get("mil_outcome").toString(),(Boolean)r.get("mil_value")),
                new Evidence((UUID)r.get("first_snapshot_id"),(UUID)r.get("last_positive_snapshot_id"),(UUID)r.get("applied_snapshot")),catalog.lookup(code),recent,earlier);
    }
    private Latest latest(UUID vehicle,Instant now) {
        var rows=jdbc.queryForList("select * from diagnostic_snapshot where vehicle_id=? order by captured_at desc,device_id desc,boot_id desc,sequence_number desc limit 1",vehicle);
        if(rows.isEmpty()) return null;var r=rows.getFirst();
        var payload=mapper.readTree(r.get("payload").toString());
        boolean complete=true; for(var ecu:payload.get("ecus")) if(!ecu.get("outcome").asText().equals("COMPLETE")) complete=false;
        return new Latest((UUID)r.get("id"),time(r,"received_at"),payload,freshness(time(r,"captured_at"),time(r,"received_at"),complete?"COMPLETE":"UNKNOWN",now));
    }
    private static String freshness(Instant captured,Instant received,String outcome,Instant now) {
        if(captured.isAfter(now) || captured.isAfter(received)) return "CLOCK_WARNING";
        if(captured.plusSeconds(300).isBefore(now) || captured.plusSeconds(300).isBefore(received)) return "STALE";
        return outcome.equals("COMPLETE")?"FRESH":"UNKNOWN";
    }
    private long generation(UUID v) { var rows=jdbc.queryForList("select generation from diagnostic_vehicle where vehicle_id=?",Long.class,v);return rows.isEmpty()?0:rows.getFirst(); }
    private String processing(UUID v) {
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from diagnostic_work where vehicle_id=? and status='REVIEW')",Boolean.class,v))) return "REVIEW";
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from diagnostic_work where vehicle_id=? and status='PENDING')",Boolean.class,v))?"PENDING":"CURRENT";
    }
    private UUID vehicle(String publicVehicle) { return registry.vehicle(publicVehicle).orElseThrow(()->DiagnosticStore.failure(404,"UNKNOWN_VEHICLE")); }
    static ApiFailure invalid() { return new ApiFailure(400,"INVALID_QUERY","Invalid bounded diagnostic query",List.of()); }
}
