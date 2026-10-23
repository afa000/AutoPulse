package dev.autopulse.trip;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Single-page retry boundary shared by explicit calls and opt-in scheduling. */
@Service
public class TripProcessingWorker {
    public enum Stage { FORWARD, PLAN, REPLAY, PUBLISH, FINALIZE }
    public record Status(Stage stage,String state,long revision,Instant nextAttemptAt,
                         Instant leaseUntil,Instant lastAttemptAt,Instant lastFinishedAt,Instant lastFailureAt,
                         int consecutiveFailures,long totalFailures,String errorCode,String sqlState,String lastResult) {}
    public record Page(String state,String result,int visited) {}
    private record Claim(UUID token,Page stopped) {}
    private record Outcome(String result,int visited) {}
    private static final Duration LEASE=Duration.ofMinutes(2);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate control;
    private final Clock clock;
    private final TripProjector projector;
    private final TripCorrectionPlanner planner;
    private final TripCorrectionReplayer replayer;
    private final TripCorrectionPublisher publisher;
    private final TripFinalizer finalizer;

    @Autowired
    public TripProcessingWorker(JdbcTemplate jdbc,PlatformTransactionManager transactions,TripProjector projector,
            TripCorrectionPlanner planner,TripCorrectionReplayer replayer,TripCorrectionPublisher publisher,TripFinalizer finalizer) {
        this(jdbc,transactions,projector,planner,replayer,publisher,finalizer,Clock.systemUTC());
    }

    public TripProcessingWorker(JdbcTemplate jdbc,PlatformTransactionManager transactions,TripProjector projector,
            TripCorrectionPlanner planner,TripCorrectionReplayer replayer,TripCorrectionPublisher publisher,TripFinalizer finalizer,Clock clock) {
        this.jdbc=jdbc; this.projector=projector; this.planner=planner; this.replayer=replayer; this.publisher=publisher; this.finalizer=finalizer;
        this.clock=java.util.Objects.requireNonNull(clock);
        control=new TransactionTemplate(transactions); control.setTimeout(10);
    }

    public Page runPage(UUID vehicle,Stage stage,Instant asOf,int limit) {
        if (vehicle==null || stage==null || asOf==null || limit<1 || limit>100) throw new IllegalArgumentException("vehicle/stage/time and limit 1..100 required");
        independent();
        var claim=control.execute(tx -> claim(vehicle,stage,clock.instant()));
        if (claim.stopped()!=null) return claim.stopped();
        Outcome outcome;
        try { outcome=step(vehicle,stage,asOf,limit); }
        catch (RuntimeException failure) {
            String sqlState=sqlState(failure);
            boolean retryable=failure instanceof TransientDataAccessException
                    || failure instanceof DataAccessException && sqlState!=null && (sqlState.startsWith("08") || java.util.Set.of("40001","40P01","55P03","57014").contains(sqlState));
            String code=retryable ? "TRANSIENT_DATABASE_FAILURE"
                    : failure instanceof DataAccessException ? "DATABASE_OPERATION_REQUIRES_REVIEW"
                    : failure instanceof IllegalStateException ? "PROCESSING_INVARIANT" : "PROCESSING_FAILURE";
            return control.execute(tx -> failed(vehicle,claim.token(),code,sqlState,retryable,clock.instant()));
        }
        return control.execute(tx -> finished(vehicle,claim.token(),outcome,clock.instant()));
    }

    public Optional<Status> status(UUID vehicle) {
        if (vehicle==null) throw new IllegalArgumentException("vehicle required");
        return jdbc.queryForList("select * from trip_processing_control where vehicle_id=?",vehicle).stream().findFirst().map(TripProcessingWorker::status);
    }

    /** Acknowledge a pause only after fixing its cause. Never changes projection/policy/work. */
    public boolean resume(UUID vehicle,long expectedRevision) {
        if (vehicle==null || expectedRevision<1) throw new IllegalArgumentException("vehicle and positive revision required");
        independent();
        return control.execute(tx -> jdbc.update("""
                update trip_processing_control set state='RETRY_WAIT',next_attempt_at=?,consecutive_failures=0,
                last_result='RESUME_REQUESTED',revision=revision+1 where vehicle_id=? and state='PAUSED' and revision=?
                """,stamp(clock.instant()),vehicle,expectedRevision)==1);
    }

