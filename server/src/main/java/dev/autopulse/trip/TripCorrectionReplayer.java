package dev.autopulse.trip;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;
import static dev.autopulse.trip.TripRules.*;

/** Isolated, resumable replay only. Does not publish or acknowledge correction work. */
@Service
public class TripCorrectionReplayer {
    public record Page(UUID replayId, String state, int visited) {}
    private record Reading(UUID id, Observation event) {}
    public static final int MAX_PAGE = 100;
    private static final Instant MIN = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX = Instant.parse("9999-12-31T23:59:59.999999Z");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public TripCorrectionReplayer(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager transactions) {
        this.jdbc = jdbc; this.mapper = mapper;
        transaction = new TransactionTemplate(transactions); transaction.setTimeout(10);
    }

    public Page replayPage(UUID vehicle, int limit) {
        if (vehicle == null || limit < 1 || limit > MAX_PAGE) throw new IllegalArgumentException("vehicle and limit 1..100 required");
        return transaction.execute(status -> replay(vehicle, limit));
    }

    private Page replay(UUID vehicle, int limit) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtextextended(cast(? as text),902))",
                Boolean.class, vehicle.toString()))) return new Page(null, "BUSY", 0);
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from trip_correction_publication where vehicle_id=? and phase not in ('DONE','ABANDONED'))",
                Boolean.class,vehicle))) return new Page(null,"PUBLICATION_PENDING",0);
        var policies = jdbc.queryForList("select primary_device_id,revision from trip_policy where vehicle_id=? for share", vehicle);
        if (policies.isEmpty()) return new Page(null, "NO_PRIMARY_DEVICE", 0);
        var policy = policies.getFirst();
        long generation = jdbc.queryForObject("select ingest_generation from trip_vehicle_activity where vehicle_id=? for update", Long.class, vehicle);
        var plans = jdbc.queryForList("select * from trip_correction_plan where vehicle_id=? and phase in ('TRIPS','INPUTS','READY') for update", vehicle);
        if (plans.isEmpty()) return new Page(null, "NO_PLAN", 0);
        var plan = plans.getFirst(); UUID planId = (UUID) plan.get("id");
        if (!policy.get("primary_device_id").equals(plan.get("primary_device_id")) || !policy.get("revision").equals(plan.get("policy_revision"))) {
            invalidate(planId);
            jdbc.update("update trip_correction_plan set phase='REVIEW_POLICY_CHANGED',current_replay_id=null where id=?", planId);
            return new Page(null, "REVIEW_POLICY_CHANGED", 0);
        }
        if (((Number) plan.get("input_generation")).longValue() != generation) {
            invalidate(planId);
            jdbc.update("""
                    update trip_correction_plan set phase='TRIPS',input_generation=?,current_replay_id=null,
                    trip_cursor_at=null,trip_cursor_id=null,input_cursor_at=null,input_cursor_id=null where id=?
                    """, generation, planId);
            return new Page(null, "REPLAN_REQUIRED", 0);
        }
        if (!plan.get("phase").equals("READY")) return new Page(null, "PLAN_NOT_READY", 0);
        Instant from = lower(instant(plan,"window_start"));
        Instant to = upper(instant(plan,"window_end"), 30);
        Instant asOf = instant(plan,"planned_as_of");
        if (upper(asOf, 2).isBefore(to)) to = upper(asOf, 2);
        boolean finalized = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from trip_record where vehicle_id=? and finalization='FINAL'
                and started_at<=? and observed_through>=?)
                """, Boolean.class, vehicle, stamp(to), stamp(from)));
        if (finalized) {
            invalidate(planId);
            jdbc.update("update trip_correction_plan set phase='REVIEW_FINALIZED',current_replay_id=null where id=?", planId);
            return new Page(null, "REVIEW_FINALIZED", 0);
        }
        UUID id = (UUID) plan.get("current_replay_id");
        if (id == null) {
            id = UUID.randomUUID();
            jdbc.update("""
                    insert into trip_correction_replay(id,plan_id,input_generation,window_start,window_end,planned_as_of,
                    read_from,read_to,status,checkpoint) values (?,?,?,?,?,?,?,?,'RUNNING',cast(? as jsonb))
                    """, id, planId, generation, plan.get("window_start"), plan.get("window_end"), plan.get("planned_as_of"),
                    stamp(from), stamp(to), json(TripFold.Checkpoint.empty()));
            jdbc.update("update trip_correction_plan set current_replay_id=? where id=?", id, planId);
        }
        var saved = jdbc.queryForMap("select * from trip_correction_replay where id=? for update", id);
        if (((Number) saved.get("input_generation")).longValue() != generation || !saved.get("window_start").equals(plan.get("window_start"))
                || !saved.get("window_end").equals(plan.get("window_end")) || !saved.get("planned_as_of").equals(plan.get("planned_as_of")))
            throw new IllegalStateException("replay/plan snapshot mismatch");
        if (saved.get("status").equals("STAGED")) return new Page(id, "STAGED", 0);
        if (!saved.get("status").equals("RUNNING")) throw new IllegalStateException("invalidated replay cannot resume");
        var fold = mapper.readValue(saved.get("checkpoint").toString(), TripFold.Checkpoint.class);
        UUID current = (UUID) saved.get("current_segment_id");
        String after = saved.get("cursor_at") == null ? "" : " and recorded_at>?";
        Object[] args = saved.get("cursor_at") == null
                ? new Object[]{vehicle, plan.get("primary_device_id"), saved.get("read_from"), saved.get("read_to"), limit}
                : new Object[]{vehicle, plan.get("primary_device_id"), saved.get("read_from"), saved.get("read_to"), saved.get("cursor_at"), limit};
        // DISTINCT ON selects the complete PostgreSQL-ordered winner at each
        // instant. The page cursor advances by distinct time, not a truncated tie.
        var source=TripReadingSource.forVehicle(jdbc,vehicle);
        var readings = jdbc.query("""
                select distinct on (recorded_at) id,recorded_at,device_id,boot_id,sequence_number,source,payload::text
                from %s where vehicle_id=? and device_id=? and recorded_at>=? and recorded_at<=?
                """.formatted(source.table()) + after + " order by recorded_at,boot_id desc,sequence_number desc limit ?", (row,n) -> {
                    var payload = mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(row.getString(7));
                    var metrics = new HashMap<String, BigDecimal>();
                    for (String field : METRICS) if (payload.hasNonNull(field)) metrics.put(field, payload.get(field).decimalValue());
                    return new Reading(row.getObject(1, UUID.class), new Observation(row.getTimestamp(2).toInstant(), row.getObject(3, UUID.class),
                            row.getObject(4, UUID.class), row.getBigDecimal(5).toBigIntegerExact(), row.getString(6), metrics));
                }, args);
        for (var reading : readings) {
            var change = TripFold.step(fold, reading.event(), DEFAULT);
            current = stage(id, fold, change, current, reading.id()); fold = change.next();
        }
        boolean done = readings.size() < limit;
        if (done) {
            var change = TripFold.tick(fold, asOf, DEFAULT);
            current = stage(id, fold, change, current, null); fold = change.next();
        }
        jdbc.update("""
                update trip_correction_replay set cursor_at=?,checkpoint=cast(? as jsonb),current_segment_id=?,
                visited_rows=visited_rows+?,status=? where id=?
                """, readings.isEmpty() ? saved.get("cursor_at") : stamp(readings.getLast().event().at()), json(fold), current,
                readings.size(), done ? "STAGED" : "RUNNING", id);
        return new Page(id, done ? "STAGED" : "RUNNING", readings.size());
    }

    private UUID stage(UUID replay, TripFold.Checkpoint prior, TripFold.Change change, UUID current, UUID reading) {
        if (change.completed() != null) {
            var trip = change.completed();
            save(current,"COMPLETED",trip.boundary().end().at(),trip.boundary().confirmedAt(),trip.boundary().reason().name(),
                    trip.boundary().end().at(),trip.summary(),trip.boundary().quality()); current = null;
        }
        var next = change.next();
        if (current != null && prior.state().first() != null && (next.state().first() == null || !prior.state().first().equals(next.state().first()))) {
            jdbc.update("update trip_correction_segment set status='DISCARDED' where id=?", current); current = null;
        }
        if (next.state().phase() == Phase.IDLE) return null;
        if (current == null) {
            current = UUID.randomUUID(); var first = next.state().first();
            jdbc.update("""
                    insert into trip_correction_segment(id,replay_id,anchor_boot,anchor_sequence,started_at,observed_through,
                    source,status,summary,quality) values (?,?,?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb))
                    """, current, replay, first.boot(), new BigDecimal(first.sequence()), stamp(first.at()), stamp(next.state().last().at()),
                    first.source(),next.state().active() ? "ACTIVE" : "CANDIDATE",json(next.current().summary()),json(next.state().quality()));
        } else save(current,next.state().active() ? "ACTIVE" : "CANDIDATE",null,null,null,next.state().last().at(),next.current().summary(),next.state().quality());
        if (reading != null) jdbc.update("insert into trip_correction_reading(replay_id,reading_id,segment_id) values (?,?,?)", replay, reading, current);
        return current;
    }
    private void save(UUID id, String status, Instant end, Instant confirmed, String reason, Instant observed, Summary summary, java.util.Set<String> quality) {
        jdbc.update("""
                update trip_correction_segment set status=?,ended_at=?,confirmed_at=?,completion_reason=?,observed_through=?,
                summary=cast(? as jsonb),quality=cast(? as jsonb) where id=?
                """, status, stamp(end), stamp(confirmed), reason, stamp(observed), json(summary), json(quality), id);
    }
    private void invalidate(UUID plan) { jdbc.update("update trip_correction_replay set status='INVALIDATED' where plan_id=? and status in ('RUNNING','STAGED')", plan); }
    private String json(Object value) { return mapper.writeValueAsString(value); }
    private static Instant instant(Map<String,Object> row, String key) { return ((Timestamp) row.get(key)).toInstant(); }
    private static Timestamp stamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant lower(Instant value) { Instant result = value.minusSeconds(30); return result.isBefore(MIN) ? MIN : result; }
    private static Instant upper(Instant value, int seconds) { Instant result = value.plusSeconds(seconds); return result.isAfter(MAX) ? MAX : result; }
}
