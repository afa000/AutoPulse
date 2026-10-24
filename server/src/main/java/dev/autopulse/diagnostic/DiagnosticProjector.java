package dev.autopulse.diagnostic;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** One ECU/category per ten-second transaction; committed queue survives process loss. */
@Service
public class DiagnosticProjector {
    public record Page(String state,UUID snapshotId,int nextPart) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    public DiagnosticProjector(JdbcTemplate jdbc,ObjectMapper mapper,PlatformTransactionManager transactions) {
        this.jdbc=jdbc;this.mapper=mapper;tx=new TransactionTemplate(transactions);tx.setTimeout(10);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public Page poll(Instant asOf) {
        // Only eligible vehicle heads may compete for a tick; blocked vehicles must not starve others.
        var vehicles=tx.execute(t->jdbc.queryForList("""
            select w.vehicle_id from diagnostic_work w join diagnostic_snapshot s on s.id=w.snapshot_id
            where w.status='PENDING' and w.next_attempt_at<=? and s.captured_at<=?
              and not exists(select 1 from diagnostic_work r where r.vehicle_id=w.vehicle_id and r.status='REVIEW')
              and not exists(
                select 1 from diagnostic_work p join diagnostic_snapshot earlier on earlier.id=p.snapshot_id
                where p.vehicle_id=w.vehicle_id and p.status='PENDING'
                  and (earlier.captured_at,earlier.boot_id,earlier.sequence_number,earlier.device_id,earlier.id)
                    < (s.captured_at,s.boot_id,s.sequence_number,s.device_id,s.id))
            order by w.next_attempt_at,w.vehicle_id,w.snapshot_id limit 1
            """,UUID.class,stamp(asOf),stamp(asOf)));
        return vehicles.isEmpty()?new Page("IDLE",null,0):processPage(vehicles.getFirst(),asOf);
    }
    public Page processPage(UUID vehicle,Instant asOf) {
        if(vehicle==null || asOf==null) throw new IllegalArgumentException("vehicle and explicit clock required");
        UUID[] claimed={null}; int[] part={-1};
        try { return tx.execute(t->page(vehicle,asOf,claimed,part)); }
        catch(RuntimeException failure) {
            if(claimed[0]==null) throw failure;
            // CAS prevents a delayed failure report from overwriting a newer successful page.
            return tx.execute(t->{
                // Use the same lock order as page processing, including when reporting a rolled-back attempt.
                jdbc.queryForObject("select vehicle_id from diagnostic_vehicle where vehicle_id=? for update",UUID.class,vehicle);
                int changed=jdbc.update("""
                    update diagnostic_work set failures=failures+1,
                      status=case when failures>=2 then 'REVIEW' else 'PENDING' end,
                      next_attempt_at=?,last_error=?
                    where snapshot_id=? and status='PENDING' and next_part=?
                    """,stamp(asOf.plusSeconds(60)),failure instanceof DataAccessException?"DATABASE_FAILURE":"PROCESSING_FAILURE",claimed[0],part[0]);
                if(changed>0) jdbc.update("update diagnostic_vehicle set generation=generation+1 where vehicle_id=?",vehicle);
                return new Page("RETRY_OR_REVIEW",claimed[0],part[0]);
            });
        }
    }
    private Page page(UUID vehicle,Instant asOf,UUID[] claimed,int[] part) {
        var lock=jdbc.queryForList("select vehicle_id from diagnostic_vehicle where vehicle_id=? for update skip locked",vehicle);
        if(lock.isEmpty()) return new Page("IDLE_OR_BUSY",null,0);
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from diagnostic_work where vehicle_id=? and status='REVIEW')",Boolean.class,vehicle)))
            return new Page("REVIEW",null,0);
        // Select the head before testing eligibility. Otherwise later evidence can advance the
        // watermark during backoff and turn the retry's accepted positives into historical-only data.
        var queue=jdbc.queryForList("""
            select s.*,w.next_part,w.next_attempt_at from diagnostic_work w join diagnostic_snapshot s on s.id=w.snapshot_id
            where w.vehicle_id=? and w.status='PENDING'
            order by s.captured_at,s.boot_id,s.sequence_number,s.device_id,s.id limit 1
            """,vehicle);
        if(queue.isEmpty()) return new Page("IDLE",null,0);
        var work=queue.getFirst();
        if(time(work,"next_attempt_at").isAfter(asOf) || time(work,"captured_at").isAfter(asOf)) return new Page("IDLE",null,0);
        UUID snapshot=(UUID)work.get("id"); int index=((Number)work.get("next_part")).intValue();
        claimed[0]=snapshot; part[0]=index;
        var payload=mapper.readTree(work.get("payload").toString()); var ecus=payload.get("ecus");
        if(index>=ecus.size()*3) throw new IllegalStateException("diagnostic work boundary invalid");
        var ecu=ecus.get(index/3); var category=ecu.get("categories").get(index%3);
        UUID device=(UUID)work.get("device_id"),boot=(UUID)work.get("boot_id"); String source=work.get("source").toString();
        String ecuId=ecu.get("ecuId").asText(),name=category.get("category").asText(),outcome=category.get("outcome").asText();
        var matches=jdbc.queryForList("select * from diagnostic_scope where vehicle_id=? and device_id=? and source=? and ecu_id=? and category=?",vehicle,device,source,ecuId,name);
        boolean created=matches.isEmpty(); UUID scope=created?UUID.randomUUID():(UUID)matches.getFirst().get("id");
        var prior=created?null:matches.getFirst();
        Instant captured=time(work,"captured_at"),received=time(work,"received_at"); long sequence=((Number)work.get("sequence_number")).longValue();
        String decision=!created && compare(captured,boot,sequence,prior)<=0?"HISTORICAL_ONLY"
                :captured.isAfter(received)?"CLOCK_WARNING":Duration.between(captured,received).compareTo(Duration.ofSeconds(300))>0?"STALE_HISTORICAL":"APPLIED";
        var codes=new TreeSet<String>(); for(var code:category.get("codes")) codes.add(code.asText());
        var active=created?List.<Map<String,Object>>of():jdbc.queryForList("select * from diagnostic_event where scope_id=? and state='PRESENT' order by code limit 101",scope);
        var activeCodes=new HashSet<String>(); active.forEach(e->activeCodes.add(e.get("code").toString()));
        var union=new HashSet<>(activeCodes); union.addAll(codes);
        if(decision.equals("APPLIED") && union.size()>100) {
            jdbc.update("update diagnostic_work set status='REVIEW',last_error='ACTIVE_CODE_BOUND' where snapshot_id=?",snapshot);
            jdbc.update("update diagnostic_vehicle set generation=generation+1 where vehicle_id=?",vehicle);
            return new Page("REVIEW",snapshot,index);
        }
        var mil=ecu.get("mil"); Object milValue=mil.get("illuminated").isNull()?null:mil.get("illuminated").asBoolean();
        if(created) jdbc.update("""
            insert into diagnostic_scope(id,vehicle_id,device_id,source,ecu_id,category,captured_at,boot_id,sequence_number,snapshot_id,outcome,mil_outcome,mil_value)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,scope,vehicle,device,source,ecuId,name,stamp(captured),boot,sequence,snapshot,outcome,mil.get("outcome").asText(),milValue);
        // Insert the immutable application first: an accidentally replayed page cannot double-count.
        jdbc.update("insert into diagnostic_application(snapshot_id,vehicle_id,scope_id,decision,processed_at) values(?,?,?,?,?)",snapshot,vehicle,scope,decision,stamp(asOf));
        if(!decision.equals("HISTORICAL_ONLY") && !created) jdbc.update("""
            update diagnostic_scope set captured_at=?,boot_id=?,sequence_number=?,snapshot_id=?,outcome=?,mil_outcome=?,mil_value=? where id=?
            """,stamp(captured),boot,sequence,snapshot,outcome,mil.get("outcome").asText(),milValue,scope);
        if(decision.equals("APPLIED")) {
            for(String code:codes) {
                var current=active.stream().filter(e->e.get("code").equals(code)).findFirst();
                UUID event;
                if(current.isPresent()) {
                    event=(UUID)current.get().get("id");
                    jdbc.update("update diagnostic_event set positive_count=positive_count+1,last_observed_at=?,last_positive_snapshot_id=? where id=?",stamp(captured),snapshot,event);
                    transition(vehicle,event,snapshot,"OBSERVED",captured);
                } else {
                    var previous=jdbc.queryForList("select cycle_number from diagnostic_event where scope_id=? and code=? order by cycle_number desc limit 1",scope,code);
                    long cycle=previous.isEmpty()?1:Math.addExact(((Number)previous.getFirst().get("cycle_number")).longValue(),1);
                    event=UUID.randomUUID();
                    jdbc.update("""
                        insert into diagnostic_event(id,vehicle_id,scope_id,category,code,cycle_number,state,positive_count,first_observed_at,last_observed_at,first_snapshot_id,last_positive_snapshot_id)
                        values(?,?,?,?,?,?,'PRESENT',1,?,?,?,?)
                        """,event,vehicle,scope,name,code,cycle,stamp(captured),stamp(captured),snapshot,snapshot);
                    transition(vehicle,event,snapshot,"PRESENT",captured);
                }
            }
            if(outcome.equals("COMPLETE")) for(var event:active) if(!codes.contains(event.get("code").toString())) {
                jdbc.update("update diagnostic_event set state='NOT_OBSERVED' where id=?",event.get("id"));
                transition(vehicle,(UUID)event.get("id"),snapshot,"NOT_OBSERVED",captured);
            }
        }
        int next=index+1;
        jdbc.update("update diagnostic_work set next_part=?,status=?,failures=0,last_error=null where snapshot_id=?",next,next==ecus.size()*3?"PROCESSED":"PENDING",snapshot);
        jdbc.update("update diagnostic_vehicle set generation=generation+1 where vehicle_id=?",vehicle);
        return new Page("PROGRESS",snapshot,next);
    }
    private void transition(UUID vehicle,UUID event,UUID snapshot,String kind,Instant captured) {
        jdbc.update("insert into diagnostic_transition(vehicle_id,event_id,snapshot_id,kind,captured_at) values(?,?,?,?,?)",vehicle,event,snapshot,kind,stamp(captured));
    }
    private static int compare(Instant at,UUID boot,long sequence,Map<String,Object> previous) {
        int time=at.compareTo(time(previous,"captured_at")); if(time!=0) return time;
        UUID p=(UUID)previous.get("boot_id"); int high=Long.compareUnsigned(boot.getMostSignificantBits(),p.getMostSignificantBits());
        if(high!=0) return high; int low=Long.compareUnsigned(boot.getLeastSignificantBits(),p.getLeastSignificantBits());
        return low!=0?low:Long.compare(sequence,((Number)previous.get("sequence_number")).longValue());
    }
    static Instant time(Map<String,Object> row,String key) { return ((Timestamp)row.get(key)).toInstant(); }
    static Timestamp stamp(Instant instant) { return Timestamp.from(instant); }
}
