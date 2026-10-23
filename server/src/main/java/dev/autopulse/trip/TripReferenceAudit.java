package dev.autopulse.trip;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit global reference diagnostic. Never repairs, validates FKs or permits deletion. */
@Service
public class TripReferenceAudit {
    public enum Kind { WORK, LINK, CORRECTION }
    /** Trusted internal boundary, bound to one reference kind; not a public token. */
    public record Cursor(Kind kind,UUID parentId,UUID readingId) {
        public Cursor {
            if (kind==null || readingId==null || (kind==Kind.CORRECTION)!=(parentId!=null))
                throw new IllegalArgumentException("Invalid reference audit boundary");
        }
    }
    /** Counts overlap: missing canonical identity also prevents owner agreement. */
    public record Page(Kind kind,int visited,int rawMissing,int ledgerMissing,int originalMismatch,
                       int ownerMismatch,Cursor lastCursor,boolean complete) {}
    private record Checked(UUID parent,UUID reading,boolean raw,boolean ledger,boolean exact,boolean owner) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate snapshot;

    public TripReferenceAudit(JdbcTemplate jdbc,PlatformTransactionManager transactions) {
        this.jdbc=Objects.requireNonNull(jdbc);
        snapshot=new TransactionTemplate(transactions);
        snapshot.setReadOnly(true);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setTimeout(10);
    }

    // Global PK traversal deliberately avoids filtering by a possibly incorrect
    // vehicle owner. Materialize <=500 references before payload/parent lookups.
    public Page readPage(Kind kind,Cursor after,int limit) {
        if (kind==null || limit<1 || limit>500 || (after!=null && after.kind()!=kind))
            throw new IllegalArgumentException("Reference kind, matching boundary and page size 1..500 required");
        return snapshot.execute(tx -> {
            String table=switch(kind) {
                case WORK -> "trip_work_item";
                case LINK -> "trip_reading_link";
                case CORRECTION -> "trip_correction_reading";
            };
            String parent=kind==Kind.CORRECTION?"replay_id":"null::uuid";
            String order=kind==Kind.CORRECTION?"replay_id,reading_id":"reading_id";
            String boundary=after==null?"":kind==Kind.CORRECTION?" where (replay_id,reading_id)>(?,?)":" where reading_id>?";
            var args=new ArrayList<Object>();
            if (after!=null) { if (kind==Kind.CORRECTION) args.add(after.parentId()); args.add(after.readingId()); }
            args.add(limit);
            String owner=switch(kind) { case WORK -> "c.vehicle_id"; case LINK -> "t.vehicle_id"; case CORRECTION -> "p.vehicle_id"; };
            String parents=switch(kind) {
                case WORK -> "";
                case LINK -> " left join trip_record t on t.id=c.trip_id ";
                case CORRECTION -> """
                     left join trip_correction_segment s on s.id=c.segment_id and s.replay_id=c.replay_id
                     left join trip_correction_replay replay on replay.id=s.replay_id
                     left join trip_correction_plan p on p.id=replay.plan_id
                    """;
            };
            var rows=jdbc.query("with candidates as materialized (select *,"+parent+" as audit_parent from "+table
                    +boundary+" order by "+order+" limit ?) "+"""
                    select c.audit_parent,c.reading_id,r.id is not null,e.reading_id is not null,
                      (r.id,r.vehicle_id,r.device_id,r.boot_id,r.sequence_number,r.recorded_at,r.received_at,r.source,r.payload)
                        is not distinct from
                      (e.reading_id,e.vehicle_id,e.device_id,e.boot_id,e.sequence_number,e.recorded_at,e.received_at,e.source,e.payload),
                    """+" coalesce(e.vehicle_id="+owner+",false) from candidates c "
                    +" left join telemetry_reading r on r.id=c.reading_id "
                    +" left join telemetry_replay_identity e on e.reading_id=c.reading_id "+parents
                    +" order by c.audit_parent nulls first,c.reading_id",
                    (r,n) -> new Checked(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getBoolean(3),
                            r.getBoolean(4),r.getBoolean(5),r.getBoolean(6)),args.toArray());
            int rawMissing=0,ledgerMissing=0,mismatch=0,ownerMismatch=0;
            for (var row:rows) {
                if (!row.raw()) rawMissing++;
                if (!row.ledger()) ledgerMissing++;
                if (row.raw() && row.ledger() && !row.exact()) mismatch++;
                if (!row.owner()) ownerMismatch++;
            }
            var last=rows.isEmpty()?after:new Cursor(kind,rows.getLast().parent(),rows.getLast().reading());
            return new Page(kind,rows.size(),rawMissing,ledgerMissing,mismatch,ownerMismatch,last,rows.size()<limit);
        });
    }
}
