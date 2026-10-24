package dev.autopulse.trip;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static dev.autopulse.trip.TripProcessingWorker.Stage.*;

/** One vehicle / one bounded stage call, selected from committed durable state. */
@Service
public class TripProcessingCoordinator {
    public record Page(UUID vehicle,TripProcessingWorker.Stage stage,String state,String result,int visited) {}
    private final JdbcTemplate jdbc;
    private final TripProcessingWorker worker;
    private final TransactionTemplate selection;
    private final Clock clock;

    @Autowired
    public TripProcessingCoordinator(JdbcTemplate jdbc,PlatformTransactionManager transactions,TripProcessingWorker worker) {
        this(jdbc,transactions,worker,Clock.systemUTC());
    }
    public TripProcessingCoordinator(JdbcTemplate jdbc,PlatformTransactionManager transactions,TripProcessingWorker worker,Clock clock) {
        this.jdbc=jdbc; this.worker=worker; this.clock=java.util.Objects.requireNonNull(clock);
        selection=new TransactionTemplate(transactions); selection.setTimeout(10);
    }

    public Page poll(int limit) {
        validate(limit);
        UUID vehicle=selection.execute(tx -> {
            var scan=jdbc.queryForList("select last_vehicle_id from trip_processing_scan where singleton=true for update skip locked");
            if (scan.isEmpty()) return null;
            UUID after=(UUID)scan.getFirst().get("last_vehicle_id");
            var rows=after==null ? jdbc.queryForList("select vehicle_id from trip_policy order by vehicle_id limit 1")
                    : jdbc.queryForList("select vehicle_id from trip_policy where vehicle_id>? order by vehicle_id limit 1",after);
            UUID next=rows.isEmpty() ? null : (UUID)rows.getFirst().get("vehicle_id");
            jdbc.update("update trip_processing_scan set last_vehicle_id=? where singleton=true",next);
            return next;
        });
        // Reaching the end resets the cursor; the next poll starts a new round.
        // The selection transaction is never held while a stage runs.
        return vehicle==null ? new Page(null,null,"NO_SELECTION",null,0) : processVehicle(vehicle,clock.instant(),limit);
    }

    public Page processVehicle(UUID vehicle,Instant asOf,int limit) {
        validate(limit);
        if (vehicle==null || asOf==null) throw new IllegalArgumentException("vehicle and time required");
        var status=worker.status(vehicle);
        TripProcessingWorker.Stage stage;
        if (status.isPresent() && !status.get().state().equals("IDLE")) stage=status.get().stage();
        else stage=choose(vehicle,asOf);
        if (stage==null) return new Page(vehicle,null,"IDLE","NO_DUE_WORK",0);
        var result=worker.runPage(vehicle,stage,asOf,limit);
        return new Page(vehicle,stage,result.state(),result.result(),result.visited());
    }

    private TripProcessingWorker.Stage choose(UUID vehicle,Instant asOf) {
        if (exists("select exists(select 1 from trip_correction_publication where vehicle_id=? and phase not in ('DONE','ABANDONED'))",vehicle)) return PUBLISH;
        var plans=jdbc.queryForList("select phase,current_replay_id from trip_correction_plan where vehicle_id=? and phase in ('TRIPS','INPUTS','READY')",vehicle);
        if (!plans.isEmpty()) {
            var plan=plans.getFirst();
            if (!plan.get("phase").equals("READY")) return PLAN;
            return plan.get("current_replay_id")!=null && exists("select exists(select 1 from trip_correction_replay where id=? and status='STAGED')",plan.get("current_replay_id")) ? PUBLISH : REPLAY;
        }
        if (!exists("select exists(select 1 from trip_backfill_progress where vehicle_id=? and complete)",vehicle)
                || !exists("select exists(select 1 from trip_projection_progress where vehicle_id=?)",vehicle)
                || exists("select exists(select 1 from trip_projection_progress g join trip_policy p using(vehicle_id) where g.vehicle_id=? and g.policy_revision<>p.revision)",vehicle)) return FORWARD;
        // Discover late corrections even when new input continues arriving.
        // Selection is advisory; the worker rechecks state/source inside its
        // stage transaction after this non-transactional discovery decision.
        var source=TripReadingSource.forVehicle(jdbc,vehicle);
        if (exists("""
                select exists(select 1 from trip_work_item w join %s r on r.id=w.reading_id
                join trip_policy p on p.vehicle_id=w.vehicle_id and p.primary_device_id=r.device_id
                where w.vehicle_id=? and w.status='REVIEW' and r.recorded_at<=?
                and not exists(select 1 from trip_correction_plan c where c.requested_work_id=w.reading_id))
                """.formatted(source.table()),vehicle,Timestamp.from(asOf.plus(TripRules.DEFAULT.futureTolerance())))) return PLAN;
        if (exists("select exists(select 1 from trip_work_item where vehicle_id=? and status='REVIEW')",vehicle)
                || exists("select exists(select 1 from trip_projection_progress where vehicle_id=? and needs_review)",vehicle)) return FINALIZE;
        if (exists("select exists(select 1 from trip_work_item where vehicle_id=? and status='PENDING' and next_attempt_at<=?)",vehicle,Timestamp.from(asOf))
                || exists("select exists(select 1 from trip_record where vehicle_id=? and status in ('CANDIDATE','ACTIVE'))",vehicle)) return FORWARD;
        if (exists("select exists(select 1 from trip_vehicle_activity where vehicle_id=? and not scan_complete)",vehicle)
                || exists("select exists(select 1 from trip_record where vehicle_id=? and status='COMPLETED' and finalization='PROVISIONAL')",vehicle)) return FINALIZE;
        return null;
    }

    private boolean exists(String sql,Object... args) { return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args)); }
    private static void validate(int limit) {
        if (limit<1 || limit>100) throw new IllegalArgumentException("limit 1..100 required");
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("coordinator must be called outside an existing transaction");
    }
}
