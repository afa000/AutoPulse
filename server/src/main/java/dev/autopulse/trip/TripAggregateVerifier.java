package dev.autopulse.trip;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** Independent, bounded verification. VERIFIED is not authorization to prune or publish. */
@Service
public class TripAggregateVerifier {
    public record Page(int visited,int compared,String state,boolean busy) {}
    private record Key(int seconds,Instant start) {}
    private record Input(UUID id,UUID vehicle,UUID device,String source,Instant at,List<BigDecimal> values) {}
    private record Progress(String state,UUID last,long visited,Integer resolution,Timestamp bucket,long compared) {}
    private record Stat(long count,BigDecimal sum,BigDecimal min,BigDecimal max) {
        Stat add(BigDecimal value) {
            if (value==null) return this;
            return new Stat(Math.addExact(count,1),sum==null?value:sum.add(value),
                    min==null?value:min.min(value),max==null?value:max.max(value));
        }
    }
    private static final class Reference {
        long count;
        Instant first,last;
        final Map<String,Stat> metrics=new LinkedHashMap<>();
        Reference() { for (String field : TripRules.METRICS) metrics.put(field,new Stat(0,null,null,null)); }
        void add(Input input) {
            count=Math.addExact(count,1);
            if (first==null || input.at().isBefore(first)) first=input.at();
            if (last==null || input.at().isAfter(last)) last=input.at();
            for (int i=0;i<TripRules.METRICS.size();i++) {
                String field=TripRules.METRICS.get(i);
                metrics.put(field,metrics.get(field).add(input.values().get(i)));
            }
        }
    }
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    public TripAggregateVerifier(JdbcTemplate jdbc,ObjectMapper mapper,PlatformTransactionManager transactions) {
        this.jdbc=jdbc; this.mapper=mapper; named=new NamedParameterJdbcTemplate(jdbc);
        transaction=new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(10);
    }
    public Page verifyPage(UUID vehicle,UUID trip,int limit) {
        if (vehicle==null || trip==null || limit<1 || limit>500)
            throw new IllegalArgumentException("vehicle, trip and page size 1..500 required");
        return transaction.execute(tx -> {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtextextended(cast(? as text),918))",Boolean.class,trip.toString())))
                return new Page(0,0,"BUSY",true);
            var targets=jdbc.queryForList("select primary_device_id,source,status,finalization,started_at,ended_at from trip_record where id=? and vehicle_id=? for share",trip,vehicle);
            if (targets.isEmpty()) throw new IllegalArgumentException("Unknown verification trip");
            var target=targets.getFirst();
            if (!target.get("status").equals("COMPLETED") || !target.get("finalization").equals("FINAL"))
                throw new IllegalArgumentException("Verification requires a FINAL COMPLETED trip");
            var source=jdbc.queryForList("select complete,retained_rows from trip_retained_evidence_progress where trip_id=? for share",trip);
            if (source.isEmpty() || !Boolean.TRUE.equals(source.getFirst().get("complete")))
                throw new IllegalStateException("Verification requires complete retained evidence");
            var build=jdbc.queryForList("select complete,visited_rows from trip_aggregate_progress where trip_id=? for share",trip);
            long expected=((Number)source.getFirst().get("retained_rows")).longValue();
            if (build.isEmpty() || !Boolean.TRUE.equals(build.getFirst().get("complete"))
                    || ((Number)build.getFirst().get("visited_rows")).longValue()!=expected)
                throw new IllegalStateException("Verification requires a complete aggregate build with matching membership count");
            jdbc.update("insert into trip_aggregate_verification(trip_id) values (?) on conflict (trip_id) do nothing",trip);
            var progress=jdbc.queryForObject("select state,last_reading_id,visited_rows,last_resolution,last_bucket_start,compared_buckets from trip_aggregate_verification where trip_id=? for update",
                    (r,n) -> new Progress(r.getString(1),r.getObject(2,UUID.class),r.getLong(3),r.getObject(4,Integer.class),r.getTimestamp(5),r.getLong(6)),trip);
            if (List.of("VERIFIED","FAILED").contains(progress.state())) return new Page(0,0,progress.state(),false);
            if (progress.state().equals("ACCUMULATE")) return accumulate(vehicle,trip,limit,target,expected,progress);
            return compare(vehicle,trip,limit,target,progress);
        });
    }
    private Page accumulate(UUID vehicle,UUID trip,int limit,Map<String,Object> target,long expected,Progress progress) {
        String after=progress.last()==null?"":" and reading_id>?";
        Object[] args=progress.last()==null?new Object[]{trip,limit}:new Object[]{trip,progress.last(),limit};
        String columns=String.join(",",TripRules.METRICS.stream().map(f -> "(e.payload->>'"+f+"')::numeric").toList());
        var inputs=jdbc.query("with ids as materialized (select reading_id from trip_retained_evidence where trip_id=?"+after
                +" order by reading_id limit ?) select i.reading_id,e.vehicle_id,e.device_id,e.source,e.recorded_at,"+columns
                +" from ids i left join telemetry_replay_identity e on e.reading_id=i.reading_id order by i.reading_id",(r,n) -> {
                    var values=new ArrayList<BigDecimal>();
                    for (int i=0;i<TripRules.METRICS.size();i++) values.add(r.getBigDecimal(6+i));
                    Timestamp at=r.getTimestamp(5);
                    return new Input(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getString(4),at==null?null:at.toInstant(),values);
                },args);
        long visited=Math.addExact(progress.visited(),inputs.size());
        boolean finished=inputs.size()<limit;
        if (visited>expected || (finished && visited!=expected)) throw new IllegalStateException("Verification membership count mismatch");
        var references=new LinkedHashMap<Key,Reference>();
        Instant start=((Timestamp)target.get("started_at")).toInstant(),end=((Timestamp)target.get("ended_at")).toInstant();
        for (var input : inputs) {
            if (!vehicle.equals(input.vehicle()) || !target.get("primary_device_id").equals(input.device())
                    || !target.get("source").equals(input.source()) || input.at()==null || input.at().isBefore(start) || input.at().isAfter(end))
                throw new IllegalStateException("Verification retained provenance mismatch");
            for (int seconds : List.of(60,300)) {
                // Floor (not truncation) also handles timestamps before the Unix epoch.
                var key=new Key(seconds,Instant.ofEpochSecond(Math.floorDiv(input.at().getEpochSecond(),seconds)*seconds));
                references.computeIfAbsent(key,k -> loadReference(trip,k)).add(input);
            }
        }
        references.forEach((key,ref) -> jdbc.update("""
                insert into trip_aggregate_reference(trip_id,resolution_seconds,bucket_start,sample_count,first_observed_at,last_observed_at,metrics)
                values (?,?,?,?,?,?,?::jsonb) on conflict (trip_id,resolution_seconds,bucket_start) do update set
                sample_count=excluded.sample_count,first_observed_at=excluded.first_observed_at,last_observed_at=excluded.last_observed_at,metrics=excluded.metrics
                """,trip,key.seconds(),Timestamp.from(key.start()),ref.count,Timestamp.from(ref.first),Timestamp.from(ref.last),mapper.writeValueAsString(ref.metrics)));
        String state=finished?"COMPARE":"ACCUMULATE";
        jdbc.update("update trip_aggregate_verification set last_reading_id=?,visited_rows=?,state=? where trip_id=?",
                inputs.isEmpty()?progress.last():inputs.getLast().id(),visited,state,trip);
        return new Page(inputs.size(),0,state,false);
    }
    private Reference loadReference(UUID trip,Key key) {
        var rows=jdbc.queryForList("select sample_count,first_observed_at,last_observed_at,metrics::text from trip_aggregate_reference where trip_id=? and resolution_seconds=? and bucket_start=?",
                trip,key.seconds(),Timestamp.from(key.start()));
        var ref=new Reference();
        if (!rows.isEmpty()) {
            var row=rows.getFirst(); ref.count=((Number)row.get("sample_count")).longValue();
            ref.first=((Timestamp)row.get("first_observed_at")).toInstant(); ref.last=((Timestamp)row.get("last_observed_at")).toInstant();
            var metrics=mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(row.get("metrics").toString());
            for (String field : TripRules.METRICS) {
                var stat=metrics.get(field);
                ref.metrics.put(field,new Stat(stat.get("count").longValue(),decimal(stat.get("sum")),decimal(stat.get("min")),decimal(stat.get("max"))));
            }
        }
        return ref;
    }
    private static BigDecimal decimal(tools.jackson.databind.JsonNode node) { return node.isNull()?null:node.decimalValue(); }
    private Page compare(UUID vehicle,UUID trip,int limit,Map<String,Object> target,Progress progress) {
        var parameters=new MapSqlParameterSource().addValue("trip",trip).addValue("limit",limit);
        String after="";
        if (progress.resolution()!=null) {
            after=" and (resolution_seconds,bucket_start)>(:resolution,:start)";
            parameters.addValue("resolution",progress.resolution()).addValue("start",progress.bucket());
        }
        // Bound each ordered index walk BEFORE the union, including unexpected actual keys.
        var keys=named.query("select resolution_seconds,bucket_start from ((select resolution_seconds,bucket_start from trip_aggregate_reference where trip_id=:trip"+after
                +" order by resolution_seconds,bucket_start limit :limit) union (select resolution_seconds,bucket_start from trip_aggregate_bucket where trip_id=:trip"+after
                +" order by resolution_seconds,bucket_start limit :limit)) k order by resolution_seconds,bucket_start limit :limit",
                parameters,(r,n) -> new Key(r.getInt(1),r.getTimestamp(2).toInstant()));
        int compared=0;
        for (var key : keys) {
            String result=jdbc.queryForObject("""
                    select case when e.trip_id is null then 'UNEXPECTED_BUCKET' when b.trip_id is null then 'MISSING_BUCKET'
                        when (e.sample_count,e.first_observed_at,e.last_observed_at,e.metrics)
                            is distinct from (b.sample_count,b.first_observed_at,b.last_observed_at,b.metrics)
                            or b.vehicle_id<>? or b.device_id<>? or b.source<>? then 'CONTENT_MISMATCH' else 'MATCH' end
                    from (values (1)) anchor(n)
                    left join trip_aggregate_reference e on e.trip_id=? and e.resolution_seconds=? and e.bucket_start=?
                    left join trip_aggregate_bucket b on b.trip_id=? and b.resolution_seconds=? and b.bucket_start=?
                    """,String.class,vehicle,target.get("primary_device_id"),target.get("source"),
                    trip,key.seconds(),Timestamp.from(key.start()),trip,key.seconds(),Timestamp.from(key.start()));
            compared++;
            if (!result.equals("MATCH")) {
                saveComparison(trip,"FAILED",key,Math.addExact(progress.compared(),compared),result);
                return new Page(0,compared,"FAILED",false);
            }
        }
        String state=keys.size()<limit?"VERIFIED":"COMPARE";
        Key last=keys.isEmpty()?(progress.resolution()==null?null:new Key(progress.resolution(),progress.bucket().toInstant())):keys.getLast();
        saveComparison(trip,state,last,Math.addExact(progress.compared(),compared),null);
        return new Page(0,compared,state,false);
    }
    private void saveComparison(UUID trip,String state,Key last,long compared,String failure) {
        jdbc.update("update trip_aggregate_verification set state=?,last_resolution=?,last_bucket_start=?,compared_buckets=?,failure_code=? where trip_id=?",
                state,last==null?null:last.seconds(),last==null?null:Timestamp.from(last.start()),compared,failure,trip);
    }
}
