package dev.autopulse.trip;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit, bounded build of internal trip contributions; never switches public reads or prunes. */
@Service
public class TripAggregateBuilder {
    public record Page(int visited,int bucketWrites,boolean complete,boolean busy) {}
    private record Progress(UUID last,long visited,boolean complete) {}
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate transaction;
    public TripAggregateBuilder(JdbcTemplate jdbc,PlatformTransactionManager transactions) {
        this.jdbc=jdbc; named=new NamedParameterJdbcTemplate(jdbc);
        transaction=new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(10);
    }
    public Page buildPage(UUID vehicle,UUID trip,int limit) {
        if (vehicle==null || trip==null || limit<1 || limit>500)
            throw new IllegalArgumentException("vehicle, trip and page size 1..500 required");
        return transaction.execute(tx -> {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtextextended(cast(? as text),917))",Boolean.class,trip.toString())))
                return new Page(0,0,false,true);
            var trips=jdbc.queryForList("select primary_device_id,source,status,finalization from trip_record where id=? and vehicle_id=? for share",trip,vehicle);
            if (trips.isEmpty()) throw new IllegalArgumentException("Unknown aggregate trip");
            var target=trips.getFirst();
            if (!target.get("status").equals("COMPLETED") || !target.get("finalization").equals("FINAL"))
                throw new IllegalArgumentException("Aggregates require a FINAL COMPLETED trip");
            var evidence=jdbc.queryForList("select complete,retained_rows from trip_retained_evidence_progress where trip_id=? for share",trip);
            if (evidence.isEmpty() || !Boolean.TRUE.equals(evidence.getFirst().get("complete")))
                throw new IllegalStateException("Aggregate requires complete retained evidence");
            long expected=((Number)evidence.getFirst().get("retained_rows")).longValue();
            jdbc.update("insert into trip_aggregate_progress(trip_id) values (?) on conflict (trip_id) do nothing",trip);
            var progress=jdbc.queryForObject("select last_reading_id,visited_rows,complete from trip_aggregate_progress where trip_id=? for update",
                    (row,n) -> new Progress(row.getObject(1,UUID.class),row.getLong(2),row.getBoolean(3)),trip);
            if (progress.complete()) return new Page(0,0,true,false);
            String after=progress.last()==null?"":" and reading_id>?";
            Object[] args=progress.last()==null?new Object[]{trip,limit}:new Object[]{trip,progress.last(),limit};
            var ids=jdbc.query("select reading_id from trip_retained_evidence where trip_id=?"+after+" order by reading_id limit ?",
                    (row,n) -> row.getObject(1,UUID.class),args);
            long visited=Math.addExact(progress.visited(),ids.size());
            boolean complete=ids.size()<limit;
            if (visited>expected || (complete && visited!=expected)) throw new IllegalStateException("Retained membership count changed; aggregate page rolled back");
            int writes=0;
            if (!ids.isEmpty()) {
                var parameters=new MapSqlParameterSource().addValue("trip",trip).addValue("vehicle",vehicle)
                        .addValue("device",target.get("primary_device_id")).addValue("source",target.get("source")).addValue("ids",ids);
                for (int seconds : List.of(60,300)) writes+=named.update(upsert(seconds),parameters);
            }
            jdbc.update("update trip_aggregate_progress set last_reading_id=?,visited_rows=?,complete=? where trip_id=?",
                    ids.isEmpty()?progress.last():ids.getLast(),visited,complete,trip);
            return new Page(ids.size(),writes,complete,false);
        });
    }
    private static String upsert(int seconds) {
        return """
                with selected as materialized (
                    select e.recorded_at,e.payload from trip_retained_evidence a
                    join telemetry_replay_identity e on e.reading_id=a.reading_id
                    where a.trip_id=:trip and a.reading_id in (:ids)
                )
                insert into trip_aggregate_bucket as b
                    (trip_id,resolution_seconds,bucket_start,vehicle_id,device_id,source,sample_count,first_observed_at,last_observed_at,metrics)
                select :trip,
                """+seconds+",date_bin(interval '"+seconds+" seconds',recorded_at,timestamptz '1970-01-01 00:00:00+00'),"
                +":vehicle,:device,:source,count(*),min(recorded_at),max(recorded_at),"+stats(false)
                +" from selected group by 3 on conflict (trip_id,resolution_seconds,bucket_start) do update set "
                +"sample_count=b.sample_count+excluded.sample_count,first_observed_at=least(b.first_observed_at,excluded.first_observed_at),"
                +"last_observed_at=greatest(b.last_observed_at,excluded.last_observed_at),metrics="+stats(true);
    }
    private static String stats(boolean merge) {
        var fields=new java.util.ArrayList<String>();
        for (String field : TripRules.METRICS) {
            String count,sum,min,max;
            if (!merge) {
                String value="(payload->>'"+field+"')::numeric";
                count="count("+value+")"; sum="sum("+value+")"; min="min("+value+")"; max="max("+value+")";
            } else {
                String old="b.metrics->'"+field+"'",next="excluded.metrics->'"+field+"'";
                count="("+old+"->>'count')::bigint+("+next+"->>'count')::bigint";
                sum="case when "+count+"=0 then null else coalesce(("+old+"->>'sum')::numeric,0)+coalesce(("+next+"->>'sum')::numeric,0) end";
                min="least(("+old+"->>'min')::numeric,("+next+"->>'min')::numeric)";
                max="greatest(("+old+"->>'max')::numeric,("+next+"->>'max')::numeric)";
            }
            fields.add("'"+field+"',jsonb_build_object('count',"+count+",'sum',"+sum+",'min',"+min+",'max',"+max+")");
        }
        return "jsonb_build_object("+String.join(",",fields)+")";
    }
}
