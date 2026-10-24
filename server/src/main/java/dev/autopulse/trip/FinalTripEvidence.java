package dev.autopulse.trip;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit bounded copy only; no runner, pruning, aggregate or public-query switch. */
@Service
public class FinalTripEvidence {
    public record Page(int visited,int retained,int copied,boolean complete,boolean busy) {}
    private record Progress(UUID last,boolean complete) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public FinalTripEvidence(JdbcTemplate jdbc,PlatformTransactionManager transactions) {
        this.jdbc=jdbc; transaction=new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(10);
    }
    public Page copyPage(UUID vehicle,UUID trip,int limit) {
        if (vehicle==null || trip==null || limit<1 || limit>500)
            throw new IllegalArgumentException("vehicle, trip and page size 1..500 required");
        return transaction.execute(tx -> {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtextextended(cast(? as text),916))",Boolean.class,trip.toString())))
                return new Page(0,0,0,false,true);
            var trips=jdbc.queryForList("select status,finalization,started_at,ended_at from trip_record where id=? and vehicle_id=? for share",trip,vehicle);
            if (trips.isEmpty()) throw new IllegalArgumentException("Unknown retained-evidence trip");
            var target=trips.getFirst();
            if (!target.get("status").equals("COMPLETED") || !target.get("finalization").equals("FINAL"))
                throw new IllegalArgumentException("Retained evidence requires a FINAL COMPLETED trip");
            Instant start=((Timestamp)target.get("started_at")).toInstant(),end=((Timestamp)target.get("ended_at")).toInstant();
            jdbc.update("insert into trip_retained_evidence_progress(trip_id) values (?) on conflict (trip_id) do nothing",trip);
            var progress=jdbc.queryForObject("select last_reading_id,complete from trip_retained_evidence_progress where trip_id=? for update",
                    (row,n) -> new Progress(row.getObject(1,UUID.class),row.getBoolean(2)),trip);
            if (progress.complete()) return new Page(0,0,0,true,false);
            var source=TripReadingSource.forVehicle(jdbc,vehicle);
            String after=progress.last()==null?"":" and reading_id>?";
            Object[] args=progress.last()==null?new Object[]{trip,limit}:new Object[]{trip,progress.last(),limit};
            // Page ALL links before metadata lookups: even arbitrarily many out-of-range
            // confirmation links consume bounded work and advance the UUID cursor.
            var ids=jdbc.query("select reading_id from trip_reading_link where trip_id=?"+after+" order by reading_id limit ?",
                    (row,n) -> row.getObject(1,UUID.class),args);
            int retained=0,copied=0;
            for (var id : ids) {
                Instant at=jdbc.queryForObject("select recorded_at from "+source.table()+" where id=?",Timestamp.class,id).toInstant();
                if (at.isBefore(start) || at.isAfter(end)) continue;
                // Ledger-based bounds cannot silently skip an original member whose
                // raw timestamp was later changed. The unchanged database guard verifies
                // raw/ledger identity, payload, receipt,
                // primary device/source and exact immutable closed membership.
                copied+=jdbc.update("insert into trip_retained_evidence(reading_id,trip_id) values (?,?) on conflict (reading_id) do nothing",id,trip);
                if (!trip.equals(jdbc.queryForObject("select trip_id from trip_retained_evidence where reading_id=?",UUID.class,id)))
                    throw new IllegalStateException("retained evidence belongs to another trip; page rolled back");
                retained++;
            }
            boolean complete=ids.size()<limit;
            jdbc.update("""
                    update trip_retained_evidence_progress set last_reading_id=?,visited_rows=visited_rows+?,
                    retained_rows=retained_rows+?,complete=? where trip_id=?
                    """,ids.isEmpty()?progress.last():ids.getLast(),ids.size(),retained,complete,trip);
            return new Page(ids.size(),retained,copied,complete,false);
        });
    }
}