    private Claim claim(UUID vehicle,Stage stage,Instant now) {
        // Separate from the stage's 902 lock. This lock protects absent status
        // rows as well, and is released before processing or ingestion can run.
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtextextended(cast(? as text),907))",Boolean.class,vehicle.toString())))
            return stopped("BUSY");
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from vehicle where id=?)",Boolean.class,vehicle))) return stopped("UNKNOWN_VEHICLE");
        jdbc.update("insert into trip_processing_control(vehicle_id,stage,state) values (?,?,'IDLE') on conflict do nothing",vehicle,stage.name());
        var row=jdbc.queryForMap("select * from trip_processing_control where vehicle_id=? for update",vehicle);
        String state=row.get("state").toString();
        if (state.equals("PAUSED")) return stopped("PAUSED");
        if (state.equals("RUNNING") && instant(row,"lease_until").isAfter(now)) return stopped("BUSY");
        if (!state.equals("IDLE") && !row.get("stage").equals(stage.name())) return stopped("RETRY_STAGE_REQUIRED");
        if (state.equals("RETRY_WAIT") && instant(row,"next_attempt_at").isAfter(now)) return stopped("RETRY_WAIT");
        if (state.equals("RUNNING")) {
            int failures=((Number)row.get("consecutive_failures")).intValue()+1;
            jdbc.update("""
                    update trip_processing_control set consecutive_failures=?,total_failures=total_failures+1,last_failure_at=?,
                    last_error_code='LEASE_EXPIRED',last_sql_state=null where vehicle_id=?
                    """,failures,stamp(now),vehicle);
            if (failures>=5) {
                jdbc.update("update trip_processing_control set state='PAUSED',claim_token=null,lease_until=null,last_result='LEASE_EXPIRED',revision=revision+1 where vehicle_id=?",vehicle);
                return stopped("PAUSED");
            }
        }
        UUID token=UUID.randomUUID();
        jdbc.update("""
                update trip_processing_control set stage=?,state='RUNNING',claim_token=?,lease_until=?,next_attempt_at=null,
                last_attempt_at=?,revision=revision+1 where vehicle_id=?
                """,stage.name(),token,stamp(now.plus(LEASE)),stamp(now),vehicle);
        return new Claim(token,null);
    }

    private Outcome step(UUID vehicle,Stage stage,Instant asOf,int limit) {
        return switch (stage) {
            case FORWARD -> { var p=projector.processPage(vehicle,asOf,limit); yield new Outcome(p.state(),p.visited()); }
            case PLAN -> { var p=planner.planPage(vehicle,asOf,limit); yield new Outcome(p.state(),p.visited()); }
            case REPLAY -> { var p=replayer.replayPage(vehicle,limit); yield new Outcome(p.state(),p.visited()); }
            case PUBLISH -> { var p=publisher.publishPage(vehicle,limit); yield new Outcome(p.state(),p.visited()); }
            case FINALIZE -> { var p=finalizer.finalizePage(vehicle,asOf,limit); yield new Outcome(p.state(),p.scanned()); }
        };
    }

    private Page finished(UUID vehicle,UUID token,Outcome outcome,Instant now) {
        var rows=jdbc.queryForList("select * from trip_processing_control where vehicle_id=? and claim_token=? for update",vehicle,token);
        if (rows.isEmpty()) return new Page("CLAIM_LOST",outcome.result(),outcome.visited());
        String result=outcome.result();
        boolean review=result.startsWith("REVIEW_") || result.equals("POLICY_CHANGED") || result.equals("NO_PRIMARY_DEVICE");
        // Pending correction/publication is a workflow handoff, not an error
        // requiring this same stage to retry forever. The orchestrator can next
        // select PLAN/REPLAY/PUBLISH. Only lock contention defers the same stage.
        boolean busy=result.equals("BUSY");
        String state=review ? "PAUSED" : busy ? "RETRY_WAIT" : "IDLE";
        jdbc.update("""
                update trip_processing_control set state=?,claim_token=null,lease_until=null,next_attempt_at=?,last_finished_at=?,last_result=?,
                consecutive_failures=?,last_error_code=?,last_sql_state=?,revision=revision+1 where vehicle_id=? and claim_token=?
                """,state,busy ? stamp(now.plusSeconds(1)) : null,stamp(now),result,
                busy ? rows.getFirst().get("consecutive_failures") : 0,review ? "POLICY_OR_REVIEW_REQUIRED" : busy ? rows.getFirst().get("last_error_code") : null,
                busy ? rows.getFirst().get("last_sql_state") : null,vehicle,token);
        return new Page(state,result,outcome.visited());
    }

    private Page failed(UUID vehicle,UUID token,String code,String sqlState,boolean retryable,Instant now) {
        var rows=jdbc.queryForList("select * from trip_processing_control where vehicle_id=? and claim_token=? for update",vehicle,token);
        if (rows.isEmpty()) return new Page("CLAIM_LOST",code,0);
        int failures=Math.min(5,((Number)rows.getFirst().get("consecutive_failures")).intValue()+1);
        boolean retry=retryable && failures<5;
        // 1,2,4,8 seconds, then pause. No sleeps or automatic retries in this call.
        jdbc.update("""
                update trip_processing_control set state=?,claim_token=null,lease_until=null,next_attempt_at=?,last_finished_at=?,
                consecutive_failures=?,total_failures=total_failures+1,last_failure_at=?,last_error_code=?,last_sql_state=?,
                last_result='FAILED',revision=revision+1 where vehicle_id=? and claim_token=?
                """,retry ? "RETRY_WAIT" : "PAUSED",retry ? stamp(now.plusSeconds(1L << (failures-1))) : null,
                stamp(now),failures,stamp(now),code,sqlState,vehicle,token);
        return new Page(retry ? "RETRY_WAIT" : "PAUSED",code,0);
    }

    private static Claim stopped(String state) { return new Claim(null,new Page(state,null,0)); }
    private static Status status(Map<String,Object> row) {
        return new Status(Stage.valueOf(row.get("stage").toString()),row.get("state").toString(),((Number)row.get("revision")).longValue(),
                instant(row,"next_attempt_at"),instant(row,"lease_until"),instant(row,"last_attempt_at"),instant(row,"last_finished_at"),instant(row,"last_failure_at"),
                ((Number)row.get("consecutive_failures")).intValue(),((Number)row.get("total_failures")).longValue(),
                (String)row.get("last_error_code"),(String)row.get("last_sql_state"),(String)row.get("last_result"));
    }
    private static String sqlState(Throwable error) {
        for (int depth=0;error!=null && depth<8;depth++,error=error.getCause())
            if (error instanceof SQLException sql && sql.getSQLState()!=null && sql.getSQLState().matches("[0-9A-Z]{5}")) return sql.getSQLState();
        return null;
    }
    private static void independent() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("worker must be called outside an existing transaction");
    }
    private static Instant instant(Map<String,Object> row,String key) { return row.get(key)==null ? null : ((Timestamp)row.get(key)).toInstant(); }
    private static Timestamp stamp(Instant value) { return value==null ? null : Timestamp.from(value); }
}
