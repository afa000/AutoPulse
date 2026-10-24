package dev.autopulse;

import dev.autopulse.telemetry.*;
import dev.autopulse.trip.*;
import java.math.BigInteger;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="spring.config.import=")
@Import(TestcontainersConfiguration.class)
class RetentionPruningTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;
    @Autowired TelemetryValidator validator;
    @Autowired TelemetryRepository repository;
    @Autowired TripProjector projector;
    @Autowired TripBackfill backfill;
    @Autowired ReplayIdentityBackfill replay;
    @Autowired TripFinalizer finalizer;
    @Autowired FinalTripEvidence evidence;
    @Autowired TripAggregateBuilder builder;
    @Autowired TripAggregateVerifier verifier;
    @Autowired HistoryQueries history;
    RetentionPreviewTests f;
    @BeforeEach void setup() {
        f=new RetentionPreviewTests(); f.jdbc=jdbc; f.transactions=transactions; f.mapper=mapper; f.validator=validator;
        f.projector=projector; f.backfill=backfill; f.replayBackfill=replay; f.finalizer=finalizer; f.setup();
    }
    RetentionPreview pruner(boolean enabled,boolean dry) { return new RetentionPreview(jdbc,transactions,new RetentionPreview.Options(enabled,dry,30,96),f.clock); }
    RetentionMaintenance maintenance(RetentionPreview p) { return new RetentionMaintenance(jdbc,evidence,builder,verifier,p); }
    int count(String table) { return jdbc.queryForObject("select count(*) from "+table+" where vehicle_id=?",Integer.class,f.vehicle); }
    HistoryRequest query(UUID trip,String resolution,int limit,String cursor) {
        return new HistoryRequest(RetentionPreviewTests.BASE,RetentionPreviewTests.BASE.plusSeconds(240),limit,trip,cursor,resolution);
    }
    List<?> points(UUID trip,String resolution) { return history.history(f.publicVehicle,query(trip,resolution,100,null)).body().points(); }
    void archived() { f.drive(true); f.archive(f.trip()); }
    @Test void disabledAndDryRunNeverPrepareArchivesOrDelete() {
        f.drive(true); String before=f.fingerprint();
        assertThat(maintenance(pruner(false,false)).runPage(f.vehicle,3,null).state()).isEqualTo("DISABLED");
        assertThat(maintenance(pruner(true,true)).runPage(f.vehicle,100,null).state()).isEqualTo("DRY_RUN");
        assertThat(count("telemetry_raw_expiry")).isZero(); assertThat(f.fingerprint()).isEqualTo(before);
        f.archive(f.trip()); before=f.fingerprint();
        var dry=pruner(true,true).prunePage(f.vehicle,100,null);
        assertThat(dry.eligible()).isEqualTo(27); assertThat(dry.deleted()).isZero(); assertThat(f.fingerprint()).isEqualTo(before);
    }
    @Test void actualPruningPreservesFinalSummariesLinksLedgerAndAllAggregateValues() {
        archived(); UUID trip=f.trip(); var summary=jdbc.queryForMap("select * from trip_record where id=?",trip);
        var links=jdbc.queryForList("select * from trip_reading_link where trip_id=? order by reading_id",trip);
        var before=points(trip,"1m"); var whole=points(null,"5m");
        var latest=repository.latest(f.vehicle);
        var result=pruner(true,false).prunePage(f.vehicle,100,null);
        assertThat(result.deleted()).isEqualTo(27); assertThat(result.complete()).isTrue();
        assertThat(count("telemetry_reading")).isEqualTo(4); assertThat(count("telemetry_raw_expiry")).isEqualTo(27);
        assertThat(count("telemetry_replay_identity")).isEqualTo(31); assertThat(count("trip_work_item")).isEqualTo(31);
        assertThat(jdbc.queryForMap("select * from trip_record where id=?",trip)).isEqualTo(summary);
        assertThat(jdbc.queryForList("select * from trip_reading_link where trip_id=? order by reading_id",trip)).isEqualTo(links);
        assertThat(points(trip,"1m")).isEqualTo(before); assertThat(points(null,"5m")).isEqualTo(whole);
        assertThat(repository.latest(f.vehicle)).isEqualTo(latest);
        assertThat(pruner(true,false).prunePage(f.vehicle,100,null).deleted()).isZero();
        assertThat(count("telemetry_raw_expiry")).isEqualTo(27);
    }
    @Test void restartableMaintenanceCreatesEvidenceBucketsVerifiesThenPrunesInSmallPages() {
        f.drive(true); var stages=new HashSet<String>(); RetentionPreview.Cursor cursor=null; int deleted=0; boolean complete=false;
        for (int i=0;i<150;i++) {
            var p=maintenance(pruner(true,false)).runPage(f.vehicle,3,cursor);
            stages.add(p.stage()); assertThat(p.deleted()).isBetween(0,3); deleted+=p.deleted(); cursor=p.nextCursor();
            if (p.complete()) { complete=true; break; }
        }
        assertThat(complete).isTrue(); assertThat(deleted).isEqualTo(27);
        assertThat(stages).contains("EVIDENCE","AGGREGATES","VERIFY","PRUNE");
        assertThat(count("telemetry_raw_expiry")).isEqualTo(27);
        assertThat(maintenance(pruner(true,false)).runPage(f.vehicle,100,null).deleted()).isZero();
    }
    @Test void deletionFailureRollsBackRawExpiryAndGenerationButRetainsCommittedArchives() {
        archived(); String before=f.fingerprint(); String generation=jdbc.queryForObject("select generation::text from vehicle_read_generation where vehicle_id=?",String.class,f.vehicle);
        var failing=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
            @Override public int update(String sql,Object... args) {
                int changed=super.update(sql,args);
                if (sql.startsWith("delete from telemetry_reading")) throw new IllegalStateException("crash after delete before commit");
                return changed;
            }
        };
        var p=new RetentionPreview(failing,transactions,new RetentionPreview.Options(true,false,30,96),f.clock);
        assertThatThrownBy(() -> p.prunePage(f.vehicle,100,null)).hasMessageContaining("crash after delete");
        assertThat(f.fingerprint()).isEqualTo(before); assertThat(count("telemetry_raw_expiry")).isZero();
        assertThat(jdbc.queryForObject("select generation::text from vehicle_read_generation where vehicle_id=?",String.class,f.vehicle)).isEqualTo(generation);
        assertThat(pruner(true,false).prunePage(f.vehicle,100,null).deleted()).isEqualTo(27);
    }
    @Test void rawExpiryAndCursorGenerationAreTruthfulAndRangeSpecific() {
        archived(); UUID trip=f.trip(); var old=history.history(f.publicVehicle,query(trip,"raw",1,null)).body();
        assertThat(old.nextCursor()).isNotNull(); pruner(true,false).prunePage(f.vehicle,100,null);
        assertThatThrownBy(() -> history.history(f.publicVehicle,query(trip,"raw",1,old.nextCursor()))).hasMessageContaining("restart paging");
        var raw=history.history(f.publicVehicle,query(trip,"raw",100,null)).body();
        assertThat(raw.points()).isEmpty(); assertThat(raw.availability().rawState()).isEqualTo("EXPIRED");
        assertThat(raw.availability().availableResolutions()).containsExactly("1m","5m");
        var whole=history.history(f.publicVehicle,query(null,"raw",100,null)).body();
        assertThat(whole.points()).hasSize(4); assertThat(whole.availability().rawState()).isEqualTo("PARTIALLY_EXPIRED");
        var empty=history.history(f.publicVehicle,new HistoryRequest(RetentionPreviewTests.BASE.plusSeconds(100),RetentionPreviewTests.BASE.plusSeconds(110),100,null,null,"raw")).body();
        assertThat(empty.availability().rawState()).isEqualTo("AVAILABLE");
        assertThat(history.history(f.publicVehicle,query(trip,"1m",100,null)).body().points()).isNotEmpty();
    }
    @Test void expiredIdentityReplayCannotResurrectRawOrRefreshReceipt() {
        archived(); var node=(ObjectNode)mapper.readTree(jdbc.queryForObject("select r.payload::text from telemetry_reading r join trip_retained_evidence m on m.reading_id=r.id where m.trip_id=? order by r.id limit 1",String.class,f.trip()));
        pruner(true,false).prunePage(f.vehicle,100,null); String before=f.fingerprint();
        assertThat(repository.store(f.vehicle,f.device,validator.validate(node))).isEqualTo("duplicate");
        assertThat(repository.store(f.vehicle,f.device,validator.validate(node.deepCopy().put("rpm",9999)))).isEqualTo("conflict");
        assertThat(f.fingerprint()).isEqualTo(before); assertThat(count("telemetry_raw_expiry")).isEqualTo(27);
        assertThat(backfill.enqueuePage(f.vehicle,100).complete()).isTrue();
    }
    @Test void pendingProvisionalFailedAndUnverifiedDataStayProtected() {
        f.drive(false); assertThat(pruner(true,false).prunePage(f.vehicle,100,null).deleted()).isZero();
        for(int i=0;i<10;i++) if(finalizer.finalizePage(f.vehicle,RetentionPreviewTests.AS_OF,100).finalized()>0) break;
        assertThat(pruner(true,false).prunePage(f.vehicle,100,null).deleted()).isZero(); // no retained/bucket verification
        f.archive(f.trip());
        jdbc.update("update trip_work_item set status='REVIEW' where vehicle_id=?",f.vehicle);
        assertThat(pruner(true,false).prunePage(f.vehicle,100,null).deleted()).isZero();
        assertThat(count("telemetry_raw_expiry")).isZero(); assertThat(count("telemetry_reading")).isEqualTo(31);
    }
    @Test void correctionReferenceProtectsItsRawEvenAfterArchiveVerification() {
        archived(); UUID reading=jdbc.queryForObject("select reading_id from trip_retained_evidence where trip_id=? order by reading_id limit 1",UUID.class,f.trip());
        var graph=f.inactiveCorrection(reading);
        jdbc.update("insert into trip_correction_reading(replay_id,reading_id,segment_id) values (?,?,?)",graph[0],reading,graph[1]);
        assertThat(pruner(true,false).prunePage(f.vehicle,100,null).deleted()).isEqualTo(26);
        assertThat(jdbc.queryForList("select id from telemetry_reading where id=?",UUID.class,reading)).containsExactly(reading);
        assertThat(jdbc.queryForObject("select count(*) from trip_correction_reading where reading_id=?",Integer.class,reading)).isEqualTo(1);
    }
    @Test void databaseGuardRejectsUnscopedDeletionAndExpiryOrFinalReferenceEdits() {
        archived(); UUID reading=jdbc.queryForObject("select reading_id from trip_retained_evidence where trip_id=? limit 1",UUID.class,f.trip());
        assertThatThrownBy(() -> jdbc.update("delete from telemetry_reading where id=?",reading)).hasMessageContaining("guarded retention required");
        pruner(true,false).prunePage(f.vehicle,100,null);
        assertThatThrownBy(() -> jdbc.update("delete from telemetry_raw_expiry where reading_id=?",reading)).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("update trip_work_item set status='REVIEW' where reading_id=?",reading)).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("delete from trip_reading_link where reading_id=?",reading)).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("delete from trip_retained_evidence where reading_id=?",reading)).hasMessageContaining("immutable");
    }
    @Test void exactMeasurementAndReceiptCutoffsStayProtected() {
        archived(); f.clock=Clock.fixed(Instant.parse("2026-11-19T00:00:00Z"),ZoneOffset.UTC); // UTC cutoff Oct20, readings are later that day
        assertThat(pruner(true,false).prunePage(f.vehicle,100,null).deleted()).isZero();
        f.clock=Clock.fixed(RetentionPreviewTests.BASE.plus(Duration.ofDays(5)),ZoneOffset.UTC); // receipt exactly 96h old
        assertThat(pruner(true,false).prunePage(f.vehicle,100,null).deleted()).isZero();
        assertThat(count("telemetry_reading")).isEqualTo(31);
    }
    @Test void lateAcceptedIdentityAfterPruningDoesNotRewriteFinalSummaryOrDisappear() {
        archived(); UUID trip=f.trip(); var before=jdbc.queryForMap("select * from trip_record where id=?",trip);
        pruner(true,false).prunePage(f.vehicle,100,null);
        f.store(f.sample(BigInteger.valueOf(99999),RetentionPreviewTests.BASE.plusSeconds(10)).put("rpm",2500));
        for(int i=0;i<5;i++) projector.processPage(f.vehicle,RetentionPreviewTests.AS_OF,100);
        assertThat(jdbc.queryForMap("select * from trip_record where id=?",trip)).isEqualTo(before);
        assertThat(count("telemetry_reading")).isEqualTo(5);
        assertThat(pruner(true,false).prunePage(f.vehicle,100,null).deleted()).isZero();
        assertThat(history.history(f.publicVehicle,query(null,"raw",100,null)).body().availability().rawState()).isEqualTo("PARTIALLY_EXPIRED");
    }
    @Test void exactReceiptQuarantineEqualityProtectsEvenOldVerifiedMeasurements() {
        f.receipt=RetentionPreviewTests.AS_OF.minus(Duration.ofHours(96)); archived();
        var dry=pruner(true,true).prunePage(f.vehicle,100,null);
        assertThat(dry.eligible()).isZero(); assertThat(pruner(true,false).prunePage(f.vehicle,100,null).deleted()).isZero();
        assertThat(count("telemetry_reading")).isEqualTo(31);
        f.clock=Clock.fixed(RetentionPreviewTests.AS_OF.plusNanos(1000),ZoneOffset.UTC);
        assertThat(pruner(true,false).prunePage(f.vehicle,100,null).deleted()).isEqualTo(27);
    }
    @Test void transactionFlagAloneCannotBypassDatabaseArchiveAndPolicyProtections() {
        f.drive(true); UUID reading=jdbc.queryForObject("select l.reading_id from trip_reading_link l join trip_record t on t.id=l.trip_id where t.vehicle_id=? limit 1",UUID.class,f.vehicle);
        String before=f.fingerprint();
        assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.queryForObject("select set_config('autopulse.retention_write','on',true)",String.class);
            jdbc.queryForObject("select set_config('autopulse.retention_as_of',?,true)",String.class,RetentionPreviewTests.AS_OF.toString());
            jdbc.queryForObject("select set_config('autopulse.retention_cutoff','2026-11-01T00:00:00Z',true)",String.class);
            jdbc.queryForObject("select set_config('autopulse.retention_receipt_before','2026-11-27T00:00:00Z',true)",String.class);
            jdbc.update("delete from telemetry_reading where id=?",reading);
        })).hasMessageContaining("retention protection failed");
        assertThat(f.fingerprint()).isEqualTo(before); assertThat(count("telemetry_raw_expiry")).isZero();
    }
    @Test void multipleTripsAndBucketsKeepWeightedAndAllNullMetricsAfterPruning() {
        f.drive(true);
        for(var input:mapper.readTree(java.nio.file.Path.of("../contracts/examples/trip-short-input.json").toFile()).get("readings")) {
            var node=((ObjectNode)input).deepCopy();
            node.put("deviceId",f.publicDevice).put("vehicleId",f.publicVehicle)
                .put("sequenceNumber",node.get("sequenceNumber").bigIntegerValue().add(BigInteger.valueOf(1000)))
                .put("recordedAt",Instant.parse(node.get("recordedAt").asText()).plusSeconds(120).toString());
            f.store(node);
        }
        for(int i=0;i<20;i++) if(projector.processPage(f.vehicle,RetentionPreviewTests.AS_OF,100).state().equals("CAUGHT_UP")) break;
        for(int i=0;i<10;i++) if(finalizer.finalizePage(f.vehicle,RetentionPreviewTests.AS_OF,100).finalized()>0) break;
        for(UUID trip:jdbc.queryForList("select id from trip_record where vehicle_id=? and status='COMPLETED'",UUID.class,f.vehicle)) f.archive(trip);
        var one=points(null,"1m"); var five=points(null,"5m");
        assertThat(one.size()).isGreaterThan(1);
        assertThat(pruner(true,false).prunePage(f.vehicle,100,null).deleted()).isEqualTo(54);
        assertThat(points(null,"1m")).isEqualTo(one); assertThat(points(null,"5m")).isEqualTo(five);
        assertThat(count("telemetry_reading")).isEqualTo(8);
    }
    @Test void ingestionWaitsAtReceiptBarrierThenRemainsVisibleAfterPruneCommit() throws Exception {
        archived(); var writer=new java.util.concurrent.atomic.AtomicReference<Future<?>>();
        var pid=new java.util.concurrent.atomic.AtomicInteger(); var started=new CountDownLatch(1);
        var observed=new java.util.concurrent.atomic.AtomicBoolean();
        try(var executor=Executors.newSingleThreadExecutor()) {
            var tracing=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
                @Override public int update(String sql,Object... args) {
                    if(sql.startsWith("delete from telemetry_reading") && observed.compareAndSet(false,true)) {
                        writer.set(executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> {
                            pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class)); started.countDown();
                            f.store(f.sample(BigInteger.valueOf(88888),RetentionPreviewTests.BASE.plusSeconds(10)).put("rpm",1800));
                        })));
                        try {
                            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue(); boolean blocked=false;
                            for(int i=0;i<200;i++) {
                                if(Boolean.TRUE.equals(jdbc.queryForObject("select cardinality(pg_blocking_pids(?))>0",Boolean.class,pid.get()))) { blocked=true; break; }
                                Thread.sleep(10);
                            }
                            assertThat(blocked).as("ingestion waits at receipt barrier during pruning").isTrue();
                        } catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                    }
                    return super.update(sql,args);
                }
            };
            var p=new RetentionPreview(tracing,transactions,new RetentionPreview.Options(true,false,30,96),f.clock);
            assertThat(p.prunePage(f.vehicle,100,null).deleted()).isEqualTo(27);
            writer.get().get(10,TimeUnit.SECONDS); assertThat(observed).isTrue();
            assertThat(count("telemetry_reading")).isEqualTo(5); assertThat(count("telemetry_replay_identity")).isEqualTo(32);
            assertThat(pruner(true,false).prunePage(f.vehicle,100,null).deleted()).isZero();
        }
    }
}
