package dev.autopulse.trip;

import dev.autopulse.shared.ApiFailure;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;

/** Internal ownership proof reader, not a public archive/expiration switch or pruner. */
@Service
public class OwnedVehicleHistory {
    /** Trusted in-process context. Not a signed public API cursor or retention certificate. */
    public record Cursor(UUID vehicle,Instant from,Instant to,String resolution,int limit,long generation,Instant after) {}
    public record Page(List<HistoryQueries.AggregatePoint> points,Cursor nextCursor,long generation,
                       long rawOwned,long archiveOwned,long rawCandidates,long ledgerCandidates,long unownedLedgerCandidates,
                       int reusedContributions,long archiveRecomputed) {
        public Page { points=List.copyOf(points); }
    }
    private static final int MAX_CANDIDATES=10000;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper mapper;
    private final TripQueries trips;
    private final TransactionTemplate snapshot;
    public OwnedVehicleHistory(JdbcTemplate jdbc,ObjectMapper mapper,TripQueries trips,PlatformTransactionManager transactions) {
        this.jdbc=jdbc; named=new NamedParameterJdbcTemplate(jdbc); this.mapper=mapper; this.trips=trips;
        snapshot=new TransactionTemplate(transactions);
        snapshot.setReadOnly(true); snapshot.setTimeout(10);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Page read(String publicVehicle,HistoryRequest query,Cursor cursor) {
        return snapshot.execute(tx -> readInSnapshot(publicVehicle,query,cursor));
    }
    // Calling service must own a read-only REPEATABLE READ transaction.
    Page readInSnapshot(String publicVehicle,HistoryRequest query,Cursor cursor) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || !org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(
                        org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))
            throw new IllegalStateException("Owned history requires a read-only REPEATABLE READ snapshot");
        if (publicVehicle==null || query==null || query.tripId()!=null || query.resolution().equals("raw") || query.cursor()!=null)
            throw new IllegalArgumentException("Owned vehicle history requires an unfiltered aggregate request and separate trusted cursor");
        if (query.from().isBefore(Instant.parse("0001-01-01T00:00:00Z")) || query.to().isAfter(Instant.parse("9999-12-31T23:59:59.999999Z")))
            throw new IllegalArgumentException("Owned history requires four-digit UTC years");
        int seconds=query.resolution().equals("1m")?60:300;
            UUID vehicle=trips.vehicle(publicVehicle); trips.gate(vehicle);
            long generation=jdbc.queryForObject("select generation from vehicle_read_generation where vehicle_id=?",Long.class,vehicle);
            Instant after=query.from();
            if (cursor!=null) {
                if (!vehicle.equals(cursor.vehicle()) || !query.from().equals(cursor.from()) || !query.to().equals(cursor.to())
                        || !query.resolution().equals(cursor.resolution()) || query.limit()!=cursor.limit() || cursor.generation()<0
                        || cursor.after()==null || !floor(cursor.after(),seconds).equals(cursor.after())
                        || !cursor.after().isAfter(query.from()) || !cursor.after().isBefore(query.to()))
                    throw new IllegalArgumentException("Owned history continuation does not match the request");
                if (generation!=cursor.generation()) throw failure(409,"PAGE_CHANGED","Vehicle history changed; restart paging");
                after=cursor.after();
            }
            var p=new MapSqlParameterSource().addValue("vehicle",vehicle).addValue("from",Timestamp.from(after))
                    .addValue("to",Timestamp.from(query.to())).addValue("rawLimit",MAX_CANDIDATES+1);
            // Both seeks are bounded even when a ledger-only candidate is not archive-owned.
            Instant first=first(p);
            if (first==null) return new Page(List.of(),null,generation,0,0,0,0,0,0,0);
            Instant pageEnd=floor(first,seconds).plusSeconds((long)seconds*query.limit());
            if (pageEnd.isAfter(query.to())) pageEnd=query.to();
            p.addValue("end",Timestamp.from(pageEnd));
            long rawCount=named.queryForObject("select count(*) from ("+rawCandidates()+") r",p,Long.class);
            if (rawCount>MAX_CANDIDATES) throw density();
            p.addValue("rawLimit",rawCount).addValue("ledgerLimit",MAX_CANDIDATES-rawCount+1);
            long ledgerCount=named.queryForObject("select count(*) from ("+ledgerCandidates()+") e",p,Long.class);
            if (rawCount+ledgerCount>MAX_CANDIDATES) throw density();
            p.addValue("ledgerLimit",ledgerCount);
            String candidates="with raw_ids as materialized ("+rawCandidates()+"), ledger_ids as materialized ("+ledgerCandidates()+"), "
                    +"owner_ids as materialized (select id reading_id from raw_ids union select reading_id from ledger_ids), "
                    +"owners as materialized (select e.reading_id,m.trip_id from owner_ids e join trip_retained_evidence m on m.reading_id=e.reading_id "
                    +"join trip_verified_archive ready on ready.trip_id=m.trip_id), "
                    +"owned_input as materialized (select o.reading_id,o.trip_id,e.recorded_at,e.device_id,e.source,"+bucket(seconds,"e.recorded_at")+" bucket "
                    +"from owners o join ledger_ids i on i.reading_id=o.reading_id join telemetry_replay_identity e on e.reading_id=i.reading_id), "
                    +"full_keys as materialized (select trip_id,bucket,count(*) n,min(recorded_at) first_at,max(recorded_at) last_at from owned_input "
                    +"where bucket>=:from and bucket+interval '"+seconds+" seconds'<=:end group by trip_id,bucket) ";
            p.addValue("seconds",seconds);
            var counts=named.queryForMap(candidates+"""
                    select (select count(*) from raw_ids r where not exists(select 1 from owners o where o.reading_id=r.id)) raw_owned,
                        (select count(*) from owners o join ledger_ids e on e.reading_id=o.reading_id) archive_owned,
                        (select count(*) from ledger_ids e where not exists(select 1 from owners o where o.reading_id=e.reading_id)) unowned,
                        (select count(*) from ledger_ids e where not exists(select 1 from owners o where o.reading_id=e.reading_id)
                            and not exists(select 1 from telemetry_reading r where r.id=e.reading_id and r.vehicle_id=:vehicle)) gaps,
                        (select count(*) from owners o join telemetry_replay_identity e on e.reading_id=o.reading_id
                            join telemetry_reading r on r.id=e.reading_id where
                            (r.vehicle_id,r.device_id,r.boot_id,r.sequence_number,r.recorded_at,r.received_at,r.source,r.payload)
                            is distinct from (e.vehicle_id,e.device_id,e.boot_id,e.sequence_number,e.recorded_at,e.received_at,e.source,e.payload)) mismatches,
                        (select count(*) from full_keys) reused,
                        (select coalesce(sum(n),0) from full_keys) cached_samples,
                        (select count(*) from full_keys k join trip_record t on t.id=k.trip_id
                            left join trip_aggregate_bucket b on b.trip_id=k.trip_id and b.resolution_seconds=:seconds and b.bucket_start=k.bucket
                            where b.trip_id is null or (b.sample_count,b.first_observed_at,b.last_observed_at) is distinct from (k.n,k.first_at,k.last_at)
                                or b.vehicle_id<>:vehicle or b.device_id<>t.primary_device_id or b.source<>t.source) bad_cache
                    """,p);
            if (((Number)counts.get("gaps")).longValue()>0)
                throw failure(503,"HISTORY_OWNERSHIP_GAP","Ledger candidates have neither raw data nor a verified archive owner; no partial summary returned");
            if (((Number)counts.get("mismatches")).longValue()>0)
                throw failure(503,"HISTORY_OWNERSHIP_MISMATCH","Raw and archive identity evidence differs; no partial summary returned");
            if (((Number)counts.get("bad_cache")).longValue()>0)
                throw failure(503,"HISTORY_OWNERSHIP_GAP","Verified full-bucket contribution is absent or inconsistent with owned membership; no partial summary returned");
            String selected=candidates+"""
                    , residual as materialized (
                        select r.recorded_at,r.device_id,r.source,r.payload from raw_ids i join telemetry_reading r on r.id=i.id
                        where not exists(select 1 from owners o where o.reading_id=r.id)
                        union all
                        select i.recorded_at,i.device_id,i.source,e.payload from owned_input i
                            join telemetry_replay_identity e on e.reading_id=i.reading_id
                        where not exists(select 1 from full_keys k where k.trip_id=i.trip_id and k.bucket=i.bucket)
                    )
                    """+", contributions as materialized (select "+bucket(seconds,"recorded_at")+" bucket,device_id,source,count(*) sample_count,"
                    +"min(recorded_at) first_at,max(recorded_at) last_at,"+stats(false)+" metrics from residual group by bucket,device_id,source "
                    +"union all select b.bucket_start,b.device_id,b.source,b.sample_count,b.first_observed_at,b.last_observed_at,b.metrics "
                    +"from full_keys k join trip_aggregate_bucket b on b.trip_id=k.trip_id and b.resolution_seconds=:seconds and b.bucket_start=k.bucket) "
                    +"select bucket,sum(sample_count),count(distinct device_id),min(first_at),max(last_at),array_agg(distinct source order by source),"
                    +stats(true)+" from contributions group by bucket order by bucket";
            var points=named.query(selected,p,(r,n) -> {
                Instant start=r.getTimestamp(1).toInstant(),end=start.plusSeconds(seconds);
                Instant clippedStart=start.isBefore(query.from())?query.from():start,clippedEnd=end.isAfter(query.to())?query.to():end;
                var metrics=new LinkedHashMap<String,TripRules.Metric>();
                var json=mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(r.getString(7));
                for (String name : TripRules.METRICS) {
                    var m=json.get(name);
                    metrics.put(name,new TripRules.Metric(m.get("count").longValue(),decimal(m.get("sum")),decimal(m.get("min")),decimal(m.get("max"))));
                }
                return new HistoryQueries.AggregatePoint("aggregate",clippedStart,clippedEnd,!clippedStart.equals(start)||!clippedEnd.equals(end),
                        r.getLong(2),java.util.Map.copyOf(metrics),List.of((String[])r.getArray(6).getArray()),r.getInt(3),r.getTimestamp(4).toInstant(),r.getTimestamp(5).toInstant());
            });
            p.addValue("from",Timestamp.from(pageEnd));
            boolean more=pageEnd.isBefore(query.to()) && first(p)!=null;
            Cursor next=more?new Cursor(vehicle,query.from(),query.to(),query.resolution(),query.limit(),generation,pageEnd):null;
            return new Page(points,next,generation,((Number)counts.get("raw_owned")).longValue(),((Number)counts.get("archive_owned")).longValue(),
                    rawCount,ledgerCount,((Number)counts.get("unowned")).longValue(),((Number)counts.get("reused")).intValue(),
                    ((Number)counts.get("archive_owned")).longValue()-((Number)counts.get("cached_samples")).longValue());
    }
    private Instant first(MapSqlParameterSource p) {
        var times=named.query("""
                select recorded_at from (
                    (select recorded_at from telemetry_reading where vehicle_id=:vehicle and recorded_at>=:from and recorded_at<:to
                        order by recorded_at,device_id,boot_id,sequence_number limit 1)
                    union all
                    (select recorded_at from telemetry_replay_identity where vehicle_id=:vehicle and recorded_at>=:from and recorded_at<:to
                        order by recorded_at,reading_id limit 1)
                ) candidates order by recorded_at limit 1
                """,p,(r,n) -> r.getTimestamp(1).toInstant());
        return times.isEmpty()?null:times.getFirst();
    }
    private static String rawCandidates() {
        return "select id from telemetry_reading where vehicle_id=:vehicle and recorded_at>=:from and recorded_at<:end order by recorded_at,device_id,boot_id,sequence_number limit :rawLimit";
    }
    private static String ledgerCandidates() {
        return "select reading_id from telemetry_replay_identity where vehicle_id=:vehicle and recorded_at>=:from and recorded_at<:end order by recorded_at,reading_id limit :ledgerLimit";
    }
    private static String bucket(int seconds,String value) {
        return "date_bin(interval '"+seconds+" seconds',"+value+",timestamptz '1970-01-01 00:00:00+00')";
    }
    private static String stats(boolean merge) {
        var fields=new ArrayList<String>();
        for (String name : TripRules.METRICS) {
            String v="(payload->>'"+name+"')::numeric";
            String metric="metrics->'"+name+"'";
            fields.add("'"+name+"',jsonb_build_object('count',"+(merge?"sum(("+metric+"->>'count')::bigint)":"count("+v+")")
                    +",'sum',sum("+(merge?"("+metric+"->>'sum')::numeric":v)+"),'min',min("+(merge?"("+metric+"->>'min')::numeric":v)
                    +"),'max',max("+(merge?"("+metric+"->>'max')::numeric":v)+"))");
        }
        return "jsonb_build_object("+String.join(",",fields)+")";
    }
    private static BigDecimal decimal(JsonNode v) { return v.isNull()?null:v.decimalValue(); }
    private static Instant floor(Instant v,int seconds) { return Instant.ofEpochSecond(Math.floorDiv(v.getEpochSecond(),seconds)*seconds); }
    private static ApiFailure density() { return failure(503,"HISTORY_DENSITY_LIMIT","Owned history exceeds 10000 combined raw/ledger metadata candidates; narrow range or limit. No partial summary returned"); }
    private static ApiFailure failure(int status,String code,String message) { return new ApiFailure(status,code,message,List.of()); }
}
