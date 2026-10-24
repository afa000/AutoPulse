package dev.autopulse.trip;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;
import static dev.autopulse.trip.TripRules.*;

/** Internal forward projector, invoked through the worker by opt-in orchestration. */
@Service
public class TripProjector {
    public static final int MAX_PAGE = 100;
    private static final String WORK_PAGE_TEMPLATE = """
            with page as materialized (
                select reading_id from trip_work_item where vehicle_id=? and status='PENDING' and sort_at is not null and next_attempt_at<=?
                order by sort_at,sort_device,sort_boot,sort_sequence limit ?
            )
            select r.id,r.recorded_at,r.device_id,r.boot_id,r.sequence_number,r.source,r.payload::text
            from page join %s r on r.id=page.reading_id
            order by r.recorded_at,r.device_id,r.boot_id,r.sequence_number
            """;
    public static final String WORK_PAGE_SQL=WORK_PAGE_TEMPLATE.formatted(TripReadingSource.RAW.table());
    public static final String LEDGER_WORK_PAGE_SQL=WORK_PAGE_TEMPLATE.formatted(TripReadingSource.LEDGER.table());
    public record Page(String state, int visited, int reviewed) {}
    private record Policy(UUID primary, long revision) {}
    private record Work(UUID id, Observation event) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final TripBackfill backfill;

    public TripProjector(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager transactions, TripBackfill backfill) {
        this.jdbc = jdbc; this.mapper = mapper; this.backfill = backfill;
        transaction = new TransactionTemplate(transactions); transaction.setTimeout(10);
    }

    public Page processPage(UUID vehicle, Instant asOf, int limit) {
        if (vehicle == null || asOf == null || limit < 1 || limit > MAX_PAGE) throw new IllegalArgumentException("vehicle/time and limit 1..100 required");
        if (!backfill.enqueuePage(vehicle, limit).complete()) return new Page("BACKFILLING", 0, 0);
        return transaction.execute(status -> process(vehicle, asOf, limit));
    }

