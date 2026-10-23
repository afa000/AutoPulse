package dev.autopulse.trip;

import dev.autopulse.shared.ApiFailure;
import dev.autopulse.vehicle.VehicleRegistry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class TripQueries {
    public record Ratio(BigDecimal numerator,int denominator) {}
    public record Distance(boolean estimated,Ratio kilometres,BigDecimal coveredSeconds,BigDecimal durationSeconds) {}
    public record Trip(UUID tripId,String vehicleId,String primaryDeviceId,String source,Instant startedAt,Instant endedAt,Instant observedThrough,
                       Instant confirmedAt,String status,String completionReason,String algorithmVersion,long revision,String finalization,
                       int sampleCount,Map<String,TripRules.Metric> metrics,Distance distance,List<String> quality,List<UUID> supersededBy) {}
    public record TripPage(List<Trip> items,String nextCursor,String generation) {}
    public record Result<T>(T body,String processingState,String generation) {
        public Result(T body,String processingState) { this(body,processingState,null); }
    }
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final VehicleRegistry registry;
    private final TripCursor cursors;
    private final TransactionTemplate snapshot;
    public TripQueries(JdbcTemplate jdbc,ObjectMapper mapper,VehicleRegistry registry,TripCursor cursors,PlatformTransactionManager transactions) {
        this.jdbc=jdbc; this.mapper=mapper; this.registry=registry; this.cursors=cursors;
        snapshot=new TransactionTemplate(transactions); snapshot.setReadOnly(true); snapshot.setTimeout(10);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public Result<TripPage> list(String publicVehicle,int limit,String cursor) {
        if (limit<1 || limit>100) throw invalid("limit must be 1..100");
        return snapshot.execute(tx -> {
            UUID vehicle=vehicle(publicVehicle); gate(vehicle);
            long generation=jdbc.queryForObject("select generation from vehicle_read_generation where vehicle_id=?",Long.class,vehicle);
            var position=cursor==null ? null : cursors.decode(cursor,vehicle,limit);
            if (position!=null && position.generation()!=generation) throw new ApiFailure(409,"PAGE_CHANGED","Trip data changed; restart paging",List.of());
            String after=position==null ? "" : " and (t.started_at,t.id)<(?,?)";
            Object[] args=position==null ? new Object[]{vehicle,limit+1}
                    : new Object[]{vehicle,Timestamp.from(position.startedAt()),position.tripId(),limit+1};
            var rows=jdbc.queryForList("""
                    select t.*,d.public_id as device_public from trip_visible_record t join device d on d.id=t.primary_device_id
                    where t.vehicle_id=? and t.status in ('ACTIVE','COMPLETED')
                    """+after+" order by t.started_at desc,t.id desc limit ?",args);
            boolean more=rows.size()>limit;
            var visible=rows.stream().limit(limit).map(row -> trip(row,publicVehicle)).toList();
            String next=null;
            if (more) { var last=visible.getLast(); next=cursors.encode(vehicle,limit,new TripCursor.Position(generation,last.startedAt(),last.tripId())); }
            return new Result<>(new TripPage(visible,next,Long.toString(generation)),processing(vehicle),Long.toString(generation));
        });
    }
    public Result<Trip> detail(String publicVehicle,UUID trip) {
        return snapshot.execute(tx -> {
            UUID vehicle=vehicle(publicVehicle); gate(vehicle);
            String generation=jdbc.queryForObject("select generation::text from vehicle_read_generation where vehicle_id=?",String.class,vehicle);
            return new Result<>(trip(lookup(vehicle,trip),publicVehicle),processing(vehicle),generation);
        });
    }
    // These helpers must be used inside the calling read service's snapshot.
    Map<String,Object> lookup(UUID vehicle,UUID trip) {
        var rows=jdbc.queryForList("""
                    select t.*,d.public_id as device_public from trip_visible_record t join device d on d.id=t.primary_device_id
                    where t.vehicle_id=? and t.id=? and (t.status in ('ACTIVE','COMPLETED') or
                    (t.status='SUPERSEDED' and exists(select 1 from trip_publication_original o where o.trip_id=t.id and o.snapshot->>'status' in ('ACTIVE','COMPLETED'))))
                    """,vehicle,trip);
        if (rows.isEmpty()) throw new ApiFailure(404,"UNKNOWN_TRIP","Trip is not available for this vehicle",List.of());
        return rows.getFirst();
    }
    UUID vehicle(String publicId) { return registry.vehicle(publicId).orElseThrow(() -> new ApiFailure(404,"UNKNOWN_VEHICLE","Vehicle is not registered",List.of())); }
    void gate(UUID vehicle) {
        if (exists("select exists(select 1 from trip_correction_publication where vehicle_id=? and phase not in ('DONE','ABANDONED'))",vehicle))
            throw new ApiFailure(503,"PUBLICATION_PENDING","Trip correction is being published; retry later",List.of());
    }
    String processing(UUID vehicle) {
        if (exists("select exists(select 1 from trip_processing_control where vehicle_id=? and state='PAUSED')",vehicle)
                || exists("select exists(select 1 from trip_projection_progress where vehicle_id=? and needs_review)",vehicle)
                || exists("select exists(select 1 from trip_work_item where vehicle_id=? and status='REVIEW')",vehicle)) return "REVIEW";
        if (exists("select exists(select 1 from trip_backfill_progress where vehicle_id=? and not complete)",vehicle)
                || exists("select exists(select 1 from trip_work_item where vehicle_id=? and status='PENDING' and next_attempt_at<=clock_timestamp())",vehicle)) return "PENDING";
        return "CURRENT";
    }
    private Trip trip(Map<String,Object> row,String vehicle) {
        var summary=mapper.readValue(row.get("summary").toString(),TripRules.Summary.class);
        List<UUID> links=List.of();
        if (row.get("status").equals("SUPERSEDED")) {
            links=jdbc.query("select child_trip_id from trip_superseded_by where parent_trip_id=? order by child_trip_id limit 101",(rs,n) -> rs.getObject(1,UUID.class),row.get("id"));
            if (links.size()>100) throw new ApiFailure(503,"SERVICE_UNAVAILABLE","Trip replacement list exceeds supported bounds",List.of());
        }
        return new Trip((UUID)row.get("id"),vehicle,row.get("device_public").toString(),row.get("source").toString(),time(row,"started_at"),time(row,"ended_at"),
                time(row,"observed_through"),time(row,"confirmed_at"),row.get("status").toString(),(String)row.get("completion_reason"),row.get("algorithm_version").toString(),
                ((Number)row.get("revision")).longValue(),row.get("finalization").toString(),summary.sampleCount(),summary.metrics(),
                new Distance(true,summary.coveredSeconds().signum()==0 ? null : new Ratio(summary.speedAreaKphSeconds(),3600),summary.coveredSeconds(),summary.durationSeconds()),
                List.of(mapper.readValue(row.get("quality").toString(),String[].class)),links);
    }
    private boolean exists(String sql,Object... args) { return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args)); }
    private static Instant time(Map<String,Object> row,String key) { return row.get(key)==null ? null : ((Timestamp)row.get(key)).toInstant(); }
    static ApiFailure invalid(String message) { return new ApiFailure(400,"INVALID_QUERY",message,List.of()); }
}
