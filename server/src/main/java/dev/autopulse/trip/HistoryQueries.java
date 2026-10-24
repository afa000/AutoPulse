package dev.autopulse.trip;

import dev.autopulse.shared.ApiFailure;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;

@Service
public class HistoryQueries {
    public sealed interface Point permits RawPoint,AggregatePoint {}
    public record RawPoint(String kind,JsonNode reading,Instant receivedAt) implements Point {}
    public record AggregatePoint(String kind,Instant bucketStart,Instant bucketEnd,boolean partial,long sampleCount,
                                 Map<String,TripRules.Metric> metrics,List<String> sources,int deviceCount,
                                 Instant firstObservedAt,Instant lastObservedAt) implements Point {}
    public record Availability(String rawState,Instant rawUnavailableBefore,List<String> availableResolutions,boolean incomplete) {}
    public record Page(Instant from,Instant to,String resolution,List<? extends Point> points,String nextCursor,String generation,Availability availability) {}
    private record Row(UUID id,RawPoint point) {}
    private record AggregateRow(UUID anchor,AggregatePoint point) {}
    private record Scope(String sql,List<Object> args,TripReadingSource source) {}
    static final int MAX_AGGREGATE_READINGS=100000;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TripQueries trips;
    private final RawHistoryCursor cursors;
    private final TransactionTemplate snapshot;
    public HistoryQueries(JdbcTemplate jdbc,ObjectMapper mapper,TripQueries trips,RawHistoryCursor cursors,PlatformTransactionManager transactions) {
        this.jdbc=jdbc; this.mapper=mapper; this.trips=trips; this.cursors=cursors;
        snapshot=new TransactionTemplate(transactions); snapshot.setReadOnly(true); snapshot.setTimeout(10);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public TripQueries.Result<Page> raw(String publicVehicle,HistoryRequest query) {
        if (!query.resolution().equals("raw")) throw TripQueries.invalid("Expected raw resolution");
        return history(publicVehicle,query);
    }
    public TripQueries.Result<Page> history(String publicVehicle,HistoryRequest query) {
        return snapshot.execute(tx -> {
            UUID vehicle=trips.vehicle(publicVehicle); trips.gate(vehicle);
            long generation=jdbc.queryForObject("select generation from vehicle_read_generation where vehicle_id=?",Long.class,vehicle);
            var cursor=query.cursor()==null ? null : cursors.decode(query.cursor(),vehicle,query);
            if (cursor!=null && cursor.generation()!=generation) throw changed("History changed; restart paging");
            Scope scope=scope(vehicle,query);
            if (!query.resolution().equals("raw")) return aggregate(vehicle,query,generation,cursor,scope);
            StringBuilder sql=new StringBuilder("select r.id,r.payload::text,r.received_at from "+scope.source().table()+" r where "+scope.sql());
            var args=new ArrayList<Object>(scope.args());
            if (cursor!=null) {
                // A PK anchor binds the complete native tuple, even when its
                // arbitrary-size NUMERIC sequence cannot fit in a small token.
                var anchors=jdbc.queryForList("select recorded_at,device_id,boot_id,sequence_number from "+scope.source().table()+" where id=? and vehicle_id=?",cursor.readingId(),vehicle);
                if (anchors.isEmpty()) throw changed("History cursor anchor is no longer available; restart paging");
                var anchor=anchors.getFirst();
                sql.append(" and (r.recorded_at,r.device_id,r.boot_id,r.sequence_number)>(?,?,?,?)");
                Collections.addAll(args,anchor.get("recorded_at"),anchor.get("device_id"),anchor.get("boot_id"),anchor.get("sequence_number"));
            }
            sql.append(" order by r.recorded_at,r.device_id,r.boot_id,r.sequence_number limit ?"); args.add(query.limit()+1);
            var rows=jdbc.query(sql.toString(),(row,n) -> new Row(row.getObject(1,UUID.class),new RawPoint("raw",
                    mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(row.getString(2)),row.getTimestamp(3).toInstant())),args.toArray());
            boolean more=rows.size()>query.limit(); var visible=rows.stream().limit(query.limit()).toList();
            String next=more ? cursors.encode(vehicle,query,new RawHistoryCursor.Position(generation,visible.getLast().id())) : null;
            String processing=trips.processing(vehicle);
            return new TripQueries.Result<>(new Page(query.from(),query.to(),"raw",visible.stream().map(Row::point).toList(),next,Long.toString(generation),
                    availability(jdbc,vehicle,query,processing)),processing);
        });
    }
    private Scope scope(UUID vehicle,HistoryRequest query) {
        String sql="r.vehicle_id=? and r.recorded_at>=? and r.recorded_at<?";
        var args=new ArrayList<Object>(List.of(vehicle,Timestamp.from(query.from()),Timestamp.from(query.to())));
        var source=TripReadingSource.RAW;
        if (!query.resolution().equals("raw") && Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from telemetry_raw_expiry where vehicle_id=?)",Boolean.class,vehicle))) {
            source=TripReadingSource.HISTORY;
        }
        if (query.tripId()!=null) {
            var trip=trips.lookup(vehicle,query.tripId());
            if (trip.get("status").equals("SUPERSEDED")) throw changed("Trip was superseded; inspect replacement links before requesting its history");
            // Trip readiness, not vehicle backfill or the optional whole-vehicle
            // ownership flag. Keep one source throughout this repeatable snapshot.
            if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from trip_verified_archive where trip_id=? and vehicle_id=?)",Boolean.class,query.tripId(),vehicle)))
                source=TripReadingSource.LEDGER;
            sql+=" and r.device_id=? and r.recorded_at>=? and r.recorded_at<=? and exists(select 1 from trip_evidence e where e.trip_id=? and e.reading_id=r.id)";
            Collections.addAll(args,trip.get("primary_device_id"),trip.get("started_at"),trip.get("ended_at")==null ? trip.get("observed_through") : trip.get("ended_at"),query.tripId());
        }
        // Actual raw rows are removed atomically with expiry; do not add an
        // anti-join to the ordinary ordered index seek. Only a trip's retained
        // ledger source could otherwise expose a physically expired raw point.
        if (query.resolution().equals("raw") && source==TripReadingSource.LEDGER)
            sql+=" and not exists(select 1 from telemetry_raw_expiry x where x.reading_id=r.id)";
        return new Scope(sql,args,source);
    }
    private TripQueries.Result<Page> aggregate(UUID vehicle,HistoryRequest query,long generation,RawHistoryCursor.Position cursor,Scope scope) {
        int seconds=query.resolution().equals("1m") ? 60 : 300;
        Instant after=query.from();
        if (cursor!=null) {
            var anchors=jdbc.queryForList("select recorded_at from "+scope.source().table()+" where id=? and vehicle_id=?",cursor.readingId(),vehicle);
            if (anchors.isEmpty()) throw changed("History cursor anchor is no longer available; restart paging");
            after=floor(((Timestamp)anchors.getFirst().get("recorded_at")).toInstant(),seconds).plusSeconds(seconds);
        }
        // Seek over empty spans, then restrict this page to at most limit UTC buckets.
        var seek=new ArrayList<Object>(scope.args()); seek.add(Timestamp.from(after));
        var first=jdbc.query("select r.recorded_at from "+scope.source().table()+" r where "+scope.sql()+" and r.recorded_at>=? order by r.recorded_at,r.device_id,r.boot_id,r.sequence_number limit 1",
                (rs,n) -> rs.getTimestamp(1).toInstant(),seek.toArray());
        String processing=trips.processing(vehicle);
        if (first.isEmpty()) return new TripQueries.Result<>(new Page(query.from(),query.to(),query.resolution(),List.of(),null,Long.toString(generation),availability(jdbc,vehicle,query,processing)),processing);
        Instant pageEnd=floor(first.getFirst(),seconds).plusSeconds((long)seconds*query.limit());
        if (pageEnd.isAfter(query.to())) pageEnd=query.to();
        var args=new ArrayList<Object>(seek); args.add(Timestamp.from(pageEnd));
        // The MATERIALIZED row cap bounds numeric aggregation and prevents silently
        // returning a bucket built from only a prefix of unusually dense input.
        StringBuilder metrics=new StringBuilder("jsonb_build_object(");
        for (String field : TripRules.METRICS) {
            if (metrics.length()>19) metrics.append(',');
            String value="(payload->>'"+field+"')::numeric";
            metrics.append("'").append(field).append("',jsonb_build_object('count',count(").append(value)
                    .append("),'sum',sum(").append(value).append("),'min',min(").append(value).append("),'max',max(").append(value).append("))");
        }
        metrics.append(')');
        String sql="with selected as materialized (select r.id,r.recorded_at,r.device_id,r.payload from "+scope.source().table()+" r where "+scope.sql()
                +" and r.recorded_at>=? and r.recorded_at<? order by r.recorded_at,r.device_id,r.boot_id,r.sequence_number limit "+(MAX_AGGREGATE_READINGS+1)
                +") select date_bin(interval '"+seconds+" seconds',recorded_at,timestamptz '1970-01-01 00:00:00+00') as bucket,"
                +"max(id::text),count(*),count(distinct device_id),min(recorded_at),max(recorded_at),array_agg(distinct payload->>'source'),"+metrics
                +" from selected group by bucket order by bucket";
        var rows=jdbc.query(sql,(rs,n) -> {
            Instant aligned=rs.getTimestamp(1).toInstant(),end=aligned.plusSeconds(seconds);
            Instant clippedStart=aligned.isBefore(query.from()) ? query.from() : aligned;
            Instant clippedEnd=end.isAfter(query.to()) ? query.to() : end;
            var stats=new LinkedHashMap<String,TripRules.Metric>();
            JsonNode json=mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(rs.getString(8));
            for (String name : TripRules.METRICS) {
                JsonNode m=json.get(name);
                stats.put(name,new TripRules.Metric(m.get("count").longValue(),decimal(m.get("sum")),decimal(m.get("min")),decimal(m.get("max"))));
            }
            return new AggregateRow(UUID.fromString(rs.getString(2)),new AggregatePoint("aggregate",clippedStart,clippedEnd,
                    !clippedStart.equals(aligned) || !clippedEnd.equals(end),rs.getLong(3),stats,
                    List.of((String[])rs.getArray(7).getArray()),rs.getInt(4),rs.getTimestamp(5).toInstant(),rs.getTimestamp(6).toInstant()));
        },args.toArray());
        checkAggregateSize(rows.stream().mapToLong(row -> row.point().sampleCount()).sum());
        var later=new ArrayList<Object>(scope.args()); later.add(Timestamp.from(pageEnd));
        boolean more=jdbc.queryForObject("select exists(select 1 from "+scope.source().table()+" r where "+scope.sql()+" and r.recorded_at>=?)",Boolean.class,later.toArray());
        String next=more ? cursors.encode(vehicle,query,new RawHistoryCursor.Position(generation,rows.getLast().anchor())) : null;
        return new TripQueries.Result<>(new Page(query.from(),query.to(),query.resolution(),rows.stream().map(AggregateRow::point).toList(),next,
                Long.toString(generation),availability(jdbc,vehicle,query,processing)),processing);
    }
    static void checkAggregateSize(long count) {
        if (count>MAX_AGGREGATE_READINGS) throw new ApiFailure(503,"HISTORY_DENSITY_LIMIT","Aggregate page exceeds 100000 readings; reduce limit or request a narrower range. No partial summary was returned",List.of());
    }
    private static Instant floor(Instant value,int seconds) { return Instant.ofEpochSecond(Math.floorDiv(value.getEpochSecond(),seconds)*seconds); }
    private static java.math.BigDecimal decimal(JsonNode value) { return value.isNull() ? null : value.decimalValue(); }
    static Availability availability(String processing) { return new Availability("AVAILABLE",null,List.of("raw","1m","5m"),!processing.equals("CURRENT")); }
    static Availability availability(JdbcTemplate jdbc,UUID vehicle,HistoryRequest query,String processing) {
        String filter="x.vehicle_id=? and x.recorded_at>=? and x.recorded_at<?";
        var args=new ArrayList<Object>(List.of(vehicle,Timestamp.from(query.from()),Timestamp.from(query.to())));
        if (query.tripId()!=null) { filter+=" and exists(select 1 from trip_retained_evidence m where m.reading_id=x.reading_id and m.trip_id=?)"; args.add(query.tripId()); }
        var end=jdbc.queryForObject("select max(x.recorded_at)+interval '1 microsecond' from telemetry_raw_expiry x where "+filter,Timestamp.class,args.toArray());
        if (end==null) return availability(processing);
        String raw="r.vehicle_id=? and r.recorded_at>=? and r.recorded_at<?";
        if (query.tripId()!=null) raw+=" and exists(select 1 from trip_evidence e where e.reading_id=r.id and e.trip_id=?)";
        boolean present=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from telemetry_reading r where "+raw+")",Boolean.class,args.toArray()));
        // Upper bound on expired observations in this range, NOT a contiguous
        // vehicle-wide cutoff: protected observations may remain before it.
        return new Availability(present?"PARTIALLY_EXPIRED":"EXPIRED",end.toInstant(),present?List.of("raw","1m","5m"):List.of("1m","5m"),!processing.equals("CURRENT"));
    }
    private static ApiFailure changed(String message) { return new ApiFailure(409,"PAGE_CHANGED",message,List.of()); }
}
