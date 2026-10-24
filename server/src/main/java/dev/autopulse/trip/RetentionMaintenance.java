package dev.autopulse.trip;

import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Explicit, restart-safe bounded maintenance. No scheduler or startup invocation. */
@Service
public class RetentionMaintenance {
    public record Page(String stage,String state,int visited,int deleted,boolean complete,RetentionPreview.Cursor nextCursor) {}
    private final JdbcTemplate jdbc;
    private final FinalTripEvidence evidence;
    private final TripAggregateBuilder aggregates;
    private final TripAggregateVerifier verifier;
    private final RetentionPreview retention;
    public RetentionMaintenance(JdbcTemplate jdbc,FinalTripEvidence evidence,TripAggregateBuilder aggregates,TripAggregateVerifier verifier,RetentionPreview retention) {
        this.jdbc=jdbc; this.evidence=evidence; this.aggregates=aggregates; this.verifier=verifier; this.retention=retention;
    }
    public Page runPage(UUID vehicle,int limit,RetentionPreview.Cursor cursor) {
        if (vehicle==null || limit<1 || limit>100) throw new IllegalArgumentException("vehicle and page size 1..100 required");
        // Dry runs must not create archive/progress rows either.
        if (!retention.enabled() || retention.dryRun()) return prune(vehicle,limit,cursor);
        var pending=jdbc.queryForList("""
                select t.id from trip_record t where t.vehicle_id=? and t.status='COMPLETED' and t.finalization='FINAL'
                  and t.ended_at<? and not exists(select 1 from trip_verified_archive a where a.trip_id=t.id)
                order by t.started_at,t.id limit 1
                """,UUID.class,vehicle,Timestamp.from(retention.cutoff()));
        if (pending.isEmpty()) return prune(vehicle,limit,cursor);
        UUID trip=pending.getFirst();
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from trip_retained_evidence_progress where trip_id=? and complete)",Boolean.class,trip))) {
            var p=evidence.copyPage(vehicle,trip,limit);
            return new Page("EVIDENCE",p.busy()?"BUSY":"PROGRESS",p.visited(),0,false,cursor);
        }
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from trip_aggregate_progress where trip_id=? and complete)",Boolean.class,trip))) {
            var p=aggregates.buildPage(vehicle,trip,limit);
            return new Page("AGGREGATES",p.busy()?"BUSY":"PROGRESS",p.visited(),0,false,cursor);
        }
        var p=verifier.verifyPage(vehicle,trip,limit);
        return new Page("VERIFY",p.state(),p.visited()+p.compared(),0,false,cursor);
    }
    private Page prune(UUID vehicle,int limit,RetentionPreview.Cursor cursor) {
        var p=retention.prunePage(vehicle,limit,cursor);
        return new Page("PRUNE",p.state(),0,p.deleted(),p.complete(),p.nextCursor());
    }
}
