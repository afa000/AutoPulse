package dev.autopulse.telemetry;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit read-only diagnostic, not a backfill, FK validator or deletion certificate. */
@Service
public class ReplayIdentityAudit {
    public record Page(int visited, int matched, int missing, int mismatched,
                       UUID lastReadingId, boolean complete) {}
    private record Reading(UUID id, String coverage) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate snapshot;

    public ReplayIdentityAudit(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc=jdbc;
        snapshot=new TransactionTemplate(transactions);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setTimeout(10);
    }

    // after is an internal, trusted UUID boundary, not an HTTP continuation token.
    // Each call has its own snapshot; multiple pages are not a single attestation.
    public Page readPage(UUID vehicle, UUID after, int limit) {
        if (vehicle==null || limit<1 || limit>500)
            throw new IllegalArgumentException("vehicle and page size 1..500 required");
        return snapshot.execute(tx -> {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from vehicle where id=?)",Boolean.class,vehicle)))
                throw new IllegalArgumentException("Unknown replay audit vehicle");
            String boundary=after==null ? "" : " and id>?";
            Object[] args=after==null ? new Object[]{vehicle,limit} : new Object[]{vehicle,after,limit};
            // Bound raw UUID candidates BEFORE fetching payloads. Exact JSONB and
            // NUMERIC comparisons stay in PostgreSQL; Java receives only counts/IDs.
            var rows=jdbc.query("""
                    with candidates as materialized (
                      select id from telemetry_reading where vehicle_id=?
                    """+boundary+" order by id limit ?)\n"+"""
                    select r.id,case when l.reading_id is null then 'MISSING'
                      when l.device_id=r.device_id and l.boot_id=r.boot_id
                        and l.sequence_number=r.sequence_number and l.vehicle_id=r.vehicle_id
                        and l.recorded_at=r.recorded_at and l.received_at=r.received_at
                        and l.source=r.source and l.payload=r.payload then 'MATCHED'
                      else 'MISMATCHED' end as coverage
                    from candidates c join telemetry_reading r on r.id=c.id
                    left join telemetry_replay_identity l on l.reading_id=r.id
                    order by r.id
                    """,(row,n) -> new Reading(row.getObject(1,UUID.class),row.getString(2)),args);
            int matched=0,missing=0,mismatched=0;
            for (var row : rows) {
                switch (row.coverage()) {
                    case "MATCHED" -> matched++;
                    case "MISSING" -> missing++;
                    default -> mismatched++;
                }
            }
            return new Page(rows.size(),matched,missing,mismatched,
                    rows.isEmpty()?after:rows.getLast().id(),rows.size()<limit);
        });
    }
}
