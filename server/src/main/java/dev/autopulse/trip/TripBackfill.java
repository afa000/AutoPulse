package dev.autopulse.trip;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded durable handoff of pre-migration readings. No trip classification yet. */
@Service
public class TripBackfill {
    public static final int MAX_PAGE = 500;
    public record Page(int visited, int queued, boolean complete, boolean busy) {}
    private record Progress(UUID last, boolean complete) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public TripBackfill(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(10);
    }

    public Page enqueuePage(UUID vehicle, int limit) {
        if (vehicle == null || limit < 1 || limit > MAX_PAGE) throw new IllegalArgumentException("vehicle and page size 1..500 required");
        return transaction.execute(status -> {
            List<Progress> locked = jdbc.query("""
                    select last_reading_id,complete from trip_backfill_progress
                    where vehicle_id=? for update skip locked
                    """, (row, n) -> new Progress(row.getObject(1, UUID.class), row.getBoolean(2)), vehicle);
            if (locked.isEmpty()) {
                if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from trip_backfill_progress where vehicle_id=?)", Boolean.class, vehicle)))
                    return new Page(0, 0, false, true);
                throw new IllegalArgumentException("vehicle has no backfill registration");
            }
            var progress = locked.getFirst();
            // Pre-V910 work can already exist even after raw backfill completed.
            // Trigger hydration changes ordering metadata only, never receipts,
            // attempts, retry deadlines or processing acknowledgments.
            var recovery = jdbc.query("""
                    select reading_id from trip_work_item where vehicle_id=? and status='PENDING' and sort_at is null
                    order by reading_id limit ?
                    """,(row,n) -> row.getObject(1,UUID.class),vehicle,limit);
            if (!recovery.isEmpty()) {
                for (var id : recovery) jdbc.update("update trip_work_item set sort_at=sort_at where reading_id=?",id);
                return new Page(recovery.size(),0,false,false);
            }
            if (progress.complete()) return new Page(0, 0, true, false);
            var source=TripReadingSource.forVehicle(jdbc,vehicle);
            String after = progress.last() == null ? "" : " and id > ?";
            Object[] args = progress.last() == null ? new Object[]{vehicle, limit} : new Object[]{vehicle, progress.last(), limit};
            var ids = jdbc.query("select id from "+source.table()+" where vehicle_id=?" + after + " order by id limit ?",
                    (row, n) -> row.getObject(1, UUID.class), args);
            int queued = 0;
            // At most 500 inserts. Existing trigger-created work stays byte-for-byte unchanged.
            // Original-raw reference guards still verify every attempted insert;
            // selecting ledger here does NOT allow a raw-free write path.
            for (var id : ids) queued += jdbc.update("""
                    insert into trip_work_item (reading_id,vehicle_id,accepted_at,next_attempt_at)
                    select id,vehicle_id,received_at,greatest(received_at,recorded_at-interval '2 seconds')
                    from %s where id=? on conflict (reading_id) do nothing
                    """.formatted(source.table()), id);
            boolean complete = ids.size() < limit;
            jdbc.update("""
                    update trip_backfill_progress set last_reading_id=?, complete=?,visited_rows=visited_rows+?
                    where vehicle_id=?
                    """, ids.isEmpty() ? progress.last() : ids.getLast(), complete, ids.size(), vehicle);
            return new Page(ids.size(), queued, complete, false);
        });
    }
}