    private Page process(UUID vehicle, Instant asOf, int limit) {
        // Take a nonblocking transaction lock before initializing progress as well
        // as updating it. A row lock alone cannot protect an absent first row.
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "select pg_try_advisory_xact_lock(hashtextextended(cast(? as text),902))", Boolean.class, vehicle.toString())))
            return new Page("BUSY", 0, 0);
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from trip_correction_plan where vehicle_id=? and phase in ('TRIPS','INPUTS','READY'))",
                Boolean.class, vehicle))) return new Page("CORRECTION_PENDING", 0, 0);
        var policies = jdbc.query("select primary_device_id,revision from trip_policy where vehicle_id=? for share",
                (row, n) -> new Policy(row.getObject(1, UUID.class), row.getLong(2)), vehicle);
        if (policies.isEmpty()) return new Page("NO_PRIMARY_DEVICE", 0, 0);
        var policy = policies.getFirst();
        jdbc.update("""
                insert into trip_projection_progress(vehicle_id,policy_revision,checkpoint)
                values (?,?,cast(? as jsonb)) on conflict do nothing
                """, vehicle, policy.revision(), json(TripFold.Checkpoint.empty()));
        var progress = jdbc.queryForList("select * from trip_projection_progress where vehicle_id=? for update skip locked", vehicle);
        if (progress.isEmpty()) return new Page("BUSY", 0, 0);
        var saved = progress.getFirst();
        if (((Number) saved.get("policy_revision")).longValue() != policy.revision()) {
            jdbc.update("update trip_projection_progress set needs_review=true where vehicle_id=?", vehicle);
            return new Page("POLICY_CHANGED", 0, 0);
        }
        TripFold.Checkpoint fold = mapper.readValue(saved.get("checkpoint").toString(), TripFold.Checkpoint.class);
        UUID current = (UUID) saved.get("current_trip_id");
        Instant cursorAt = saved.get("cursor_at") == null ? null : ((Timestamp) saved.get("cursor_at")).toInstant();
        UUID cursorBoot = (UUID) saved.get("cursor_boot");
        BigInteger cursorSequence = saved.get("cursor_sequence") == null ? null : ((BigDecimal) saved.get("cursor_sequence")).toBigIntegerExact();
        var source=TripReadingSource.forVehicle(jdbc,vehicle);
        var work = jdbc.query(source==TripReadingSource.LEDGER ? LEDGER_WORK_PAGE_SQL : WORK_PAGE_SQL,
                (row, n) -> work(row), vehicle, Timestamp.from(asOf), limit);
        int reviewed = 0;
        for (Work item : work) {
            Observation event = item.event();
            if (!event.device().equals(policy.primary())) { handled(item.id(), "PROCESSED", "SECONDARY_DEVICE"); continue; }
            if (event.at().isAfter(asOf.plus(DEFAULT.futureTolerance()))) continue;
            // Future input may become eligible after insertion; use its original
            // receipt, not processing time, when recovering the quiet horizon.
            jdbc.update("""
                    update trip_vehicle_activity a set last_eligible_receipt_at=greatest(a.last_eligible_receipt_at,w.accepted_at)
                    from trip_work_item w where a.vehicle_id=? and w.reading_id=?
                    """, vehicle, item.id());
            if (cursorAt != null && !event.at().isAfter(cursorAt)) {
                boolean oldTie = event.at().equals(cursorAt) && (event.boot().toString().compareTo(cursorBoot.toString()) < 0
                        || event.boot().equals(cursorBoot) && event.sequence().compareTo(cursorSequence) <= 0);
                handled(item.id(), oldTie ? "PROCESSED" : "REVIEW", oldTie ? "COLLAPSED_TIE" : "LATE_DATA_UNAPPLIED");
                if (!oldTie) reviewed++;
                continue;
            }
            // A limit can cut a timestamp tie. Always select its complete canonical winner via the index.
            Work winner = jdbc.query("""
                    select id,recorded_at,device_id,boot_id,sequence_number,source,payload::text from %s
                    where vehicle_id=? and device_id=? and recorded_at=? order by boot_id desc,sequence_number desc limit 1
                    """.formatted(source.table()), (row, n) -> work(row), vehicle, policy.primary(), Timestamp.from(event.at())).getFirst();
            event = winner.event();
            // A timer can close an old capture between separately committed
            // chunks. Later event-time-adjacent evidence must revise that
            // provisional timeout or expired start candidate, not permanently
            // manufacture a second trip (or lose its first running observation).
            // Finalized overlap follows the planner's explicit review path.
            if (fold.state().phase()==Phase.IDLE && Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from trip_record where vehicle_id=? and primary_device_id=?
                    and status='COMPLETED' and completion_reason='SIGNAL_TIMEOUT'
                    and ended_at<? and ended_at>?::timestamptz-interval '30 seconds')
                    or exists(select 1 from trip_record where vehicle_id=? and primary_device_id=? and status='DISCARDED'
                    and observed_through<? and observed_through>=?::timestamptz-interval '5 seconds')
                    or exists(select 1 from %s where device_id=? and boot_id=? and sequence_number=?
                    and source=? and (payload->>'rpm')::numeric>0 and recorded_at>=?::timestamptz-interval '5 seconds')
                    """.formatted(source.table()),Boolean.class,vehicle,policy.primary(),Timestamp.from(event.at()),Timestamp.from(event.at()),
                    vehicle,policy.primary(),Timestamp.from(event.at()),Timestamp.from(event.at()),
                    policy.primary(),cursorBoot,cursorSequence==null ? null : new BigDecimal(cursorSequence),event.source(),Timestamp.from(event.at())))) {
                handled(item.id(),"REVIEW","TIMER_CONTINUATION");
                if (!winner.id().equals(item.id())) handled(winner.id(),"REVIEW","TIMER_CONTINUATION");
                reviewed++;
                continue;
            }
            var change = TripFold.step(fold, event, DEFAULT);
            current = persistChange(vehicle, policy.primary(), fold, change, current, winner.id());
            fold = change.next(); cursorAt = event.at(); cursorBoot = event.boot(); cursorSequence = event.sequence();
            handled(item.id(), "PROCESSED", winner.id().equals(item.id()) ? null : "COLLAPSED_TIE");
            handled(winner.id(), "PROCESSED", null);
        }
        // Do not time out between pages of a historical batch. Deferred future input
        // is not eligible backlog and must not keep a trip alive indefinitely.
        boolean backlog = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from trip_work_item where vehicle_id=? and status='PENDING' and next_attempt_at<=?)
                """, Boolean.class, vehicle, Timestamp.from(asOf)));
        if (!backlog) {
            var change = TripFold.tick(fold, asOf, DEFAULT);
            current = persistChange(vehicle, policy.primary(), fold, change, current, null);
            fold = change.next();
        }
        jdbc.update("""
                update trip_projection_progress set cursor_at=?,cursor_boot=?,cursor_sequence=?,current_trip_id=?,
                checkpoint=cast(? as jsonb),needs_review=needs_review or ? where vehicle_id=?
                """, cursorAt == null ? null : Timestamp.from(cursorAt), cursorBoot,
                cursorSequence == null ? null : new BigDecimal(cursorSequence), current, json(fold), reviewed > 0, vehicle);
        return new Page(backlog ? "MORE" : "CAUGHT_UP", work.size(), reviewed);
    }

    private UUID persistChange(UUID vehicle, UUID primary, TripFold.Checkpoint prior, TripFold.Change change, UUID current, UUID reading) {
        if (change.completed() != null) {
            Trip trip = change.completed();
            save(current, "COMPLETED", trip.boundary().end().at(), trip.boundary().confirmedAt(), trip.boundary().reason().name(),
                    trip.boundary().end().at(), trip.summary(), trip.boundary().quality());
            current = null;
        }
        var next = change.next();
        boolean replaced = prior.state().first() != null && (next.state().first() == null || !prior.state().first().equals(next.state().first()));
        if (current != null && replaced) { jdbc.update("update trip_record set status='DISCARDED',revision=revision+1 where id=?", current); current = null; }
        if (next.state().phase() == Phase.IDLE) return null;
        if (current == null) {
            current = UUID.randomUUID(); var first = next.state().first();
            jdbc.update("""
                    insert into trip_record(id,vehicle_id,primary_device_id,anchor_boot,anchor_sequence,started_at,
                    observed_through,source,status,algorithm_version,summary,quality)
                    values (?,?,?,?,?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb))
                    """, current, vehicle, primary, first.boot(), new BigDecimal(first.sequence()), Timestamp.from(first.at()),
                    Timestamp.from(next.state().last().at()), first.source(), next.state().active() ? "ACTIVE" : "CANDIDATE", VERSION,
                    json(next.current().summary()), json(next.state().quality()));
        } else {
            save(current, next.state().active() ? "ACTIVE" : "CANDIDATE", null, null, null,
                    next.state().last().at(), next.current().summary(), next.state().quality());
        }
        if (reading != null) jdbc.update("insert into trip_reading_link(reading_id,trip_id) values (?,?) on conflict do nothing", reading, current);
        return current;
    }

    private void save(UUID id, String state, Instant end, Instant confirmation, String reason, Instant observed,
                      Summary summary, java.util.Set<String> quality) {
        jdbc.update("""
                update trip_record set status=?,ended_at=?,confirmed_at=?,completion_reason=?,observed_through=?,
                summary=cast(? as jsonb),quality=cast(? as jsonb),revision=revision+1
                where id=? and (status,ended_at,confirmed_at,completion_reason,observed_through,summary,quality)
                is distinct from (cast(? as text),cast(? as timestamptz),cast(? as timestamptz),cast(? as text),
                cast(? as timestamptz),cast(? as jsonb),cast(? as jsonb))
                """, state, stamp(end), stamp(confirmation), reason, stamp(observed), json(summary), json(quality), id,
                state, stamp(end), stamp(confirmation), reason, stamp(observed), json(summary), json(quality));
    }
    private Work work(java.sql.ResultSet row) throws java.sql.SQLException {
        var payload = mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(row.getString(7));
        var metrics = new HashMap<String, BigDecimal>();
        for (String field : METRICS) if (payload.hasNonNull(field)) metrics.put(field, payload.get(field).decimalValue());
        return new Work(row.getObject(1, UUID.class), new Observation(row.getTimestamp(2).toInstant(), row.getObject(3, UUID.class),
                row.getObject(4, UUID.class), row.getBigDecimal(5).toBigIntegerExact(), row.getString(6), metrics));
    }
    private void handled(UUID id, String state, String reason) {
        jdbc.update("update trip_work_item set status=?,attempts=attempts+1,last_error=? where reading_id=? and status='PENDING'", state, reason, id);
    }
    private String json(Object value) { return mapper.writeValueAsString(value); }
    private static Timestamp stamp(Instant value) { return value == null ? null : Timestamp.from(value); }
}
