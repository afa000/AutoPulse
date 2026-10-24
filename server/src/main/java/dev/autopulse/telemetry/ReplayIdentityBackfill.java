package dev.autopulse.telemetry;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit bounded capture of legacy identities; no runner, pruning or replay routing. */
@Service
public class ReplayIdentityBackfill {
    public record Page(int visited, int copied, boolean complete, boolean busy) {}
    private record Progress(UUID last, boolean complete) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public ReplayIdentityBackfill(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc=jdbc;
        transaction=new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(10);
    }

    public Page copyPage(UUID vehicle, int limit) {
        if (vehicle==null || limit<1 || limit>500)
            throw new IllegalArgumentException("vehicle and page size 1..500 required");
        return transaction.execute(status -> {
            var locked=jdbc.query("""
                    select last_reading_id,complete from telemetry_replay_backfill
                    where vehicle_id=? for update skip locked
                    """,(row,n) -> new Progress(row.getObject(1,UUID.class),row.getBoolean(2)),vehicle);
            if (locked.isEmpty()) {
                if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from telemetry_replay_backfill where vehicle_id=?)",Boolean.class,vehicle)))
                    return new Page(0,0,false,true);
                throw new IllegalArgumentException("vehicle has no replay backfill registration");
            }
            var progress=locked.getFirst();
            if (progress.complete()) return new Page(0,0,true,false);
            String after=progress.last()==null ? "" : " and id>?";
            Object[] args=progress.last()==null ? new Object[]{vehicle,limit} : new Object[]{vehicle,progress.last(),limit};
            var ids=jdbc.query("select id from telemetry_reading where vehicle_id=?"+after+" order by id limit ?",
                    (row,n) -> row.getObject(1,UUID.class),args);
            int copied=0;
            for (var id : ids) {
                copied+=jdbc.update("""
                        insert into telemetry_replay_identity
                        (device_id,boot_id,sequence_number,reading_id,vehicle_id,recorded_at,received_at,source,payload)
                        select device_id,boot_id,sequence_number,id,vehicle_id,recorded_at,received_at,source,payload
                        from telemetry_reading where id=? on conflict (device_id,boot_id,sequence_number) do nothing
                        """,id);
                // Never acknowledge a page containing a missing or mismatched identity.
                // Compare in PostgreSQL, preserving JSONB numeric equality and exact receipts.
                Boolean matches=jdbc.queryForObject("""
                        select exists(select 1 from telemetry_reading r join telemetry_replay_identity l
                          on l.device_id=r.device_id and l.boot_id=r.boot_id and l.sequence_number=r.sequence_number
                        where r.id=? and l.reading_id=r.id and l.vehicle_id=r.vehicle_id
                          and l.recorded_at=r.recorded_at and l.received_at=r.received_at
                          and l.source=r.source and l.payload=r.payload)
                        """,Boolean.class,id);
                if (!Boolean.TRUE.equals(matches)) throw new IllegalStateException("replay identity mismatch; page rolled back");
            }
            boolean complete=ids.size()<limit;
            jdbc.update("""
                    update telemetry_replay_backfill set last_reading_id=?,complete=?,visited_rows=visited_rows+?
                    where vehicle_id=?
                    """,ids.isEmpty()?progress.last():ids.getLast(),complete,ids.size(),vehicle);
            return new Page(ids.size(),copied,complete,false);
        });
    }
}
