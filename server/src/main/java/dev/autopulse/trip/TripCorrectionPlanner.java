package dev.autopulse.trip;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Resumable connected-window discovery, not correction replay or publication. */
@Service
public class TripCorrectionPlanner {
    public static final int MAX_PAGE = 100;
    private static final Duration HALO = Duration.ofSeconds(30);
    private static final Instant MIN = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX = Instant.parse("9999-12-31T23:59:59.999999Z");
    public record Page(UUID planId, String state, int visited, Instant from, Instant to) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public TripCorrectionPlanner(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc; transaction = new TransactionTemplate(transactions); transaction.setTimeout(10);
    }

    public Page planPage(UUID vehicle, Instant asOf, int limit) {
        if (vehicle == null || asOf == null || limit < 1 || limit > MAX_PAGE)
            throw new IllegalArgumentException("vehicle/time and limit 1..100 required");
        return transaction.execute(status -> plan(vehicle, asOf, limit));
    }

    private Page plan(UUID vehicle, Instant asOf, int limit) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtextextended(cast(? as text),902))",
                Boolean.class, vehicle.toString()))) return new Page(null, "BUSY", 0, null, null);
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from trip_correction_publication where vehicle_id=? and phase not in ('DONE','ABANDONED'))",
                Boolean.class,vehicle))) return new Page(null,"PUBLICATION_PENDING",0,null,null);
        var policies = jdbc.queryForList("select primary_device_id,revision from trip_policy where vehicle_id=? for share", vehicle);
        if (policies.isEmpty()) return new Page(null, "NO_PRIMARY_DEVICE", 0, null, null);
        var policy = policies.getFirst();
        long generation = jdbc.queryForObject("select ingest_generation from trip_vehicle_activity where vehicle_id=? for update", Long.class, vehicle);
        var progressRows = jdbc.queryForList("select policy_revision,cursor_at from trip_projection_progress where vehicle_id=? for update", vehicle);
        if (progressRows.isEmpty() || progressRows.getFirst().get("cursor_at") == null)
            return new Page(null, "PROJECTION_NOT_READY", 0, null, null);
        var progress = progressRows.getFirst();
        var active = jdbc.queryForList("select * from trip_correction_plan where vehicle_id=? and phase in ('TRIPS','INPUTS','READY') for update", vehicle);
        if (!policy.get("revision").equals(progress.get("policy_revision"))) {
            if (!active.isEmpty()) {
                var previous = active.getFirst();
                return end((UUID) previous.get("id"), "REVIEW_POLICY_CHANGED", instant(previous,"window_start"), instant(previous,"window_end"), 0);
            }
            return new Page(null, "POLICY_CHANGED", 0, null, null);
        }
        var source=TripReadingSource.forVehicle(jdbc,vehicle);
        Map<String,Object> job;
        if (active.isEmpty()) {
            var seeds = jdbc.queryForList("""
                    select w.reading_id,r.recorded_at from trip_work_item w join %s r on r.id=w.reading_id
                    where w.vehicle_id=? and w.status='REVIEW' and r.device_id=? and r.recorded_at<=?
                    and not exists(select 1 from trip_correction_plan p where p.requested_work_id=w.reading_id)
                    order by r.recorded_at,w.reading_id limit 1
                    """.formatted(source.table()), vehicle, policy.get("primary_device_id"), stamp(upper(asOf, TripRules.DEFAULT.futureTolerance())));
            if (seeds.isEmpty()) return new Page(null, "NO_UNPLANNED_REVIEW", 0, null, null);
            UUID id = UUID.randomUUID(); var seed = seeds.getFirst();
            jdbc.update("""
                    insert into trip_correction_plan(id,vehicle_id,requested_work_id,primary_device_id,policy_revision,
                    projection_cursor_at,planned_as_of,window_start,window_end,phase,input_generation)
                    values (?,?,?,?,?,?,?,?,?,'TRIPS',?)
                    """, id, vehicle, seed.get("reading_id"), policy.get("primary_device_id"), policy.get("revision"),
                    progress.get("cursor_at"), stamp(asOf), seed.get("recorded_at"), seed.get("recorded_at"), generation);
            job = jdbc.queryForMap("select * from trip_correction_plan where id=?", id);
        } else job = active.getFirst();
        UUID id = (UUID) job.get("id");
        Instant from = instant(job, "window_start"), to = instant(job, "window_end");
        if (!policy.get("revision").equals(job.get("policy_revision")) || !policy.get("primary_device_id").equals(job.get("primary_device_id")))
            return end(id, "REVIEW_POLICY_CHANGED", from, to, 0);
        if (((Number) job.get("input_generation")).longValue() != generation) {
            jdbc.update("update trip_correction_replay set status='INVALIDATED' where plan_id=? and status in ('RUNNING','STAGED')", id);
            jdbc.update("""
                    update trip_correction_plan set phase='TRIPS',input_generation=?,current_replay_id=null,
                    trip_cursor_at=null,trip_cursor_id=null,input_cursor_at=null,input_cursor_id=null where id=?
                    """, generation, id);
            return new Page(id, "TRIPS", 0, from, to);
        }
        if (job.get("phase").equals("READY")) return new Page(id, "READY", 0, from, to);
        Instant left = lower(from), right = upper(to, HALO);
        List<Map<String,Object>> rows;
        boolean trips = job.get("phase").equals("TRIPS");
        if (trips) {
            String after = job.get("trip_cursor_at") == null ? "" : " and (started_at,id)>(?,?)";
            Object[] args = job.get("trip_cursor_at") == null
                    ? new Object[]{vehicle, stamp(right), stamp(left), limit}
                    : new Object[]{vehicle, stamp(right), stamp(left), job.get("trip_cursor_at"), job.get("trip_cursor_id"), limit};
            rows = jdbc.queryForList("""
                    select id,started_at,observed_through,finalization from trip_record
                    where vehicle_id=? and status not in ('SUPERSEDED','REBUILDING') and started_at<=? and observed_through>=?
                    """ + after + " order by started_at,id limit ?", args);
        } else {
            String after = job.get("input_cursor_at") == null ? "" : " and (r.recorded_at,w.reading_id)>(?,?)";
            Object[] args = job.get("input_cursor_at") == null
                    ? new Object[]{vehicle, job.get("primary_device_id"), stamp(left), stamp(right), stamp(upper(instant(job,"planned_as_of"),TripRules.DEFAULT.futureTolerance())), limit}
                    : new Object[]{vehicle, job.get("primary_device_id"), stamp(left), stamp(right), stamp(upper(instant(job,"planned_as_of"),TripRules.DEFAULT.futureTolerance())),
                        job.get("input_cursor_at"), job.get("input_cursor_id"), limit};
            rows = jdbc.queryForList("""
                    select w.reading_id,r.recorded_at from trip_work_item w join %s r on r.id=w.reading_id
                    where w.vehicle_id=? and r.device_id=? and w.status in ('PENDING','REVIEW')
                    and r.recorded_at>=? and r.recorded_at<=? and r.recorded_at<=?
                    """.formatted(source.table()) + after + " order by r.recorded_at,w.reading_id limit ?", args);
        }
        Instant start = from, end = to;
        for (var row : rows) {
            if (trips && row.get("finalization").equals("FINAL")) return end(id, "REVIEW_FINALIZED", from, to, rows.size());
            Instant a = instant(row, trips ? "started_at" : "recorded_at");
            Instant b = trips ? instant(row, "observed_through") : a;
            if (a.isBefore(start)) start = a;
            if (b.isAfter(end)) end = b;
        }
        if (Duration.between(start,end).compareTo(TripReconciliation.MAX_AUTOMATIC_SPAN) > 0)
            return end(id, "REVIEW_TOO_WIDE", start, end, rows.size());
        if (!from.equals(start) || !to.equals(end)) {
            jdbc.update("""
                    update trip_correction_plan set window_start=?,window_end=?,phase='TRIPS',trip_cursor_at=null,trip_cursor_id=null,
                    input_cursor_at=null,input_cursor_id=null,visited_rows=visited_rows+? where id=?
                    """, stamp(start), stamp(end), rows.size(), id);
            return new Page(id, "TRIPS", rows.size(), start, end);
        }
        if (rows.size() == limit) {
            var last = rows.getLast();
            String cursor = trips ? "trip" : "input";
            jdbc.update("update trip_correction_plan set " + cursor + "_cursor_at=?," + cursor + "_cursor_id=?,visited_rows=visited_rows+? where id=?",
                    last.get(trips ? "started_at" : "recorded_at"), last.get(trips ? "id" : "reading_id"), rows.size(), id);
            return new Page(id, trips ? "TRIPS" : "INPUTS", rows.size(), from, to);
        }
        if (trips) {
            jdbc.update("update trip_correction_plan set phase='INPUTS',visited_rows=visited_rows+? where id=?", rows.size(), id);
            return new Page(id, "INPUTS", rows.size(), from, to);
        }
        // READY is a planned interval, not permission to publish a stale snapshot.
        // Replay/publication must revalidate incoming data and policy under a fence.
        return end(id, "READY", from, to, rows.size());
    }

    private Page end(UUID id, String state, Instant from, Instant to, int count) {
        if (state.startsWith("REVIEW_")) {
            jdbc.update("update trip_correction_replay set status='INVALIDATED' where plan_id=? and status in ('RUNNING','STAGED')", id);
            jdbc.update("update trip_correction_plan set current_replay_id=null where id=?", id);
        }
        jdbc.update("update trip_correction_plan set phase=?,window_start=?,window_end=?,visited_rows=visited_rows+? where id=?",
                state, stamp(from), stamp(to), count, id);
        return new Page(id, state, count, from, to);
    }
    private static Instant instant(Map<String,Object> row, String key) { return ((Timestamp) row.get(key)).toInstant(); }
    private static Timestamp stamp(Instant value) { return Timestamp.from(value); }
    private static Instant lower(Instant value) { Instant result = value.minus(HALO); return result.isBefore(MIN) ? MIN : result; }
    private static Instant upper(Instant value, Duration amount) { Instant result = value.plus(amount); return result.isAfter(MAX) ? MAX : result; }
}
