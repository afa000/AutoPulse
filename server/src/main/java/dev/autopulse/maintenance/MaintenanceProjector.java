package dev.autopulse.maintenance;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static dev.autopulse.maintenance.MaintenanceService.stamp;

/** One durable work item per bounded transaction; no historical scan or SSE callbacks. */
@Service
public class MaintenanceProjector {
    public record Page(String state,Long workId) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MaintenanceService service;
    private final TemperatureRule thermal;
    private final TransactionTemplate tx;
    public MaintenanceProjector(JdbcTemplate jdbc,ObjectMapper mapper,MaintenanceService service,PlatformTransactionManager transactions) {
        this.jdbc=jdbc;this.mapper=mapper;this.service=service;thermal=new TemperatureRule(mapper);
        tx=new TransactionTemplate(transactions);tx.setTimeout(10);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public Page poll(Instant at) {
        var candidates=tx.execute(t->{
            // A review/backoff vehicle must not starve unrelated vehicles or overtake its own earlier work.
            var ready=jdbc.queryForList("""
                select w.vehicle_id from maintenance_work w where w.status='PENDING' and w.next_attempt_at<=?
                  and not exists(select 1 from maintenance_work r where r.vehicle_id=w.vehicle_id and r.status='REVIEW')
                  and not exists(select 1 from maintenance_work p where p.vehicle_id=w.vehicle_id and p.status='PENDING' and p.id<w.id)
                order by w.next_attempt_at,w.id limit 1
                """,UUID.class,stamp(at));
            return ready.isEmpty()?jdbc.queryForList("""
                select v.vehicle_id from maintenance_vehicle v where v.next_evaluation_at<=?
                  and not exists(select 1 from maintenance_work w where w.vehicle_id=v.vehicle_id and w.status<>'PROCESSED')
                order by v.next_evaluation_at,v.vehicle_id limit 1
                """,UUID.class,stamp(at)):ready;
        });
        return candidates.isEmpty()?new Page("IDLE",null):processPage(candidates.getFirst(),at);
    }
    public Page processPage(UUID vehicle,Instant at) {
        if(vehicle==null||at==null)throw new IllegalArgumentException("vehicle and explicit clock required");
        Long[] claimed={null};
        try { return tx.execute(t->{
            if(jdbc.queryForList("select vehicle_id from maintenance_vehicle where vehicle_id=? for update skip locked",vehicle).isEmpty()) return new Page("IDLE_OR_BUSY",null);
            if(jdbc.queryForObject("select exists(select 1 from maintenance_work where vehicle_id=? and status='REVIEW')",Boolean.class,vehicle))return new Page("REVIEW",null);
            var work=jdbc.queryForList("select * from maintenance_work where vehicle_id=? and status='PENDING' order by id limit 1",vehicle);
            if(!work.isEmpty()&&((Timestamp)work.getFirst().get("next_attempt_at")).toInstant().isAfter(at))return new Page("IDLE",null);
            if(work.isEmpty()) {
                Instant due=jdbc.queryForObject("select next_evaluation_at from maintenance_vehicle where vehicle_id=?",Timestamp.class,vehicle).toInstant();
                if(due.isAfter(at))return new Page("IDLE",null);
                evaluateOil(vehicle,mapper.createObjectNode().put("reason","CALENDAR"),at);
                jdbc.update("update maintenance_vehicle set next_evaluation_at=?,generation=generation+1 where vehicle_id=?",stamp(at.plusSeconds(60)),vehicle);
                return new Page("CALENDAR",null);
            }
            var row=work.getFirst();long id=((Number)row.get("id")).longValue();claimed[0]=id;
            var evidence=mapper.readTree(row.get("evidence").toString());String kind=row.get("kind").toString(),decision,version;
            switch(kind) {
                case "MANUAL" -> { decision=evaluateOil(vehicle,evidence,at);version=OilRule.VERSION; }
                case "DIAGNOSTIC" -> { decision=diagnostic(vehicle,evidence,at);version="confirmed-diagnostic-v1"; }
                case "TEMPERATURE" -> { decision=temperature(vehicle,evidence,at);version=TemperatureRule.VERSION; }
                default -> throw new IllegalStateException("unknown maintenance work");
            }
            jdbc.update("insert into maintenance_rule_decision(work_id,vehicle_id,rule_version,decision,recorded_at,evidence) values(?,?,?,?,?,cast(? as jsonb))",id,vehicle,version,decision,stamp(at),evidence.toString());
            jdbc.update("update maintenance_work set status='PROCESSED',processed_at=?,last_error=null where id=?",stamp(at),id);
            jdbc.update("update maintenance_vehicle set generation=generation+1 where vehicle_id=?",vehicle);
            return new Page("PROGRESS",id);
        }); } catch(RuntimeException failure) {
            if(claimed[0]==null)throw failure;
            return tx.execute(t->{
                // Take the same lock before reporting failure so delayed reports cannot overwrite success.
                jdbc.queryForObject("select vehicle_id from maintenance_vehicle where vehicle_id=? for update",UUID.class,vehicle);
                int changed=jdbc.update("update maintenance_work set failures=failures+1,status=case when failures>=2 then 'REVIEW' else 'PENDING' end,next_attempt_at=?,last_error=? where id=? and status='PENDING'",stamp(at.plusSeconds(60)),failure instanceof DataAccessException?"DATABASE_FAILURE":"PROCESSING_FAILURE",claimed[0]);
                if(changed>0)jdbc.update("update maintenance_vehicle set generation=generation+1 where vehicle_id=?",vehicle);
                return new Page("RETRY_OR_REVIEW",claimed[0]);
            });
        }
    }
    private String evaluateOil(UUID v,JsonNode cause,Instant at) {
        var evidence=(ObjectNode)(cause.has("inputs")?service.assessmentFromInputs(cause.get("inputs"),at):service.assessment(v,at));String state=evidence.get("state").asText();
        var previous=jdbc.queryForList("select * from maintenance_recommendation where vehicle_id=? and kind='OIL_FILTER' and state in ('OPEN','ACKNOWLEDGED') limit 1",v);
        var basis=evidence.get("service");var profile=evidence.get("profile");
        String basisKey=basis==null||basis.isNull()||profile==null||profile.isNull()?null:OilRule.VERSION+":"+basis.get("entryId").asText()+":"+basis.get("revision").asLong()+":"+profile.get("revision").asLong();
        var condition=(ObjectNode)mapper.readTree(jdbc.queryForObject("select oil_state::text from maintenance_vehicle where vehicle_id=?",String.class,v));
        String priorBasis=condition.path("basis").isNull()?null:condition.path("basis").asText();
        boolean basisChanged=!Objects.equals(basisKey,priorBasis);long cycle=basisChanged?0:condition.path("cycle").asLong();
        boolean wasDue=!basisChanged&&condition.path("active").asBoolean();
        if(state.equals("DUE")&&!wasDue)cycle=Math.addExact(cycle,1);
        // UNKNOWN cannot manufacture recovery of a known due interval; only a qualified NOT_DUE or changed basis ends it.
        boolean active=state.equals("DUE")||state.equals("UNKNOWN")&&wasDue;
        condition.put("active",active).put("cycle",cycle);if(basisKey==null)condition.putNull("basis");else condition.put("basis",basisKey);
        jdbc.update("update maintenance_vehicle set oil_state=cast(? as jsonb) where vehicle_id=?",condition.toString(),v);
        String key=basisKey==null?null:basisKey+":cycle:"+cycle;
        if(!previous.isEmpty()) {
            var old=previous.getFirst();boolean changed=!Objects.equals(key,old.get("semantic_key"));
            if(changed||state.equals("NOT_DUE")) {
                boolean completed=false;
                if("SERVICE".equals(cause.path("reason").asText())&&!basis.isNull()&&basis.get("entryId").asText().equals(cause.path("entryId").asText())) {
                    var oldEvidence=mapper.readTree(old.get("evidence").toString());
                    completed=Instant.parse(basis.get("observedAt").asText()).isAfter(Instant.parse(oldEvidence.get("service").get("observedAt").asText()));
                }
                evidence.set("evaluationCause",cause.deepCopy());service.transition(v,(UUID)old.get("id"),completed?"COMPLETED":"EXPIRED",completed?"MATCHING_SERVICE_RECORDED":"INPUT_OR_APPLICABILITY_CHANGED",evidence,at);
            }
        }
        if(state.equals("DUE")) open(v,"OIL_FILTER",key,OilRule.VERSION,evidence,at);
        return state;
    }
    private String diagnostic(UUID v,JsonNode input,Instant at) {
        String key="confirmed-diagnostic-v1:"+input.get("eventId").asText();
        var evidence=((ObjectNode)input).deepCopy().put("ruleVersion","confirmed-diagnostic-v1")
                .put("explanation","A confirmed-category code was observed. Investigate the evidence; this is not proof of a failed component, repair or driving safety.");
        evidence.put("observationRule","CONFIRMED category only; one advisory per diagnostic event cycle; source labels are retained.");
        if(input.get("kind").asText().equals("NOT_OBSERVED")) { expire(v,key,"QUALIFIED_ABSENCE_NOT_REPAIR",evidence,at);return "EXPIRE"; }
        open(v,"DIAGNOSTIC",key,"confirmed-diagnostic-v1",evidence,at);return "OBSERVED";
    }
    private String temperature(UUID v,JsonNode input,Instant at) {
        UUID device=UUID.fromString(input.get("deviceId").asText()),boot=UUID.fromString(input.get("bootId").asText());
        Instant captured=Instant.parse(input.get("capturedAt").asText());BigDecimal sequence=input.get("sequenceNumber").decimalValue();
        var rows=jdbc.queryForList("select * from maintenance_thermal_scope where vehicle_id=? and device_id=? and source='SIMULATED'",v,device);
        JsonNode prior=null;
        if(!rows.isEmpty()) {
            var old=rows.getFirst();int compare=captured.compareTo(((Timestamp)old.get("captured_at")).toInstant());
            // UUID ordering is unsigned, matching PostgreSQL and diagnostic watermarks.
            if(captured.equals(((Timestamp)old.get("captured_at")).toInstant())) {
                UUID oldBoot=(UUID)old.get("boot_id");compare=Long.compareUnsigned(boot.getMostSignificantBits(),oldBoot.getMostSignificantBits());
                if(compare==0)compare=Long.compareUnsigned(boot.getLeastSignificantBits(),oldBoot.getLeastSignificantBits());
                if(compare==0)compare=sequence.compareTo((BigDecimal)old.get("sequence_number"));
            }
            if(compare<=0)return "HISTORICAL_ONLY";
            prior=mapper.readTree(old.get("state").toString());
        }
        var result=thermal.apply(prior,input);var state=result.state();
        jdbc.update("insert into maintenance_thermal_scope(vehicle_id,device_id,source,captured_at,boot_id,sequence_number,state) values(?,?,'SIMULATED',?,?,?,cast(? as jsonb)) on conflict(vehicle_id,device_id,source) do update set captured_at=excluded.captured_at,boot_id=excluded.boot_id,sequence_number=excluded.sequence_number,state=excluded.state",v,device,stamp(captured),boot,sequence,state.toString());
        String key=TemperatureRule.VERSION+":"+device+":"+state.get("cycle").asLong();
        var evidence=((ObjectNode)input).deepCopy().put("ruleVersion",TemperatureRule.VERSION).put("synthetic",true)
                .put("thresholdC","110").put("sustainSeconds",30).put("maxGapSeconds",5).put("recoveryC","95").put("recoverySeconds",2)
                .put("explanation","SIMULATED temperature condition under synthetic test thresholds, not Hyundai specifications or a physical overheating diagnosis. Cause and driving safety are unknown.");
        evidence.set("condition",state.deepCopy());
        if(result.action().equals("OPEN"))open(v,"TEMPERATURE",key,TemperatureRule.VERSION,evidence,at);
        if(result.action().equals("RECOVER"))expire(v,key,"SYNTHETIC_QUALIFIED_RECOVERY_NOT_REPAIR",evidence,at);
        return result.action();
    }
    private void open(UUID v,String kind,String key,String version,JsonNode evidence,Instant at) {
        UUID id=UUID.randomUUID();int inserted=jdbc.update("insert into maintenance_recommendation(id,vehicle_id,kind,semantic_key,rule_version,revision,state,generated_at,evidence) values(?,?,?,?,?,1,'OPEN',?,cast(? as jsonb)) on conflict(vehicle_id,semantic_key) do nothing",id,v,kind,key,version,stamp(at),evidence.toString());
        if(inserted==1)jdbc.update("insert into maintenance_recommendation_revision(recommendation_id,vehicle_id,revision,state,recorded_at,reason,evidence) values(?,?,1,'OPEN',?,'RULE_OBSERVATION',cast(? as jsonb))",id,v,stamp(at),evidence.toString());
    }
    private void expire(UUID v,String key,String reason,JsonNode evidence,Instant at) {
        var ids=jdbc.queryForList("select id from maintenance_recommendation where vehicle_id=? and semantic_key=? and state in ('OPEN','ACKNOWLEDGED')",UUID.class,v,key);
        if(!ids.isEmpty())service.transition(v,ids.getFirst(),"EXPIRED",reason,evidence,at);
    }
}
