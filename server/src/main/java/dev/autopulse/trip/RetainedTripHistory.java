package dev.autopulse.trip;

import dev.autopulse.shared.ApiFailure;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Internal, archive-only FINAL-trip queries. Does not switch public readers or authorize pruning. */
@Service
public class RetainedTripHistory {
    /** Trusted in-process continuation, not a signed public API cursor. */
    public record Cursor(UUID vehicle,UUID trip,Instant from,Instant to,String resolution,int limit,Instant afterBucket) {}
    public record Page(List<HistoryQueries.AggregatePoint> points,Cursor nextCursor,
                       int storedBuckets,int clippedBuckets,long clippedCandidates) {
        public Page { points=List.copyOf(points); }
    }
    private record Bucket(Instant start,long count,Instant first,Instant last,String metrics) {}
    private record Clipped(long candidates,Bucket bucket) {}
    static final int MAX_CLIPPED_CANDIDATES=10000;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TripQueries trips;
    private final TransactionTemplate snapshot;
    public RetainedTripHistory(JdbcTemplate jdbc,ObjectMapper mapper,TripQueries trips,PlatformTransactionManager transactions) {
        this.jdbc=jdbc; this.mapper=mapper; this.trips=trips;
        snapshot=new TransactionTemplate(transactions);
        snapshot.setReadOnly(true); snapshot.setTimeout(10);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Page read(UUID vehicle,HistoryRequest query,Cursor cursor) {
        if (vehicle==null || query==null || query.tripId()==null || query.resolution().equals("raw") || query.cursor()!=null)
            throw new IllegalArgumentException("Retained history requires a vehicle, trip, aggregate resolution and separate trusted cursor");
        int seconds=query.resolution().equals("1m")?60:300;
        if (query.from().isBefore(Instant.parse("0001-01-01T00:00:00Z")) || query.to().isAfter(Instant.parse("9999-12-31T23:59:59.999999Z")))
            throw new IllegalArgumentException("Retained history requires four-digit UTC years");
        if (cursor!=null && (!vehicle.equals(cursor.vehicle()) || !query.tripId().equals(cursor.trip())
                || !query.from().equals(cursor.from()) || !query.to().equals(cursor.to())
                || !query.resolution().equals(cursor.resolution()) || query.limit()!=cursor.limit()
                || cursor.afterBucket()==null || !floor(cursor.afterBucket(),seconds).equals(cursor.afterBucket())
                || cursor.afterBucket().isBefore(floor(query.from(),seconds)) || !cursor.afterBucket().isBefore(query.to())))
            throw new IllegalArgumentException("Retained history continuation does not match the request");
        return snapshot.execute(tx -> {
            trips.gate(vehicle);
            var targets=jdbc.queryForList("select primary_device_id,source,status,finalization from trip_record where vehicle_id=? and id=?",vehicle,query.tripId());
            if (targets.isEmpty()) throw new IllegalArgumentException("Unknown retained history trip");
            var target=targets.getFirst();
            if (!target.get("status").equals("COMPLETED") || !target.get("finalization").equals("FINAL"))
                throw new IllegalStateException("Retained history requires a FINAL COMPLETED trip");
            boolean ready=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from trip_verified_archive where trip_id=?)",Boolean.class,query.tripId()));
            if (!ready) throw new IllegalStateException("Retained history requires independently VERIFIED aggregates");
            var args=new ArrayList<Object>(List.of(query.tripId(),seconds,Timestamp.from(floor(query.from(),seconds)),Timestamp.from(query.to())));
            String after=cursor==null?"":" and bucket_start>?";
            if (cursor!=null) args.add(Timestamp.from(cursor.afterBucket()));
            args.add(query.limit()+1);
            var rows=jdbc.query("select bucket_start,sample_count,first_observed_at,last_observed_at,metrics::text from trip_aggregate_bucket"
                    +" where trip_id=? and resolution_seconds=? and bucket_start>=? and bucket_start<?"+after+" order by bucket_start limit ?",
                    (r,n) -> new Bucket(r.getTimestamp(1).toInstant(),r.getLong(2),r.getTimestamp(3).toInstant(),r.getTimestamp(4).toInstant(),r.getString(5)),args.toArray());
            var points=new ArrayList<HistoryQueries.AggregatePoint>();
            var visible=rows.stream().limit(query.limit()).toList();
            int stored=0,clipped=0; long candidates=0;
            for (Bucket row : visible) {
                Instant end=row.start().plusSeconds(seconds);
                Instant from=query.from().isAfter(row.start())?query.from():row.start();
                Instant to=query.to().isBefore(end)?query.to():end;
                boolean partial=!from.equals(row.start()) || !to.equals(end);
                Bucket stats=row;
                if (partial) {
                    var result=clip(vehicle,(UUID)target.get("primary_device_id"),query.tripId(),from,to,MAX_CLIPPED_CANDIDATES-candidates);
                    candidates+=result.candidates(); stats=result.bucket(); clipped++;
                } else stored++;
                if (stats.count()>0) points.add(new HistoryQueries.AggregatePoint("aggregate",from,to,partial,stats.count(),metrics(stats.metrics()),
                        List.of(target.get("source").toString()),1,stats.first(),stats.last()));
            }
            Cursor next=rows.size()>query.limit()?new Cursor(vehicle,query.tripId(),query.from(),query.to(),query.resolution(),query.limit(),visible.getLast().start()):null;
            return new Page(points,next,stored,clipped,candidates);
        });
    }
    private Clipped clip(UUID vehicle,UUID device,UUID trip,Instant from,Instant to,long remaining) {
        // Cap metadata candidates BEFORE membership filtering or any payload lookup.
        String selected="select reading_id from telemetry_replay_identity where vehicle_id=? and device_id=? and recorded_at>=? and recorded_at<? order by recorded_at,reading_id limit ?";
        Object[] bounds={vehicle,device,Timestamp.from(from),Timestamp.from(to),remaining+1};
        long count=jdbc.queryForObject("select count(*) from ("+selected+") candidates",Long.class,bounds);
        if (count>remaining) throw new ApiFailure(503,"HISTORY_DENSITY_LIMIT",
                "Clipped retained history exceeds 10000 ledger candidates per page; narrow the range. No partial summary was returned",List.of());
        String sql="with candidates as materialized ("+selected+"), owned as materialized ("
                +"select e.recorded_at,e.payload from candidates c join trip_retained_evidence m on m.reading_id=c.reading_id and m.trip_id=?"
                +" join telemetry_replay_identity e on e.reading_id=c.reading_id) select count(*),min(recorded_at),max(recorded_at),"+stats()+" from owned";
        var args=new ArrayList<Object>(List.of(bounds)); args.add(trip);
        Bucket result=jdbc.queryForObject(sql,(r,n) -> new Bucket(from,r.getLong(1),r.getTimestamp(2)==null?null:r.getTimestamp(2).toInstant(),
                r.getTimestamp(3)==null?null:r.getTimestamp(3).toInstant(),r.getString(4)),args.toArray());
        return new Clipped(count,result);
    }
    private static String stats() {
        var fields=new ArrayList<String>();
        for (String name : TripRules.METRICS) {
            String value="(payload->>'"+name+"')::numeric";
            fields.add("'"+name+"',jsonb_build_object('count',count("+value+"),'sum',sum("+value+"),'min',min("+value+"),'max',max("+value+"))");
        }
        return "jsonb_build_object("+String.join(",",fields)+")";
    }
    private Map<String,TripRules.Metric> metrics(String value) {
        var result=new LinkedHashMap<String,TripRules.Metric>();
        var json=mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(value);
        for (String name : TripRules.METRICS) {
            var stat=json.get(name);
            result.put(name,new TripRules.Metric(stat.get("count").longValue(),decimal(stat.get("sum")),decimal(stat.get("min")),decimal(stat.get("max"))));
        }
        return Map.copyOf(result);
    }
    private static BigDecimal decimal(JsonNode value) { return value.isNull()?null:value.decimalValue(); }
    private static Instant floor(Instant value,int seconds) { return Instant.ofEpochSecond(Math.floorDiv(value.getEpochSecond(),seconds)*seconds); }
}
