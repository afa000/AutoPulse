package dev.autopulse.trip;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded sealing only. No scheduler and no replacement for late reconciliation. */
@Service
public class TripFinalizer {
    public record Page(String state, int scanned, int finalized) {}
    public static final int MAX_PAGE = 100;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public TripFinalizer(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc; transaction = new TransactionTemplate(transactions); transaction.setTimeout(10);
    }

    public Page finalizePage(UUID vehicle, Instant asOf, int limit) {
        if (vehicle == null || asOf == null || limit < 1 || limit > MAX_PAGE)
            throw new IllegalArgumentException("vehicle/time and limit 1..100 required");
        return transaction.execute(status -> seal(vehicle, asOf, limit));
    }

    private Page seal(UUID vehicle, Instant asOf, int limit) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtextextended(cast(? as text),902))",
                Boolean.class, vehicle.toString()))) return new Page("BUSY", 0, 0);
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from trip_correction_plan where vehicle_id=? and phase in ('TRIPS','INPUTS','READY'))",
                Boolean.class, vehicle))) return new Page("CORRECTION_PENDING", 0, 0);
        var policies = jdbc.queryForList("select primary_device_id,revision from trip_policy where vehicle_id=? for share", vehicle);
        if (policies.isEmpty()) return new Page("NO_PRIMARY_DEVICE", 0, 0);
        var policy = policies.getFirst();
        var activityRows = jdbc.queryForList("select * from trip_vehicle_activity where vehicle_id=? for update", vehicle);
        if (activityRows.isEmpty()) throw new IllegalStateException("vehicle activity registration missing");
        var activity = activityRows.getFirst();
        var source=TripReadingSource.forVehicle(jdbc,vehicle);
        // V902 installations may already have processed work. Recover their quiet
        // horizon in resumable indexed pages, never one migration-time history scan.
        if (!Boolean.TRUE.equals(activity.get("scan_complete"))) {
            UUID cursor = (UUID) activity.get("scan_cursor");
            String after = cursor == null ? "" : " and w.reading_id>?";
            Object[] args = cursor == null ? new Object[]{vehicle, limit} : new Object[]{vehicle, cursor, limit};
            var rows = jdbc.queryForList("""
                    select w.reading_id,w.accepted_at,r.device_id,r.recorded_at from trip_work_item w
                    join %s r on r.id=w.reading_id where w.vehicle_id=?
                    """.formatted(source.table()) + after + " order by w.reading_id limit ?", args);
            Instant last = activity.get("last_eligible_receipt_at") == null ? null
                    : ((Timestamp) activity.get("last_eligible_receipt_at")).toInstant();
            for (var row : rows) {
                Instant receipt = ((Timestamp) row.get("accepted_at")).toInstant();
                if (row.get("device_id").equals(policy.get("primary_device_id"))
                        && !((Timestamp) row.get("recorded_at")).toInstant().isAfter(asOf.plus(TripRules.DEFAULT.futureTolerance()))
                        && (last == null || receipt.isAfter(last))) last = receipt;
            }
            jdbc.update("""
                    update trip_vehicle_activity set scan_cursor=?,scan_complete=?,last_eligible_receipt_at=? where vehicle_id=?
                    """, rows.isEmpty() ? cursor : rows.getLast().get("reading_id"), rows.size() < limit,
                    last == null ? null : Timestamp.from(last), vehicle);
            return new Page("BACKFILLING_ACTIVITY", rows.size(), 0);
        }
        var progressRows = jdbc.queryForList("select policy_revision,needs_review from trip_projection_progress where vehicle_id=? for update", vehicle);
        if (progressRows.isEmpty()) return new Page("PROJECTION_NOT_READY", 0, 0);
        var progress = progressRows.getFirst();
        if (!progress.get("policy_revision").equals(policy.get("revision"))) return new Page("POLICY_CHANGED", 0, 0);
        boolean review = Boolean.TRUE.equals(progress.get("needs_review")) || Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from trip_work_item where vehicle_id=? and status='REVIEW')", Boolean.class, vehicle));
        if (review) return new Page("REVIEW_REQUIRED", 0, 0);
        boolean queued = !Boolean.TRUE.equals(jdbc.queryForObject("select complete from trip_backfill_progress where vehicle_id=?", Boolean.class, vehicle))
                || Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from trip_work_item w join %s r on r.id=w.reading_id
                    where w.vehicle_id=? and w.status='PENDING' and r.recorded_at<=?)
                    """.formatted(source.table()), Boolean.class, vehicle, Timestamp.from(asOf.plus(TripRules.DEFAULT.futureTolerance()))));
        if (queued) return new Page("PENDING_WORK", 0, 0);
        Timestamp last = (Timestamp) activity.get("last_eligible_receipt_at");
        if (last == null) return new Page("NO_ELIGIBLE_DATA", 0, 0);
        if (!TripReconciliation.canFinalize(asOf, last.toInstant(), true, false, false)) return new Page("QUIET_PERIOD", 0, 0);
        var ids = jdbc.query("""
                select id from trip_record where vehicle_id=? and status='COMPLETED' and finalization='PROVISIONAL'
                order by started_at,id limit ? for update
                """, (row, n) -> row.getObject(1, UUID.class), vehicle, limit);
        for (var id : ids) jdbc.update("update trip_record set finalization='FINAL',revision=revision+1 where id=?", id);
        return new Page("FINALIZED", 0, ids.size());
    }
}
