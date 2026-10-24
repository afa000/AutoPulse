package dev.autopulse;

import dev.autopulse.telemetry.*;
import dev.autopulse.trip.*;
import java.math.BigInteger;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.config.import=","autopulse.retention.enabled=true"})
@Import(TestcontainersConfiguration.class)
class RetentionPreviewTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;
    @Autowired TelemetryRepository repository;
    @Autowired TelemetryValidator validator;
    @Autowired TripProjector projector;
    @Autowired TripBackfill backfill;
    @Autowired ReplayIdentityBackfill replayBackfill;
    @Autowired TripFinalizer finalizer;
    @Autowired TripCorrectionPlanner planner;
    @Autowired TripCorrectionReplayer replayer;
    @Autowired TripCorrectionPublisher publisher;
    UUID vehicle,device;
    String publicVehicle,publicDevice;
    static final Instant AS_OF=Instant.parse("2026-12-01T12:00:00.123456Z"),BASE=Instant.parse("2026-10-20T12:00:00Z");
    Clock clock;
    Instant receipt=BASE.plus(Duration.ofDays(1));
    @BeforeEach void setup() {
        clock=Clock.fixed(AS_OF,ZoneOffset.UTC); vehicle=UUID.randomUUID(); device=UUID.randomUUID();
        publicVehicle="retention-"+vehicle; publicDevice="device-"+device;
        jdbc.update("insert into vehicle(id,public_id) values (?,?)",vehicle,publicVehicle);
        jdbc.update("insert into device(id,vehicle_id,public_id) values (?,?,?)",device,vehicle,publicDevice);
        jdbc.update("insert into trip_policy(vehicle_id,primary_device_id) values (?,?)",vehicle,device);
    }
    RetentionPreview preview() { return new RetentionPreview(jdbc,transactions,new RetentionPreview.Options(true,true,30,96),clock); }
    ObjectNode sample(BigInteger sequence,Instant at) {
        return ((ObjectNode)mapper.readTree(Path.of("../contracts/examples/telemetry-sample.json").toFile())).put("deviceId",publicDevice)
                .put("vehicleId",publicVehicle).put("sequenceNumber",sequence).put("recordedAt",at.toString());
    }
    void store(ObjectNode input) {
        // Fixed original receipts keep the fixed-clock policy fixture valid in future CI runs.
        // Use real canonical validation and all database triggers, not a bypassed work queue.
        var r=validator.validate(input);
        jdbc.update("insert into telemetry_reading(id,vehicle_id,device_id,boot_id,sequence_number,recorded_at,received_at,source,payload) values (?,?,?,?,?,?,?,? ,?::jsonb)",
                UUID.randomUUID(),vehicle,device,r.bootId(),new java.math.BigDecimal(r.sequenceNumber()),Timestamp.from(r.recordedAt()),
                Timestamp.from(receipt),r.payload().get("source").asText(),r.payload().toString());
    }
    void drive(boolean seal) {
        for (var value : mapper.readTree(Path.of("../contracts/examples/trip-short-input.json").toFile()).get("readings"))
            store(((ObjectNode)value).deepCopy().put("vehicleId",publicVehicle).put("deviceId",publicDevice));
        assertThat(backfill.enqueuePage(vehicle,100).complete()).isTrue();
        assertThat(replayBackfill.copyPage(vehicle,100).complete()).isTrue();
        boolean drained=false;
        for (int i=0;i<100;i++) if (projector.processPage(vehicle,AS_OF,100).state().equals("CAUGHT_UP")) { drained=true; break; }
        assertThat(drained).isTrue();
        jdbc.update("insert into trip_processing_control(vehicle_id,stage,state) values (?,'FORWARD','IDLE')",vehicle);
        if (seal) {
            int finalized=0;
            for (int i=0;i<20;i++) { var result=finalizer.finalizePage(vehicle,AS_OF,100); finalized+=result.finalized(); if (finalized>0) break; }
            assertThat(finalized).isEqualTo(1);
        }
    }
    String fingerprint() {
        var values=new ArrayList<String>();
        for (String table : List.of("telemetry_reading","telemetry_replay_identity","telemetry_replay_backfill","trip_work_item","trip_record","trip_projection_progress","trip_vehicle_activity","trip_backfill_progress","trip_processing_control","vehicle_read_generation","trip_policy","trip_correction_plan","trip_correction_publication"))
            values.add(jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text from "+table+" t where vehicle_id=?",String.class,vehicle));
        values.add(jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(l) order by l.reading_id),'[]'::jsonb)::text from trip_reading_link l join trip_record t on t.id=l.trip_id where t.vehicle_id=?",String.class,vehicle));
        for (String table : List.of("trip_retained_evidence","trip_retained_evidence_progress","trip_aggregate_progress","trip_aggregate_bucket","trip_aggregate_verification","trip_aggregate_reference"))
            values.add(jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(a) order by to_jsonb(a)::text),'[]'::jsonb)::text from "+table+" a join trip_record t on t.id=a.trip_id where t.vehicle_id=?",String.class,vehicle));
        values.add(jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(r) order by r.id),'[]'::jsonb)::text from trip_correction_replay r join trip_correction_plan p on p.id=r.plan_id where p.vehicle_id=?",String.class,vehicle));
        values.add(jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(s) order by s.id),'[]'::jsonb)::text from trip_correction_segment s join trip_correction_replay r on r.id=s.replay_id join trip_correction_plan p on p.id=r.plan_id where p.vehicle_id=?",String.class,vehicle));
        values.add(jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(c) order by c.replay_id,c.reading_id),'[]'::jsonb)::text from trip_correction_reading c join trip_correction_replay r on r.id=c.replay_id join trip_correction_plan p on p.id=r.plan_id where p.vehicle_id=?",String.class,vehicle));
        return values.toString();
    }
    UUID trip() { return jdbc.queryForObject("select id from trip_record where vehicle_id=? and status='COMPLETED'",UUID.class,vehicle); }
    void archive(UUID trip) {
        assertThat(new FinalTripEvidence(jdbc,transactions).copyPage(vehicle,trip,500).complete()).isTrue();
        assertThat(new TripAggregateBuilder(jdbc,transactions).buildPage(vehicle,trip,500).complete()).isTrue();
        var verifier=new TripAggregateVerifier(jdbc,mapper,transactions);
        boolean verified=false;
        for (int i=0;i<10;i++) if (verifier.verifyPage(vehicle,trip,500).state().equals("VERIFIED")) { verified=true; break; }
        assertThat(verified).isTrue();
    }
    UUID[] inactiveCorrection(UUID reading) {
        UUID plan=UUID.randomUUID(),replay=UUID.randomUUID(),segment=UUID.randomUUID();
        jdbc.update("""
                insert into trip_correction_plan(id,vehicle_id,requested_work_id,primary_device_id,policy_revision,projection_cursor_at,
                  planned_as_of,window_start,window_end,phase)
                values (?,?,?,?,1,?,?,?,?,'DONE')
                """,plan,vehicle,reading,device,Timestamp.from(BASE),Timestamp.from(AS_OF),Timestamp.from(BASE),Timestamp.from(BASE.plusSeconds(60)));
        jdbc.update("""
                insert into trip_correction_replay(id,plan_id,input_generation,window_start,window_end,planned_as_of,read_from,read_to,status,checkpoint)
                values (?,?,0,?,?,?,?,?,'INVALIDATED','{}')
                """,replay,plan,Timestamp.from(BASE),Timestamp.from(BASE.plusSeconds(60)),Timestamp.from(AS_OF),Timestamp.from(BASE),Timestamp.from(BASE.plusSeconds(60)));
        jdbc.update("""
                insert into trip_correction_segment(id,replay_id,anchor_boot,anchor_sequence,started_at,observed_through,source,status,summary,quality)
                values (?,?,?,1,?,?,'SIMULATED','DISCARDED','{}','[]')
                """,segment,replay,UUID.randomUUID(),Timestamp.from(BASE),Timestamp.from(BASE));
        return new UUID[]{replay,segment};
    }
    @Test void barrierReferenceChecksMatchVerifiedOwnershipWithoutAuthorizingDeletion() {
        drive(true); archive(trip()); String before=fingerprint(); var page=preview().reportUnderBarrier(vehicle,100,null);
        assertThat(page.referenceChecks()).hasSize(31);
        assertThat(page.referenceChecks()).allSatisfy(c -> { assertThat(c.workState()).isEqualTo("MATCHED"); assertThat(c.correctionReference()).isFalse(); });
        assertThat(page.referenceChecks().stream().filter(c -> c.linkState().equals("MATCHED"))).hasSize(27);
        assertThat(page.referenceChecks().stream().filter(c -> c.linkState().equals("MISSING"))).hasSize(4);
        assertThat(page.referenceChecks().stream().map(RetentionPreview.ReferenceCheck::readingId).toList())
                .containsExactlyElementsOf(page.preview().readings().stream().map(RetentionPreview.Reading::readingId).toList());
        assertThat(page.state()).isEqualTo("CHECKED_NOT_AUTHORIZED"); assertThat(page.preview().wouldDelete()).isZero();
        assertThat(fingerprint()).isEqualTo(before);
    }
    @Test void barrierReferenceChecksDistinguishMissingUnprocessedAndUncopiedReferences() {
        drive(false); var ids=preview().report(vehicle,2,null).readings();
        jdbc.update("delete from trip_work_item where reading_id=?",ids.get(0).readingId());
        jdbc.update("update trip_work_item set status='REVIEW' where reading_id=?",ids.get(1).readingId());
        String before=fingerprint(); var page=preview().reportUnderBarrier(vehicle,100,null);
        assertThat(page.referenceChecks().get(0).workState()).isEqualTo("MISSING");
        assertThat(page.referenceChecks().get(1).workState()).isEqualTo("NOT_PROCESSED");
        assertThat(page.referenceChecks().stream().filter(c -> c.linkState().equals("RETAINED_MEMBERSHIP_MISSING"))).hasSize(27);
        assertThat(page.archiveChecks()).allSatisfy(c -> assertThat(c.verifiedTrip()).isNull());
        assertThat(page.preview().wouldDelete()).isZero(); assertThat(fingerprint()).isEqualTo(before);
    }
    @Test void barrierReferenceChecksKeepEvenInactiveCorrectionReferencesAndStayPageBounded() {
        drive(true); archive(trip()); UUID reading=preview().report(vehicle,1,null).readings().getFirst().readingId();
        var graph=inactiveCorrection(reading);
        jdbc.update("insert into trip_correction_reading(replay_id,reading_id,segment_id) values (?,?,?)",graph[0],reading,graph[1]);
        UUID second=preview().report(vehicle,2,null).readings().get(1).readingId(); var other=inactiveCorrection(second);
        jdbc.update("insert into trip_correction_reading(replay_id,reading_id,segment_id) values (?,?,?)",other[0],reading,other[1]);
        String before=fingerprint(); var page=preview().reportUnderBarrier(vehicle,1,null);
        assertThat(page.referenceChecks()).hasSize(1);
        assertThat(page.referenceChecks().getFirst().readingId()).isEqualTo(reading);
        assertThat(page.referenceChecks().getFirst().correctionReference()).isTrue();
        var next=preview().reportUnderBarrier(vehicle,1,page.preview().nextCursor());
        assertThat(next.referenceChecks()).hasSize(1).allSatisfy(c -> assertThat(c.correctionReference()).isFalse());
        assertThat(page.preview().wouldDelete()).isZero(); assertThat(fingerprint()).isEqualTo(before);
    }
    @Test void correctionReferenceWriterWaitsUntilInspectionReleasesRawPin() throws Exception {
        drive(true); UUID reading=preview().report(vehicle,1,null).readings().getFirst().readingId(); var graph=inactiveCorrection(reading);
        var writer=new java.util.concurrent.atomic.AtomicReference<Future<?>>(); var pid=new java.util.concurrent.atomic.AtomicInteger();
        var observed=new java.util.concurrent.atomic.AtomicBoolean(); var started=new CountDownLatch(1);
        try (var executor=Executors.newSingleThreadExecutor()) {
            var tracing=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
                @Override public <T> List<T> query(String sql,org.springframework.jdbc.core.RowMapper<T> mapper,Object... args) {
                    if (sql.startsWith("with reference_requested") && observed.compareAndSet(false,true)) {
                        writer.set(executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                            pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class)); started.countDown();
                            jdbc.update("insert into trip_correction_reading(replay_id,reading_id,segment_id) values (?,?,?)",graph[0],reading,graph[1]);
                        })));
                        try {
                            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue(); boolean blocked=false;
                            for (int i=0;i<200;i++) {
                                if (Boolean.TRUE.equals(jdbc.queryForObject("select cardinality(pg_blocking_pids(?))>0",Boolean.class,pid.get()))) { blocked=true; break; }
                                Thread.sleep(10);
                            }
                            assertThat(blocked).as("reference writer waits at exact-original raw guard").isTrue();
                            assertThat(writer.get().isDone()).isFalse();
                        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                    }
                    return super.query(sql,mapper,args);
                }
            };
            var service=new RetentionPreview(tracing,transactions,new RetentionPreview.Options(true,true,30,96),clock);
            var old=service.reportUnderBarrier(vehicle,1,null);
            assertThat(observed).isTrue(); assertThat(old.referenceChecks().getFirst().correctionReference()).isFalse();
            writer.get().get(10,TimeUnit.SECONDS);
            assertThat(preview().reportUnderBarrier(vehicle,1,null).referenceChecks().getFirst().correctionReference()).isTrue();
            assertThat(old.preview().wouldDelete()).isZero();
        }
    }
    @Test void selectedReferenceOrParentContentionReturnsBusyAndReleasesEarlierPins() throws Exception {
        drive(true); String before=fingerprint();
        UUID reading=jdbc.queryForObject("select l.reading_id from trip_reading_link l join trip_record t on t.id=l.trip_id where t.vehicle_id=? order by l.reading_id limit 1",UUID.class,vehicle);
        for (String sql:List.of("select reading_id from trip_work_item where reading_id=? for update",
                "select reading_id from trip_reading_link where reading_id=? for update",
                "select id from trip_record where id=? for update")) {
            var held=new CountDownLatch(1); var release=new CountDownLatch(1);
            try (var executor=Executors.newSingleThreadExecutor()) {
                var holder=executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    jdbc.queryForList(sql,sql.contains("trip_record")?trip():reading); held.countDown();
                    try { assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                }));
                try {
                    assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
                    var busy=preview().reportUnderBarrier(vehicle,100,null);
                    assertThat(busy.state()).isEqualTo("BUSY"); assertThat(busy.preview()).isNull();
                    assertThat(busy.archiveChecks()).isEmpty(); assertThat(busy.referenceChecks()).isEmpty();
                    // No raw lock is taken while an existing reference/parent is busy.
                    assertThat(jdbc.queryForList("select id from telemetry_reading where vehicle_id=? for update skip locked",UUID.class,vehicle)).hasSize(31);
                    // Other work rows pinned earlier have also been released.
                    var work=jdbc.queryForList("select reading_id from trip_work_item where vehicle_id=? for update skip locked",UUID.class,vehicle);
                    assertThat(work).hasSize(sql.contains("trip_work_item")?30:31);
                } finally { release.countDown(); }
                holder.get(10,TimeUnit.SECONDS);
            }
            assertThat(preview().reportUnderBarrier(vehicle,100,null).state()).isEqualTo("CHECKED_NOT_AUTHORIZED");
        }
        assertThat(fingerprint()).isEqualTo(before);
    }
    @Test void referencePinsExcludeLookaheadAndUnrelatedParentsAndReleaseAfterSuccess() throws Exception {
        drive(true); var first=preview().report(vehicle,1,null).readings().getFirst().readingId();
        assertThat(jdbc.queryForList("select reading_id from trip_reading_link where reading_id=?",UUID.class,first)).isEmpty();
        String before=fingerprint(); var observed=new java.util.concurrent.atomic.AtomicBoolean();
        try (var executor=Executors.newSingleThreadExecutor()) {
            var tracing=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
                @Override public <T> List<T> query(String sql,org.springframework.jdbc.core.RowMapper<T> mapper,Object... args) {
                    if (sql.startsWith("with requested") && observed.compareAndSet(false,true)) {
                        try {
                            executor.submit(() -> {
                                assertThat(jdbc.queryForList("select reading_id from trip_work_item where vehicle_id=? for update skip locked",UUID.class,vehicle))
                                        .hasSize(30).doesNotContain(first);
                                assertThat(jdbc.queryForList("select l.reading_id from trip_reading_link l join trip_record t on t.id=l.trip_id where t.vehicle_id=? for update of l skip locked",UUID.class,vehicle)).hasSize(27);
                                assertThat(jdbc.queryForList("select id from trip_record where vehicle_id=? for update skip locked",UUID.class,vehicle)).containsExactly(trip());
                            }).get(5,TimeUnit.SECONDS);
                        } catch (Exception failure) { throw new AssertionError("reference pin scope observation failed",failure); }
                    }
                    return super.query(sql,mapper,args);
                }
            };
            var service=new RetentionPreview(tracing,transactions,new RetentionPreview.Options(true,true,30,96),clock);
            assertThat(service.reportUnderBarrier(vehicle,1,null).state()).isEqualTo("CHECKED_NOT_AUTHORIZED");
            assertThat(observed).isTrue();
            assertThat(jdbc.queryForList("select reading_id from trip_work_item where vehicle_id=? for update skip locked",UUID.class,vehicle)).hasSize(31);
        }
        assertThat(fingerprint()).isEqualTo(before);
    }
    @Test void missingWorkInsertedBetweenReferenceAndRawPinsDiscardsTheInspection() throws Exception {
        drive(true); UUID reading=preview().report(vehicle,1,null).readings().getFirst().readingId();
        jdbc.update("delete from trip_work_item where reading_id=?",reading);
        var observed=new java.util.concurrent.atomic.AtomicBoolean();
        try (var executor=Executors.newSingleThreadExecutor()) {
            var tracing=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
                @Override public <T> List<T> queryForList(String sql,Class<T> type,Object... args) {
                    if (sql.endsWith("order by id for update skip locked") && observed.compareAndSet(false,true)) {
                        try {
                            executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx ->
                                    jdbc.update("insert into trip_work_item(reading_id,vehicle_id,accepted_at,next_attempt_at) select id,vehicle_id,received_at,received_at from telemetry_reading where id=?",reading)))
                                    .get(5,TimeUnit.SECONDS);
                        } catch (Exception failure) { throw new AssertionError("reference-gap insertion failed",failure); }
                    }
                    return super.queryForList(sql,type,args);
                }
            };
            var service=new RetentionPreview(tracing,transactions,new RetentionPreview.Options(true,true,30,96),clock);
            var busy=service.reportUnderBarrier(vehicle,1,null);
            assertThat(observed).isTrue(); assertThat(busy.state()).isEqualTo("BUSY"); assertThat(busy.preview()).isNull();
            assertThat(busy.archiveChecks()).isEmpty(); assertThat(busy.referenceChecks()).isEmpty();
            assertThat(jdbc.queryForList("select id from telemetry_reading where id=? for update skip locked",UUID.class,reading)).containsExactly(reading);
            String afterWriter=fingerprint(); var fresh=preview().reportUnderBarrier(vehicle,1,null);
            assertThat(fresh.state()).isEqualTo("CHECKED_NOT_AUTHORIZED");
            assertThat(fresh.referenceChecks().getFirst().workState()).isEqualTo("NOT_PROCESSED");
            assertThat(fresh.preview().wouldDelete()).isZero(); assertThat(fingerprint()).isEqualTo(afterWriter);
        }
    }
    @Test void referencePinQueryFailureRollsBackPreviouslyAcquiredWorkLocks() {
        drive(true); String before=fingerprint();
        var tracing=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
            @Override public <T> T queryForObject(String sql,Class<T> type,Object... args) {
                if (sql.startsWith("with reference_locks") && sql.contains("trip_reading_link")) throw new IllegalStateException("reference pin outage");
                return super.queryForObject(sql,type,args);
            }
        };
        var service=new RetentionPreview(tracing,transactions,new RetentionPreview.Options(true,true,30,96),clock);
        assertThatThrownBy(() -> service.reportUnderBarrier(vehicle,100,null)).hasMessageContaining("reference pin outage");
        assertThat(jdbc.queryForList("select reading_id from trip_work_item where vehicle_id=? for update skip locked",UUID.class,vehicle)).hasSize(31);
        assertThat(preview().reportUnderBarrier(vehicle,100,null).state()).isEqualTo("CHECKED_NOT_AUTHORIZED");
        assertThat(fingerprint()).isEqualTo(before);
    }
    @Test void processedWorkUpdateWaitsForReferencePinAndFreshInspectionSeesReview() throws Exception {
        drive(true); UUID reading=preview().report(vehicle,1,null).readings().getFirst().readingId();
        var writer=new java.util.concurrent.atomic.AtomicReference<Future<?>>(); var pid=new java.util.concurrent.atomic.AtomicInteger();
        var started=new CountDownLatch(1); var observed=new java.util.concurrent.atomic.AtomicBoolean();
        try (var executor=Executors.newSingleThreadExecutor()) {
            var tracing=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
                @Override public <T> List<T> query(String sql,org.springframework.jdbc.core.RowMapper<T> mapper,Object... args) {
                    if (sql.startsWith("with reference_requested") && observed.compareAndSet(false,true)) {
                        writer.set(executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                            pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class)); started.countDown();
                            jdbc.update("update trip_work_item set status='REVIEW' where reading_id=?",reading);
                        })));
                        try {
                            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue(); boolean blocked=false;
                            for (int i=0;i<200;i++) {
                                if (Boolean.TRUE.equals(jdbc.queryForObject("select cardinality(pg_blocking_pids(?))>0",Boolean.class,pid.get()))) { blocked=true; break; }
                                Thread.sleep(10);
                            }
                            assertThat(blocked).as("existing work writer waits for reference pin").isTrue();
                            assertThat(writer.get().isDone()).isFalse();
                        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                    }
                    return super.query(sql,mapper,args);
                }
            };
            var service=new RetentionPreview(tracing,transactions,new RetentionPreview.Options(true,true,30,96),clock);
            var old=service.reportUnderBarrier(vehicle,1,null);
            assertThat(observed).isTrue(); assertThat(old.referenceChecks().getFirst().workState()).isEqualTo("MATCHED");
            writer.get().get(10,TimeUnit.SECONDS);
            var fresh=preview().reportUnderBarrier(vehicle,1,null);
            assertThat(fresh.referenceChecks().getFirst().workState()).isEqualTo("NOT_PROCESSED");
            assertThat(fresh.preview().vehicleProtections()).contains("PENDING_OR_REVIEW_WORK");
            assertThat(fresh.preview().wouldDelete()).isZero();
        }
    }
    @Test void referenceInspectionFailureReleasesPinsAndAllOtherBarrierLocks() {
        drive(true); String before=fingerprint();
        var tracing=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
            @Override public <T> List<T> query(String sql,org.springframework.jdbc.core.RowMapper<T> mapper,Object... args) {
                if (sql.startsWith("with reference_requested")) throw new IllegalStateException("reference inspection outage");
                return super.query(sql,mapper,args);
            }
        };
        var service=new RetentionPreview(tracing,transactions,new RetentionPreview.Options(true,true,30,96),clock);
        assertThatThrownBy(() -> service.reportUnderBarrier(vehicle,2,null)).hasMessageContaining("reference inspection outage");
        assertThat(jdbc.queryForList("select id from telemetry_reading where vehicle_id=? for update skip locked",UUID.class,vehicle)).hasSize(31);
        assertThat(preview().reportUnderBarrier(vehicle,2,null).referenceChecks()).hasSize(2);
        assertThat(fingerprint()).isEqualTo(before);
    }
    @Test void barrierRawRowContentionReturnsNoPartialReportAndReleasesOtherPageLocks() throws Exception {
        drive(true); String before=fingerprint(); var readings=preview().report(vehicle,2,null).readings();
        for (String mode : List.of("update","share")) {
            var held=new CountDownLatch(1); var release=new CountDownLatch(1);
            try (var executor=Executors.newSingleThreadExecutor()) {
                var holder=executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    jdbc.queryForList("select id from telemetry_reading where id=? for "+mode,readings.get(1).readingId()); held.countDown();
                    try { assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                }));
                try {
                    assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
                    var busy=preview().reportUnderBarrier(vehicle,2,null);
                    assertThat(busy.state()).isEqualTo("BUSY"); assertThat(busy.preview()).isNull(); assertThat(busy.archiveChecks()).isEmpty();
                    // Any uncontended row locked before discovering the conflict was released.
                    assertThat(jdbc.queryForList("select id from telemetry_reading where id=? for update skip locked",UUID.class,readings.getFirst().readingId()))
                            .containsExactly(readings.getFirst().readingId());
                } finally { release.countDown(); }
                holder.get(10,TimeUnit.SECONDS);
            }
            assertThat(preview().reportUnderBarrier(vehicle,2,null).state()).isEqualTo("CHECKED_NOT_AUTHORIZED");
        }
        assertThat(fingerprint()).isEqualTo(before);
    }
    @Test void barrierPinsOnlyReturnedRowsNotLookaheadAndReleasesThemOnSuccess() {
        drive(true); var first=preview().report(vehicle,1,null).readings().getFirst().readingId();
        var observed=new java.util.concurrent.atomic.AtomicBoolean();
        try (var executor=Executors.newSingleThreadExecutor()) {
            var tracing=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
                @Override public <T> List<T> query(String sql,org.springframework.jdbc.core.RowMapper<T> mapper,Object... args) {
                    if (sql.startsWith("with requested") && observed.compareAndSet(false,true)) {
                        try {
                            var available=executor.submit(() -> jdbc.queryForList("select id from telemetry_reading where vehicle_id=? order by id for update skip locked",UUID.class,vehicle)).get(5,TimeUnit.SECONDS);
                            assertThat(available).hasSize(30).doesNotContain(first);
                        } catch (Exception failure) { throw new AssertionError("row-pin observation failed",failure); }
                    }
                    return super.query(sql,mapper,args);
                }
            };
            var checked=new RetentionPreview(tracing,transactions,new RetentionPreview.Options(true,true,30,96),clock).reportUnderBarrier(vehicle,1,null);
            assertThat(checked.state()).isEqualTo("CHECKED_NOT_AUTHORIZED"); assertThat(observed).isTrue();
            assertThat(jdbc.queryForList("select id from telemetry_reading where vehicle_id=? for update skip locked",UUID.class,vehicle)).hasSize(31);
        }
    }
    @Test void barrierAvoidsRawWriterReceiptLockCycleAndWriterCommitsAfterBusy() throws Exception {
        drive(true); var old=preview().report(vehicle,1,null); UUID id=old.readings().getFirst().readingId();
        var started=new CountDownLatch(1); var pid=new java.util.concurrent.atomic.AtomicInteger();
        var writer=new java.util.concurrent.atomic.AtomicReference<Future<?>>(); var observed=new java.util.concurrent.atomic.AtomicBoolean();
        try (var executor=Executors.newSingleThreadExecutor()) {
            var tracing=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
                @Override public <T> List<T> queryForList(String sql,Class<T> type,Object... args) {
                    if (sql.endsWith("order by id for update skip locked") && observed.compareAndSet(false,true)) {
                        writer.set(executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                            pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class)); started.countDown();
                            jdbc.update("update telemetry_reading set received_at=received_at+interval '1 microsecond' where id=?",id);
                        })));
                        try {
                            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue(); boolean blocked=false;
                            for (int i=0;i<200;i++) {
                                if (Boolean.TRUE.equals(jdbc.queryForObject("select cardinality(pg_blocking_pids(?))>0",Boolean.class,pid.get()))) { blocked=true; break; }
                                Thread.sleep(10);
                            }
                            assertThat(blocked).as("raw writer is waiting at the held receipt barrier").isTrue();
                            assertThat(writer.get().isDone()).isFalse();
                        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                    }
                    return super.queryForList(sql,type,args);
                }
            };
            var service=new RetentionPreview(tracing,transactions,new RetentionPreview.Options(true,true,30,96),clock);
            var busy=service.reportUnderBarrier(vehicle,1,null);
            assertThat(observed).isTrue(); assertThat(busy.state()).isEqualTo("BUSY");
            assertThat(busy.preview()).isNull(); assertThat(busy.archiveChecks()).isEmpty();
            writer.get().get(10,TimeUnit.SECONDS);
            assertThatThrownBy(() -> preview().reportUnderBarrier(vehicle,1,old.nextCursor())).hasMessageContaining("data/policy changed");
            var fresh=preview().reportUnderBarrier(vehicle,1,null);
            assertThat(fresh.archiveChecks().getFirst().state()).isEqualTo("REPLAY_MISMATCH"); assertThat(fresh.preview().wouldDelete()).isZero();
        }
    }
    @Test void archiveQueryFailureRollsBackAndReleasesAllRawPins() {
        drive(true); String before=fingerprint();
        var tracing=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
            @Override public <T> List<T> query(String sql,org.springframework.jdbc.core.RowMapper<T> mapper,Object... args) {
                if (sql.startsWith("with requested")) throw new IllegalStateException("archive inspection outage");
                return super.query(sql,mapper,args);
            }
        };
        var service=new RetentionPreview(tracing,transactions,new RetentionPreview.Options(true,true,30,96),clock);
        assertThatThrownBy(() -> service.reportUnderBarrier(vehicle,100,null)).hasMessageContaining("archive inspection outage");
        assertThat(jdbc.queryForList("select id from telemetry_reading where vehicle_id=? for update skip locked",UUID.class,vehicle)).hasSize(31);
        assertThat(preview().reportUnderBarrier(vehicle,100,null).state()).isEqualTo("CHECKED_NOT_AUTHORIZED");
        assertThat(fingerprint()).isEqualTo(before);
    }
    @Test void barrierArchiveCoverageRequiresIndependentVerificationNotFinalizationOrCopyAlone() {
        drive(true); UUID trip=trip();
        var finalized=preview().reportUnderBarrier(vehicle,100,null);
        assertThat(finalized.archiveChecks()).hasSize(31).allSatisfy(c -> {
            assertThat(c.state()).isEqualTo("RETAINED_MEMBERSHIP_MISSING"); assertThat(c.verifiedTrip()).isNull();
        });
        assertThat(finalized.preview().policyCandidates()).isEqualTo(27); // Policy is not archive coverage.
        assertThat(new FinalTripEvidence(jdbc,transactions).copyPage(vehicle,trip,500).complete()).isTrue();
        var copied=preview().reportUnderBarrier(vehicle,100,null);
        assertThat(copied.archiveChecks().stream().filter(c -> c.state().equals("ARCHIVE_NOT_VERIFIED"))).hasSize(27);
        assertThat(new TripAggregateBuilder(jdbc,transactions).buildPage(vehicle,trip,500).complete()).isTrue();
        var verifier=new TripAggregateVerifier(jdbc,mapper,transactions);
        assertThat(verifier.verifyPage(vehicle,trip,500).state()).isEqualTo("COMPARE");
        assertThat(preview().reportUnderBarrier(vehicle,100,null).archiveChecks()).isEqualTo(copied.archiveChecks());
        assertThat(verifier.verifyPage(vehicle,trip,500).state()).isEqualTo("VERIFIED");
        String before=fingerprint(); var verified=preview().reportUnderBarrier(vehicle,100,null);
        assertThat(verified.archiveChecks().stream().filter(c -> c.state().equals("VERIFIED_COVERAGE"))).hasSize(27)
                .allSatisfy(c -> assertThat(c.verifiedTrip()).isEqualTo(trip));
        assertThat(verified.archiveChecks().stream().filter(c -> c.state().equals("RETAINED_MEMBERSHIP_MISSING"))).hasSize(4);
        assertThat(verified.preview().wouldDelete()).isZero(); assertThat(verified.state()).isEqualTo("CHECKED_NOT_AUTHORIZED");
        assertThat(fingerprint()).isEqualTo(before);
    }
    @Test void barrierArchiveChecksArePageBoundedOrderedAndExcludeOtherVehicles() {
        drive(true); UUID ownTrip=trip(); archive(ownTrip);
        UUID other=UUID.randomUUID(); jdbc.update("insert into vehicle(id,public_id) values (?,?)",other,"other-"+other);
        var all=preview().reportUnderBarrier(vehicle,100,null).archiveChecks();
        var collected=new ArrayList<RetentionPreview.ArchiveCheck>(); RetentionPreview.Cursor cursor=null;
        for (int i=0;i<40;i++) {
            var page=preview().reportUnderBarrier(vehicle,1,cursor);
            assertThat(page.archiveChecks()).hasSize(1);
            assertThat(page.archiveChecks().getFirst().readingId()).isEqualTo(page.preview().readings().getFirst().readingId());
            collected.addAll(page.archiveChecks()); cursor=page.preview().nextCursor(); if (cursor==null) break;
        }
        assertThat(collected).containsExactlyElementsOf(all); assertThat(collected).hasSize(31);
        assertThat(preview().reportUnderBarrier(other,100,null).archiveChecks()).isEmpty();
    }
    @Test void barrierArchiveChecksRecheckExactPayloadAndOriginalMetadata() {
        // Privileged unprojected disposable corruption fixture, not an owner repair.
        for (int i=1;i<=7;i++) store(sample(BigInteger.valueOf(i),BASE.plusSeconds(i)));
        assertThat(replayBackfill.copyPage(vehicle,100).complete()).isTrue();
        jdbc.update("update telemetry_reading set payload=jsonb_set(payload,'{rpm}','1900.00000000000000000001'::jsonb) where vehicle_id=? and sequence_number=1",vehicle);
        jdbc.update("update telemetry_reading set received_at=received_at+interval '1 microsecond' where vehicle_id=? and sequence_number=2",vehicle);
        jdbc.update("update telemetry_reading set recorded_at=recorded_at+interval '1 microsecond' where vehicle_id=? and sequence_number=3",vehicle);
        jdbc.update("update telemetry_reading set source='OBD' where vehicle_id=? and sequence_number=4",vehicle);
        jdbc.update("update telemetry_reading set boot_id=? where vehicle_id=? and sequence_number=5",UUID.randomUUID(),vehicle);
        jdbc.update("update telemetry_reading set sequence_number=777777 where vehicle_id=? and sequence_number=6",vehicle);
        String before=fingerprint(); var page=preview().reportUnderBarrier(vehicle,100,null);
        assertThat(page.archiveChecks()).hasSize(7);
        assertThat(page.archiveChecks().stream().filter(c -> c.state().equals("REPLAY_MISMATCH"))).hasSize(6);
        assertThat(page.archiveChecks().stream().filter(c -> c.state().equals("RETAINED_MEMBERSHIP_MISSING"))).hasSize(1);
        assertThat(page.archiveChecks()).allSatisfy(c -> assertThat(c.verifiedTrip()).isNull());
        assertThat(page.preview().wouldDelete()).isZero(); assertThat(fingerprint()).isEqualTo(before);
    }
    @Test void barrierArchiveCoverageDoesNotOverridePendingWorkOrMissingReplayCheckpoint() {
        drive(true); archive(trip());
        store(sample(BigInteger.valueOf(999),BASE.plusSeconds(120)));
        jdbc.update("update telemetry_replay_backfill set complete=false where vehicle_id=?",vehicle);
        var page=preview().reportUnderBarrier(vehicle,100,null);
        assertThat(page.archiveChecks().stream().filter(c -> c.state().equals("VERIFIED_COVERAGE"))).hasSize(27);
        assertThat(page.preview().vehicleProtections()).contains("PENDING_OR_REVIEW_WORK","REPLAY_BACKFILL_INCOMPLETE");
        assertThat(page.preview().policyCandidates()).isZero(); assertThat(page.preview().wouldDelete()).isZero();
        assertThat(page.preview().missingPrerequisites()).contains("EXPLICIT_PRUNE_TRANSACTION_REQUIRED","WRITE_TIME_REPLAY_COVERAGE_RECHECK_REQUIRED");
    }
    @Test void barrierArchiveChecksSeeArchiveCompletionAfterTheEarlierPreviewRead() {
        drive(true); UUID trip=trip();
        var changed=new java.util.concurrent.atomic.AtomicBoolean();
        try (var executor=Executors.newSingleThreadExecutor()) {
            var tracing=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
                @Override public <T> List<T> query(String sql,org.springframework.jdbc.core.RowMapper<T> mapper,Object... args) {
                    if (sql.startsWith("with requested") && changed.compareAndSet(false,true)) {
                        try { executor.submit(() -> archive(trip)).get(5,TimeUnit.SECONDS); }
                        catch (Exception failure) { throw new AssertionError("disposable archive completion failed",failure); }
                    }
                    return super.query(sql,mapper,args);
                }
            };
            var service=new RetentionPreview(tracing,transactions,new RetentionPreview.Options(true,true,30,96),clock);
            var page=service.reportUnderBarrier(vehicle,100,null);
            assertThat(changed).isTrue();
            assertThat(page.archiveChecks().stream().filter(c -> c.state().equals("VERIFIED_COVERAGE"))).hasSize(27)
                    .allSatisfy(c -> assertThat(c.verifiedTrip()).isEqualTo(trip));
            assertThat(page.preview().wouldDelete()).isZero();
        }
    }
    @Test void emptyBarrierPageHasNoArchiveCoverage() {
        var page=preview().reportUnderBarrier(vehicle,100,null);
        assertThat(page.archiveChecks()).isEmpty(); assertThat(page.preview().readings()).isEmpty();
        assertThat(page.preview().wouldDelete()).isZero();
    }
    @Test void barrierInspectionMatchesPreviewWithoutMutationAndUsesFreshStatementSnapshots() {
        drive(true); String before=fingerprint(); var expected=preview().report(vehicle,100,null);
        var tracing=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
            @Override public <T> List<T> queryForList(String sql,Class<T> type,Object... args) {
                if (sql.startsWith("select generation::text")) {
                    assertThat(jdbc.queryForObject("show transaction_read_only",String.class)).isEqualTo("off");
                    assertThat(jdbc.queryForObject("show transaction_isolation",String.class)).isEqualTo("read committed");
                }
                return super.queryForList(sql,type,args);
            }
        };
        var service=new RetentionPreview(tracing,transactions,new RetentionPreview.Options(true,true,30,96),clock);
        var checked=service.reportUnderBarrier(vehicle,100,null);
        assertThat(checked.state()).isEqualTo("CHECKED_NOT_AUTHORIZED"); assertThat(checked.preview()).isEqualTo(expected);
        assertThat(checked.preview().wouldDelete()).isZero();
        assertThat(checked.preview().missingPrerequisites()).contains("WRITE_TIME_REPLAY_COVERAGE_RECHECK_REQUIRED","EXPLICIT_PRUNE_TRANSACTION_REQUIRED");
        assertThat(fingerprint()).isEqualTo(before);
    }
    @Test void barrierInspectionReturnsBusyForProcessingPolicyAndReceiptContention() throws Exception {
        drive(true); String before=fingerprint();
        for (String lockSql : List.of(
                "select pg_advisory_xact_lock(hashtextextended(cast(? as text),902))",
                "select vehicle_id from trip_policy where vehicle_id=? for update",
                "select vehicle_id from trip_vehicle_activity where vehicle_id=? for update")) {
            var acquired=new CountDownLatch(1); var release=new CountDownLatch(1);
            try (var executor=Executors.newSingleThreadExecutor()) {
                var holder=executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    jdbc.queryForList(lockSql,vehicle); acquired.countDown();
                    try { if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("test lock timed out"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                }));
                try {
                    assertThat(acquired.await(5,TimeUnit.SECONDS)).isTrue();
                    var busy=preview().reportUnderBarrier(vehicle,100,null);
                    assertThat(busy.state()).isEqualTo("BUSY"); assertThat(busy.preview()).isNull();
                    assertThat(busy.archiveChecks()).isEmpty();
                } finally { release.countDown(); }
                holder.get(10,TimeUnit.SECONDS);
            }
            assertThat(preview().reportUnderBarrier(vehicle,100,null).state()).isEqualTo("CHECKED_NOT_AUTHORIZED");
        }
        assertThat(fingerprint()).isEqualTo(before);
    }
    @Test void barrierInspectionRejectsChangedPolicyAndGenerationContinuations() {
        drive(true); var cursor=preview().report(vehicle,1,null).nextCursor(); assertThat(cursor).isNotNull();
        jdbc.update("update trip_policy set revision=revision+1 where vehicle_id=?",vehicle);
        assertThatThrownBy(() -> preview().reportUnderBarrier(vehicle,1,cursor)).hasMessageContaining("data/policy changed");
        jdbc.update("update trip_policy set revision=revision-1 where vehicle_id=?",vehicle);
        store(sample(BigInteger.valueOf(999),BASE.plusSeconds(120)));
        assertThatThrownBy(() -> preview().reportUnderBarrier(vehicle,1,cursor)).hasMessageContaining("data/policy changed");
        var fresh=preview().reportUnderBarrier(vehicle,100,null).preview();
        assertThat(fresh.vehicleProtections()).contains("PENDING_OR_REVIEW_WORK"); assertThat(fresh.wouldDelete()).isZero();
    }
    @Test void barrierInspectionBlocksInFlightIngestionThenInvalidatesItsOldResult() throws Exception {
        drive(true); String generation=preview().report(vehicle,1,null).generation();
        var started=new CountDownLatch(1); var pid=new java.util.concurrent.atomic.AtomicInteger();
        var writer=new java.util.concurrent.atomic.AtomicReference<Future<?>>();
        var observed=new java.util.concurrent.atomic.AtomicBoolean();
        try (var executor=Executors.newSingleThreadExecutor()) {
            var tracing=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
                @Override public <T> List<T> queryForList(String sql,Class<T> type,Object... args) {
                    if (sql.startsWith("select generation::text") && observed.compareAndSet(false,true)) {
                        writer.set(executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                            pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class)); started.countDown();
                            store(sample(BigInteger.valueOf(999),BASE.plusSeconds(120)));
                        })));
                        try {
                            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue(); boolean blocked=false;
                            for (int i=0;i<200;i++) {
                                if (Boolean.TRUE.equals(jdbc.queryForObject("select cardinality(pg_blocking_pids(?))>0",Boolean.class,pid.get()))) { blocked=true; break; }
                                Thread.sleep(10);
                            }
                            assertThat(blocked).as("actual PostgreSQL lock wait, not just an unfinished future").isTrue();
                            assertThat(writer.get().isDone()).isFalse();
                        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                    }
                    return super.queryForList(sql,type,args);
                }
            };
            var service=new RetentionPreview(tracing,transactions,new RetentionPreview.Options(true,true,30,96),clock);
            var checked=service.reportUnderBarrier(vehicle,1,null);
            assertThat(checked.preview().generation()).isEqualTo(generation); assertThat(checked.preview().wouldDelete()).isZero();
            assertThat(observed).isTrue(); writer.get().get(10,TimeUnit.SECONDS);
            assertThatThrownBy(() -> preview().reportUnderBarrier(vehicle,1,checked.preview().nextCursor())).hasMessageContaining("data/policy changed");
        }
    }
    @Test void barrierInspectionSeesCommitBetweenPolicyAndReceiptLocks() {
        drive(true); var cursor=preview().report(vehicle,1,null).nextCursor();
        var inserted=new java.util.concurrent.atomic.AtomicBoolean();
        try (var executor=Executors.newSingleThreadExecutor()) {
            var tracing=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
                @Override public List<Map<String,Object>> queryForList(String sql,Object... args) {
                    var result=super.queryForList(sql,args);
                    if (sql.startsWith("select vehicle_id from trip_policy") && inserted.compareAndSet(false,true)) {
                        try { executor.submit(() -> store(sample(BigInteger.valueOf(999),BASE.plusSeconds(120)))).get(5,TimeUnit.SECONDS); }
                        catch (Exception failure) { throw new AssertionError("test ingestion failed before receipt lock",failure); }
                    }
                    return result;
                }
            };
            var service=new RetentionPreview(tracing,transactions,new RetentionPreview.Options(true,true,30,96),clock);
            assertThatThrownBy(() -> service.reportUnderBarrier(vehicle,1,cursor)).hasMessageContaining("data/policy changed");
            assertThat(inserted).isTrue();
        }
    }
    @Test void barrierInspectionDistinguishesUnknownVehicleAndMissingReceiptRegistration() {
        assertThatThrownBy(() -> preview().reportUnderBarrier(UUID.randomUUID(),1,null)).hasMessageContaining("Unknown retention preview vehicle");
        // Empty disposable fixture only; loss of registration must fail, not look like BUSY forever.
        jdbc.update("delete from trip_vehicle_activity where vehicle_id=?",vehicle);
        assertThatThrownBy(() -> preview().reportUnderBarrier(vehicle,1,null)).hasMessageContaining("receipt barrier registration missing");
    }
    @Test void finalizedEvidenceMayMeetPolicyButNeverBecomesDeletableAndPreviewIsReadOnly() {
        drive(true); String before=fingerprint();
        var tracing=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
            @Override public List<Map<String,Object>> queryForList(String sql,Object... args) {
                assertThat(jdbc.queryForObject("show transaction_read_only",String.class)).isEqualTo("on");
                assertThat(jdbc.queryForObject("show transaction_isolation",String.class)).isEqualTo("repeatable read");
                var result=super.queryForList(sql,args);
                assertThat(result).allSatisfy(row -> assertThat(row).doesNotContainKey("payload"));
                return result;
            }
        };
        var service=new RetentionPreview(tracing,transactions,new RetentionPreview.Options(true,true,30,96),clock);
        var first=service.report(vehicle,100,null);
        var again=service.report(vehicle,100,null);
        assertThat(first).isEqualTo(again); assertThat(first.state()).isEqualTo("READ_ONLY_PREVIEW");
        assertThat(first.vehicleProtections()).isEmpty(); assertThat(first.readings()).hasSize(31); assertThat(first.policyCandidates()).isEqualTo(27);
        assertThat(first.wouldDelete()).isZero(); assertThat(first.missingPrerequisites()).contains("RETAINED_FINAL_EVIDENCE_REQUIRED","WRITE_TIME_REPLAY_COVERAGE_RECHECK_REQUIRED","EXPLICIT_PRUNE_TRANSACTION_REQUIRED")
                .doesNotContain("EXACT_REPLAY_IDENTITY_LEDGER_REQUIRED");
        assertThat(first.replayCoverage()).isEqualTo(new RetentionPreview.ReplayCoverage("COMPLETE",31,31,0,0));
        assertThat(first.readings()).allSatisfy(row -> assertThat(row.replayIdentityState()).isEqualTo("MATCHED"));
        assertThat(first.readings()).anySatisfy(row -> assertThat(row.protections()).contains("LATEST_READING_PROTECTED"));
        assertThat(fingerprint()).isEqualTo(before);
    }
    @Test void unfinishedReplayBackfillProtectsEvenACompletelyMatchingFinalizedPage() {
        drive(true);
        jdbc.update("update telemetry_replay_backfill set complete=false where vehicle_id=?",vehicle);
        String before=fingerprint(); var report=preview().report(vehicle,100,null);
        assertThat(report.replayCoverage()).isEqualTo(new RetentionPreview.ReplayCoverage("INCOMPLETE",31,31,0,0));
        assertThat(report.vehicleProtections()).contains("REPLAY_BACKFILL_INCOMPLETE");
        assertThat(report.policyCandidates()).isZero(); assertThat(report.wouldDelete()).isZero();
        assertThat(fingerprint()).isEqualTo(before);
        assertThat(replayBackfill.copyPage(vehicle,100).complete()).isTrue();
        var complete=preview().report(vehicle,100,null);
        assertThat(complete.vehicleProtections()).doesNotContain("REPLAY_BACKFILL_INCOMPLETE");
        assertThat(complete.policyCandidates()).isEqualTo(27); assertThat(complete.wouldDelete()).isZero();
    }
    @Test void missingReplayRegistrationIsNotSilentlyCreatedOrConsideredComplete() {
        drive(true);
        jdbc.update("delete from telemetry_replay_backfill where vehicle_id=?",vehicle); // Disposable missing-metadata fixture only.
        String before=fingerprint(); var report=preview().report(vehicle,100,null);
        assertThat(report.replayCoverage()).isEqualTo(new RetentionPreview.ReplayCoverage("MISSING",31,31,0,0));
        assertThat(report.vehicleProtections()).contains("REPLAY_BACKFILL_MISSING");
        assertThat(report.policyCandidates()).isZero(); assertThat(fingerprint()).isEqualTo(before);
    }
    @Test void completeBackfillFlagCannotHidePayloadTimestampSourceOrIdentityDivergence() {
        for (int i=1;i<=7;i++) store(sample(BigInteger.valueOf(i),BASE.plusSeconds(i)));
        assertThat(replayBackfill.copyPage(vehicle,100).complete()).isTrue();
        // Deliberately inconsistent unprojected raw fixtures; never disable ledger
        // immutability or final-trip guards to manufacture these cases.
        jdbc.update("update telemetry_reading set payload=jsonb_set(payload,'{rpm}','1900.00'::jsonb) where vehicle_id=? and sequence_number=1",vehicle);
        jdbc.update("update telemetry_reading set payload=jsonb_set(payload,'{rpm}','1900.00000000000000000001'::jsonb) where vehicle_id=? and sequence_number=2",vehicle);
        jdbc.update("update telemetry_reading set received_at=received_at+interval '1 microsecond' where vehicle_id=? and sequence_number=3",vehicle);
        jdbc.update("update telemetry_reading set recorded_at=recorded_at+interval '1 microsecond' where vehicle_id=? and sequence_number=4",vehicle);
        jdbc.update("update telemetry_reading set source='OBD' where vehicle_id=? and sequence_number=5",vehicle);
        jdbc.update("update telemetry_reading set boot_id=? where vehicle_id=? and sequence_number=6",UUID.randomUUID(),vehicle);
        jdbc.update("update telemetry_reading set sequence_number=777777 where vehicle_id=? and sequence_number=7",vehicle);
        String before=fingerprint(); var report=preview().report(vehicle,100,null);
        assertThat(report.replayCoverage()).isEqualTo(new RetentionPreview.ReplayCoverage("COMPLETE",7,1,2,4));
        assertThat(report.readings().getFirst().replayIdentityState()).isEqualTo("MATCHED");
        assertThat(report.readings().stream().filter(row -> row.replayIdentityState().equals("MISMATCH")))
                .allSatisfy(row -> { assertThat(row.protections()).contains("REPLAY_IDENTITY_MISMATCH"); assertThat(row.policyCandidate()).isFalse(); });
        assertThat(report.readings().stream().filter(row -> row.replayIdentityState().equals("MISSING")))
                .allSatisfy(row -> { assertThat(row.protections()).contains("REPLAY_IDENTITY_MISSING"); assertThat(row.policyCandidate()).isFalse(); });
        assertThat(report.wouldDelete()).isZero(); assertThat(preview().report(vehicle,100,null)).isEqualTo(report);
        assertThat(fingerprint()).isEqualTo(before);
    }
    @Test void originalReadingAndVehicleMetadataMustAlsoMatchLedger() {
        store(sample(BigInteger.ONE,BASE)); store(sample(BigInteger.TWO,BASE.plusSeconds(1)));
        assertThat(replayBackfill.copyPage(vehicle,100).complete()).isTrue();
        // No trip/evidence exists in this synthetic corruption fixture.
        jdbc.update("delete from trip_work_item where vehicle_id=?",vehicle);
        jdbc.update("update telemetry_reading set id=? where vehicle_id=? and sequence_number=1",UUID.randomUUID(),vehicle);
        UUID other=UUID.randomUUID(); jdbc.update("insert into vehicle(id,public_id) values (?,?)",other,"other-"+other);
        jdbc.update("update telemetry_reading set vehicle_id=? where vehicle_id=? and sequence_number=2",other,vehicle);
        var original=preview().report(vehicle,100,null); var moved=preview().report(other,100,null);
        assertThat(original.replayCoverage()).isEqualTo(new RetentionPreview.ReplayCoverage("COMPLETE",1,0,0,1));
        assertThat(moved.replayCoverage()).isEqualTo(new RetentionPreview.ReplayCoverage("INCOMPLETE",1,0,0,1));
        assertThat(original.readings().getFirst().protections()).contains("REPLAY_IDENTITY_MISMATCH");
        assertThat(moved.readings().getFirst().protections()).contains("REPLAY_IDENTITY_MISMATCH");
    }
    @Test void coverageCountsOnlyReturnedRowsNotLookaheadOrAnotherVehicle() {
        for (int i=1;i<=3;i++) store(sample(BigInteger.valueOf(i),BASE.plusSeconds(i)));
        assertThat(replayBackfill.copyPage(vehicle,100).complete()).isTrue();
        jdbc.update("update telemetry_reading set boot_id=? where vehicle_id=? and sequence_number=2",UUID.randomUUID(),vehicle);
        var first=preview().report(vehicle,1,null);
        assertThat(first.replayCoverage()).isEqualTo(new RetentionPreview.ReplayCoverage("COMPLETE",1,1,0,0));
        var second=preview().report(vehicle,1,first.nextCursor());
        assertThat(second.replayCoverage()).isEqualTo(new RetentionPreview.ReplayCoverage("COMPLETE",1,0,1,0));
        var third=preview().report(vehicle,1,second.nextCursor());
        assertThat(third.replayCoverage()).isEqualTo(new RetentionPreview.ReplayCoverage("COMPLETE",1,1,0,0));
        assertThat(third.nextCursor()).isNull();
        UUID other=UUID.randomUUID(); jdbc.update("insert into vehicle(id,public_id) values (?,?)",other,"other-"+other);
        var empty=preview().report(other,1,null);
        assertThat(empty.replayCoverage()).isEqualTo(new RetentionPreview.ReplayCoverage("INCOMPLETE",0,0,0,0));
        assertThat(empty.vehicleProtections()).contains("REPLAY_BACKFILL_INCOMPLETE");
        assertThat(empty.policyCandidates()).isZero();
    }
    @Test void concurrentBackfillCompletionIsSnapshotCoherentAndReevaluatedOnContinuation() throws Exception {
        drive(true);
        jdbc.update("update telemetry_replay_backfill set complete=false where vehicle_id=?",vehicle);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var tracing=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
            @Override public <T> List<T> queryForList(String sql,Class<T> type,Object... args) {
                var rows=super.queryForList(sql,type,args);
                if (sql.startsWith("select generation::text")) {
                    entered.countDown();
                    try { assertThat(release.await(5,TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                }
                return rows;
            }
        };
        var service=new RetentionPreview(tracing,transactions,new RetentionPreview.Options(true,true,30,96),clock);
        var pending=CompletableFuture.supplyAsync(() -> service.report(vehicle,1,null));
        try {
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(replayBackfill.copyPage(vehicle,100).complete()).isTrue();
        } finally { release.countDown(); }
        var old=pending.get(5,TimeUnit.SECONDS);
        assertThat(old.replayCoverage()).isEqualTo(new RetentionPreview.ReplayCoverage("INCOMPLETE",1,1,0,0));
        assertThat(old.vehicleProtections()).contains("REPLAY_BACKFILL_INCOMPLETE");
        var next=preview().report(vehicle,1,old.nextCursor());
        assertThat(next.generation()).isEqualTo(old.generation()); // Backfill is not a raw/trip mutation.
        assertThat(next.replayCoverage()).isEqualTo(new RetentionPreview.ReplayCoverage("COMPLETE",1,1,0,0));
        assertThat(next.vehicleProtections()).doesNotContain("REPLAY_BACKFILL_INCOMPLETE");
        assertThat(next.wouldDelete()).isZero();
    }
    @Test void provisionalOpenPendingReviewAndUnknownWorkerRemainProtected() {
        drive(false); var before=preview().report(vehicle,100,null);
        assertThat(before.policyCandidates()).isZero(); assertThat(before.vehicleProtections()).contains("PROVISIONAL_TRIP");
        // Synthetic nonfinal open-state fixture; trip-rule correctness has separate tests.
        jdbc.update("update trip_record set status='ACTIVE',ended_at=null,confirmed_at=null,completion_reason=null where vehicle_id=? and status='COMPLETED'",vehicle);
        assertThat(preview().report(vehicle,100,null).vehicleProtections()).contains("OPEN_OR_REBUILDING_TRIP");
        jdbc.update("update trip_projection_progress set needs_review=true where vehicle_id=?",vehicle);
        assertThat(preview().report(vehicle,100,null).vehicleProtections()).contains("PROJECTION_REVIEW_REQUIRED");
        jdbc.update("delete from trip_processing_control where vehicle_id=?",vehicle); // Disposable fixture only; never an operator recipe.
        assertThat(preview().report(vehicle,100,null).vehicleProtections()).contains("WORKER_NOT_IDLE_OR_UNKNOWN");
        store(sample(BigInteger.valueOf(1000),BASE.plusSeconds(100)));
        assertThat(preview().report(vehicle,100,null).vehicleProtections()).contains("PENDING_OR_REVIEW_WORK");
    }
    @Test void failedLeasedAndRetryingWorkersCannotYieldPolicyCandidates() {
        drive(true);
        for (String state : List.of("PAUSED","RUNNING","RETRY_WAIT")) {
            jdbc.update("update trip_processing_control set state=?,claim_token=?,lease_until=?,next_attempt_at=? where vehicle_id=?",state,
                    state.equals("RUNNING") ? UUID.randomUUID() : null,state.equals("RUNNING") ? Timestamp.from(AS_OF.plusSeconds(120)) : null,
                    state.equals("RETRY_WAIT") ? Timestamp.from(AS_OF.plusSeconds(60)) : null,vehicle);
            var report=preview().report(vehicle,100,null);
            assertThat(report.policyCandidates()).isZero(); assertThat(report.vehicleProtections()).contains("WORKER_NOT_IDLE_OR_UNKNOWN");
        }
    }
    @Test void cutoffAndReceiptHoldsAreStrictAndFutureInputCannotAdvanceThem() {
        drive(true); var initial=preview().report(vehicle,100,null); Instant cutoff=initial.measurementBefore(),receipt=initial.receiptBefore();
        for (int i=0;i<3;i++) {
            var r=validator.validate(sample(BigInteger.valueOf(1000+i),cutoff.plusNanos((i-1)*1000L)));
            jdbc.update("insert into telemetry_reading(id,vehicle_id,device_id,boot_id,sequence_number,recorded_at,received_at,source,payload) values (?,?,?,?,?,?,?,'SIMULATED',?::jsonb)",
                    UUID.randomUUID(),vehicle,device,r.bootId(),new java.math.BigDecimal(r.sequenceNumber()),Timestamp.from(r.recordedAt()),Timestamp.from(receipt.plusNanos((i-1)*1000L)),r.payload().toString());
        }
        store(sample(BigInteger.valueOf(9999),AS_OF.plus(Duration.ofDays(365))));
        var report=preview().report(vehicle,100,null);
        assertThat(report.measurementBefore()).isEqualTo(cutoff).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(report.receiptBefore()).isEqualTo(receipt); assertThat(report.readings()).hasSize(32);
        assertThat(report.readings()).allMatch(row -> row.recordedAt().isBefore(cutoff));
        assertThat(report.readings().getLast().protections()).doesNotContain("RECEIPT_QUARANTINE");
        // Additional old events at/exactly after the receipt boundary are held.
        for (int i=0;i<2;i++) {
            var r=validator.validate(sample(BigInteger.valueOf(10000+i),BASE.minusSeconds(i+1)));
            jdbc.update("insert into telemetry_reading(id,vehicle_id,device_id,boot_id,sequence_number,recorded_at,received_at,source,payload) values (?,?,?,?,?,?,?,'SIMULATED',?::jsonb)",
                    UUID.randomUUID(),vehicle,device,r.bootId(),new java.math.BigDecimal(r.sequenceNumber()),Timestamp.from(r.recordedAt()),Timestamp.from(receipt.plusNanos(i*1000L)),r.payload().toString());
        }
        assertThat(preview().report(vehicle,100,null).readings().stream().filter(row -> row.recordedAt().isBefore(BASE)).toList())
                .allSatisfy(row -> assertThat(row.protections()).contains("RECEIPT_QUARANTINE"));
    }
    @Test void preciseNativeTuplePagingIsFrozenBoundedAndGenerationFenced() {
        drive(true);
        store(sample(new BigInteger("1234567890123456789012345678901234567890"),BASE.plusSeconds(10)));
        store(sample(BigInteger.valueOf(9999),BASE.plusSeconds(10)).put("bootId",UUID.randomUUID().toString()));
        var expected=jdbc.queryForList("select id from telemetry_reading where vehicle_id=? order by recorded_at,device_id,boot_id,sequence_number",UUID.class,vehicle);
        var seen=new ArrayList<UUID>(); RetentionPreview.Cursor cursor=null;
        for (int i=0;i<100;i++) {
            var page=preview().report(vehicle,1,cursor); assertThat(page.readings()).hasSize(1); assertThat(page.asOf()).isEqualTo(AS_OF);
            assertThat(page.replayCoverage()).isEqualTo(new RetentionPreview.ReplayCoverage("COMPLETE",1,1,0,0));
            seen.add(page.readings().getFirst().readingId()); cursor=page.nextCursor(); if (cursor==null) break;
            clock=Clock.fixed(AS_OF.plusSeconds(2),ZoneOffset.UTC);
        }
        assertThat(cursor).isNull(); assertThat(seen).isEqualTo(expected);
        clock=Clock.fixed(AS_OF,ZoneOffset.UTC);
        var token=preview().report(vehicle,1,null).nextCursor();
        assertThatThrownBy(() -> preview().report(UUID.randomUUID(),1,token)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> preview().report(vehicle,2,token)).isInstanceOf(IllegalArgumentException.class);
        clock=Clock.fixed(AS_OF.plusSeconds(900),ZoneOffset.UTC);
        assertThatThrownBy(() -> preview().report(vehicle,1,token)).isInstanceOf(IllegalArgumentException.class);
        clock=Clock.fixed(AS_OF,ZoneOffset.UTC);
        var original=(ObjectNode)mapper.readTree(Path.of("../contracts/examples/trip-short-input.json").toFile()).get("readings").get(0).deepCopy();
        assertThat(repository.store(vehicle,device,validator.validate(original.put("vehicleId",publicVehicle).put("deviceId",publicDevice)))).isEqualTo("duplicate");
        assertThat(preview().report(vehicle,1,token).readings()).hasSize(1);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> { store(sample(BigInteger.valueOf(7777),BASE)); tx.setRollbackOnly(); });
        assertThat(preview().report(vehicle,1,token).readings()).hasSize(1);
        store(sample(BigInteger.valueOf(8888),BASE));
        assertThatThrownBy(() -> preview().report(vehicle,1,token)).isInstanceOf(IllegalStateException.class).hasMessageContaining("changed");
        var newToken=preview().report(vehicle,1,null).nextCursor();
        jdbc.update("update trip_policy set revision=revision+1 where vehicle_id=?",vehicle);
        assertThatThrownBy(() -> preview().report(vehicle,1,newToken)).isInstanceOf(IllegalStateException.class).hasMessageContaining("changed");
    }
    @Test void missingBackfillPolicyAndProjectionNeverBecomeImplicitlySafe() {
        store(sample(BigInteger.ONE,BASE)); var report=preview().report(vehicle,100,null);
        assertThat(report.vehicleProtections()).contains("BACKFILL_INCOMPLETE","ACTIVITY_SCAN_INCOMPLETE","PROJECTION_MISSING");
        assertThat(report.policyCandidates()).isZero();
        jdbc.update("delete from trip_policy where vehicle_id=?",vehicle); // Disposable fixture, not a recovery procedure.
        assertThat(preview().report(vehicle,100,null).vehicleProtections()).contains("PRIMARY_POLICY_MISSING");
        assertThatThrownBy(() -> preview().report(UUID.randomUUID(),10,null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown");
    }
    @Test void realCorrectionAndPublicationProtectTheWholeVehicle() {
        drive(false);
        var late=(ObjectNode)mapper.readTree(Path.of("../contracts/examples/trip-short-input.json").toFile()).get("readings").get(5).deepCopy();
        store(late.put("vehicleId",publicVehicle).put("deviceId",publicDevice).put("sequenceNumber",999).put("rpm",2000));
        projector.processPage(vehicle,AS_OF,100);
        boolean planned=false;
        for (int i=0;i<100;i++) if (planner.planPage(vehicle,AS_OF,100).state().equals("READY")) { planned=true; break; }
        assertThat(planned).isTrue();
        var plannedReport=preview().report(vehicle,100,null);
        assertThat(plannedReport.vehicleProtections()).contains("CORRECTION_PENDING"); assertThat(plannedReport.policyCandidates()).isZero();
        boolean staged=false;
        for (int i=0;i<100;i++) if (replayer.replayPage(vehicle,100).state().equals("STAGED")) { staged=true; break; }
        assertThat(staged).isTrue(); publisher.publishPage(vehicle,100);
        String before=fingerprint();
        var publishing=preview().report(vehicle,100,null);
        assertThat(publishing.vehicleProtections()).contains("PUBLICATION_PENDING"); assertThat(publishing.policyCandidates()).isZero();
        assertThat(fingerprint()).isEqualTo(before);
    }
    @Test void acceptedInsertDuringPreviewCannotMixItsSnapshotAndContinuation() throws Exception {
        drive(true); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var tracing=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
            @Override public <T> List<T> queryForList(String sql,Class<T> type,Object... args) {
                var rows=super.queryForList(sql,type,args);
                if (sql.startsWith("select generation::text")) {
                    entered.countDown();
                    try { assertThat(release.await(5,TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                }
                return rows;
            }
        };
        var service=new RetentionPreview(tracing,transactions,new RetentionPreview.Options(true,true,30,96),clock);
        var pending=CompletableFuture.supplyAsync(() -> service.report(vehicle,1,null));
        try { assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); store(sample(BigInteger.valueOf(9999),BASE.minusSeconds(1))); }
        finally { release.countDown(); }
        var old=pending.get(5,TimeUnit.SECONDS); assertThat(old.readings().getFirst().recordedAt()).isEqualTo(BASE);
        assertThatThrownBy(() -> preview().report(vehicle,1,old.nextCursor())).isInstanceOf(IllegalStateException.class);
        assertThat(preview().report(vehicle,1,null).readings().getFirst().recordedAt()).isEqualTo(BASE.minusSeconds(1));
    }
}
