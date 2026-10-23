package dev.autopulse.database;

import com.zaxxer.hikari.HikariDataSource;
import dev.autopulse.telemetry.TelemetryRepository;
import dev.autopulse.telemetry.TelemetryValidator;
import dev.autopulse.telemetry.ValidatedReading;
import dev.autopulse.trip.TripBackfill;
import dev.autopulse.trip.TripProjector;
import dev.autopulse.trip.TripFinalizer;
import dev.autopulse.trip.TripCorrectionPlanner;
import dev.autopulse.trip.TripCorrectionReplayer;
import dev.autopulse.trip.TripCorrectionPublisher;
import dev.autopulse.trip.TripProcessingWorker;
import dev.autopulse.trip.TripProcessingCoordinator;
import dev.autopulse.trip.TripReferenceAudit;
import static dev.autopulse.trip.TripProcessingWorker.Stage.*;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static dev.autopulse.database.LocalFixtures.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class TripWorkMigrationTests {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");
    static final JsonMapper JSON = JsonMapper.builder().build();
    HikariDataSource data;
    JdbcTemplate jdbc;
    DataSourceTransactionManager transactions;
    String schema;

    @BeforeEach void isolatedSchema() {
        // Every schema lives only inside this disposable Testcontainer.
        schema = "trip_test_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        new JdbcTemplate(admin).execute("create schema " + schema);
        String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema;
        // One-row restart tests make many transactions. Reuse a few real
        // connections instead of exhausting Windows ephemeral sockets.
        data = new HikariDataSource();
        data.setJdbcUrl(url); data.setUsername(POSTGRES.getUsername()); data.setPassword(POSTGRES.getPassword());
        data.setMaximumPoolSize(4); data.setMinimumIdle(0); data.setConnectionTimeout(5000);
        jdbc = new JdbcTemplate(data);
        transactions = new DataSourceTransactionManager(data);
    }

    @AfterEach void closePool() { if (data!=null) data.close(); }

    Flyway current() { return Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration").load(); }
    void migrate() { MigrationSupport.migrate(current()); }
    void local() { new LocalFixtures(jdbc, transactions).seed(); }
    void legacy() {
        Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration", "classpath:db/local")
                .target("900").load().migrate();
    }
    int count(String table) { return jdbc.queryForObject("select count(*) from " + table, Integer.class); }
    void assertRawReferenceTransition() {
        assertThat(jdbc.queryForList("select conrelid::regclass::text from pg_constraint where connamespace=current_schema()::regnamespace and contype='f' and confrelid='telemetry_reading'::regclass",String.class)).containsExactly("trip_correction_reading");
        assertThat(jdbc.queryForObject("select count(*) from pg_trigger where tgrelid='telemetry_reading'::regclass and tgname='guarded_final_raw_deletion' and tgenabled='O'",Integer.class)).isEqualTo(1);
    }
    @Test void guardedRetentionUpgradePreservesUncoveredLegacyRowsWithoutValidationOrPruning() {
        at913(); UUID reading=oldRow(1); var graph=correctionOwnerGraph(VEHICLE,reading);
        auditReferences(reading,linkOwnerTrip(VEHICLE,DEVICE),graph);
        Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration").target("930").load().migrate();
        var before=correctionOwnerMaps(); var checksums=jdbc.queryForList("select version,checksum from flyway_schema_history order by installed_rank");
        migrate(); migrate(); assertThat(correctionOwnerMaps()).isEqualTo(before);
        assertThat(count("telemetry_raw_expiry")).isZero(); assertThat(count("telemetry_replay_identity")).isZero();
        assertRawReferenceTransition(); assertThat(validatedReferences()).isZero(); assertThat(workVehicleValidated()).isFalse();
        assertThat(jdbc.queryForList("select version,checksum from flyway_schema_history where version::int<=930 order by installed_rank")).isEqualTo(checksums);
        assertThatThrownBy(() -> jdbc.update("delete from telemetry_reading where id=?",reading)).hasMessageContaining("guarded retention required");
        assertThat(correctionOwnerMaps()).isEqualTo(before);
    }
    TripBackfill backfill() { return new TripBackfill(jdbc, transactions); }
    ValidatedReading sample(int sequence, Instant recordedAt) {
        var node = (ObjectNode) JSON.readTree(Path.of("../contracts/examples/telemetry-sample.json").toFile());
        node.put("sequenceNumber", sequence).put("recordedAt", recordedAt.toString());
        return new TelemetryValidator(JSON).validate(node);
    }
    String store(ValidatedReading sample) {
        return new TelemetryRepository(new NamedParameterJdbcTemplate(jdbc), JSON).store(VEHICLE, DEVICE, sample);
    }
    UUID oldRow(int sequence) {
        var reading = sample(sequence, Instant.parse("2026-10-20T12:00:00Z").plusSeconds(sequence));
        UUID id = UUID.randomUUID();
        insert(id, reading);
        return id;
    }
    void insert(UUID id, ValidatedReading reading) {
        jdbc.update("""
                insert into telemetry_reading (id,vehicle_id,device_id,boot_id,sequence_number,recorded_at,source,payload)
                values (?,?,?,?,?,?,?,cast(? as jsonb))
                """, id, VEHICLE, DEVICE, reading.bootId(), new java.math.BigDecimal(reading.sequenceNumber()),
                Timestamp.from(reading.recordedAt()), "SIMULATED", reading.payload().toString());
    }

    dev.autopulse.telemetry.ReplayIdentityBackfill replayBackfill() {
        return new dev.autopulse.telemetry.ReplayIdentityBackfill(jdbc,transactions);
    }
    void at913() {
        Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration")
                .target("913").load().migrate();
        local();
    }
    dev.autopulse.telemetry.ReplayIdentityAudit replayAudit() {
        return new dev.autopulse.telemetry.ReplayIdentityAudit(jdbc,transactions);
    }
    static final java.util.Map<String,String> DURABLE_REFERENCES=java.util.Map.of(
            "trip_work_item","trip_work_durable_reading",
            "trip_reading_link","trip_link_durable_reading",
            "trip_correction_reading","trip_correction_durable_reading");
    void validateDisposableReferences() {
        // Test-only, whole-table PostgreSQL validation. Never an application job
        // or a claim of bounded/resumable production DDL.
        var tx=new TransactionTemplate(transactions); tx.setTimeout(10);
        tx.executeWithoutResult(status -> DURABLE_REFERENCES.forEach((table,constraint) ->
                jdbc.execute("alter table "+table+" validate constraint "+constraint)));
    }
    int validatedReferences() {
        return jdbc.queryForObject("select count(*) from pg_constraint where connamespace=current_schema()::regnamespace and conname in ('trip_work_durable_reading','trip_link_durable_reading','trip_correction_durable_reading') and confrelid='telemetry_replay_identity'::regclass and convalidated",Integer.class);
    }
    java.util.Map<String,Object> referenceSources() {
        var result=new java.util.LinkedHashMap<String,Object>();
        for (String table : java.util.List.of("telemetry_reading","trip_work_item","trip_record","trip_reading_link","trip_correction_reading"))
            result.put(table,jdbc.queryForList("select * from "+table+" order by 1,2"));
        return result;
    }
    void finishReplayOneRowAtATime() {
        for (int i=0;i<100;i++) {
            var page=replayBackfill().copyPage(VEHICLE,1);
            assertThat(page.visited()).isBetween(0,1);
            if (page.complete()) return;
        }
        throw new AssertionError("Replay fixture exceeded 100 one-row pages");
    }
    void pendingBeforeOrdering() {
        Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration").target("909").load().migrate();
        local(); oldRow(1); oldRow(2);
        jdbc.update("update trip_work_item set attempts=3,last_error='preserve retry',next_attempt_at=next_attempt_at+interval '2 hours'");
    }
    static final String WORK_ACKS="select reading_id,vehicle_id,status,accepted_at,next_attempt_at,attempts,last_error,acceptance_order from trip_work_item order by reading_id";
    @Test void readyLedgerWorkDiscoveryIsBoundedRestartableAndPreservesOriginalReceipts() {
        legacy(); for (int i=1;i<=7;i++) oldRow(i); migrate(); finishReplayOneRowAtATime();
        var raw=jdbc.queryForList("select * from telemetry_reading order by id");
        var queries=new java.util.ArrayList<String>(); int queued=0,visited=0; boolean done=false;
        for (int i=0;i<10;i++) {
            var page=new TripBackfill(observedCorrectionJdbc(queries),transactions).enqueuePage(VEHICLE,2);
            assertThat(page.visited()).isBetween(0,2); queued+=page.queued(); visited+=page.visited();
            if (page.complete()) { done=true; break; }
        }
        assertThat(done).isTrue(); assertThat(queued).isEqualTo(7); assertThat(visited).isEqualTo(7);
        assertThat(queries).anySatisfy(sql -> assertThat(sql).contains("select id from trip_ledger_reading"));
        assertThat(queries).allSatisfy(sql -> assertThat(sql).doesNotContain("from telemetry_reading"));
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item w join telemetry_replay_identity e on e.reading_id=w.reading_id where w.accepted_at=e.received_at and w.next_attempt_at=greatest(e.received_at,e.recorded_at-interval '2 seconds') and (w.sort_at,w.sort_device,w.sort_boot,w.sort_sequence)=(e.recorded_at,e.device_id,e.boot_id,e.sequence_number)",Integer.class)).isEqualTo(7);
        var work=jdbc.queryForList(WORK_ACKS); assertThat(backfill().enqueuePage(VEHICLE,2).visited()).isZero();
        assertThat(jdbc.queryForList(WORK_ACKS)).isEqualTo(work); assertThat(jdbc.queryForList("select * from telemetry_reading order by id")).isEqualTo(raw);
        assertThatThrownBy(() -> jdbc.update("delete from telemetry_reading where id=(select reading_id from trip_work_item limit 1)"))
                .hasMessageContaining("foreign key constraint");
    }
    @Test void workDiscoveryKeepsRawUntilIdentityCompletionThenResumesSameUuidBoundary() {
        legacy(); for (int i=1;i<=5;i++) oldRow(i); migrate();
        var rawQueries=new java.util.ArrayList<String>();
        assertThat(new TripBackfill(observedCorrectionJdbc(rawQueries),transactions).enqueuePage(VEHICLE,2).queued()).isEqualTo(2);
        assertThat(rawQueries).anySatisfy(sql -> assertThat(sql).contains("select id from telemetry_reading"));
        assertThat(rawQueries).allSatisfy(sql -> assertThat(sql).doesNotContain("from trip_ledger_reading"));
        var progress=jdbc.queryForMap("select * from trip_backfill_progress where vehicle_id=?",VEHICLE);
        var work=jdbc.queryForList(WORK_ACKS); finishReplayOneRowAtATime();
        assertThat(jdbc.queryForMap("select * from trip_backfill_progress where vehicle_id=?",VEHICLE)).isEqualTo(progress);
        assertThat(jdbc.queryForList(WORK_ACKS)).isEqualTo(work);
        var ledgerQueries=new java.util.ArrayList<String>();
        assertThat(new TripBackfill(observedCorrectionJdbc(ledgerQueries),transactions).enqueuePage(VEHICLE,2).queued()).isEqualTo(2);
        assertThat(backfill().enqueuePage(VEHICLE,2)).isEqualTo(new TripBackfill.Page(1,1,true,false));
        assertThat(ledgerQueries).anySatisfy(sql -> assertThat(sql).contains("select id from trip_ledger_reading"));
        assertThat(count("trip_work_item")).isEqualTo(5); assertThat(count("telemetry_reading")).isEqualTo(5);
        assertThat(jdbc.queryForObject("select visited_rows from trip_backfill_progress where vehicle_id=?",Integer.class,VEHICLE)).isEqualTo(5);
    }
    @Test void workPreparationUpgradeDoesNotHydrateOrRewriteExistingPendingWork() {
        pendingBeforeOrdering();
        Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration").target("924").load().migrate();
        finishReplayOneRowAtATime(); var before=referenceSources(); var acks=jdbc.queryForList(WORK_ACKS);
        var ledger=jdbc.queryForList("select * from telemetry_replay_identity order by reading_id");
        migrate(); migrate(); assertThat(referenceSources()).isEqualTo(before);
        assertThat(jdbc.queryForList("select * from telemetry_replay_identity order by reading_id")).isEqualTo(ledger);
        assertThat(backfill().enqueuePage(VEHICLE,1).visited()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item where sort_at is null",Integer.class)).isEqualTo(1);
        assertThat(backfill().enqueuePage(VEHICLE,1).visited()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item w join telemetry_replay_identity e on e.reading_id=w.reading_id where (w.sort_at,w.sort_device,w.sort_boot,w.sort_sequence)=(e.recorded_at,e.device_id,e.boot_id,e.sequence_number)",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList(WORK_ACKS)).isEqualTo(acks);
    }
    @Test void readyQueueHydrationUsesOriginalLedgerNativeOrderingNotPrivilegedRawEdit() {
        pendingBeforeOrdering(); migrate(); finishReplayOneRowAtATime();
        var acks=jdbc.queryForList(WORK_ACKS);
        // Disposable unprojected corruption: ordering comes from the accepted ledger.
        jdbc.update("update telemetry_reading set recorded_at=recorded_at+interval '1 microsecond',sequence_number=sequence_number+1234567890123456789012345678901234567890");
        var raw=jdbc.queryForList("select * from telemetry_reading order by id");
        assertThat(backfill().enqueuePage(VEHICLE,2).visited()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item w join telemetry_replay_identity e on e.reading_id=w.reading_id where (w.sort_at,w.sort_device,w.sort_boot,w.sort_sequence)=(e.recorded_at,e.device_id,e.boot_id,e.sequence_number)",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList(WORK_ACKS)).isEqualTo(acks); assertThat(jdbc.queryForList("select * from telemetry_reading order by id")).isEqualTo(raw);
    }
    @Test void ledgerDiscoveryStillRejectsChangedOriginalRawAndRollsBackCursor() {
        legacy(); oldRow(1); migrate(); finishReplayOneRowAtATime();
        jdbc.update("update telemetry_reading set received_at=received_at+interval '1 microsecond'");
        var progress=jdbc.queryForMap("select * from trip_backfill_progress where vehicle_id=?",VEHICLE);
        assertThatThrownBy(() -> backfill().enqueuePage(VEHICLE,1)).hasMessageContaining("does not match original reading");
        assertThat(count("trip_work_item")).isZero();
        assertThat(jdbc.queryForMap("select * from trip_backfill_progress where vehicle_id=?",VEHICLE)).isEqualTo(progress);
    }
    @Test void falseIdentityCompletionCannotSilentlyHydrateMissingLedgerTargets() {
        pendingBeforeOrdering(); migrate(); assertThat(count("telemetry_replay_identity")).isZero();
        var acks=jdbc.queryForList(WORK_ACKS); var progress=jdbc.queryForMap("select * from trip_backfill_progress where vehicle_id=?",VEHICLE);
        jdbc.update("update telemetry_replay_backfill set complete=true where vehicle_id=?",VEHICLE); // Disposable corrupt checkpoint only.
        assertThatThrownBy(() -> backfill().enqueuePage(VEHICLE,2)).hasMessageContaining("canonical ledger reading");
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item where sort_at is null",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList(WORK_ACKS)).isEqualTo(acks);
        assertThat(jdbc.queryForMap("select * from trip_backfill_progress where vehicle_id=?",VEHICLE)).isEqualTo(progress);
    }
    @Test void replayAuditIsReadOnlyIsolatedAndValidatesItsBounds() {
        migrate(); local();
        assertThatThrownBy(() -> replayAudit().readPage(null,null,1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> replayAudit().readPage(VEHICLE,null,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> replayAudit().readPage(VEHICLE,null,501)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> replayAudit().readPage(UUID.randomUUID(),null,1)).hasMessageContaining("Unknown replay audit vehicle");
        var observed=new JdbcTemplate(data) {
            @Override public <T> T queryForObject(String sql,Class<T> type,Object... args) {
                if (sql.startsWith("select exists(select 1 from vehicle")) {
                    assertThat(jdbc.queryForObject("show transaction_read_only",String.class)).isEqualTo("on");
                    assertThat(jdbc.queryForObject("show transaction_isolation",String.class)).isEqualTo("repeatable read");
                }
                return super.queryForObject(sql,type,args);
            }
        };
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertThat(jdbc.queryForObject("show transaction_read_only",String.class)).isEqualTo("off");
            assertThat(new dev.autopulse.telemetry.ReplayIdentityAudit(observed,transactions).readPage(VEHICLE,null,1))
                    .isEqualTo(new dev.autopulse.telemetry.ReplayIdentityAudit.Page(0,0,0,0,null,true));
            assertThat(jdbc.queryForObject("show transaction_read_only",String.class)).isEqualTo("off");
        });
        assertThat(count("telemetry_replay_identity")).isZero(); assertThat(validatedReferences()).isZero();
    }
    @Test void replayAuditPartitionsMissingMatchedAndMismatchedRowsWithoutCopying() {
        at913(); oldRow(1); oldRow(2); oldRow(3); migrate();
        var ids=jdbc.queryForList("select id from telemetry_reading order by id",UUID.class);
        jdbc.update("insert into telemetry_replay_identity select device_id,boot_id,sequence_number,id,vehicle_id,recorded_at,received_at,source,payload from telemetry_reading where id=?",ids.get(0));
        jdbc.update("insert into telemetry_replay_identity select device_id,boot_id,sequence_number,id,vehicle_id,recorded_at,received_at,source,'{}'::jsonb from telemetry_reading where id=?",ids.get(1));
        var sources=referenceSources();
        var ledger=jdbc.queryForList("select * from telemetry_replay_identity order by reading_id");
        var progress=jdbc.queryForMap("select * from telemetry_replay_backfill where vehicle_id=?",VEHICLE);
        UUID after=null; int matched=0,missing=0,mismatched=0;
        for (int i=0;i<4;i++) {
            var page=replayAudit().readPage(VEHICLE,after,1);
            assertThat(page.visited()).isEqualTo(i<3?1:0);
            if (i<3) assertThat(page.lastReadingId()).isEqualTo(ids.get(i));
            assertThat(page.complete()).isEqualTo(i==3);
            matched+=page.matched(); missing+=page.missing(); mismatched+=page.mismatched(); after=page.lastReadingId();
        }
        assertThat(matched).isEqualTo(1); assertThat(missing).isEqualTo(1); assertThat(mismatched).isEqualTo(1);
        assertThat(referenceSources()).isEqualTo(sources);
        assertThat(jdbc.queryForList("select * from telemetry_replay_identity order by reading_id")).isEqualTo(ledger);
        assertThat(jdbc.queryForMap("select * from telemetry_replay_backfill where vehicle_id=?",VEHICLE)).isEqualTo(progress);
        assertThat(validatedReferences()).isZero();
    }
    @Test void legacyFinalReferencesValidateAfterBoundedBackfillWithoutEditingFinalEvidence() {
        at913(); commute("short"); Instant asOf=processingTime(); drain(asOf,100);
        int sealed=0; for (int i=0;i<20 && sealed==0;i++) sealed+=finalizer().finalizePage(VEHICLE,asOf,100).finalized();
        assertThat(sealed).isEqualTo(1); migrate();
        int raw=count("telemetry_reading"); var before=referenceSources();
        assertThat(replayAudit().readPage(VEHICLE,null,500).missing()).isEqualTo(raw);
        assertThatThrownBy(this::validateDisposableReferences).hasMessageContaining("foreign key constraint");
        assertThat(validatedReferences()).isZero();
        finishReplayOneRowAtATime();
        var audit=replayAudit().readPage(VEHICLE,null,500);
        assertThat(audit.matched()).isEqualTo(raw); assertThat(audit.missing()+audit.mismatched()).isZero();
        validateDisposableReferences(); assertThat(validatedReferences()).isEqualTo(3);
        validateDisposableReferences(); migrate(); assertThat(validatedReferences()).isEqualTo(3);
        assertThat(referenceSources()).isEqualTo(before);
        assertThatThrownBy(() -> jdbc.update("update trip_reading_link set trip_id=trip_id"))
                .hasMessageContaining("finalized trip evidence is immutable");
        assertThatThrownBy(() -> jdbc.update("delete from telemetry_reading where id=(select reading_id from trip_reading_link limit 1)"))
                .hasMessageContaining("foreign key constraint");
    }
    @Test void legacyCorrectionReferencesRequireCompleteCoverageAndSurviveValidation() {
        at913(); commute("short"); drain(processingTime(),100);
        override(999,10,1900,12); drain(processingTime(),100); planned(1); staged(1); migrate();
        var before=referenceSources(); int raw=count("telemetry_reading");
        DURABLE_REFERENCES.forEach((table,constraint) -> {
            assertThat(count(table)).isPositive();
            assertThatThrownBy(() -> jdbc.execute("alter table "+table+" validate constraint "+constraint))
                    .hasMessageContaining("foreign key constraint");
        });
        assertThat(replayBackfill().copyPage(VEHICLE,1).copied()).isEqualTo(1);
        var partial=replayAudit().readPage(VEHICLE,null,500);
        assertThat(partial.matched()).isEqualTo(1); assertThat(partial.missing()).isEqualTo(raw-1);
        assertThatThrownBy(this::validateDisposableReferences).hasMessageContaining("foreign key constraint");
        assertThat(validatedReferences()).isZero(); // Whole validation transaction rolls back.
        finishReplayOneRowAtATime(); validateDisposableReferences();
        assertThat(validatedReferences()).isEqualTo(3);
        assertThat(replayAudit().readPage(VEHICLE,null,500).matched()).isEqualTo(raw);
        assertThat(referenceSources()).isEqualTo(before);
    }
    @Test void validatedForeignKeysAndCompletedCopyDoNotHideLaterMetadataMismatch() {
        migrate(); local(); UUID id=oldRow(1); replayBackfill().copyPage(VEHICLE,500);
        var ledger=jdbc.queryForMap("select * from telemetry_replay_identity where reading_id=?",id);
        jdbc.update("update telemetry_reading set received_at=received_at+interval '1 microsecond' where id=?",id);
        assertThat(replayBackfill().copyPage(VEHICLE,500).visited()).isZero();
        validateDisposableReferences(); assertThat(validatedReferences()).isEqualTo(3);
        var audit=replayAudit().readPage(VEHICLE,null,1);
        assertThat(audit.mismatched()).isEqualTo(1); assertThat(audit.matched()+audit.missing()).isZero();
        assertThat(jdbc.queryForMap("select * from telemetry_replay_identity where reading_id=?",id)).isEqualTo(ledger);
    }
    @Test void vehicleAuditDoesNotCertifyOtherVehiclesOrGlobalReferenceValidation() {
        at913(); oldRow(1);
        UUID other=UUID.randomUUID(),otherDevice=UUID.randomUUID();
        jdbc.update("insert into vehicle(id,public_id) values (?,?)",other,"audit-other");
        jdbc.update("insert into device(id,vehicle_id,public_id) values (?,?,?)",otherDevice,other,"audit-other-device");
        var payload=sample(1,Instant.parse("2026-10-20T12:00:00Z")).payload()
                .put("vehicleId","audit-other").put("deviceId","audit-other-device");
        var reading=new TelemetryValidator(JSON).validate(payload);
        jdbc.update("insert into telemetry_reading(id,vehicle_id,device_id,boot_id,sequence_number,recorded_at,source,payload) values (?,?,?,?,?,?,?,?::jsonb)",
                UUID.randomUUID(),other,otherDevice,reading.bootId(),new java.math.BigDecimal(reading.sequenceNumber()),Timestamp.from(reading.recordedAt()),"SIMULATED",reading.payload().toString());
        migrate(); finishReplayOneRowAtATime();
        assertThat(replayAudit().readPage(VEHICLE,null,500).matched()).isEqualTo(1);
        assertThat(replayAudit().readPage(other,null,500).missing()).isEqualTo(1);
        assertThatThrownBy(this::validateDisposableReferences).hasMessageContaining("foreign key constraint");
        assertThat(validatedReferences()).isZero();
        assertThat(replayBackfill().copyPage(other,500).complete()).isTrue(); validateDisposableReferences();
        assertThat(validatedReferences()).isEqualTo(3);
        assertThat(replayAudit().readPage(other,null,500).matched()).isEqualTo(1);
    }
    JdbcTemplate observedCorrectionJdbc(java.util.List<String> queries) {
        return new JdbcTemplate(data) {
            @Override public java.util.List<java.util.Map<String,Object>> queryForList(String sql,Object... args) {
                queries.add(sql); return jdbc.queryForList(sql,args);
            }
            @Override public java.util.Map<String,Object> queryForMap(String sql,Object... args) {
                queries.add(sql); return jdbc.queryForMap(sql,args);
            }
            @Override public <T> T queryForObject(String sql,Class<T> type,Object... args) {
                queries.add(sql); return jdbc.queryForObject(sql,type,args);
            }
            @Override public <T> java.util.List<T> query(String sql,org.springframework.jdbc.core.RowMapper<T> mapper,Object... args) {
                queries.add(sql); return jdbc.query(sql,mapper,args);
            }
        };
    }
    void finishCorrectionWith(JdbcTemplate reads,int limit) {
        boolean ready=false,staged=false,done=false;
        for (int i=0;i<1000;i++) {
            var page=new TripCorrectionPlanner(reads,transactions).planPage(VEHICLE,processingTime(),limit);
            assertThat(page.visited()).isBetween(0,limit);
            if (page.state().equals("READY")) { ready=true; break; }
            assertThat(page.state()).isIn("TRIPS","INPUTS");
        }
        assertThat(ready).isTrue();
        for (int i=0;i<1000;i++) {
            var page=new TripCorrectionReplayer(reads,JSON,transactions).replayPage(VEHICLE,limit);
            assertThat(page.visited()).isBetween(0,limit);
            if (page.state().equals("STAGED")) { staged=true; break; }
            assertThat(page.state()).isEqualTo("RUNNING");
        }
        assertThat(staged).isTrue();
        for (int i=0;i<1000;i++) {
            var page=new TripCorrectionPublisher(reads,JSON,transactions).publishPage(VEHICLE,limit);
            assertThat(page.visited()).isBetween(0,limit);
            if (page.state().equals("DONE")) { done=true; break; }
        }
        assertThat(done).isTrue();
    }
    void assertCorrectedShortSummary() {
        var summary=JSON.readTree(jdbc.queryForObject("select summary::text from trip_record where status='COMPLETED'",String.class));
        assertThat(summary.get("sampleCount").intValue()).isEqualTo(27);
        assertThat(summary.get("metrics").get("rpm").get("sum").decimalValue()).isEqualByComparingTo("47237.5");
        assertThat(summary.get("speedAreaKphSeconds").decimalValue()).isEqualByComparingTo("1784");
    }
    @Test void completedIdentityBackfillRoutesWholeCorrectionCycleToLedgerReads() {
        migrate(); local(); commute("short"); drain(processingTime(),100); finishReplayOneRowAtATime();
        UUID original=jdbc.queryForObject("select id from trip_record where status='COMPLETED'",UUID.class);
        override(999,10,1900,12); drain(processingTime(),100);
        var raw=jdbc.queryForList("select * from telemetry_reading order by id");
        var queries=new java.util.ArrayList<String>(); finishCorrectionWith(observedCorrectionJdbc(queries),1);
        assertThat(queries).anySatisfy(sql -> assertThat(sql).contains("from trip_ledger_reading"));
        assertThat(queries).allSatisfy(sql -> assertThat(sql).doesNotContain("telemetry_reading"));
        assertCorrectedShortSummary();
        assertThat(jdbc.queryForObject("select id from trip_record where status='COMPLETED'",UUID.class)).isEqualTo(original);
        assertThat(jdbc.queryForList("select * from telemetry_reading order by id")).isEqualTo(raw);
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item where status<>'PROCESSED'",Integer.class)).isZero();
    }
    @Test void incompleteLegacyIdentityBackfillKeepsCorrectionReadsOnRaw() {
        at913(); commute("short"); drain(processingTime(),100); override(999,10,1900,12); drain(processingTime(),100);
        migrate(); assertThat(count("telemetry_replay_identity")).isZero();
        var queries=new java.util.ArrayList<String>(); finishCorrectionWith(observedCorrectionJdbc(queries),1);
        assertThat(queries).anySatisfy(sql -> assertThat(sql).contains("telemetry_reading"));
        assertThat(queries).allSatisfy(sql -> assertThat(sql).doesNotContain("from trip_ledger_reading","join trip_ledger_reading"));
        assertThat(jdbc.queryForObject("select complete from telemetry_replay_backfill where vehicle_id=?",Boolean.class,VEHICLE)).isFalse();
        assertCorrectedShortSummary();
    }
    @Test void identitySourceHandoffBetweenReplayPagesPreservesEvidenceAndGeneration() {
        correctedShortPlan(); var first=replayer().replayPage(VEHICLE,10);
        assertThat(first.state()).isEqualTo("RUNNING");
        var before=jdbc.queryForList("select * from trip_correction_evidence order by replay_id,reading_id");
        assertThat(before).isNotEmpty();
        var replay=jdbc.queryForMap("select * from trip_correction_replay where id=?",first.replayId());
        var generation=jdbc.queryForMap("select * from vehicle_read_generation where vehicle_id=?",VEHICLE);
        finishReplayOneRowAtATime();
        assertThat(jdbc.queryForList("select * from trip_correction_evidence order by replay_id,reading_id")).isEqualTo(before);
        assertThat(jdbc.queryForMap("select * from trip_correction_replay where id=?",first.replayId())).isEqualTo(replay);
        assertThat(jdbc.queryForMap("select * from vehicle_read_generation where vehicle_id=?",VEHICLE)).isEqualTo(generation);
        var queries=new java.util.ArrayList<String>(); finishCorrectionWith(observedCorrectionJdbc(queries),1);
        assertThat(queries).allSatisfy(sql -> assertThat(sql).doesNotContain("telemetry_reading"));
        assertCorrectedShortSummary();
    }
    @Test void canonicalCorrectionUpgradePreservesAlreadyStagedReadyInputs() {
        Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration").target("922").load().migrate();
        local(); commute("short"); drain(processingTime(),100); override(999,10,1900,12); drain(processingTime(),100);
        planned(1); finishReplayOneRowAtATime(); staged(1);
        var inputs=jdbc.queryForList("select * from trip_correction_evidence order by replay_id,reading_id");
        var before=referenceSources(); var progress=jdbc.queryForMap("select * from telemetry_replay_backfill where vehicle_id=?",VEHICLE);
        int ledger=count("telemetry_replay_identity"); migrate(); migrate();
        assertThat(jdbc.queryForList("select * from trip_correction_evidence order by replay_id,reading_id")).isEqualTo(inputs);
        assertThat(referenceSources()).isEqualTo(before); assertThat(count("telemetry_replay_identity")).isEqualTo(ledger);
        assertThat(jdbc.queryForMap("select * from telemetry_replay_backfill where vehicle_id=?",VEHICLE)).isEqualTo(progress);
        assertRawReferenceTransition();
        var queries=new java.util.ArrayList<String>(); finishCorrectionWith(observedCorrectionJdbc(queries),1);
        assertThat(queries).allSatisfy(sql -> assertThat(sql).doesNotContain("telemetry_reading"));
        assertCorrectedShortSummary();
    }
    @Test void canonicalCorrectionStillRefusesExternallyFinalizedOverlap() {
        correctedShortPlan(); finishReplayOneRowAtATime(); var first=replayer().replayPage(VEHICLE,3);
        // Same deliberate disposable external finalization fixture as the legacy
        // fence regression. Normal finalization is already paused by this plan.
        jdbc.update("update trip_record set finalization='FINAL' where status='COMPLETED'");
        var before=referenceSources();
        assertThat(replayer().replayPage(VEHICLE,3).state()).isEqualTo("REVIEW_FINALIZED");
        assertThat(jdbc.queryForObject("select status from trip_correction_replay where id=?",String.class,first.replayId())).isEqualTo("INVALIDATED");
        assertThat(referenceSources()).isEqualTo(before);
    }
    @Test void canonicalCorrectionRangePageUsesLedgerNativeOrderingIndex() {
        migrate(); local(); bulkRunningHistory(5000); jdbc.execute("analyze telemetry_replay_identity");
        var query=plan("""
                select distinct on (recorded_at) id,recorded_at,device_id,boot_id,sequence_number,source,payload::text
                from trip_ledger_reading where vehicle_id=? and device_id=? and recorded_at>=? and recorded_at<=?
                order by recorded_at,boot_id desc,sequence_number desc limit ?
                """,VEHICLE,DEVICE,Timestamp.from(Instant.parse("2026-10-20T00:00:00Z")),Timestamp.from(processingTime()),10);
        assertThat(query.toString()).contains("replay_correction_order");
        assertThat(query.get("Actual Rows").longValue()).isEqualTo(10);
        assertThat(visited(query,"telemetry_replay_identity")).isLessThanOrEqualTo(11);
        assertThat(visited(query,"telemetry_reading")).isZero();
    }
    void finishWorkBackfill() {
        for (int i=0;i<100;i++) if (backfill().enqueuePage(VEHICLE,100).complete()) return;
        throw new AssertionError("work fixture exceeded 100 pages");
    }
    void drainWith(JdbcTemplate reads,Instant asOf,int limit) {
        for (int i=0;i<1000;i++) {
            var page=new TripProjector(reads,JSON,transactions,new TripBackfill(reads,transactions)).processPage(VEHICLE,asOf,limit);
            assertThat(page.visited()).isBetween(0,limit);
            if (page.state().equals("CAUGHT_UP")) return;
            assertThat(page.state()).isIn("MORE","BACKFILLING");
        }
        throw new AssertionError("forward fixture exceeded 1000 pages");
    }
    TripFinalizer.Page finishActivityWith(JdbcTemplate reads,Instant asOf,int limit) {
        for (int i=0;i<1000;i++) {
            var page=new TripFinalizer(reads,transactions).finalizePage(VEHICLE,asOf,limit);
            assertThat(page.scanned()).isBetween(0,limit); assertThat(page.finalized()).isBetween(0,limit);
            if (!page.state().equals("BACKFILLING_ACTIVITY")) return page;
        }
        throw new AssertionError("activity fixture exceeded 1000 pages");
    }
    @Test void readyForwardPagesReadLedgerAndPreserveHandCalculatedTrip() {
        migrate(); local(); commute("short"); finishWorkBackfill(); finishReplayOneRowAtATime();
        var raw=jdbc.queryForList("select * from telemetry_reading order by id");
        var queries=new java.util.ArrayList<String>(); drainWith(observedCorrectionJdbc(queries),processingTime(),1);
        assertThat(queries).anySatisfy(sql -> assertThat(sql).contains("join trip_ledger_reading"));
        assertThat(queries).allSatisfy(sql -> assertThat(sql).doesNotContain("telemetry_reading"));
        assertThat(count("trip_record")).isEqualTo(1); assertThat(count("trip_evidence")).isEqualTo(27);
        var summary=JSON.readTree(jdbc.queryForObject("select summary::text from trip_record",String.class));
        assertThat(summary.get("sampleCount").intValue()).isEqualTo(27);
        assertThat(summary.get("metrics").get("rpm").get("sum").decimalValue()).isEqualByComparingTo("46275");
        assertThat(summary.get("speedAreaKphSeconds").decimalValue()).isEqualByComparingTo("1760");
        assertThat(jdbc.queryForList("select * from telemetry_reading order by id")).isEqualTo(raw);
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item where status<>'PROCESSED'",Integer.class)).isZero();
    }
    @Test void incompleteLegacyForwardPagesRetainRawFallback() {
        at913(); commute("short"); migrate();
        replayBackfill().copyPage(VEHICLE,1); finishWorkBackfill();
        assertThat(jdbc.queryForObject("select complete from telemetry_replay_backfill where vehicle_id=?",Boolean.class,VEHICLE)).isFalse();
        var queries=new java.util.ArrayList<String>(); drainWith(observedCorrectionJdbc(queries),processingTime(),1);
        assertThat(queries).anySatisfy(sql -> assertThat(sql).contains("join telemetry_reading"));
        assertThat(queries).allSatisfy(sql -> assertThat(sql).doesNotContain("from trip_ledger_reading","join trip_ledger_reading"));
        assertThat(count("trip_record")).isEqualTo(1); assertThat(count("trip_evidence")).isEqualTo(27);
    }
    @Test void forwardSourceHandoffKeepsCheckpointLinksAndGenerationBeforeResume() {
        migrate(); local(); commute("short"); finishWorkBackfill();
        assertThat(projector().processPage(VEHICLE,processingTime(),3).state()).isEqualTo("MORE");
        var before=referenceSources(); var progress=jdbc.queryForMap("select * from trip_projection_progress where vehicle_id=?",VEHICLE);
        var generation=jdbc.queryForMap("select * from vehicle_read_generation where vehicle_id=?",VEHICLE);
        finishReplayOneRowAtATime();
        assertThat(referenceSources()).isEqualTo(before);
        assertThat(jdbc.queryForMap("select * from trip_projection_progress where vehicle_id=?",VEHICLE)).isEqualTo(progress);
        assertThat(jdbc.queryForMap("select * from vehicle_read_generation where vehicle_id=?",VEHICLE)).isEqualTo(generation);
        var queries=new java.util.ArrayList<String>(); drainWith(observedCorrectionJdbc(queries),processingTime(),1);
        assertThat(queries).allSatisfy(sql -> assertThat(sql).doesNotContain("telemetry_reading"));
        assertThat(count("trip_record")).isEqualTo(1); assertThat(count("trip_evidence")).isEqualTo(27);
        var summary=JSON.readTree(jdbc.queryForObject("select summary::text from trip_record",String.class));
        assertThat(summary.get("speedAreaKphSeconds").decimalValue()).isEqualByComparingTo("1760");
    }
    @Test void ledgerForwardTieWinnerPreservesArbitrarySizeSequenceAcrossOneRowPages() {
        migrate(); local(); Instant base=processingTime();
        var huge=new java.math.BigInteger("1234567890123456789012345678901234567890");
        store(new TelemetryValidator(JSON).validate(sample(0,base).payload().put("rpm",0)));
        store(new TelemetryValidator(JSON).validate(sample(1,base).payload().put("sequenceNumber",huge)));
        store(sample(2,base.plusSeconds(2))); finishWorkBackfill(); finishReplayOneRowAtATime();
        var queries=new java.util.ArrayList<String>(); drainWith(observedCorrectionJdbc(queries),base.plusSeconds(2),1);
        assertThat(queries).allSatisfy(sql -> assertThat(sql).doesNotContain("telemetry_reading"));
        assertThat(count("telemetry_reading")).isEqualTo(3); assertThat(count("trip_evidence")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select anchor_sequence from trip_record",java.math.BigDecimal.class).toBigIntegerExact()).isEqualTo(huge);
        assertThat(jdbc.queryForObject("select status from trip_record",String.class)).isEqualTo("ACTIVE");
        var summary=JSON.readTree(jdbc.queryForObject("select summary::text from trip_record",String.class));
        assertThat(summary.get("sampleCount").intValue()).isEqualTo(2);
    }
    @Test void ledgerFinalizationKeepsExactQuietBoundaryAndImmutableSummary() {
        migrate(); local(); commute("short"); finishWorkBackfill(); finishReplayOneRowAtATime(); drain(processingTime(),100);
        Instant last=receipt(); var original=jdbc.queryForMap("select * from trip_record where status='COMPLETED'");
        var raw=jdbc.queryForList("select * from telemetry_reading order by id");
        var links=jdbc.queryForList("select * from trip_reading_link order by reading_id");
        var queries=new java.util.ArrayList<String>(); var reads=observedCorrectionJdbc(queries);
        assertThat(finishActivityWith(reads,last.plusSeconds(299),1).state()).isEqualTo("QUIET_PERIOD");
        assertThat(new TripFinalizer(reads,transactions).finalizePage(VEHICLE,last.plusSeconds(300),1).finalized()).isEqualTo(1);
        var sealed=jdbc.queryForMap("select * from trip_record where status='COMPLETED'");
        assertThat(sealed.get("finalization")).isEqualTo("FINAL");
        assertThat(((Number)sealed.get("revision")).longValue()).isEqualTo(((Number)original.get("revision")).longValue()+1);
        var comparable=new java.util.HashMap<>(sealed); comparable.put("finalization",original.get("finalization")); comparable.put("revision",original.get("revision"));
        assertThat(comparable).isEqualTo(original);
        assertThat(new TripFinalizer(reads,transactions).finalizePage(VEHICLE,last.plusSeconds(600),1).finalized()).isZero();
        assertThat(queries).allSatisfy(sql -> assertThat(sql).doesNotContain("telemetry_reading"));
        assertThat(jdbc.queryForList("select * from telemetry_reading order by id")).isEqualTo(raw);
        assertThat(jdbc.queryForList("select * from trip_reading_link order by reading_id")).isEqualTo(links);
        assertThatThrownBy(() -> jdbc.update("update trip_reading_link set trip_id=trip_id"))
                .hasMessageContaining("finalized trip evidence is immutable");
    }
    @Test void ledgerFinalizationStillProtectsPendingAndReviewWork() {
        migrate(); local(); commute("short"); finishWorkBackfill(); finishReplayOneRowAtATime(); drain(processingTime(),100);
        store(sample(999,Instant.parse("2026-10-20T12:00:10Z")));
        var original=jdbc.queryForMap("select * from trip_record where status='COMPLETED'");
        var queries=new java.util.ArrayList<String>(); var reads=observedCorrectionJdbc(queries);
        assertThat(finishActivityWith(reads,receipt().plusSeconds(300),1).state()).isEqualTo("PENDING_WORK");
        drain(processingTime(),100);
        assertThat(new TripFinalizer(reads,transactions).finalizePage(VEHICLE,receipt().plusSeconds(300),1).state()).isEqualTo("REVIEW_REQUIRED");
        assertThat(queries).allSatisfy(sql -> assertThat(sql).doesNotContain("telemetry_reading"));
        assertThat(jdbc.queryForMap("select * from trip_record where status='COMPLETED'")).isEqualTo(original);
    }
    @Test void ledgerCoordinatorDiscoversAndFinishesLateCorrectionWithoutRawJavaReads() {
        migrate(); local(); commute("short"); finishWorkBackfill(); finishReplayOneRowAtATime(); drain(processingTime(),100);
        override(999,10,1900,12); drain(processingTime(),100);
        var queries=new java.util.ArrayList<String>(); var reads=observedCorrectionJdbc(queries); Instant now=processingTime();
        var clock=java.time.Clock.fixed(now,java.time.ZoneOffset.UTC);
        var worker=new TripProcessingWorker(reads,transactions,
                new TripProjector(reads,JSON,transactions,new TripBackfill(reads,transactions)),
                new TripCorrectionPlanner(reads,transactions),new TripCorrectionReplayer(reads,JSON,transactions),
                new TripCorrectionPublisher(reads,JSON,transactions),new TripFinalizer(reads,transactions),clock);
        var coordinator=new TripProcessingCoordinator(reads,transactions,worker,clock);
        var stages=new java.util.HashSet<TripProcessingWorker.Stage>(); boolean idle=false;
        for (int i=0;i<1000;i++) {
            var page=coordinator.processVehicle(VEHICLE,now,1);
            assertThat(page.visited()).isBetween(0,1); assertThat(page.state()).isNotIn("PAUSED","RETRY_STAGE_REQUIRED","RETRY_WAIT");
            if ("NO_DUE_WORK".equals(page.result())) { idle=true; break; }
            stages.add(page.stage());
        }
        assertThat(idle).isTrue(); assertThat(stages).contains(PLAN,REPLAY,PUBLISH,FINALIZE);
        assertThat(queries).anySatisfy(sql -> assertThat(sql).contains("join trip_ledger_reading"));
        assertThat(queries).allSatisfy(sql -> assertThat(sql).doesNotContain("telemetry_reading"));
        assertCorrectedShortSummary();
    }
    @Test void ledgerForwardWorkPageDoesNotScanProcessedLedgerHistory() {
        migrate(); local(); bulkRunningHistory(5000);
        jdbc.execute("analyze telemetry_replay_identity"); jdbc.execute("analyze trip_work_item");
        var backlog=plan(TripProjector.LEDGER_WORK_PAGE_SQL,VEHICLE,Timestamp.from(processingTime()),10);
        jdbc.update("update trip_work_item w set status='PROCESSED' from telemetry_replay_identity r where r.reading_id=w.reading_id and r.sequence_number<4980");
        jdbc.execute("analyze trip_work_item");
        var tail=plan(TripProjector.LEDGER_WORK_PAGE_SQL,VEHICLE,Timestamp.from(processingTime()),10);
        for (var query : java.util.List.of(backlog,tail)) {
            assertThat(query.get("Actual Rows").longValue()).isEqualTo(10);
            assertThat(visited(query,"telemetry_replay_identity")).isLessThanOrEqualTo(100);
            assertThat(visited(query,"trip_work_item")).isLessThanOrEqualTo(100);
            assertThat(visited(query,"telemetry_reading")).isZero();
        }
    }
    @Test void retainedTripNativeAscendingPageUsesItsForwardOnlyIndex() {
        migrate(); local(); bulkRunningHistory(5000); jdbc.execute("analyze telemetry_replay_identity");
        var query=plan("""
                select id,payload::text,received_at from trip_ledger_reading
                where vehicle_id=? and device_id=? and recorded_at>=? and recorded_at<?
                order by recorded_at,device_id,boot_id,sequence_number limit ?
                """,VEHICLE,DEVICE,Timestamp.from(Instant.parse("2026-10-20T00:00:00Z")),Timestamp.from(processingTime()),11);
        assertThat(query.toString()).contains("replay_trip_history_order");
        assertThat(query.get("Actual Rows").longValue()).isEqualTo(11);
        assertThat(visited(query,"telemetry_replay_identity")).isLessThanOrEqualTo(11);
        assertThat(visited(query,"telemetry_reading")).isZero();
    }
    @Test void durableReferenceUpgradeDoesNotScanOrValidateLegacyRowsAutomatically() {
        at913(); UUID id=oldRow(1);
        migrate();
        assertThat(count("telemetry_replay_identity")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where connamespace=current_schema()::regnamespace and conname in ('trip_work_durable_reading','trip_link_durable_reading','trip_correction_durable_reading') and not convalidated",Integer.class)).isEqualTo(3);
        assertRawReferenceTransition();
        jdbc.update("update trip_work_item set status='PROCESSED' where reading_id=?",id);
        assertThat(count("telemetry_replay_identity")).isZero();
        migrate();
        assertThat(count("telemetry_reading")).isEqualTo(1);
        assertThat(count("telemetry_replay_identity")).isZero();
    }
    @Test void legacyWorkReferenceCopiesExactlyOneIdentityAndRollsBackAtomically() {
        at913(); UUID id=oldRow(1); oldRow(2);
        jdbc.update("delete from trip_work_item where reading_id=?",id); migrate();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.update("insert into trip_work_item(reading_id,vehicle_id,accepted_at,next_attempt_at) select id,vehicle_id,received_at,received_at from telemetry_reading where id=?",id);
            assertThat(count("telemetry_replay_identity")).isEqualTo(1);
            tx.setRollbackOnly();
        });
        assertThat(count("telemetry_replay_identity")).isZero();
        assertThat(count("trip_work_item")).isEqualTo(1);
        jdbc.update("insert into trip_work_item(reading_id,vehicle_id,accepted_at,next_attempt_at) select id,vehicle_id,received_at,received_at from telemetry_reading where id=?",id);
        assertThat(count("telemetry_replay_identity")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select l.payload=r.payload and l.device_id=r.device_id and l.boot_id=r.boot_id and l.sequence_number=r.sequence_number and l.vehicle_id=r.vehicle_id and l.recorded_at=r.recorded_at and l.received_at=r.received_at and l.source=r.source from telemetry_replay_identity l join telemetry_reading r on r.id=l.reading_id where r.id=?",Boolean.class,id)).isTrue();
        assertThat(jdbc.queryForObject("select complete from telemetry_replay_backfill where vehicle_id=?",Boolean.class,VEHICLE)).isFalse();
    }
    @Test void legacyLinkAndCorrectionWritesCaptureOnlyTheirExactTargets() {
        at913(); commute("short"); drain(processingTime(),100);
        override(999,10,1900,12); drain(processingTime(),100); planned(1); staged(1);
        var link=jdbc.queryForMap("select * from trip_reading_link limit 1");
        var correction=jdbc.queryForMap("select * from trip_correction_reading where reading_id<>? limit 1",link.get("reading_id"));
        migrate(); assertThat(count("telemetry_replay_identity")).isZero();
        jdbc.update("delete from trip_reading_link where reading_id=?",link.get("reading_id"));
        jdbc.update("insert into trip_reading_link(reading_id,trip_id) values (?,?)",link.get("reading_id"),link.get("trip_id"));
        assertThat(count("telemetry_replay_identity")).isEqualTo(1);
        jdbc.update("delete from trip_correction_reading where replay_id=? and reading_id=?",correction.get("replay_id"),correction.get("reading_id"));
        jdbc.update("insert into trip_correction_reading(replay_id,reading_id,segment_id) values (?,?,?)",correction.get("replay_id"),correction.get("reading_id"),correction.get("segment_id"));
        assertThat(count("telemetry_replay_identity")).isEqualTo(2);
        assertThat(jdbc.queryForList("select reading_id from telemetry_replay_identity",UUID.class))
                .containsExactlyInAnyOrder((UUID)link.get("reading_id"),(UUID)correction.get("reading_id"));
    }
    @Test void conflictingNativeIdentityCannotBeAttachedUnderAnotherReadingUuid() {
        at913(); UUID id=oldRow(1); jdbc.update("delete from trip_work_item"); migrate();
        UUID wrong=UUID.randomUUID();
        jdbc.update("insert into telemetry_replay_identity(device_id,boot_id,sequence_number,reading_id,vehicle_id,recorded_at,received_at,source,payload) select device_id,boot_id,sequence_number,?,vehicle_id,recorded_at,received_at,source,payload from telemetry_reading where id=?",wrong,id);
        assertThatThrownBy(() -> jdbc.update("insert into trip_work_item(reading_id,vehicle_id,accepted_at,next_attempt_at) select id,vehicle_id,received_at,received_at from telemetry_reading where id=?",id))
                .hasMessageContaining("trip replay reference does not match");
        assertThat(count("trip_work_item")).isZero();
        assertThat(jdbc.queryForList("select reading_id from telemetry_replay_identity",UUID.class)).containsExactly(wrong);
    }
    @Test void newReferenceRejectsChangedOriginalInsteadOfRepairingLedger() {
        migrate(); local(); UUID id=oldRow(1);
        var ledger=jdbc.queryForMap("select * from telemetry_replay_identity where reading_id=?",id);
        jdbc.update("delete from trip_work_item where reading_id=?",id);
        jdbc.update("update telemetry_reading set received_at=received_at+interval '1 second' where id=?",id);
        assertThatThrownBy(() -> jdbc.update("insert into trip_work_item(reading_id,vehicle_id,accepted_at,next_attempt_at) select id,vehicle_id,received_at,received_at from telemetry_reading where id=?",id))
                .hasMessageContaining("trip replay reference does not match");
        assertThat(count("trip_work_item")).isZero();
        assertThat(jdbc.queryForMap("select * from telemetry_replay_identity where reading_id=?",id)).isEqualTo(ledger);
    }
    @Test void normalProjectionAndCorrectionReferencesAllHaveDurableIdentityTargets() {
        correctedShortPlan(); staged(1);
        for (String table : java.util.List.of("trip_work_item","trip_reading_link","trip_correction_reading")) {
            assertThat(count(table)).isPositive();
            assertThat(jdbc.queryForObject("select count(*) from "+table+" x left join telemetry_replay_identity l on l.reading_id=x.reading_id where l.reading_id is null",Integer.class)).isZero();
        }
        assertThatThrownBy(() -> jdbc.update("delete from telemetry_reading where id=(select reading_id from trip_reading_link limit 1)"))
                .hasMessageContaining("foreign key constraint");
    }
    @Test void replayLedgerCapturesOnlyAcceptedCommitsWithOriginalMetadata() {
        migrate(); local();
        var reading=sample(1,Instant.parse("2026-10-20T12:00:00.123456Z"));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertThat(store(reading)).isEqualTo("accepted");
            assertThat(count("telemetry_replay_identity")).isEqualTo(1);
            tx.setRollbackOnly();
        });
        assertThat(count("telemetry_replay_identity")).isZero();
        assertThat(count("telemetry_reading")).isZero();
        assertThat(count("trip_work_item")).isZero();
        assertThat(store(reading)).isEqualTo("accepted");
        assertThat(jdbc.queryForObject("""
                select l.payload=r.payload and l.reading_id=r.id and l.vehicle_id=r.vehicle_id
                  and l.recorded_at=r.recorded_at and l.received_at=r.received_at and l.source=r.source
                from telemetry_replay_identity l join telemetry_reading r on r.id=l.reading_id
                """,Boolean.class)).isTrue();
    }
    @Test void replayLedgerPreservesJsonbNumericEqualityAndDoesNotChangeDuplicateConflictBehavior() {
        migrate(); local();
        var base=(ObjectNode)JSON.readTree(Path.of("../contracts/examples/telemetry-sample.json").toFile());
        base.put("sequenceNumber",new java.math.BigInteger("1234567890123456789012345678901234567890"));
        base.put("rpm",new java.math.BigDecimal("1.00"));
        var validator=new TelemetryValidator(JSON);
        var first=validator.validate(base);
        assertThat(store(first)).isEqualTo("accepted");
        var before=jdbc.queryForMap("select * from telemetry_replay_identity");
        base.put("rpm",new java.math.BigDecimal("1.0"));
        var equivalent=validator.validate(base);
        assertThat(store(equivalent)).isEqualTo("duplicate");
        assertThat(jdbc.queryForObject("select payload=?::jsonb from telemetry_replay_identity",Boolean.class,equivalent.payload().toString())).isTrue();
        base.put("rpm",new java.math.BigDecimal("1.000000000000000000000000000000000000001"));
        var different=validator.validate(base);
        assertThat(store(different)).isEqualTo("conflict");
        assertThat(jdbc.queryForObject("select payload=?::jsonb from telemetry_replay_identity",Boolean.class,different.payload().toString())).isFalse();
        base.putNull("rpm");
        assertThat(store(validator.validate(base))).isEqualTo("conflict");
        assertThat(jdbc.queryForMap("select * from telemetry_replay_identity")).isEqualTo(before);
        assertThat(count("trip_work_item")).isEqualTo(1);
    }
    @Test void replayLedgerRejectsEvenNoOpUpdatesAndDeletion() {
        migrate(); local(); oldRow(1);
        assertThatThrownBy(() -> jdbc.update("update telemetry_replay_identity set received_at=received_at"))
                .hasMessageContaining("accepted replay identity is immutable");
        assertThatThrownBy(() -> jdbc.update("delete from telemetry_replay_identity"))
                .hasMessageContaining("accepted replay identity is immutable");
        assertThat(count("telemetry_replay_identity")).isEqualTo(1);
    }
    @Test void replayLedgerUpgradeCopiesLegacyRowsInRestartableBoundedPagesOnly() {
        at913(); oldRow(1); oldRow(2); oldRow(3);
        var raw=jdbc.queryForList("select * from telemetry_reading order by id");
        var work=jdbc.queryForList("select * from trip_work_item order by reading_id");
        migrate(); assertThat(count("telemetry_replay_identity")).isZero();
        var page=replayBackfill().copyPage(VEHICLE,1);
        assertThat(page.visited()).isEqualTo(1); assertThat(page.copied()).isEqualTo(1); assertThat(page.complete()).isFalse();
        migrate(); // Process/service reconstruction resumes the persisted UUID cursor.
        assertThat(replayBackfill().copyPage(VEHICLE,1).copied()).isEqualTo(1);
        assertThat(replayBackfill().copyPage(VEHICLE,1).copied()).isEqualTo(1);
        assertThat(replayBackfill().copyPage(VEHICLE,1).complete()).isTrue();
        assertThat(replayBackfill().copyPage(VEHICLE,1).visited()).isZero();
        assertThat(count("telemetry_replay_identity")).isEqualTo(3);
        assertThat(jdbc.queryForList("select * from telemetry_reading order by id")).isEqualTo(raw);
        assertThat(jdbc.queryForList("select * from trip_work_item order by reading_id")).isEqualTo(work);
        assertThat(jdbc.queryForObject("select visited_rows from telemetry_replay_backfill where vehicle_id=?",Long.class,VEHICLE)).isEqualTo(3);
    }
    @Test void replayLedgerV900UpgradeAndNewRowsBehindCompletedCursorAreCovered() {
        legacy(); oldRow(1); migrate();
        assertThat(count("telemetry_replay_identity")).isZero();
        assertThat(replayBackfill().copyPage(VEHICLE,500).complete()).isTrue();
        insert(UUID.fromString("00000000-0000-0000-0000-000000000001"),sample(2,Instant.parse("2026-10-20T12:00:02Z")));
        assertThat(replayBackfill().copyPage(VEHICLE,500).visited()).isZero();
        assertThat(count("telemetry_replay_identity")).isEqualTo(2);
        // A second vehicle gets its own registration and cannot move this cursor.
        UUID other=UUID.randomUUID(); jdbc.update("insert into vehicle(id,public_id) values (?,?)",other,"other-"+other);
        assertThat(replayBackfill().copyPage(other,1).complete()).isTrue();
        assertThat(count("telemetry_replay_identity")).isEqualTo(2);
    }
    @Test void replayLedgerMismatchRollsBackTheWholeBackfillPageAndCursor() {
        at913();
        UUID a=UUID.fromString("00000000-0000-0000-0000-000000000001"),b=UUID.fromString("00000000-0000-0000-0000-000000000002");
        insert(a,sample(1,Instant.parse("2026-10-20T12:00:00Z")));
        insert(b,sample(2,Instant.parse("2026-10-20T12:00:02Z")));
        migrate();
        // Deliberately corrupted fixture in this disposable schema, not a repair recipe.
        jdbc.update("""
                insert into telemetry_replay_identity
                select device_id,boot_id,sequence_number,id,vehicle_id,recorded_at,received_at,source,'{}'::jsonb
                from telemetry_reading where id=?
                """,b);
        var progress=jdbc.queryForMap("select * from telemetry_replay_backfill where vehicle_id=?",VEHICLE);
        assertThatThrownBy(() -> replayBackfill().copyPage(VEHICLE,2)).hasMessageContaining("replay identity mismatch");
        assertThat(count("telemetry_replay_identity")).isEqualTo(1); // Earlier row A was rolled back too.
        assertThat(jdbc.queryForMap("select * from telemetry_replay_backfill where vehicle_id=?",VEHICLE)).isEqualTo(progress);
    }
    @Test void replayLedgerBackfillSkipsBusyVehicleAndThenResumes() throws Exception {
        at913(); oldRow(1); migrate();
        var locked=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        try (var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var holder=executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                jdbc.queryForObject("select vehicle_id from telemetry_replay_backfill where vehicle_id=? for update",UUID.class,VEHICLE);
                locked.countDown();
                try { if (!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("lock fixture timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            try {
                assertThat(locked.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(replayBackfill().copyPage(VEHICLE,1).busy()).isTrue();
                assertThat(count("telemetry_replay_identity")).isZero();
            } finally { release.countDown(); }
            holder.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(replayBackfill().copyPage(VEHICLE,1).copied()).isEqualTo(1);
    }
    @Test void replayLedgerBackfillValidatesBoundsAndAcknowledgesAlreadyCapturedRows() {
        migrate(); local(); oldRow(1);
        assertThatThrownBy(() -> replayBackfill().copyPage(null,1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> replayBackfill().copyPage(VEHICLE,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> replayBackfill().copyPage(VEHICLE,501)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> replayBackfill().copyPage(UUID.randomUUID(),1)).hasMessageContaining("no replay backfill registration");
        var result=replayBackfill().copyPage(VEHICLE,500);
        assertThat(result.visited()).isEqualTo(1); assertThat(result.copied()).isZero(); assertThat(result.complete()).isTrue();
    }

    @Test void retainedReplayFallsBackToUnbackfilledLegacyRawWithoutChangingEvidence() {
        at913(); oldRow(1);
        var original=jdbc.queryForMap("select * from telemetry_reading");
        migrate(); assertThat(count("telemetry_replay_identity")).isZero();
        var reading=sample(1,Instant.parse("2026-10-20T12:00:01Z"));
        assertThat(store(reading)).isEqualTo("duplicate");
        var changed=reading.payload().deepCopy().put("rpm",999);
        assertThat(store(new TelemetryValidator(JSON).validate(changed))).isEqualTo("conflict");
        assertThat(count("telemetry_replay_identity")).isZero();
        assertThat(jdbc.queryForMap("select * from telemetry_reading")).isEqualTo(original);
        assertThat(replayBackfill().copyPage(VEHICLE,500).copied()).isEqualTo(1);
        assertThat(store(reading)).isEqualTo("duplicate");
        assertThat(jdbc.queryForObject("select received_at from telemetry_replay_identity",Timestamp.class)).isEqualTo(original.get("received_at"));
    }
    @Test void retainedReplayV914UpgradePreservesCapturedPayloadAndReceiptAfterFixtureRawRemoval() {
        Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration")
                .target("914").load().migrate();
        local(); oldRow(1);
        var before=jdbc.queryForMap("select * from telemetry_replay_identity");
        migrate(); migrate();
        jdbc.update("delete from trip_work_item"); jdbc.update("delete from telemetry_reading");
        assertThat(store(sample(1,Instant.parse("2026-10-20T12:00:01Z")))).isEqualTo("duplicate");
        assertThat(count("telemetry_reading")).isZero(); assertThat(count("trip_work_item")).isZero();
        assertThat(jdbc.queryForMap("select * from telemetry_replay_identity")).isEqualTo(before);
    }

    @Test void retentionPreviewReportsActualLegacyReplayCoverageAcrossBoundedBackfill() {
        at913(); oldRow(1); oldRow(2); migrate();
        var preview=new dev.autopulse.trip.RetentionPreview(jdbc,transactions,
                new dev.autopulse.trip.RetentionPreview.Options(true,true,30,96),
                java.time.Clock.fixed(Instant.parse("2026-12-01T12:00:00Z"),java.time.ZoneOffset.UTC));
        var before=preview.report(VEHICLE,100,null);
        assertThat(before.replayCoverage()).isEqualTo(new dev.autopulse.trip.RetentionPreview.ReplayCoverage("INCOMPLETE",2,0,2,0));
        assertThat(before.readings()).allSatisfy(row -> assertThat(row.protections()).contains("REPLAY_IDENTITY_MISSING","REPLAY_BACKFILL_INCOMPLETE"));
        assertThat(count("telemetry_replay_identity")).isZero(); // Reporting never repairs/copies.
        assertThat(replayBackfill().copyPage(VEHICLE,1).copied()).isEqualTo(1);
        var partial=preview.report(VEHICLE,100,null);
        assertThat(partial.replayCoverage()).isEqualTo(new dev.autopulse.trip.RetentionPreview.ReplayCoverage("INCOMPLETE",2,1,1,0));
        assertThat(replayBackfill().copyPage(VEHICLE,1).copied()).isEqualTo(1);
        assertThat(replayBackfill().copyPage(VEHICLE,1).complete()).isTrue();
        var complete=preview.report(VEHICLE,100,null);
        assertThat(complete.replayCoverage()).isEqualTo(new dev.autopulse.trip.RetentionPreview.ReplayCoverage("COMPLETE",2,2,0,0));
        assertThat(complete.wouldDelete()).isZero();
        assertThat(complete.missingPrerequisites()).contains("WRITE_TIME_REPLAY_COVERAGE_RECHECK_REQUIRED","EXPLICIT_PRUNE_TRANSACTION_REQUIRED");
    }

    @Test void retainedFinalEvidenceUpgradeDoesNotScanAndWaitsForExactLegacyLedger() {
        at913(); commute("short"); Instant asOf=processingTime(); drain(asOf,100);
        int sealed=0;
        for (int i=0;i<20 && sealed==0;i++) sealed+=finalizer().finalizePage(VEHICLE,asOf,100).finalized();
        assertThat(sealed).isEqualTo(1);
        UUID trip=jdbc.queryForObject("select id from trip_record where finalization='FINAL'",UUID.class);
        var originalTrip=jdbc.queryForMap("select * from trip_record where id=?",trip);
        var originalRaw=jdbc.queryForList("select * from telemetry_reading order by id");
        migrate();
        assertThat(count("trip_retained_evidence")).isZero(); assertThat(count("trip_retained_evidence_progress")).isZero();
        var copier=new dev.autopulse.trip.FinalTripEvidence(jdbc,transactions);
        assertThatThrownBy(() -> copier.copyPage(VEHICLE,trip,500)).hasMessageContaining("exact original closed membership");
        assertThat(count("trip_retained_evidence")).isZero(); assertThat(count("trip_retained_evidence_progress")).isZero();
        assertThat(replayBackfill().copyPage(VEHICLE,100).complete()).isTrue();
        var page=copier.copyPage(VEHICLE,trip,500);
        assertThat(page.complete()).isTrue(); assertThat(page.retained()).isEqualTo(27);
        assertThat(count("trip_retained_evidence")).isEqualTo(27);
        migrate();
        assertThat(new dev.autopulse.trip.FinalTripEvidence(jdbc,transactions).copyPage(VEHICLE,trip,1).visited()).isZero();
        assertThat(jdbc.queryForMap("select * from trip_record where id=?",trip)).isEqualTo(originalTrip);
        assertThat(jdbc.queryForList("select * from telemetry_reading order by id")).isEqualTo(originalRaw);
    }

    @Test void aggregateUpgradePreservesExistingFinalEvidenceAndBuildsOnlyExplicitly() {
        Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration").target("916").load().migrate();
        local(); commute("short"); Instant asOf=processingTime(); drain(asOf,100);
        int sealed=0; for (int i=0;i<20 && sealed==0;i++) sealed+=finalizer().finalizePage(VEHICLE,asOf,100).finalized();
        assertThat(sealed).isEqualTo(1);
        UUID trip=jdbc.queryForObject("select id from trip_record where finalization='FINAL'",UUID.class);
        assertThat(replayBackfill().copyPage(VEHICLE,100).complete()).isTrue();
        assertThat(new dev.autopulse.trip.FinalTripEvidence(jdbc,transactions).copyPage(VEHICLE,trip,500).complete()).isTrue();
        var original=jdbc.queryForMap("select * from trip_record where id=?",trip);
        var members=jdbc.queryForList("select * from trip_retained_evidence order by reading_id");
        migrate(); assertThat(count("trip_aggregate_bucket")).isZero(); assertThat(count("trip_aggregate_progress")).isZero();
        var page=new dev.autopulse.trip.TripAggregateBuilder(jdbc,transactions).buildPage(VEHICLE,trip,500);
        assertThat(page.complete()).isTrue(); assertThat(page.visited()).isEqualTo(27); assertThat(page.bucketWrites()).isEqualTo(2);
        var buckets=jdbc.queryForList("select * from trip_aggregate_bucket order by resolution_seconds,bucket_start");
        migrate();
        assertThat(new dev.autopulse.trip.TripAggregateBuilder(jdbc,transactions).buildPage(VEHICLE,trip,1).visited()).isZero();
        assertThat(jdbc.queryForList("select * from trip_aggregate_bucket order by resolution_seconds,bucket_start")).isEqualTo(buckets);
        assertThat(jdbc.queryForMap("select * from trip_record where id=?",trip)).isEqualTo(original);
        assertThat(jdbc.queryForList("select * from trip_retained_evidence order by reading_id")).isEqualTo(members);
    }

    @Test void verificationUpgradeDoesNotScanExistingCompleteAggregatesOrPublishThem() {
        Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration").target("917").load().migrate();
        local(); commute("short"); Instant asOf=processingTime(); drain(asOf,100);
        int sealed=0; for (int i=0;i<20 && sealed==0;i++) sealed+=finalizer().finalizePage(VEHICLE,asOf,100).finalized();
        assertThat(sealed).isEqualTo(1);
        UUID trip=jdbc.queryForObject("select id from trip_record where finalization='FINAL'",UUID.class);
        replayBackfill().copyPage(VEHICLE,100);
        new dev.autopulse.trip.FinalTripEvidence(jdbc,transactions).copyPage(VEHICLE,trip,500);
        new dev.autopulse.trip.TripAggregateBuilder(jdbc,transactions).buildPage(VEHICLE,trip,500);
        var buckets=jdbc.queryForList("select * from trip_aggregate_bucket order by resolution_seconds,bucket_start");
        var original=jdbc.queryForMap("select * from trip_record where id=?",trip);
        migrate(); assertThat(count("trip_aggregate_verification")).isZero(); assertThat(count("trip_aggregate_reference")).isZero();
        var verifier=new dev.autopulse.trip.TripAggregateVerifier(jdbc,JSON,transactions);
        assertThat(verifier.verifyPage(VEHICLE,trip,500).state()).isEqualTo("COMPARE");
        assertThat(verifier.verifyPage(VEHICLE,trip,500).state()).isEqualTo("VERIFIED");
        migrate();
        assertThat(new dev.autopulse.trip.TripAggregateVerifier(jdbc,JSON,transactions).verifyPage(VEHICLE,trip,1).compared()).isZero();
        assertThat(jdbc.queryForMap("select * from trip_record where id=?",trip)).isEqualTo(original);
        assertThat(jdbc.queryForList("select * from trip_aggregate_bucket order by resolution_seconds,bucket_start")).isEqualTo(buckets);
    }

    @Test void retainedRangeIndexUpgradePreservesVerifiedHistoryAndSupportsExactClips() {
        Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration").target("918").load().migrate();
        local(); commute("short"); Instant asOf=processingTime(); drain(asOf,100);
        int sealed=0; for (int i=0;i<20 && sealed==0;i++) sealed+=finalizer().finalizePage(VEHICLE,asOf,100).finalized();
        assertThat(sealed).isEqualTo(1);
        UUID trip=jdbc.queryForObject("select id from trip_record where finalization='FINAL'",UUID.class);
        replayBackfill().copyPage(VEHICLE,100);
        new dev.autopulse.trip.FinalTripEvidence(jdbc,transactions).copyPage(VEHICLE,trip,500);
        new dev.autopulse.trip.TripAggregateBuilder(jdbc,transactions).buildPage(VEHICLE,trip,500);
        var verifier=new dev.autopulse.trip.TripAggregateVerifier(jdbc,JSON,transactions);
        verifier.verifyPage(VEHICLE,trip,500); assertThat(verifier.verifyPage(VEHICLE,trip,500).state()).isEqualTo("VERIFIED");
        var original=jdbc.queryForMap("select * from trip_record where id=?",trip);
        var verification=jdbc.queryForMap("select * from trip_aggregate_verification where trip_id=?",trip);
        var ledger=jdbc.queryForList("select * from telemetry_replay_identity order by reading_id");
        migrate();
        assertThat(jdbc.queryForObject("select exists(select 1 from pg_indexes where schemaname=current_schema() and indexname='replay_device_history_range')",Boolean.class)).isTrue();
        var history=new dev.autopulse.trip.RetainedTripHistory(jdbc,JSON,new dev.autopulse.trip.TripQueries(jdbc,JSON,null,null,transactions),transactions);
        var query=new dev.autopulse.trip.HistoryRequest(((Timestamp)original.get("started_at")).toInstant().plusNanos(1000),
                ((Timestamp)original.get("ended_at")).toInstant().plusNanos(1000),20,trip,null,"1m");
        var page=history.read(VEHICLE,query,null);
        assertThat(page.points().stream().mapToLong(dev.autopulse.trip.HistoryQueries.AggregatePoint::sampleCount).sum()).isEqualTo(26);
        migrate(); assertThat(history.read(VEHICLE,query,null)).isEqualTo(page);
        assertThat(jdbc.queryForMap("select * from trip_record where id=?",trip)).isEqualTo(original);
        assertThat(jdbc.queryForMap("select * from trip_aggregate_verification where trip_id=?",trip)).isEqualTo(verification);
        assertThat(jdbc.queryForList("select * from telemetry_replay_identity order by reading_id")).isEqualTo(ledger);
    }

    @Test void wholeVehicleIndexUpgradePreservesDisjointOwnershipAndOriginalData() {
        Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration").target("919").load().migrate();
        local(); commute("short"); Instant asOf=processingTime(); drain(asOf,100);
        int sealed=0; for (int i=0;i<20 && sealed==0;i++) sealed+=finalizer().finalizePage(VEHICLE,asOf,100).finalized();
        assertThat(sealed).isEqualTo(1);
        UUID trip=jdbc.queryForObject("select id from trip_record where finalization='FINAL'",UUID.class);
        replayBackfill().copyPage(VEHICLE,100);
        new dev.autopulse.trip.FinalTripEvidence(jdbc,transactions).copyPage(VEHICLE,trip,500);
        new dev.autopulse.trip.TripAggregateBuilder(jdbc,transactions).buildPage(VEHICLE,trip,500);
        var verifier=new dev.autopulse.trip.TripAggregateVerifier(jdbc,JSON,transactions);
        verifier.verifyPage(VEHICLE,trip,500); assertThat(verifier.verifyPage(VEHICLE,trip,500).state()).isEqualTo("VERIFIED");
        var original=jdbc.queryForMap("select * from trip_record where id=?",trip);
        var ledger=jdbc.queryForList("select * from telemetry_replay_identity order by reading_id");
        long rawCount=count("telemetry_reading");
        var evidenceBefore=jdbc.queryForList("select reading_id from trip_evidence where trip_id=? order by reading_id",UUID.class,trip);
        migrate();
        assertThat(count("trip_verified_archive")).isEqualTo(1);
        assertThat(jdbc.queryForList("select reading_id from trip_evidence where trip_id=? order by reading_id",UUID.class,trip)).isEqualTo(evidenceBefore);
        assertThat(jdbc.queryForObject("select exists(select 1 from pg_indexes where schemaname=current_schema() and indexname='replay_vehicle_history_range')",Boolean.class)).isTrue();
        var queries=new dev.autopulse.trip.TripQueries(jdbc,JSON,new dev.autopulse.vehicle.VehicleRegistry(jdbc),null,transactions);
        var history=new dev.autopulse.trip.OwnedVehicleHistory(jdbc,JSON,queries,transactions);
        Instant from=jdbc.queryForObject("select min(recorded_at) from telemetry_reading",Timestamp.class).toInstant();
        var query=new dev.autopulse.trip.HistoryRequest(from,from.plusSeconds(600),20,null,null,"1m");
        var page=history.read("venue-2021",query,null);
        assertThat(page.archiveOwned()).isEqualTo(27); assertThat(page.rawOwned()).isEqualTo(rawCount-27);
        assertThat(page.points().stream().mapToLong(dev.autopulse.trip.HistoryQueries.AggregatePoint::sampleCount).sum()).isEqualTo(rawCount);
        migrate(); assertThat(history.read("venue-2021",query,null)).isEqualTo(page);
        assertThat(jdbc.queryForMap("select * from trip_record where id=?",trip)).isEqualTo(original);
        assertThat(jdbc.queryForList("select * from telemetry_replay_identity order by reading_id")).isEqualTo(ledger);
    }

    @Test void finalEvidenceReadUpgradePreservesVerifiedAndLegacyMembershipWithoutBackfill() {
        Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration").target("920").load().migrate();
        local(); commute("short"); Instant asOf=processingTime(); drain(asOf,100);
        int sealed=0; for (int i=0;i<20 && sealed==0;i++) sealed+=finalizer().finalizePage(VEHICLE,asOf,100).finalized();
        assertThat(sealed).isEqualTo(1);
        UUID trip=jdbc.queryForObject("select id from trip_record where finalization='FINAL'",UUID.class);
        var original=jdbc.queryForMap("select * from trip_record where id=?",trip);
        var raw=jdbc.queryForList("select * from telemetry_reading order by id");
        var links=jdbc.queryForList("select * from trip_reading_link order by reading_id");
        var members=jdbc.queryForList("select reading_id from trip_evidence where trip_id=? order by reading_id",UUID.class,trip);
        migrate();
        assertThat(count("trip_verified_archive")).isZero(); assertThat(count("trip_retained_evidence")).isZero();
        assertThat(jdbc.queryForList("select reading_id from trip_evidence where trip_id=? order by reading_id",UUID.class,trip)).isEqualTo(members);
        replayBackfill().copyPage(VEHICLE,100);
        new dev.autopulse.trip.FinalTripEvidence(jdbc,transactions).copyPage(VEHICLE,trip,500);
        new dev.autopulse.trip.TripAggregateBuilder(jdbc,transactions).buildPage(VEHICLE,trip,500);
        var verifier=new dev.autopulse.trip.TripAggregateVerifier(jdbc,JSON,transactions);
        verifier.verifyPage(VEHICLE,trip,500); assertThat(count("trip_verified_archive")).isZero();
        assertThat(verifier.verifyPage(VEHICLE,trip,500).state()).isEqualTo("VERIFIED");
        assertThat(count("trip_verified_archive")).isEqualTo(1);
        assertThat(jdbc.queryForList("select reading_id from trip_evidence where trip_id=? order by reading_id",UUID.class,trip)).isEqualTo(members);
        migrate();
        assertThat(jdbc.queryForList("select reading_id from trip_evidence where trip_id=? order by reading_id",UUID.class,trip)).isEqualTo(members);
        assertThat(jdbc.queryForMap("select * from trip_record where id=?",trip)).isEqualTo(original);
        assertThat(jdbc.queryForList("select * from telemetry_reading order by id")).isEqualTo(raw);
        assertThat(jdbc.queryForList("select * from trip_reading_link order by reading_id")).isEqualTo(links);
    }

    @Test void freshDefaultIsEmptyAndRestartDoesNotRewriteMigrations() {
        migrate();
        assertThat(current().info().current().getVersion().toString()).isEqualTo("931");
        assertThat(count("vehicle")).isZero();
        assertThat(count("trip_policy")).isZero();
        var history = jdbc.queryForList("select version,checksum from flyway_schema_history order by installed_rank");
        migrate();
        assertThat(jdbc.queryForList("select version,checksum from flyway_schema_history order by installed_rank")).isEqualTo(history);
        assertThat(count("trip_work_item")).isZero();
    }

    @Test void canonicalLinkedEvidenceUpgradeChangesNoExistingDataOrGeneration() {
        Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration").target("925").load().migrate();
        local(); commute("short"); drain(processingTime(),100); finishReplayOneRowAtATime();
        var before=referenceSources(); var generation=jdbc.queryForList("select * from vehicle_read_generation order by vehicle_id");
        var members=jdbc.queryForList("select * from trip_evidence order by trip_id,reading_id");
        var ledger=jdbc.queryForList("select * from telemetry_replay_identity order by reading_id");
        var oldHistory=jdbc.queryForList("select version,checksum from flyway_schema_history order by installed_rank");
        assertThat(members).hasSize(27); migrate(); migrate();
        assertThat(referenceSources()).isEqualTo(before);
        assertThat(jdbc.queryForList("select * from vehicle_read_generation order by vehicle_id")).isEqualTo(generation);
        assertThat(jdbc.queryForList("select * from trip_evidence order by trip_id,reading_id")).isEqualTo(members);
        assertThat(jdbc.queryForList("select * from telemetry_replay_identity order by reading_id")).isEqualTo(ledger);
        assertThat(jdbc.queryForList("select version,checksum from flyway_schema_history where version::int<=925 order by installed_rank")).isEqualTo(oldHistory);
        assertThat(count("trip_retained_evidence")).isZero(); assertThat(count("trip_verified_archive")).isZero();
        assertRawReferenceTransition();
    }

    @Test void canonicalLinkedEvidenceStillHidesEveryBoundedPublicationStage() {
        correctedShortPlan(); finishReplayOneRowAtATime(); staged(1);
        var raw=jdbc.queryForList("select * from telemetry_reading order by id");
        assertThat(count("trip_evidence")).isEqualTo(27);
        published(1); // Helper asserts both visibility views are empty after every unfinished page.
        assertThat(count("trip_evidence")).isEqualTo(27);
        assertThat(jdbc.queryForObject("select count(distinct reading_id) from trip_evidence",Integer.class)).isEqualTo(27);
        assertCorrectedShortSummary(); assertThat(count("trip_visible_record")).isEqualTo(1);
        assertThat(jdbc.queryForList("select * from telemetry_reading order by id")).isEqualTo(raw);
    }

    UUID wrongVehicleWorkAt926() {
        Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration").target("926").load().migrate();
        local(); UUID id=oldRow(1);
        // Legal in the old schema: existing vehicle and reading FKs do not bind the pair.
        jdbc.update("update trip_work_item set vehicle_id=? where reading_id=?",EMPTY,id);
        return id;
    }
    UUID linkOwnerTrip(UUID owner,UUID primary) {
        UUID trip=UUID.randomUUID();
        jdbc.update("""
                insert into trip_record(id,vehicle_id,primary_device_id,anchor_boot,anchor_sequence,started_at,observed_through,
                    source,status,algorithm_version,summary,quality)
                values (?,?,?,?,1,'2026-10-20T12:00:00Z','2026-10-20T12:00:00Z','SIMULATED','DISCARDED','trip-v1-draft','{}','[]')
                """,trip,owner,primary,UUID.randomUUID());
        return trip;
    }
    TripReferenceAudit referenceAudit() { return new TripReferenceAudit(jdbc,transactions); }
    @Test void correctionReadingLookupUpgradePreservesReferencesAndSupportsReadingFirstInspection() {
        Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration").target("929").load().migrate();
        local(); UUID reading=oldRow(1); var graph=correctionOwnerGraph(VEHICLE,reading);
        auditReferences(reading,linkOwnerTrip(VEHICLE,DEVICE),graph); var before=correctionOwnerMaps();
        migrate(); migrate(); assertThat(correctionOwnerMaps()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select indexdef from pg_indexes where schemaname=current_schema() and indexname='trip_correction_reading_lookup'",String.class))
                .contains("(reading_id, replay_id)");
        assertThat(validatedReferences()).isZero(); assertThat(workVehicleValidated()).isFalse();
        assertThatThrownBy(() -> jdbc.update("delete from telemetry_reading where id=?",reading)).hasMessageContaining("foreign key constraint");
    }
    @Test void barrierReferenceInspectionReportsLegacyWrongWorkAndTripOwnersWithoutRepair() {
        Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration").target("926").load().migrate();
        local(); UUID reading=oldRow(1),foreign=foreignCorrectionWork();
        UUID primary=jdbc.queryForObject("select primary_device_id from trip_policy where vehicle_id=?",UUID.class,EMPTY);
        auditReferences(reading,linkOwnerTrip(EMPTY,primary),correctionOwnerGraph(EMPTY,foreign));
        jdbc.update("update trip_work_item set vehicle_id=? where reading_id=?",EMPTY,reading);
        migrate(); var before=correctionOwnerMaps();
        var service=new dev.autopulse.trip.RetentionPreview(jdbc,transactions,new dev.autopulse.trip.RetentionPreview.Options(true,true,30,96),
                java.time.Clock.fixed(Instant.parse("2026-12-01T12:00:00Z"),java.time.ZoneOffset.UTC));
        var page=service.reportUnderBarrier(VEHICLE,1,null);
        assertThat(page.state()).isEqualTo("CHECKED_NOT_AUTHORIZED"); assertThat(page.preview().wouldDelete()).isZero();
        assertThat(page.referenceChecks()).containsExactly(new dev.autopulse.trip.RetentionPreview.ReferenceCheck(reading,"OWNER_MISMATCH","OWNER_MISMATCH",true));
        assertThat(correctionOwnerMaps()).isEqualTo(before);
    }
    void auditReferences(UUID reading,UUID trip,UUID[] graph) {
        jdbc.update("insert into trip_reading_link(reading_id,trip_id) values (?,?)",reading,trip);
        jdbc.update("insert into trip_correction_reading(replay_id,reading_id,segment_id) values (?,?,?)",graph[1],reading,graph[2]);
    }
    @Test void referenceAuditRejectsInvalidBoundsAndReportsEmptyKindsWithoutWrites() {
        migrate(); local(); var before=correctionOwnerMaps();
        for (var kind:TripReferenceAudit.Kind.values()) {
            var page=referenceAudit().readPage(kind,null,1);
            assertThat(page.visited()).isZero(); assertThat(page.complete()).isTrue(); assertThat(page.lastCursor()).isNull();
            assertThat(page.rawMissing()+page.ledgerMissing()+page.originalMismatch()+page.ownerMismatch()).isZero();
        }
        assertThatThrownBy(() -> referenceAudit().readPage(null,null,1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> referenceAudit().readPage(TripReferenceAudit.Kind.WORK,null,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> referenceAudit().readPage(TripReferenceAudit.Kind.WORK,null,501)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TripReferenceAudit.Cursor(TripReferenceAudit.Kind.CORRECTION,null,UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TripReferenceAudit.Cursor(TripReferenceAudit.Kind.WORK,UUID.randomUUID(),UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        var boundary=new TripReferenceAudit.Cursor(TripReferenceAudit.Kind.LINK,null,UUID.randomUUID());
        assertThatThrownBy(() -> referenceAudit().readPage(TripReferenceAudit.Kind.WORK,boundary,1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(correctionOwnerMaps()).isEqualTo(before);
    }
    @Test void referenceAuditFindsMissingLegacyTargetsWithoutBackfillingAndSeesExplicitCoverageLater() {
        at913(); UUID reading=oldRow(1); var graph=correctionOwnerGraph(VEHICLE,reading);
        auditReferences(reading,linkOwnerTrip(VEHICLE,DEVICE),graph); migrate(); var before=correctionOwnerMaps();
        for (var kind:TripReferenceAudit.Kind.values()) {
            var page=referenceAudit().readPage(kind,null,2);
            assertThat(page.visited()).isEqualTo(1); assertThat(page.complete()).isTrue();
            assertThat(page.ledgerMissing()).isEqualTo(1); assertThat(page.ownerMismatch()).isEqualTo(1);
            assertThat(page.rawMissing()).isZero(); assertThat(page.originalMismatch()).isZero();
        }
        assertThat(correctionOwnerMaps()).isEqualTo(before); assertThat(count("telemetry_replay_identity")).isZero();
        finishReplayOneRowAtATime(); var covered=correctionOwnerMaps();
        for (var kind:TripReferenceAudit.Kind.values()) {
            var page=referenceAudit().readPage(kind,null,2);
            assertThat(page.visited()).isEqualTo(1);
            assertThat(page.rawMissing()+page.ledgerMissing()+page.originalMismatch()+page.ownerMismatch()).isZero();
        }
        assertThat(correctionOwnerMaps()).isEqualTo(covered); assertThat(validatedReferences()).isZero();
    }
    @Test void referenceAuditGlobalTraversalFindsAllThreeLegacyWrongVehicleOwners() {
        Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration").target("926").load().migrate();
        local(); UUID reading=oldRow(1),foreign=foreignCorrectionWork();
        UUID primary=jdbc.queryForObject("select primary_device_id from trip_policy where vehicle_id=?",UUID.class,EMPTY);
        auditReferences(reading,linkOwnerTrip(EMPTY,primary),correctionOwnerGraph(EMPTY,foreign));
        jdbc.update("update trip_work_item set vehicle_id=? where reading_id=?",EMPTY,reading);
        migrate(); var before=correctionOwnerMaps();
        for (var kind:TripReferenceAudit.Kind.values()) {
            var page=referenceAudit().readPage(kind,null,500);
            assertThat(page.visited()).isEqualTo(kind==TripReferenceAudit.Kind.WORK?2:1);
            assertThat(page.ownerMismatch()).isEqualTo(1);
            assertThat(page.rawMissing()+page.ledgerMissing()+page.originalMismatch()).isZero();
        }
        assertThat(correctionOwnerMaps()).isEqualTo(before); assertThat(workVehicleValidated()).isFalse();
    }
    @Test void referenceAuditDetectsChangedOriginalMetadataIndependentlyOfOwnerAgreement() {
        migrate(); local(); UUID reading=oldRow(1);
        auditReferences(reading,linkOwnerTrip(VEHICLE,DEVICE),correctionOwnerGraph(VEHICLE,reading));
        // Privileged disposable corruption only, not a supported application write.
        jdbc.update("update telemetry_reading set received_at=received_at+interval '1 microsecond' where id=?",reading);
        var before=correctionOwnerMaps();
        for (var kind:TripReferenceAudit.Kind.values()) {
            var page=referenceAudit().readPage(kind,null,2);
            assertThat(page.originalMismatch()).isEqualTo(1);
            assertThat(page.rawMissing()+page.ledgerMissing()+page.ownerMismatch()).isZero();
        }
        assertThat(correctionOwnerMaps()).isEqualTo(before);
    }
    @Test void referenceAuditCompositePagesRetainEveryReplayAndReadingAcrossRestarts() {
        migrate(); local(); UUID first=oldRow(1),second=oldRow(2);
        var one=correctionOwnerGraph(VEHICLE,first); var two=correctionOwnerGraph(VEHICLE,second);
        for (var pair:java.util.List.of(new UUID[]{one[1],first,one[2]},new UUID[]{one[1],second,one[2]},new UUID[]{two[1],first,two[2]}))
            jdbc.update("insert into trip_correction_reading(replay_id,reading_id,segment_id) values (?,?,?)",pair[0],pair[1],pair[2]);
        var before=correctionOwnerMaps(); var seen=new java.util.ArrayList<TripReferenceAudit.Cursor>();
        TripReferenceAudit.Cursor after=null; boolean exhausted=false;
        for (int i=0;i<4;i++) {
            var page=referenceAudit().readPage(TripReferenceAudit.Kind.CORRECTION,after,1);
            assertThat(page.visited()).isBetween(0,1);
            assertThat(page.rawMissing()+page.ledgerMissing()+page.originalMismatch()+page.ownerMismatch()).isZero();
            if (page.visited()>0) seen.add(page.lastCursor());
            if (page.complete()) { assertThat(page.lastCursor()).isEqualTo(after); exhausted=true; break; }
            after=page.lastCursor();
        }
        assertThat(exhausted).isTrue(); assertThat(seen).hasSize(3).doesNotHaveDuplicates();
        var expected=jdbc.query("select replay_id,reading_id from trip_correction_reading order by replay_id,reading_id",
                (r,n) -> new TripReferenceAudit.Cursor(TripReferenceAudit.Kind.CORRECTION,r.getObject(1,UUID.class),r.getObject(2,UUID.class)));
        assertThat(seen).containsExactlyElementsOf(expected); assertThat(correctionOwnerMaps()).isEqualTo(before);
        for (var kind:java.util.List.of(TripReferenceAudit.Kind.WORK,TripReferenceAudit.Kind.LINK)) {
            var page=referenceAudit().readPage(kind,null,1);
            if (kind==TripReferenceAudit.Kind.LINK) { assertThat(page.complete()).isTrue(); continue; }
            var next=referenceAudit().readPage(kind,page.lastCursor(),1);
            assertThat(next.lastCursor()).isNotEqualTo(page.lastCursor());
            assertThat(referenceAudit().readPage(kind,next.lastCursor(),1).complete()).isTrue();
        }
    }
    UUID foreignCorrectionWork() {
        UUID primary=UUID.randomUUID(),readingId=UUID.randomUUID(); String publicDevice="correction-owner-"+primary;
        jdbc.update("insert into device(id,vehicle_id,public_id) values (?,?,?)",primary,EMPTY,publicDevice);
        jdbc.update("insert into trip_policy(vehicle_id,primary_device_id) values (?,?)",EMPTY,primary);
        var input=sample(9999,Instant.parse("2026-10-20T12:00:02Z")).payload().deepCopy().put("vehicleId","empty-vehicle").put("deviceId",publicDevice);
        var reading=new TelemetryValidator(JSON).validate(input);
        jdbc.update("insert into telemetry_reading(id,vehicle_id,device_id,boot_id,sequence_number,recorded_at,source,payload) values (?,?,?,?,?,?,? ,?::jsonb)",readingId,EMPTY,primary,reading.bootId(),new java.math.BigDecimal(reading.sequenceNumber()),Timestamp.from(reading.recordedAt()),"SIMULATED",reading.payload().toString());
        return readingId;
    }
    UUID correctionOwnerSegment(UUID replay) {
        UUID segment=UUID.randomUUID();
        jdbc.update("insert into trip_correction_segment(id,replay_id,anchor_boot,anchor_sequence,started_at,observed_through,source,status,summary,quality) values (?,?,?,1,'2026-10-20T12:00:00Z','2026-10-20T12:00:00Z','SIMULATED','DISCARDED','{}','[]')",segment,replay,UUID.randomUUID());
        return segment;
    }
    UUID[] correctionOwnerGraph(UUID owner,UUID requestedWork) {
        UUID plan=UUID.randomUUID(),replay=UUID.randomUUID();
        UUID primary=jdbc.queryForObject("select primary_device_id from trip_policy where vehicle_id=?",UUID.class,owner);
        jdbc.update("""
                insert into trip_correction_plan(id,vehicle_id,requested_work_id,primary_device_id,policy_revision,projection_cursor_at,
                    planned_as_of,window_start,window_end,phase)
                values (?,?,?,?,1,'2026-10-20T12:00:00Z','2026-10-20T12:02:00Z','2026-10-20T12:00:00Z','2026-10-20T12:01:00Z','DONE')
                """,plan,owner,requestedWork,primary);
        jdbc.update("""
                insert into trip_correction_replay(id,plan_id,input_generation,window_start,window_end,planned_as_of,read_from,read_to,status,checkpoint)
                values (?,?,0,'2026-10-20T12:00:00Z','2026-10-20T12:01:00Z','2026-10-20T12:02:00Z','2026-10-20T12:00:00Z','2026-10-20T12:01:00Z','INVALIDATED','{}')
                """,replay,plan);
        return new UUID[]{plan,replay,correctionOwnerSegment(replay)};
    }
    java.util.List<Object> correctionOwnerMaps() {
        var maps=new java.util.ArrayList<Object>(); maps.add(referenceSources());
        for (String table : java.util.List.of("trip_correction_plan","trip_correction_replay","trip_correction_segment","telemetry_replay_identity"))
            maps.add(jdbc.queryForList("select * from "+table+" order by 1"));
        return maps;
    }
    @Test void correctionOwnerUpgradeDoesNotRewriteOrCertifyLegacyCrossVehicleReferences() {
        Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration").target("928").load().migrate();
        local(); UUID own=oldRow(1),foreign=foreignCorrectionWork(); var other=correctionOwnerGraph(EMPTY,foreign);
        jdbc.update("insert into trip_correction_reading(replay_id,reading_id,segment_id) values (?,?,?)",other[1],own,other[2]);
        var before=correctionOwnerMaps(); migrate(); migrate(); assertThat(correctionOwnerMaps()).isEqualTo(before);
        assertThatThrownBy(() -> jdbc.update("update trip_correction_reading set segment_id=segment_id"))
                .hasMessageContaining("correction reference requires matching vehicle ownership");
        assertThatThrownBy(() -> jdbc.update("insert into trip_correction_reading(replay_id,reading_id,segment_id) values (?,?,?) on conflict do nothing",other[1],own,other[2]))
                .hasMessageContaining("correction reference requires matching vehicle ownership");
        assertThat(correctionOwnerMaps()).isEqualTo(before);
    }
    @Test void wrongCorrectionOwnerRollsBackLegacyCaptureAndCorrectOwnerRetrySucceeds() {
        at913(); UUID own=oldRow(1),foreign=foreignCorrectionWork(); var other=correctionOwnerGraph(EMPTY,foreign); var correct=correctionOwnerGraph(VEHICLE,own);
        migrate(); assertThat(count("telemetry_replay_identity")).isZero();
        assertThatThrownBy(() -> jdbc.update("insert into trip_correction_reading(replay_id,reading_id,segment_id) values (?,?,?)",other[1],own,other[2]))
                .hasMessageContaining("correction reference requires matching vehicle ownership");
        assertThat(count("telemetry_replay_identity")).isZero(); assertThat(count("trip_correction_reading")).isZero();
        jdbc.update("insert into trip_correction_reading(replay_id,reading_id,segment_id) values (?,?,?)",correct[1],own,correct[2]);
        assertThat(count("telemetry_replay_identity")).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("delete from telemetry_reading where id=?",own)).hasMessageContaining("foreign key constraint");
    }
    @Test void correctionParentOnlyRetargetRechecksExactRawAndReplaySegmentRelationship() {
        at913(); UUID own=oldRow(1),foreign=foreignCorrectionWork(); var correct=correctionOwnerGraph(VEHICLE,own); var other=correctionOwnerGraph(EMPTY,foreign);
        UUID next=correctionOwnerSegment(correct[1]);
        jdbc.update("insert into trip_correction_reading(replay_id,reading_id,segment_id) values (?,?,?)",correct[1],own,correct[2]);
        migrate(); assertThat(count("telemetry_replay_identity")).isZero();
        jdbc.update("update trip_correction_reading set segment_id=? where reading_id=?",next,own);
        assertThat(count("telemetry_replay_identity")).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("update trip_correction_reading set replay_id=? where reading_id=?",other[1],own))
                .hasMessageContaining("matching replay segment");
        assertThatThrownBy(() -> jdbc.update("update trip_correction_reading set replay_id=?,segment_id=? where reading_id=?",other[1],other[2],own))
                .hasMessageContaining("correction reference requires matching vehicle ownership");
        jdbc.update("update telemetry_reading set received_at=received_at+interval '1 microsecond' where id=?",own);
        assertThatThrownBy(() -> jdbc.update("update trip_correction_reading set segment_id=? where reading_id=?",correct[2],own))
                .hasMessageContaining("does not match original reading");
        assertThat(jdbc.queryForObject("select segment_id from trip_correction_reading where reading_id=?",UUID.class,own)).isEqualTo(next);
    }
    @Test void correctionParentOwnershipCannotBeReassignedButProgressStillChanges() {
        migrate(); local(); UUID own=oldRow(1),foreign=foreignCorrectionWork(); var correct=correctionOwnerGraph(VEHICLE,own); var other=correctionOwnerGraph(EMPTY,foreign);
        UUID otherDevice=jdbc.queryForObject("select primary_device_id from trip_policy where vehicle_id=?",UUID.class,EMPTY);
        var before=correctionOwnerMaps();
        assertThatThrownBy(() -> jdbc.update("update trip_correction_plan set vehicle_id=?,primary_device_id=? where id=?",EMPTY,otherDevice,correct[0])).hasMessageContaining("correction parent ownership is immutable");
        assertThatThrownBy(() -> jdbc.update("update trip_correction_replay set plan_id=? where id=?",other[0],correct[1])).hasMessageContaining("correction parent ownership is immutable");
        assertThatThrownBy(() -> jdbc.update("update trip_correction_segment set replay_id=? where id=?",other[1],correct[2])).hasMessageContaining("correction parent ownership is immutable");
        assertThat(correctionOwnerMaps()).isEqualTo(before);
        jdbc.update("update trip_correction_plan set vehicle_id=vehicle_id,visited_rows=visited_rows+1 where id=?",correct[0]);
        jdbc.update("update trip_correction_replay set plan_id=plan_id,visited_rows=visited_rows+1 where id=?",correct[1]);
        jdbc.update("update trip_correction_segment set replay_id=replay_id,source='OBD' where id=?",correct[2]);
        assertThat(jdbc.queryForObject("select visited_rows from trip_correction_plan where id=?",Integer.class,correct[0])).isEqualTo(1);
        assertThat(jdbc.queryForObject("select visited_rows from trip_correction_replay where id=?",Integer.class,correct[1])).isEqualTo(1);
        assertThat(jdbc.queryForObject("select source from trip_correction_segment where id=?",String.class,correct[2])).isEqualTo("OBD");
    }
    @Test void correctionReferenceLocksParentsBeforeRawAndReleasesAfterCommit() throws Exception {
        migrate(); local(); UUID own=oldRow(1); var correct=correctionOwnerGraph(VEHICLE,own);
        var started=new java.util.concurrent.CountDownLatch(1); var pid=new java.util.concurrent.atomic.AtomicInteger();
        try (var held=data.getConnection(); var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            held.setAutoCommit(false);
            try (var statement=held.prepareStatement("select id from trip_correction_plan where id=? for update")) {
                statement.setObject(1,correct[0]); try (var row=statement.executeQuery()) { assertThat(row.next()).isTrue(); }
            }
            var writer=executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class)); started.countDown();
                jdbc.update("insert into trip_correction_reading(replay_id,reading_id,segment_id) values (?,?,?)",correct[1],own,correct[2]);
            }));
            try {
                assertThat(started.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); boolean blocked=false;
                for (int i=0;i<200;i++) {
                    if (Boolean.TRUE.equals(jdbc.queryForObject("select cardinality(pg_blocking_pids(?))>0",Boolean.class,pid.get()))) { blocked=true; break; }
                    Thread.sleep(10);
                }
                assertThat(blocked).isTrue(); assertThat(writer.isDone()).isFalse();
                assertThat(jdbc.queryForList("select id from telemetry_reading where id=? for update skip locked",UUID.class,own)).containsExactly(own);
            } finally { held.rollback(); }
            writer.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("select segment_id from trip_correction_reading where reading_id=?",UUID.class,own)).isEqualTo(correct[2]);
    }
    @Test void correctionOwnershipGuardsPreserveRealOneRowReplayAndPublication() {
        correctedShortPlan(); finishReplayOneRowAtATime(); var raw=jdbc.queryForList("select * from telemetry_reading order by id");
        staged(1); published(1); assertCorrectedShortSummary();
        assertThat(count("trip_evidence")).isEqualTo(27); assertThat(count("trip_visible_record")).isEqualTo(1);
        assertThat(jdbc.queryForList("select * from telemetry_reading order by id")).isEqualTo(raw);
        assertThat(jdbc.queryForObject("select count(*) from trip_correction_reading c join telemetry_replay_identity e on e.reading_id=c.reading_id join trip_correction_replay r on r.id=c.replay_id join trip_correction_plan p on p.id=r.plan_id where e.vehicle_id<>p.vehicle_id",Integer.class)).isZero();
    }
    UUID foreignLinkOwnerTrip() {
        UUID primary=UUID.randomUUID();
        jdbc.update("insert into device(id,vehicle_id,public_id) values (?,?,?)",primary,EMPTY,"link-owner-"+primary);
        return linkOwnerTrip(EMPTY,primary);
    }
    @Test void tripLinkOwnerUpgradeDoesNotRewriteOrCertifyLegacyCrossVehicleLinks() {
        Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration").target("927").load().migrate();
        local(); UUID reading=oldRow(1); UUID foreignTrip=foreignLinkOwnerTrip();
        jdbc.update("insert into trip_reading_link(reading_id,trip_id) values (?,?)",reading,foreignTrip);
        var before=referenceSources(); var generation=jdbc.queryForList("select * from vehicle_read_generation order by vehicle_id");
        var ledger=jdbc.queryForList("select * from telemetry_replay_identity order by reading_id");
        migrate(); migrate();
        assertThat(referenceSources()).isEqualTo(before);
        assertThat(jdbc.queryForList("select * from vehicle_read_generation order by vehicle_id")).isEqualTo(generation);
        assertThat(jdbc.queryForList("select * from telemetry_replay_identity order by reading_id")).isEqualTo(ledger);
        assertThat(jdbc.queryForObject("select count(*) from pg_trigger where tgrelid='trip_reading_link'::regclass and tgname in ('trip_evidence_immutable','trip_link_replay_reference','trip_link_vehicle_owner')",Integer.class)).isEqualTo(3);
        assertThatThrownBy(() -> jdbc.update("update trip_reading_link set trip_id=trip_id where reading_id=?",reading)).hasMessageContaining("matching vehicle ownership");
    }
    @Test void crossVehicleTripLinkAndConflictAttemptRollbackLegacyCapture() {
        legacy(); UUID reading=oldRow(1); migrate(); UUID foreignTrip=foreignLinkOwnerTrip();
        assertThat(count("telemetry_replay_identity")).isZero();
        assertThatThrownBy(() -> jdbc.update("insert into trip_reading_link(reading_id,trip_id) values (?,?)",reading,foreignTrip))
                .hasMessageContaining("matching vehicle ownership");
        assertThat(count("telemetry_replay_identity")).isZero(); assertThat(count("trip_reading_link")).isZero();
        UUID ownTrip=linkOwnerTrip(VEHICLE,DEVICE);
        jdbc.update("insert into trip_reading_link(reading_id,trip_id) values (?,?)",reading,ownTrip);
        var before=referenceSources();
        assertThatThrownBy(() -> jdbc.update("insert into trip_reading_link(reading_id,trip_id) values (?,?) on conflict do nothing",reading,foreignTrip))
                .hasMessageContaining("matching vehicle ownership");
        assertThat(referenceSources()).isEqualTo(before); assertThat(count("telemetry_replay_identity")).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("delete from telemetry_reading where id=?",reading)).hasMessageContaining("foreign key constraint");
    }
    @Test void tripIdOnlyRetargetVerifiesLegacyRawAndRejectsWrongVehicleOrChangedOriginal() {
        at913(); UUID reading=oldRow(1); UUID originalTrip=linkOwnerTrip(VEHICLE,DEVICE);
        jdbc.update("insert into trip_reading_link(reading_id,trip_id) values (?,?)",reading,originalTrip); migrate();
        assertThat(count("telemetry_replay_identity")).isZero();
        UUID nextTrip=linkOwnerTrip(VEHICLE,DEVICE),foreignTrip=foreignLinkOwnerTrip();
        jdbc.update("update trip_reading_link set trip_id=? where reading_id=?",nextTrip,reading);
        assertThat(count("telemetry_replay_identity")).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("update trip_reading_link set trip_id=? where reading_id=?",foreignTrip,reading))
                .hasMessageContaining("matching vehicle ownership");
        jdbc.update("update telemetry_reading set received_at=received_at+interval '1 microsecond' where id=?",reading);
        assertThatThrownBy(() -> jdbc.update("update trip_reading_link set trip_id=? where reading_id=?",originalTrip,reading))
                .hasMessageContaining("does not match original reading");
        assertThat(jdbc.queryForObject("select trip_id from trip_reading_link where reading_id=?",UUID.class,reading)).isEqualTo(nextTrip);
    }
    @Test void tripVehicleCannotBeMovedAfterLinkChecksButOtherProvisionalFieldsRemainMutable() {
        migrate(); local(); UUID reading=oldRow(1); UUID trip=linkOwnerTrip(VEHICLE,DEVICE),foreignTrip=foreignLinkOwnerTrip();
        UUID foreignPrimary=jdbc.queryForObject("select primary_device_id from trip_record where id=?",UUID.class,foreignTrip);
        jdbc.update("insert into trip_reading_link(reading_id,trip_id) values (?,?)",reading,trip);
        var before=referenceSources();
        assertThatThrownBy(() -> jdbc.update("update trip_record set vehicle_id=?,primary_device_id=? where id=?",EMPTY,foreignPrimary,trip))
                .hasMessageContaining("trip vehicle ownership is immutable");
        assertThat(referenceSources()).isEqualTo(before);
        jdbc.update("update trip_record set vehicle_id=vehicle_id,source='OBD' where id=?",trip);
        assertThat(jdbc.queryForObject("select vehicle_id from trip_record where id=?",UUID.class,trip)).isEqualTo(VEHICLE);
        assertThat(jdbc.queryForObject("select source from trip_record where id=?",String.class,trip)).isEqualTo("OBD");
    }
    @Test void tripLinkOwnershipDoesNotBypassOrReplaceFinalGuardErrors() {
        migrate(); local(); commute("short"); drain(processingTime(),100); int sealed=0;
        for (int i=0;i<20 && sealed==0;i++) sealed+=finalizer().finalizePage(VEHICLE,processingTime(),100).finalized();
        assertThat(sealed).isEqualTo(1); var before=referenceSources();
        assertThatThrownBy(() -> jdbc.update("update trip_reading_link set trip_id=trip_id"))
                .hasMessageContaining("finalized trip evidence is immutable");
        assertThatThrownBy(() -> jdbc.update("update trip_record set vehicle_id=? where finalization='FINAL'",EMPTY))
                .hasMessageContaining("finalized trip is immutable");
        assertThat(referenceSources()).isEqualTo(before);
    }
    @Test void tripLinkWaitsForParentBeforeAcquiringOriginalRawLock() throws Exception {
        migrate(); local(); UUID reading=oldRow(1); UUID trip=linkOwnerTrip(VEHICLE,DEVICE);
        var started=new java.util.concurrent.CountDownLatch(1); var pid=new java.util.concurrent.atomic.AtomicInteger();
        try (var held=data.getConnection(); var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            held.setAutoCommit(false);
            try (var statement=held.prepareStatement("select id from trip_record where id=? for update")) {
                statement.setObject(1,trip); try (var row=statement.executeQuery()) { assertThat(row.next()).isTrue(); }
            }
            var writer=executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class)); started.countDown();
                jdbc.update("insert into trip_reading_link(reading_id,trip_id) values (?,?)",reading,trip);
            }));
            try {
                assertThat(started.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); boolean blocked=false;
                for (int i=0;i<200;i++) {
                    if (Boolean.TRUE.equals(jdbc.queryForObject("select cardinality(pg_blocking_pids(?))>0",Boolean.class,pid.get()))) { blocked=true; break; }
                    Thread.sleep(10);
                }
                assertThat(blocked).isTrue(); assertThat(writer.isDone()).isFalse();
                assertThat(jdbc.queryForList("select id from telemetry_reading where id=? for update skip locked",UUID.class,reading)).containsExactly(reading);
            } finally { held.rollback(); }
            writer.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("select trip_id from trip_reading_link where reading_id=?",UUID.class,reading)).isEqualTo(trip);
    }
    int insertWorkFor(UUID id,UUID owner) {
        return jdbc.update("insert into trip_work_item(reading_id,vehicle_id,accepted_at,next_attempt_at) select id,?,received_at,greatest(received_at,recorded_at-interval '2 seconds') from telemetry_reading where id=?",owner,id);
    }
    boolean workVehicleValidated() {
        return Boolean.TRUE.equals(jdbc.queryForObject("select convalidated from pg_constraint where connamespace=current_schema()::regnamespace and conname='trip_work_durable_vehicle'",Boolean.class));
    }
    @Test void workVehicleReferenceUpgradePreservesLegacyMismatchWithoutPretendingValidation() {
        UUID id=wrongVehicleWorkAt926(); var before=referenceSources();
        var ledger=jdbc.queryForList("select * from telemetry_replay_identity order by reading_id");
        var generation=jdbc.queryForList("select * from vehicle_read_generation order by vehicle_id");
        migrate(); migrate();
        assertThat(referenceSources()).isEqualTo(before); assertThat(workVehicleValidated()).isFalse();
        assertThat(jdbc.queryForList("select * from telemetry_replay_identity order by reading_id")).isEqualTo(ledger);
        assertThat(jdbc.queryForList("select * from vehicle_read_generation order by vehicle_id")).isEqualTo(generation);
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where connamespace=current_schema()::regnamespace and conname='replay_vehicle_reading_identity' and contype='u' and convalidated",Integer.class)).isEqualTo(1);
        // Updating non-key status is not proof that historical ownership was validated.
        jdbc.update("update trip_work_item set status='PROCESSED',last_error='legacy fixture' where reading_id=?",id);
        assertThat(jdbc.queryForObject("select vehicle_id from trip_work_item where reading_id=?",UUID.class,id)).isEqualTo(EMPTY);
        assertThat(workVehicleValidated()).isFalse();
    }
    @Test void newWorkCannotBindAnotherVehiclesCanonicalReading() {
        migrate(); local(); UUID id=oldRow(1); jdbc.update("delete from trip_work_item where reading_id=?",id);
        var ledger=jdbc.queryForList("select * from telemetry_replay_identity");
        assertThatThrownBy(() -> insertWorkFor(id,EMPTY)).hasMessageContaining("trip_work_durable_vehicle");
        assertThat(count("trip_work_item")).isZero(); assertThat(workVehicleValidated()).isFalse();
        assertThat(jdbc.queryForList("select * from telemetry_replay_identity")).isEqualTo(ledger);
        assertThat(insertWorkFor(id,VEHICLE)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select vehicle_id from trip_work_item where reading_id=?",UUID.class,id)).isEqualTo(VEHICLE);
    }
    @Test void vehicleOnlyAndReadingOnlyWorkReassignmentsCannotCrossVehicles() {
        migrate(); local(); UUID id=oldRow(1); UUID otherDevice=UUID.randomUUID();
        jdbc.update("insert into device(id,vehicle_id,public_id) values (?,?,'other-work-device')",otherDevice,EMPTY);
        var input=sample(2,Instant.parse("2026-10-20T12:00:02Z")).payload().deepCopy().put("vehicleId","empty-vehicle").put("deviceId","other-work-device");
        var reading=new TelemetryValidator(JSON).validate(input); UUID otherId=UUID.randomUUID();
        jdbc.update("insert into telemetry_reading(id,vehicle_id,device_id,boot_id,sequence_number,recorded_at,source,payload) values (?,?,?,?,?,?,? ,?::jsonb)",otherId,EMPTY,otherDevice,reading.bootId(),new java.math.BigDecimal(reading.sequenceNumber()),Timestamp.from(reading.recordedAt()),"SIMULATED",reading.payload().toString());
        jdbc.update("delete from trip_work_item where reading_id=?",otherId);
        var before=jdbc.queryForMap("select * from trip_work_item where reading_id=?",id);
        assertThatThrownBy(() -> jdbc.update("update trip_work_item set vehicle_id=? where reading_id=?",EMPTY,id)).hasMessageContaining("trip_work_durable_vehicle");
        assertThatThrownBy(() -> jdbc.update("update trip_work_item set reading_id=? where reading_id=?",otherId,id)).hasMessageContaining("trip_work_durable_vehicle");
        assertThat(jdbc.queryForMap("select * from trip_work_item where reading_id=?",id)).isEqualTo(before);
        assertThat(count("telemetry_reading")).isEqualTo(2); assertThat(count("telemetry_replay_identity")).isEqualTo(2);
    }
    @Test void wrongWorkOwnerFailureRollsBackLegacyWriteThroughLedgerCapture() {
        legacy(); UUID id=oldRow(1); migrate(); assertThat(count("telemetry_replay_identity")).isZero();
        var progress=jdbc.queryForMap("select * from trip_backfill_progress where vehicle_id=?",VEHICLE);
        assertThatThrownBy(() -> insertWorkFor(id,EMPTY)).hasMessageContaining("trip_work_durable_vehicle");
        assertThat(count("telemetry_replay_identity")).isZero(); assertThat(count("trip_work_item")).isZero();
        assertThat(jdbc.queryForMap("select * from trip_backfill_progress where vehicle_id=?",VEHICLE)).isEqualTo(progress);
        assertThat(backfill().enqueuePage(VEHICLE,1).queued()).isEqualTo(1);
        assertThat(count("telemetry_replay_identity")).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("delete from telemetry_reading where id=?",id)).hasMessageContaining("foreign key constraint");
    }
    @Test void compositeWorkOwnerValidationIsExplicitAndOnlyRehearsedInDisposableFixture() {
        UUID id=wrongVehicleWorkAt926(); migrate();
        assertThatThrownBy(() -> jdbc.execute("alter table trip_work_item validate constraint trip_work_durable_vehicle")).hasMessageContaining("trip_work_durable_vehicle");
        assertThat(workVehicleValidated()).isFalse();
        // Test-only correction of the deliberately invalid fixture, not an owner repair job.
        jdbc.update("update trip_work_item set vehicle_id=? where reading_id=?",VEHICLE,id);
        jdbc.execute("alter table trip_work_item validate constraint trip_work_durable_vehicle");
        assertThat(workVehicleValidated()).isTrue(); migrate();
        assertThat(workVehicleValidated()).isTrue();
        assertRawReferenceTransition();
    }
    @Test void workConflictNoOpPreservesStoredOwnershipAndDoesNotValidateHistory() {
        migrate(); local(); UUID id=oldRow(1); var before=referenceSources();
        // AFTER INSERT FKs do not check a proposed tuple discarded by ON CONFLICT.
        // The stored row remains correctly owned; BEFORE INSERT raw guards still run.
        assertThat(jdbc.update("insert into trip_work_item(reading_id,vehicle_id,accepted_at,next_attempt_at) select id,?,received_at,received_at from telemetry_reading where id=? on conflict(reading_id) do nothing",EMPTY,id)).isZero();
        assertThat(referenceSources()).isEqualTo(before); assertThat(workVehicleValidated()).isFalse();
        assertThat(jdbc.queryForObject("select vehicle_id from trip_work_item where reading_id=?",UUID.class,id)).isEqualTo(VEHICLE);
    }

    @Test void freshLocalAndLaterLocalSwitchUseExplicitIdempotentSeeding() {
        migrate();
        assertThat(count("vehicle")).isZero();
        local(); local(); migrate(); local();
        assertThat(count("vehicle")).isEqualTo(2);
        assertThat(count("device")).isEqualTo(1);
        assertThat(count("trip_policy")).isEqualTo(1);
        assertThat(count("trip_backfill_progress")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='900'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select primary_device_id from trip_policy where vehicle_id=?", UUID.class, VEHICLE)).isEqualTo(DEVICE);
    }

    @Test void alreadyV900PreservesRowsReceiptsAndChecksumsAndCanRestart() {
        legacy();
        var originalHistory = jdbc.queryForList("select version,checksum from flyway_schema_history where version in ('1','900') order by version");
        UUID row = oldRow(1);
        var original = jdbc.queryForMap("select payload::text,received_at from telemetry_reading where id=?", row);
        migrate(); local(); migrate(); local();
        assertThat(count("telemetry_reading")).isEqualTo(1);
        assertThat(jdbc.queryForMap("select payload::text,received_at from telemetry_reading where id=?", row)).isEqualTo(original);
        assertThat(jdbc.queryForList("select version,checksum from flyway_schema_history where version in ('1','900') order by version")).isEqualTo(originalHistory);
        assertThat(jdbc.queryForObject("select max(version::int) from flyway_schema_history", Integer.class)).isEqualTo(931);
        assertThat(count("trip_work_item")).isZero(); // backfill is bounded work, not migration-time raw scanning
        assertThat(backfill().enqueuePage(VEHICLE, 2)).isEqualTo(new TripBackfill.Page(1, 1, true, false));
        assertThat(jdbc.queryForObject("select accepted_at from trip_work_item where reading_id=?", Timestamp.class, row))
                .isEqualTo(original.get("received_at"));
    }

    @Test void appliedLegacyChecksumStillValidatesInsteadOfBeingIgnored() {
        legacy();
        jdbc.update("update flyway_schema_history set checksum=123 where version='900'");
        assertThatThrownBy(this::migrate).hasMessageContaining("checksum mismatch");
    }

    @Test void acceptedInsertQueuesWorkButDuplicateAndConflictDoNotChangeIt() {
        migrate(); local();
        var reading = sample(1, Instant.parse("2026-10-20T12:00:00Z"));
        assertThat(store(reading)).isEqualTo("accepted");
        var original = jdbc.queryForList("select * from trip_work_item");
        assertThat(store(reading)).isEqualTo("duplicate");
        var changed = reading.payload().deepCopy().put("rpm", 999);
        assertThat(store(new TelemetryValidator(JSON).validate(changed))).isEqualTo("conflict");
        assertThat(jdbc.queryForList("select * from trip_work_item")).isEqualTo(original);
        assertThat(count("telemetry_reading")).isEqualTo(1);
        assertThat(count("trip_work_item")).isEqualTo(1);
    }

    @Test void rollbackCannotLeavePhantomWork() {
        migrate(); local();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(store(sample(1, Instant.parse("2026-10-21T12:00:00.123456Z")))).isEqualTo("accepted");
            assertThat(count("trip_work_item")).isEqualTo(1);
            status.setRollbackOnly();
        });
        assertThat(count("telemetry_reading")).isZero();
        assertThat(count("trip_work_item")).isZero();
    }

    @Test void futureWorkIsDeferredWithoutChangingOriginalReceipt() {
        migrate(); local();
        Instant future = Instant.parse("2099-01-01T00:00:00.123456Z");
        store(sample(1, future));
        assertThat(jdbc.queryForObject("select next_attempt_at from trip_work_item", Timestamp.class).toInstant()).isEqualTo(future.minusSeconds(2));
        assertThat(jdbc.queryForObject("select accepted_at=received_at from trip_work_item join telemetry_reading on reading_id=id", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select next_attempt_at > accepted_at from trip_work_item", Boolean.class)).isTrue();
    }

    @Test void backfillResumesInBoundedPagesAfterServiceRestart() {
        legacy();
        for (int i = 0; i < 7; i++) oldRow(i);
        migrate();
        assertThat(backfill().enqueuePage(VEHICLE, 2)).isEqualTo(new TripBackfill.Page(2, 2, false, false));
        assertThat(count("trip_work_item")).isEqualTo(2);
        // New service instance reads persisted cursor; no process-local progress is needed.
        assertThat(backfill().enqueuePage(VEHICLE, 2)).isEqualTo(new TripBackfill.Page(2, 2, false, false));
        assertThat(backfill().enqueuePage(VEHICLE, 2)).isEqualTo(new TripBackfill.Page(2, 2, false, false));
        assertThat(backfill().enqueuePage(VEHICLE, 2)).isEqualTo(new TripBackfill.Page(1, 1, true, false));
        assertThat(backfill().enqueuePage(VEHICLE, 2)).isEqualTo(new TripBackfill.Page(0, 0, true, false));
        assertThat(count("trip_work_item")).isEqualTo(7);
        assertThat(jdbc.queryForObject("select visited_rows from trip_backfill_progress where vehicle_id=?", Long.class, VEHICLE)).isEqualTo(7L);
    }

    @Test void backfillFailureRollsBackQueuedRowsAndCursorTogether() {
        legacy(); oldRow(1); migrate();
        jdbc.execute("create function fail_backfill() returns trigger language plpgsql as $$ begin raise exception 'test-only checkpoint failure'; end $$");
        jdbc.execute("create trigger fail_backfill before update on trip_backfill_progress for each row execute function fail_backfill()");
        assertThatThrownBy(() -> backfill().enqueuePage(VEHICLE, 1)).hasMessageContaining("checkpoint failure");
        assertThat(count("trip_work_item")).isZero();
        assertThat(jdbc.queryForObject("select last_reading_id from trip_backfill_progress where vehicle_id=?", UUID.class, VEHICLE)).isNull();
        jdbc.execute("drop trigger fail_backfill on trip_backfill_progress");
        assertThat(backfill().enqueuePage(VEHICLE, 2).queued()).isEqualTo(1);
    }

    @Test void anotherWorkerSkipsLockedProgressRatherThanBlocking() throws Exception {
        migrate(); local();
        try (var lock = data.getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement("select * from trip_backfill_progress where vehicle_id=? for update")) {
                statement.setObject(1, VEHICLE);
                try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
            }
            assertThat(backfill().enqueuePage(VEHICLE, 2)).isEqualTo(new TripBackfill.Page(0, 0, false, true));
            lock.rollback();
        }
        assertThat(backfill().enqueuePage(VEHICLE, 2).complete()).isTrue();
    }

    @Test void acceptedInsertBehindBackfillCursorCannotBeLost() {
        legacy(); oldRow(1); oldRow(2); migrate();
        backfill().enqueuePage(VEHICLE, 1);
        UUID behind = UUID.fromString("00000000-0000-0000-0000-000000000001");
        insert(behind, sample(3, Instant.parse("2026-10-21T12:00:00.123456Z")));
        assertThat(count("trip_work_item")).isEqualTo(2);
        while (!backfill().enqueuePage(VEHICLE, 1).complete()) { /* fixture is three rows */ }
        assertThat(count("trip_work_item")).isEqualTo(3);
        assertThat(jdbc.queryForObject("select visited_rows from trip_backfill_progress where vehicle_id=?", Long.class, VEHICLE)).isEqualTo(2L);
    }

    @Test void invalidBackfillBoundsAndWrongVehiclePolicyFailSafely() {
        migrate(); local();
        assertThatThrownBy(() -> backfill().enqueuePage(VEHICLE, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> backfill().enqueuePage(VEHICLE, 501)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> backfill().enqueuePage(UUID.randomUUID(), 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jdbc.update("insert into trip_policy(vehicle_id,primary_device_id) values (?,?)", EMPTY, DEVICE))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(count("trip_policy")).isEqualTo(1);
    }

    @Test void localSeedingDoesNotOverwriteConflictingOwnerIdentity() {
        migrate();
        UUID other = UUID.randomUUID();
        jdbc.update("insert into vehicle(id,public_id) values (?,?)", other, "venue-2021");
        assertThatThrownBy(this::local).hasMessageContaining("not overwritten");
        assertThat(count("vehicle")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select id from vehicle", UUID.class)).isEqualTo(other);
        assertThat(count("device")).isZero();
        assertThat(count("trip_policy")).isZero();
    }

    TripProjector projector() { return new TripProjector(jdbc, JSON, transactions, backfill()); }
    Instant processingTime() {
        // Receipt times are assigned by PostgreSQL. Anchor injected processing
        // time after them so these tests do not expire on a fixed calendar date.
        return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant()
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS).plus(java.time.Duration.ofDays(1));
    }
    void drain(Instant asOf, int pageSize) {
        for (int page = 0; page < 1000; page++) {
            var result = projector().processPage(VEHICLE, asOf, pageSize);
            assertThat(result.state()).isIn("BACKFILLING", "MORE", "CAUGHT_UP");
            if (result.state().equals("CAUGHT_UP")) return;
        }
        fail("fixture did not finish within 1000 bounded calls");
    }
    void commute(String name) {
        for (var reading : JSON.readTree(Path.of("../contracts/examples/trip-" + name + "-input.json").toFile()).get("readings"))
            store(new TelemetryValidator(JSON).validate(reading));
    }

    @Test void projectionResumesPerPageAndMatchesHandCalculatedFullCommute() {
        migrate(); local(); commute("normal");
        drain(processingTime(), 7);
        var trip = jdbc.queryForMap("select * from trip_record where status='COMPLETED'");
        assertThat(trip.get("completion_reason")).isEqualTo("ENGINE_OFF");
        assertThat(((Timestamp) trip.get("started_at")).toInstant()).isEqualTo(Instant.parse("2026-10-20T12:00:10Z"));
        assertThat(((Timestamp) trip.get("ended_at")).toInstant()).isEqualTo(Instant.parse("2026-10-20T12:08:10Z"));
        var summary = JSON.readTree(trip.get("summary").toString());
        assertThat(summary.get("sampleCount").intValue()).isEqualTo(241);
        assertThat(summary.get("speedAreaKphSeconds").decimalValue()).isEqualByComparingTo("23400");
        assertThat(summary.get("metrics").get("rpm").get("sum").decimalValue()).isEqualByComparingTo("506050");
        assertThat(count("trip_evidence")).isEqualTo(241);
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item where status='PENDING'", Integer.class)).isZero();
        var before = jdbc.queryForList("select * from trip_record");
        commute("normal"); drain(processingTime(), 1);
        assertThat(jdbc.queryForList("select * from trip_record")).isEqualTo(before);
    }

    @Test void projectionHandlesEveryReadingInItsOwnPageWithoutPrematureHistoricalTimeout() {
        migrate(); local(); commute("short");
        drain(processingTime(), 1);
        assertThat(jdbc.queryForObject("select count(*) from trip_record where status='COMPLETED'", Integer.class)).isEqualTo(1);
        var summary = JSON.readTree(jdbc.queryForObject("select summary::text from trip_record where status='COMPLETED'", String.class));
        assertThat(summary.get("sampleCount").intValue()).isEqualTo(27);
        assertThat(summary.get("speedAreaKphSeconds").decimalValue()).isEqualByComparingTo("1760");
        assertThat(summary.get("coveredSeconds").decimalValue()).isEqualByComparingTo("52");
        assertThat(count("trip_evidence")).isEqualTo(27);
    }

    @Test void timerAfterServiceRestartClosesAtLastRunningAndIgnoresFutureOutlier() {
        migrate(); local();
        Instant base = processingTime();
        store(sample(0, base)); store(sample(1, base.plusSeconds(2)));
        store(sample(2, Instant.parse("2099-01-01T00:00:00Z")));
        drain(base.plusSeconds(2), 2);
        UUID id = jdbc.queryForObject("select id from trip_record where status='ACTIVE'", UUID.class);
        projector().processPage(VEHICLE, base.plusSeconds(31), 2);
        assertThat(jdbc.queryForObject("select status from trip_record where id=?", String.class, id)).isEqualTo("ACTIVE");
        projector().processPage(VEHICLE, base.plusSeconds(32), 2);
        assertThat(jdbc.queryForObject("select completion_reason from trip_record where id=?", String.class, id)).isEqualTo("SIGNAL_TIMEOUT");
        assertThat(jdbc.queryForObject("select ended_at from trip_record where id=?", Timestamp.class, id).toInstant()).isEqualTo(base.plusSeconds(2));
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item where status='PENDING'", Integer.class)).isEqualTo(1);
    }

    @Test void projectionFailureRollsBackTripsLinksAcknowledgmentsAndCheckpointTogether() {
        migrate(); local(); commute("short");
        while (!backfill().enqueuePage(VEHICLE, 100).complete()) { /* bounded fixture */ }
        jdbc.execute("create function fail_projection() returns trigger language plpgsql as $$ begin raise exception 'test-only projection failure'; end $$");
        jdbc.execute("create trigger fail_projection before update on trip_projection_progress for each row execute function fail_projection()");
        assertThatThrownBy(() -> projector().processPage(VEHICLE, processingTime(), 100))
                .hasMessageContaining("projection failure");
        assertThat(count("trip_record")).isZero(); assertThat(count("trip_reading_link")).isZero();
        assertThat(count("trip_projection_progress")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item where status='PROCESSED'", Integer.class)).isZero();
        jdbc.execute("drop trigger fail_projection on trip_projection_progress");
        drain(processingTime(), 100);
        assertThat(jdbc.queryForObject("select count(*) from trip_record where status='COMPLETED'", Integer.class)).isEqualTo(1);
    }

    @Test void lateInputIsRetainedAndExplicitlyReviewedUntilReconciliationIsImplemented() {
        migrate(); local(); commute("short");
        Instant asOf = processingTime(); drain(asOf, 100);
        var original = jdbc.queryForList("select * from trip_record");
        store(sample(999, Instant.parse("2026-10-20T12:00:10Z")));
        assertThat(projector().processPage(VEHICLE, asOf, 100).reviewed()).isEqualTo(1);
        assertThat(jdbc.queryForList("select * from trip_record")).isEqualTo(original);
        assertThat(jdbc.queryForObject("select needs_review from trip_projection_progress", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select last_error from trip_work_item where status='REVIEW'", String.class)).isEqualTo("LATE_DATA_UNAPPLIED");
        assertThat(count("telemetry_reading")).isEqualTo(32);
    }

    @Test void canonicalTieAcrossPageBoundaryCountsOnlyWinnerAndRetainsRawTies() {
        migrate(); local();
        Instant base = processingTime();
        var first = sample(0, base).payload().put("rpm", 0);
        store(new TelemetryValidator(JSON).validate(first));
        store(sample(1, base)); store(sample(2, base.plusSeconds(2)));
        drain(base.plusSeconds(2), 1);
        assertThat(count("telemetry_reading")).isEqualTo(3);
        assertThat(count("trip_evidence")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select status from trip_record", String.class)).isEqualTo("ACTIVE");
        var summary = JSON.readTree(jdbc.queryForObject("select summary::text from trip_record", String.class));
        assertThat(summary.get("sampleCount").intValue()).isEqualTo(2);
    }

    @Test void unconfirmedCandidateIsDiscardedAndAnotherVehicleHasNoAutomaticPrimary() {
        migrate(); local();
        Instant base = processingTime();
        store(sample(0, base));
        drain(base.plusSeconds(6), 100);
        assertThat(jdbc.queryForObject("select status from trip_record", String.class)).isEqualTo("DISCARDED");
        assertThat(count("trip_evidence")).isZero();
        assertThat(projector().processPage(EMPTY, base.plusSeconds(6), 100).state()).isEqualTo("NO_PRIMARY_DEVICE");
        assertThatThrownBy(() -> projector().processPage(VEHICLE, base, 101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void workerClaimProtectsInitialProgressAndDatabasePreventsTwoOpenTrips() throws Exception {
        migrate(); local();
        Instant base = processingTime();
        store(sample(0, base)); store(sample(1, base.plusSeconds(2)));
        backfill().enqueuePage(VEHICLE, 100);
        try (var lock = data.getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement("select pg_advisory_xact_lock(hashtextextended(cast(? as text),902))")) {
                statement.setString(1, VEHICLE.toString()); statement.execute();
            }
            assertThat(projector().processPage(VEHICLE, base.plusSeconds(2), 100).state()).isEqualTo("BUSY");
            assertThat(count("trip_projection_progress")).isZero();
            lock.rollback();
        }
        drain(base.plusSeconds(2), 100);
        assertThatThrownBy(() -> jdbc.update("""
                insert into trip_record(id,vehicle_id,primary_device_id,anchor_boot,anchor_sequence,started_at,
                observed_through,source,status,algorithm_version,summary,quality)
                select ?,vehicle_id,primary_device_id,anchor_boot,anchor_sequence+99,started_at,
                observed_through,source,status,algorithm_version,summary,quality from trip_record where status='ACTIVE'
                """, UUID.randomUUID())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("update trip_policy set revision=revision+1 where vehicle_id=?", VEHICLE);
        assertThat(projector().processPage(VEHICLE, base.plusSeconds(2), 100).state()).isEqualTo("POLICY_CHANGED");
        assertThat(jdbc.queryForObject("select needs_review from trip_projection_progress", Boolean.class)).isTrue();
    }

    @Test void tinyTripPersistsExactExpectedMetricsAndClosedEndEvidence() {
        migrate(); local();
        Instant base = Instant.parse("2026-10-20T12:00:00Z");
        var fixture = JSON.readTree(Path.of("../contracts/examples/trip-rule-fixtures.json").toFile()).get("tiny");
        int index = 0;
        for (var item : fixture.get("readings")) {
            var payload = sample(index++, base.plusSeconds(item.get("offsetSeconds").longValue())).payload();
            for (String field : dev.autopulse.trip.TripRules.METRICS)
                payload.set(field, item.has(field) ? item.get(field) : JSON.nullNode());
            store(new TelemetryValidator(JSON).validate(payload));
        }
        drain(processingTime(), 2);
        var summary = JSON.readTree(jdbc.queryForObject("select summary::text from trip_record where status='COMPLETED'", String.class));
        assertThat(summary.get("sampleCount").intValue()).isEqualTo(4);
        assertThat(summary.get("speedAreaKphSeconds").decimalValue()).isEqualByComparingTo("144");
        assertThat(summary.get("coveredSeconds").decimalValue()).isEqualByComparingTo("6");
        assertThat(summary.get("metrics").get("coolantTempC").get("sum").decimalValue()).isEqualByComparingTo("110");
        assertThat(summary.get("metrics").get("coolantTempC").get("count").intValue()).isEqualTo(3);
        assertThat(summary.get("metrics").get("mafGps").get("sum").isNull()).isTrue();
        assertThat(count("trip_evidence")).isEqualTo(4);
    }

    TripFinalizer finalizer() { return new TripFinalizer(jdbc, transactions); }
    Instant receipt() { return jdbc.queryForObject("select last_eligible_receipt_at from trip_vehicle_activity where vehicle_id=?", Timestamp.class, VEHICLE).toInstant(); }
    TripFinalizer.Page finishActivity(Instant asOf, int limit) {
        for (int page = 0; page < 1000; page++) {
            var result = finalizer().finalizePage(VEHICLE, asOf, limit);
            if (!result.state().equals("BACKFILLING_ACTIVITY")) return result;
            assertThat(result.scanned()).isLessThanOrEqualTo(limit);
        }
        throw new AssertionError("activity fixture exceeded 1000 pages");
    }

    @Test void finalizationWaitsExactReceiptQuietPeriodAndThenIsIdempotentAndImmutable() {
        migrate(); local(); commute("short"); drain(processingTime(), 100);
        Instant last = receipt();
        var original = jdbc.queryForMap("select * from trip_record where status='COMPLETED'");
        assertThat(finishActivity(last.plusSeconds(299), 3).state()).isEqualTo("QUIET_PERIOD");
        assertThat(finalizer().finalizePage(VEHICLE, last.plusSeconds(300), 1).finalized()).isEqualTo(1);
        var sealed = jdbc.queryForMap("select * from trip_record where status='COMPLETED'");
        assertThat(sealed.get("finalization")).isEqualTo("FINAL");
        assertThat(((Number) sealed.get("revision")).longValue()).isEqualTo(((Number) original.get("revision")).longValue() + 1);
        commute("short"); drain(processingTime(), 100);
        assertThat(receipt()).isEqualTo(last);
        assertThat(finalizer().finalizePage(VEHICLE, last.plusSeconds(600), 1).finalized()).isZero();
        assertThat(jdbc.queryForMap("select * from trip_record where status='COMPLETED'")).isEqualTo(sealed);
        assertThatThrownBy(() -> jdbc.update("update trip_record set summary='{}'::jsonb where status='COMPLETED'"))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("delete from trip_record where status='COMPLETED'"))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("delete from trip_reading_link where trip_id=?", sealed.get("id")))
                .hasMessageContaining("immutable");
        assertThat(count("trip_evidence")).isEqualTo(27);
    }

    @Test void pendingAndReviewWorkPreventSealingEvenAfterQuietPeriod() {
        migrate(); local(); commute("short"); drain(processingTime(), 100);
        store(sample(999, Instant.parse("2026-10-20T12:00:10Z")));
        Instant last = receipt();
        assertThat(finishActivity(last.plusSeconds(300), 100).state()).isEqualTo("PENDING_WORK");
        drain(processingTime(), 100);
        assertThat(finalizer().finalizePage(VEHICLE, last.plusSeconds(300), 100).state()).isEqualTo("REVIEW_REQUIRED");
        assertThat(jdbc.queryForObject("select finalization from trip_record where status='COMPLETED'", String.class)).isEqualTo("PROVISIONAL");
    }

    @Test void futureAndSecondaryInsertionsDoNotRestartPrimaryQuietPeriod() {
        migrate(); local(); commute("short"); drain(processingTime(), 100);
        Instant last = receipt();
        store(sample(999, Instant.parse("2099-01-01T00:00:00Z")));
        UUID secondary = UUID.randomUUID();
        jdbc.update("insert into device(id,vehicle_id,public_id) values (?,?,?)", secondary, VEHICLE, "secondary-test");
        var payload = sample(1000, Instant.parse("2026-10-20T12:00:10Z")).payload().put("deviceId", "secondary-test");
        new TelemetryRepository(new NamedParameterJdbcTemplate(jdbc), JSON).store(VEHICLE, secondary, new TelemetryValidator(JSON).validate(payload));
        assertThat(receipt()).isEqualTo(last);
        drain(processingTime(), 100); // secondary acknowledged; future stays deferred
        assertThat(finishActivity(last.plusSeconds(300), 100).finalized()).isEqualTo(1);
        assertThat(receipt()).isEqualTo(last);
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item where status='PENDING'", Integer.class)).isEqualTo(1);
    }

    @Test void postFinalizationLateDataStaysRawAndCannotChangeSealedTrip() {
        migrate(); local(); commute("short"); drain(processingTime(), 100);
        finishActivity(receipt().plusSeconds(300), 100);
        var original = jdbc.queryForMap("select * from trip_record where status='COMPLETED'");
        store(sample(999, Instant.parse("2026-10-20T12:00:10Z")));
        drain(processingTime(), 100);
        assertThat(jdbc.queryForMap("select * from trip_record where status='COMPLETED'")).isEqualTo(original);
        assertThat(count("telemetry_reading")).isEqualTo(32);
        assertThat(jdbc.queryForObject("select last_error from trip_work_item where status='REVIEW'", String.class)).isEqualTo("LATE_DATA_UNAPPLIED");
    }

    @Test void finalizationFailureRollsBackTheFinalFlagAndRevision() {
        migrate(); local(); commute("short"); drain(processingTime(), 100);
        Instant last = receipt(); finishActivity(last.plusSeconds(299), 100);
        var original = jdbc.queryForMap("select * from trip_record where status='COMPLETED'");
        jdbc.execute("create function fail_finalization() returns trigger language plpgsql as $$ begin raise exception 'test-only seal failure'; end $$");
        jdbc.execute("create trigger fail_finalization before update on trip_record for each row execute function fail_finalization()");
        assertThatThrownBy(() -> finalizer().finalizePage(VEHICLE, last.plusSeconds(300), 100)).hasMessageContaining("seal failure");
        assertThat(jdbc.queryForMap("select * from trip_record where status='COMPLETED'")).isEqualTo(original);
        jdbc.execute("drop trigger fail_finalization on trip_record");
        assertThat(finalizer().finalizePage(VEHICLE, last.plusSeconds(300), 100).finalized()).isEqualTo(1);
    }

    @Test void sealingSharesWorkerLockAndReceiptBarrier() throws Exception {
        migrate(); local(); commute("short"); drain(processingTime(), 100);
        Instant last = receipt(); finishActivity(last.plusSeconds(299), 100);
        try (var lock = data.getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement("select pg_advisory_xact_lock(hashtextextended(cast(? as text),902))")) {
                statement.setString(1, VEHICLE.toString()); statement.execute();
            }
            assertThat(finalizer().finalizePage(VEHICLE, last.plusSeconds(300), 100).state()).isEqualTo("BUSY");
            lock.rollback();
        }
        // A separately committed eligible receipt wins before sealing: it cannot
        // disappear behind a stale in-process copy of the quiet horizon.
        store(sample(999, Instant.parse("2026-10-20T12:02:00Z")));
        assertThat(finalizer().finalizePage(VEHICLE, last.plusSeconds(300), 100).state()).isEqualTo("PENDING_WORK");
    }

    @Test void upgradeRecoversPreviouslyProcessedReceiptHorizonInBoundedPages() {
        Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration").target("902").load().migrate();
        local(); for (int index=0; index<7; index++) oldRow(index);
        jdbc.update("update trip_work_item set status='PROCESSED'");
        Timestamp original = jdbc.queryForObject("select max(accepted_at) from trip_work_item", Timestamp.class);
        migrate();
        assertThat(jdbc.queryForObject("select last_eligible_receipt_at from trip_vehicle_activity where vehicle_id=?", Timestamp.class, VEHICLE)).isNull();
        assertThat(finalizer().finalizePage(VEHICLE, processingTime(), 2).scanned()).isEqualTo(2);
        assertThat(finalizer().finalizePage(VEHICLE, processingTime(), 2).scanned()).isEqualTo(2);
        assertThat(finalizer().finalizePage(VEHICLE, processingTime(), 2).scanned()).isEqualTo(2);
        assertThat(finalizer().finalizePage(VEHICLE, processingTime(), 2).scanned()).isEqualTo(1);
        assertThat(receipt()).isEqualTo(original.toInstant());
        assertThat(finalizer().finalizePage(VEHICLE, processingTime(), 2).state()).isEqualTo("PROJECTION_NOT_READY");
        assertThatThrownBy(() -> finalizer().finalizePage(VEHICLE, processingTime(), 101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void newAcceptedReceiptWaitsForFinalizationBarrierBeforeItCanCommit() throws Exception {
        migrate(); local(); commute("short"); drain(processingTime(), 100);
        Instant last = receipt(); finishActivity(last.plusSeconds(299), 100);
        var backend = new java.util.concurrent.atomic.AtomicInteger();
        var started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CompletableFuture<String> insert;
        // Hold exactly the activity row that finalization locks. The other real
        // connection must wait inside the insert trigger, not commit invisibly.
        try (var barrier = data.getConnection()) {
            barrier.setAutoCommit(false);
            try (var statement = barrier.prepareStatement("select * from trip_vehicle_activity where vehicle_id=? for update")) {
                statement.setObject(1, VEHICLE); statement.execute();
            }
            insert = java.util.concurrent.CompletableFuture.supplyAsync(() -> new TransactionTemplate(transactions).execute(status -> {
                backend.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); started.countDown();
                return store(sample(999, Instant.parse("2026-10-20T12:02:00Z")));
            }));
            try {
                assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    waiting = Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",
                            Boolean.class, backend.get()));
                    if (waiting) break;
                    Thread.sleep(20);
                }
                assertThat(waiting).as("accepted insert waits on the receipt barrier").isTrue();
                assertThat(insert.isDone()).isFalse();
                assertThat(count("telemetry_reading")).isEqualTo(31);
            } finally { barrier.rollback(); }
        }
        assertThat(insert.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("accepted");
        assertThat(count("telemetry_reading")).isEqualTo(32);
        assertThat(finalizer().finalizePage(VEHICLE, receipt().plusSeconds(300), 100).state()).isEqualTo("PENDING_WORK");
    }

    TripCorrectionPlanner planner() { return new TripCorrectionPlanner(jdbc, transactions); }
    TripCorrectionPlanner.Page planned(int limit) {
        for (int page=0; page<1000; page++) {
            var result = planner().planPage(VEHICLE, processingTime(), limit);
            assertThat(result.visited()).isLessThanOrEqualTo(limit);
            if (!java.util.Set.of("TRIPS","INPUTS").contains(result.state())) return result;
        }
        throw new AssertionError("correction fixture exceeded 1000 bounded pages");
    }
    void lateShort() {
        store(sample(999, Instant.parse("2026-10-20T12:00:10Z")));
        drain(processingTime(), 100);
    }

    @Test void correctionPlanningResumesAcrossPagesWithoutChangingSavedTripsOrEvidence() {
        migrate(); local(); commute("short"); drain(processingTime(), 100); lateShort();
        var trips = jdbc.queryForList("select * from trip_record");
        var links = jdbc.queryForList("select * from trip_reading_link order by reading_id");
        var first = planner().planPage(VEHICLE, processingTime(), 1);
        assertThat(first.state()).isEqualTo("TRIPS");
        UUID id = first.planId();
        assertThat(projector().processPage(VEHICLE, processingTime(), 100).state()).isEqualTo("CORRECTION_PENDING");
        assertThat(finalizer().finalizePage(VEHICLE, processingTime(), 100).state()).isEqualTo("CORRECTION_PENDING");
        var result = planned(1); // a fresh planner instance on every page
        assertThat(result.state()).isEqualTo("READY"); assertThat(result.planId()).isEqualTo(id);
        assertThat(result.from()).isEqualTo(Instant.parse("2026-10-20T12:00:04Z"));
        assertThat(result.to()).isEqualTo(Instant.parse("2026-10-20T12:00:56Z"));
        assertThat(planner().planPage(VEHICLE, processingTime(), 1).planId()).isEqualTo(id);
        assertThat(count("trip_correction_plan")).isEqualTo(1);
        assertThat(jdbc.queryForList("select * from trip_record")).isEqualTo(trips);
        assertThat(jdbc.queryForList("select * from trip_reading_link order by reading_id")).isEqualTo(links);
        assertThat(jdbc.queryForObject("select status from trip_work_item where reading_id=(select requested_work_id from trip_correction_plan)", String.class)).isEqualTo("REVIEW");
    }

    @Test void plannerRejectsFinalizedOverlapWithoutTouchingProtectedTrip() {
        migrate(); local(); commute("short"); drain(processingTime(), 100);
        finishActivity(receipt().plusSeconds(300), 100);
        var original = jdbc.queryForMap("select * from trip_record where status='COMPLETED'");
        lateShort();
        assertThat(planned(1).state()).isEqualTo("REVIEW_FINALIZED");
        assertThat(jdbc.queryForMap("select * from trip_record where status='COMPLETED'")).isEqualTo(original);
        assertThat(count("trip_evidence")).isEqualTo(27);
        assertThat(planner().planPage(VEHICLE, processingTime(), 1).state()).isEqualTo("NO_UNPLANNED_REVIEW");
    }

    @Test void plannerEscalatesOverwideLegacySpanRatherThanScanningWithoutLimit() {
        migrate(); local(); commute("short"); drain(processingTime(), 100); lateShort();
        // A deliberately oversized legacy summary is outside the accepted rule
        // horizon; discovery must not turn it into an automatic history rebuild.
        jdbc.update("update trip_record set started_at=started_at-interval '49 hours' where status='COMPLETED'");
        assertThat(planned(2).state()).isEqualTo("REVIEW_TOO_WIDE");
        assertThat(jdbc.queryForObject("select needs_review from trip_projection_progress", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select status from trip_work_item where reading_id=(select requested_work_id from trip_correction_plan)", String.class)).isEqualTo("REVIEW");
    }

    @Test void plannerIncludesConnectingLateInputButIgnoresOtherVehicleAndFutureInput() {
        migrate(); local(); commute("short"); drain(processingTime(), 100); lateShort();
        // New primary readings can extend the connected halo while planning is
        // paused. They remain pending, not falsely acknowledged by the planner.
        store(sample(1000, Instant.parse("2026-10-20T12:01:20Z")));
        store(sample(1001, Instant.parse("2099-01-01T00:00:00Z")));
        store(sample(1002, Instant.parse("2026-10-20T12:01:45Z")));
        store(sample(1003, Instant.parse("2026-10-20T12:03:20Z")));
        var result = planned(1);
        assertThat(result.state()).isEqualTo("READY");
        assertThat(result.to()).isEqualTo(Instant.parse("2026-10-20T12:01:45Z"));
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item where status='PENDING'", Integer.class)).isEqualTo(4);
        assertThat(planner().planPage(EMPTY, processingTime(), 1).state()).isEqualTo("NO_PRIMARY_DEVICE");
    }

    @Test void policyChangeEscalatesExistingPlanAndPlanningFailureRollsBackItsPage() {
        migrate(); local(); commute("short"); drain(processingTime(), 100); lateShort();
        var first = planner().planPage(VEHICLE, processingTime(), 1);
        var before = jdbc.queryForMap("select * from trip_correction_plan where id=?", first.planId());
        jdbc.execute("create function fail_correction_plan() returns trigger language plpgsql as $$ begin raise exception 'test-only plan failure'; end $$");
        jdbc.execute("create trigger fail_correction_plan before update on trip_correction_plan for each row execute function fail_correction_plan()");
        assertThatThrownBy(() -> planner().planPage(VEHICLE, processingTime(), 1)).hasMessageContaining("plan failure");
        assertThat(jdbc.queryForMap("select * from trip_correction_plan where id=?", first.planId())).isEqualTo(before);
        jdbc.execute("drop trigger fail_correction_plan on trip_correction_plan");
        jdbc.update("update trip_policy set revision=revision+1 where vehicle_id=?", VEHICLE);
        assertThat(planner().planPage(VEHICLE, processingTime(), 1).state()).isEqualTo("REVIEW_POLICY_CHANGED");
        assertThatThrownBy(() -> planner().planPage(VEHICLE, processingTime(), 101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void timeoutConfirmationClockDoesNotInflateCorrectionMeasurementSpan() {
        migrate(); local();
        Instant base = Instant.parse("2026-10-20T12:00:00Z");
        store(sample(0, base)); store(sample(1, base.plusSeconds(2)));
        drain(processingTime(), 100); // timer confirmation is days after the event
        store(sample(999, base.plusSeconds(1))); drain(processingTime(), 100);
        var result = planned(1);
        assertThat(result.state()).isEqualTo("READY");
        assertThat(result.from()).isEqualTo(base); assertThat(result.to()).isEqualTo(base.plusSeconds(2));
        assertThat(jdbc.queryForObject("select completion_reason from trip_record where status='COMPLETED'", String.class)).isEqualTo("SIGNAL_TIMEOUT");
    }

    TripCorrectionReplayer replayer() { return new TripCorrectionReplayer(jdbc, JSON, transactions); }
    TripCorrectionReplayer.Page staged(int limit) {
        for (int page=0; page<1000; page++) {
            var result = replayer().replayPage(VEHICLE, limit);
            assertThat(result.visited()).isLessThanOrEqualTo(limit);
            if (!result.state().equals("RUNNING")) return result;
        }
        throw new AssertionError("replay fixture exceeded 1000 bounded pages");
    }
    void override(int sequence, int offset, int rpm, int speed) {
        var payload = sample(sequence, Instant.parse("2026-10-20T12:00:00Z").plusSeconds(offset)).payload()
                .put("bootId", "123e4567-e89b-42d3-a456-426614174000").put("rpm", rpm).put("speedKph", speed);
        store(new TelemetryValidator(JSON).validate(payload));
    }
    void correctedShortPlan() {
        migrate(); local(); commute("short"); drain(processingTime(), 100);
        override(999,10,1900,12); drain(processingTime(),100);
        assertThat(planned(1).state()).isEqualTo("READY");
    }

    @Test void replayStagesCorrectedExactTotalsAcrossRestartsWithoutPublishingAnything() {
        correctedShortPlan();
        var old = jdbc.queryForList("select * from trip_record");
        var links = jdbc.queryForList("select * from trip_reading_link order by reading_id");
        var result = staged(1);
        assertThat(result.state()).isEqualTo("STAGED");
        var summary = JSON.readTree(jdbc.queryForObject("select summary::text from trip_correction_segment where status='COMPLETED'", String.class));
        // At second 10 the old cold-idle point is 937.5 RPM / 0 km/h.
        // Replacing it with 1900 RPM / 12 km/h adds 962.5 RPM to the sum,
        // and 2*(0+12)/2 + 2*(12+0)/2 = 24 to the speed area.
        assertThat(summary.get("sampleCount").intValue()).isEqualTo(27);
        assertThat(summary.get("metrics").get("rpm").get("sum").decimalValue()).isEqualByComparingTo("47237.5");
        assertThat(summary.get("speedAreaKphSeconds").decimalValue()).isEqualByComparingTo("1784");
        assertThat(summary.get("coveredSeconds").decimalValue()).isEqualByComparingTo("52");
        assertThat(count("trip_correction_evidence")).isEqualTo(27);
        assertThat(jdbc.queryForList("select * from trip_record")).isEqualTo(old);
        assertThat(jdbc.queryForList("select * from trip_reading_link order by reading_id")).isEqualTo(links);
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item where status='REVIEW'", Integer.class)).isEqualTo(1);
        var checkpoint = jdbc.queryForMap("select * from trip_correction_replay where id=?", result.replayId());
        assertThat(replayer().replayPage(VEHICLE,100).replayId()).isEqualTo(result.replayId());
        assertThat(jdbc.queryForMap("select * from trip_correction_replay where id=?", result.replayId())).isEqualTo(checkpoint);
    }

    @Test void failedReplayPageRollsBackSegmentsLinksAndCheckpointTogether() {
        correctedShortPlan(); var first = replayer().replayPage(VEHICLE,3);
        var before = jdbc.queryForMap("select * from trip_correction_replay where id=?", first.replayId());
        var segments = jdbc.queryForList("select * from trip_correction_segment order by id");
        var links = jdbc.queryForList("select * from trip_correction_reading order by reading_id");
        jdbc.execute("create function fail_replay() returns trigger language plpgsql as $$ begin raise exception 'test-only replay failure'; end $$");
        jdbc.execute("create trigger fail_replay before update on trip_correction_replay for each row execute function fail_replay()");
        assertThatThrownBy(() -> replayer().replayPage(VEHICLE,3)).hasMessageContaining("replay failure");
        assertThat(jdbc.queryForMap("select * from trip_correction_replay where id=?", first.replayId())).isEqualTo(before);
        assertThat(jdbc.queryForList("select * from trip_correction_segment order by id")).isEqualTo(segments);
        assertThat(jdbc.queryForList("select * from trip_correction_reading order by reading_id")).isEqualTo(links);
        jdbc.execute("drop trigger fail_replay on trip_correction_replay");
        assertThat(staged(2).replayId()).isEqualTo(first.replayId());
    }

    @Test void relevantArrivalInvalidatesReplayAndReplansBeforeRestarting() {
        correctedShortPlan(); var first = replayer().replayPage(VEHICLE,3);
        override(1000,76,1900,12); // inside the current right halo, extends the core
        assertThat(replayer().replayPage(VEHICLE,3).state()).isEqualTo("REPLAN_REQUIRED");
        assertThat(jdbc.queryForObject("select status from trip_correction_replay where id=?", String.class, first.replayId())).isEqualTo("INVALIDATED");
        assertThat(jdbc.queryForObject("select count(*) from trip_correction_evidence where replay_id=?", Integer.class, first.replayId())).isZero();
        var plan = planned(1); assertThat(plan.to()).isEqualTo(Instant.parse("2026-10-20T12:01:16Z"));
        var next = staged(2); assertThat(next.state()).isEqualTo("STAGED");
        assertThat(next.replayId()).isNotEqualTo(first.replayId());
        assertThat(count("trip_correction_replay")).isEqualTo(2); // old attempt retained, not mass-deleted
    }

    @Test void completedStageIsRevalidatedWhenAnotherRelevantReadingArrives() {
        correctedShortPlan(); var first = staged(100);
        override(1000,20,2000,30);
        assertThat(replayer().replayPage(VEHICLE,100).state()).isEqualTo("REPLAN_REQUIRED");
        assertThat(jdbc.queryForObject("select status from trip_correction_replay where id=?", String.class, first.replayId())).isEqualTo("INVALIDATED");
        assertThat(planned(1).state()).isEqualTo("READY");
        assertThat(staged(1).replayId()).isNotEqualTo(first.replayId());
    }

    @Test void unrelatedFutureAndSecondaryInputDoNotInvalidateRelevantReplay() {
        correctedShortPlan(); var first = replayer().replayPage(VEHICLE,3);
        long generation = jdbc.queryForObject("select ingest_generation from trip_vehicle_activity where vehicle_id=?", Long.class, VEHICLE);
        override(1000,200,1900,12); // outside connected halo
        store(sample(1001,Instant.parse("2099-01-01T00:00:00Z")));
        UUID secondary = UUID.randomUUID();
        jdbc.update("insert into device(id,vehicle_id,public_id) values (?,?,?)",secondary,VEHICLE,"replay-secondary");
        var payload = sample(1002,Instant.parse("2026-10-20T12:00:20Z")).payload().put("deviceId","replay-secondary");
        new TelemetryRepository(new NamedParameterJdbcTemplate(jdbc),JSON).store(VEHICLE,secondary,new TelemetryValidator(JSON).validate(payload));
        assertThat(jdbc.queryForObject("select ingest_generation from trip_vehicle_activity where vehicle_id=?",Long.class,VEHICLE)).isEqualTo(generation);
        assertThat(staged(3).replayId()).isEqualTo(first.replayId());
    }

    @Test void policyAndFinalizationChangesInvalidateRatherThanPublishingStaleStage() {
        correctedShortPlan(); var first = replayer().replayPage(VEHICLE,3);
        jdbc.update("update trip_policy set revision=revision+1 where vehicle_id=?",VEHICLE);
        assertThat(replayer().replayPage(VEHICLE,3).state()).isEqualTo("REVIEW_POLICY_CHANGED");
        assertThat(jdbc.queryForObject("select status from trip_correction_replay where id=?",String.class,first.replayId())).isEqualTo("INVALIDATED");
        assertThat(jdbc.queryForObject("select count(*) from trip_record where status='COMPLETED'",Integer.class)).isEqualTo(1);
    }

    @Test void finalizedOverlapRecheckedAfterPlanningAndBeforeEachReplayPage() {
        correctedShortPlan(); var first = replayer().replayPage(VEHICLE,3);
        // Deliberate external/manual change in the disposable fixture; the normal
        // finalizer is already paused by the active correction plan.
        jdbc.update("update trip_record set finalization='FINAL' where status='COMPLETED'");
        assertThat(replayer().replayPage(VEHICLE,3).state()).isEqualTo("REVIEW_FINALIZED");
        assertThat(jdbc.queryForObject("select status from trip_correction_replay where id=?",String.class,first.replayId())).isEqualTo("INVALIDATED");
        assertThat(count("trip_evidence")).isEqualTo(27);
    }

    @Test void rolledBackArrivalCannotInvalidateReplayAndBoundsRemainStrict() {
        correctedShortPlan(); var first = replayer().replayPage(VEHICLE,3);
        long generation = jdbc.queryForObject("select ingest_generation from trip_vehicle_activity where vehicle_id=?",Long.class,VEHICLE);
        new TransactionTemplate(transactions).executeWithoutResult(status -> { override(1000,20,2000,30); status.setRollbackOnly(); });
        assertThat(jdbc.queryForObject("select ingest_generation from trip_vehicle_activity where vehicle_id=?",Long.class,VEHICLE)).isEqualTo(generation);
        assertThat(staged(3).replayId()).isEqualTo(first.replayId());
        assertThatThrownBy(() -> replayer().replayPage(VEHICLE,101)).isInstanceOf(IllegalArgumentException.class);
        assertThat(replayer().replayPage(EMPTY,1).state()).isEqualTo("NO_PRIMARY_DEVICE");
    }

    @Test void stagedLateOffEvidenceSplitsTripWithoutChangingItsSavedIdentity() {
        migrate(); local(); commute("short"); drain(processingTime(),100);
        UUID original = jdbc.queryForObject("select id from trip_record where status='COMPLETED'",UUID.class);
        override(999,20,0,0); override(1000,22,0,0); drain(processingTime(),100); planned(1); staged(1);
        var segments = jdbc.queryForList("select started_at,ended_at,summary::text from trip_correction_segment where status='COMPLETED' order by started_at");
        assertThat(segments).hasSize(2);
        assertThat(((Timestamp)segments.get(0).get("ended_at")).toInstant()).isEqualTo(Instant.parse("2026-10-20T12:00:20Z"));
        assertThat(((Timestamp)segments.get(1).get("started_at")).toInstant()).isEqualTo(Instant.parse("2026-10-20T12:00:24Z"));
        assertThat(JSON.readTree(segments.get(0).get("summary").toString()).get("sampleCount").intValue()).isEqualTo(9);
        assertThat(JSON.readTree(segments.get(1).get("summary").toString()).get("sampleCount").intValue()).isEqualTo(17);
        assertThat(jdbc.queryForObject("select id from trip_record where status='COMPLETED'",UUID.class)).isEqualTo(original);
    }

    @Test void stagedLateRunningEvidenceMergesPreviouslySeparateTripsWithoutPublishingThem() {
        migrate(); local();
        for (var input : JSON.readTree(Path.of("../contracts/examples/trip-short-input.json").toFile()).get("readings")) {
            var payload = ((ObjectNode)input).deepCopy();
            if (java.util.Set.of(10,11).contains(payload.get("sequenceNumber").intValue())) payload.put("rpm",0).put("speedKph",0);
            store(new TelemetryValidator(JSON).validate(payload));
        }
        drain(processingTime(),100);
        assertThat(jdbc.queryForObject("select count(*) from trip_record where status='COMPLETED'",Integer.class)).isEqualTo(2);
        override(999,20,1900,12); override(1000,22,1900,12); drain(processingTime(),100); planned(1); staged(1);
        assertThat(jdbc.queryForObject("select count(*) from trip_correction_segment where status='COMPLETED'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from trip_record where status='COMPLETED'",Integer.class)).isEqualTo(2);
        assertThat(count("trip_correction_evidence")).isEqualTo(27);
    }

    TripCorrectionPublisher publisher() { return new TripCorrectionPublisher(jdbc,JSON,transactions); }
    TripCorrectionPublisher.Page published(int limit) {
        for (int page=0;page<2000;page++) {
            var result=publisher().publishPage(VEHICLE,limit);
            assertThat(result.visited()).isLessThanOrEqualTo(limit);
            if (result.state().equals("DONE")) return result;
            assertThat(result.state()).isIn("CAPTURE","MAP","RETIRE","TRIPS","OLD","LINEAGE","DELETE_LINKS","INSERT_LINKS","ACK","FINISH");
            assertThat(count("trip_visible_record")).isZero();
            assertThat(count("trip_evidence")).isZero();
        }
        throw new AssertionError("publication fixture exceeded bounded page budget");
    }

    @Test void publicationCorrectsStableIdentityExactTotalsAndAcknowledgesOnlyOnCompletion() {
        correctedShortPlan(); staged(1);
        var original=jdbc.queryForMap("select * from trip_record");
        var receipts=jdbc.queryForList("select id,received_at from telemetry_reading order by id");
        var result=published(1);
        var current=jdbc.queryForMap("select * from trip_record where status='COMPLETED'");
        assertThat(current.get("id")).isEqualTo(original.get("id"));
        assertThat(current.get("created_at")).isEqualTo(original.get("created_at"));
        assertThat(((Number)current.get("revision")).longValue()).isEqualTo(((Number)original.get("revision")).longValue()+1);
        var summary=JSON.readTree(current.get("summary").toString());
        assertThat(summary.get("sampleCount").intValue()).isEqualTo(27);
        assertThat(summary.get("metrics").get("rpm").get("sum").decimalValue()).isEqualByComparingTo("47237.5");
        assertThat(summary.get("speedAreaKphSeconds").decimalValue()).isEqualByComparingTo("1784");
        assertThat(count("trip_evidence")).isEqualTo(27);
        assertThat(count("trip_visible_record")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item where status<>'PROCESSED'",Integer.class)).isZero();
        assertThat(jdbc.queryForList("select id,received_at from telemetry_reading order by id")).isEqualTo(receipts);
        assertThat(publisher().publishPage(VEHICLE,100)).isEqualTo(new TripCorrectionPublisher.Page(result.publicationId(),"DONE",0));
        assertThat(jdbc.queryForMap("select * from trip_record where status='COMPLETED'")).isEqualTo(current);
        assertThat(jdbc.queryForObject("select needs_review from trip_projection_progress",Boolean.class)).isFalse();
        drain(processingTime(),1);
        assertThat(count("trip_record")).isEqualTo(1);
    }

    @Test void publicationSplitKeepsAnchoredIdAndAddsTypedLineageWithoutDuplicateEvidence() {
        migrate(); local(); commute("short"); drain(processingTime(),100);
        UUID original=jdbc.queryForObject("select id from trip_record where status='COMPLETED'",UUID.class);
        override(999,20,0,0); override(1000,22,0,0); drain(processingTime(),100); planned(1); staged(1); published(1);
        var trips=jdbc.queryForList("select id,summary::text from trip_record where status='COMPLETED' order by started_at");
        assertThat(trips).hasSize(2); assertThat(trips.getFirst().get("id")).isEqualTo(original);
        assertThat(JSON.readTree(trips.getFirst().get("summary").toString()).get("sampleCount").intValue()).isEqualTo(9);
        assertThat(JSON.readTree(trips.getLast().get("summary").toString()).get("sampleCount").intValue()).isEqualTo(17);
        assertThat(jdbc.queryForMap("select parent_trip_id,child_trip_id,relation from trip_correction_lineage"))
                .containsEntry("parent_trip_id",original).containsEntry("child_trip_id",trips.getLast().get("id")).containsEntry("relation","SPLIT");
        assertThat(count("trip_superseded_by")).isZero(); assertThat(count("trip_evidence")).isEqualTo(26);
    }

    @Test void publicationMergeKeepsOldestPersistedAnchorAndRedirectsOtherId() {
        migrate(); local();
        for (var input : JSON.readTree(Path.of("../contracts/examples/trip-short-input.json").toFile()).get("readings")) {
            var payload=((ObjectNode)input).deepCopy();
            if (java.util.Set.of(10,11).contains(payload.get("sequenceNumber").intValue())) payload.put("rpm",0).put("speedKph",0);
            store(new TelemetryValidator(JSON).validate(payload));
        }
        drain(processingTime(),100);
        var originals=jdbc.queryForList("select id from trip_record order by created_at,id");
        override(999,20,1900,12); override(1000,22,1900,12); drain(processingTime(),100); planned(1); staged(1); published(1);
        assertThat(jdbc.queryForObject("select id from trip_record where status='COMPLETED'",UUID.class)).isEqualTo(originals.getFirst().get("id"));
        assertThat(jdbc.queryForObject("select id from trip_record where status='SUPERSEDED'",UUID.class)).isEqualTo(originals.getLast().get("id"));
        assertThat(jdbc.queryForMap("select parent_trip_id,child_trip_id from trip_superseded_by"))
                .containsEntry("parent_trip_id",originals.getLast().get("id")).containsEntry("child_trip_id",originals.getFirst().get("id"));
        assertThat(count("trip_evidence")).isEqualTo(27); assertThat(count("trip_record")).isEqualTo(2);
        // Another correction must ignore the retired parent's old interval.
        override(1001,10,2000,15); drain(processingTime(),100); assertThat(planned(1).state()).isEqualTo("READY");
        staged(1); published(1); assertThat(count("trip_record")).isEqualTo(2); assertThat(count("trip_evidence")).isEqualTo(27);
    }

    @Test void publicationFenceLeavesConcurrentAcceptedInputForFollowUpCorrection() {
        correctedShortPlan(); staged(1);
        var first=publisher().publishPage(VEHICLE,1); assertThat(first.state()).isEqualTo("CAPTURE");
        override(1000,20,2000,30);
        assertThat(planner().planPage(VEHICLE,processingTime(),1).state()).isEqualTo("PUBLICATION_PENDING");
        assertThat(replayer().replayPage(VEHICLE,1).state()).isEqualTo("PUBLICATION_PENDING");
        published(1);
        assertThat(jdbc.queryForObject("select w.status from trip_work_item w join telemetry_reading r on r.id=w.reading_id where r.sequence_number=1000",String.class)).isEqualTo("PENDING");
        var summary=JSON.readTree(jdbc.queryForObject("select summary::text from trip_record where status='COMPLETED'",String.class));
        assertThat(summary.get("speedAreaKphSeconds").decimalValue()).isEqualByComparingTo("1784");
        drain(processingTime(),100);
        assertThat(jdbc.queryForObject("select w.status from trip_work_item w join telemetry_reading r on r.id=w.reading_id where r.sequence_number=1000",String.class)).isEqualTo("REVIEW");
        assertThat(planned(1).state()).isEqualTo("READY"); staged(1); published(1);
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item where status<>'PROCESSED'",Integer.class)).isZero();
    }

    @Test void staleStageCannotStartPublicationAndFinalizedTripsRemainUntouched() {
        correctedShortPlan(); staged(1); var before=jdbc.queryForList("select * from trip_record");
        override(1000,20,2000,30);
        assertThat(publisher().publishPage(VEHICLE,1).state()).isEqualTo("REPLAN_REQUIRED");
        assertThat(count("trip_correction_publication")).isZero(); assertThat(jdbc.queryForList("select * from trip_record")).isEqualTo(before);
        planned(1); staged(1); jdbc.update("update trip_record set finalization='FINAL' where status='COMPLETED'");
        assertThat(publisher().publishPage(VEHICLE,1).state()).isEqualTo("REVIEW_PUBLICATION");
        assertThat(count("trip_correction_publication")).isZero(); assertThat(count("trip_evidence")).isEqualTo(27);
        assertThatThrownBy(() -> publisher().publishPage(VEHICLE,101)).isInstanceOf(IllegalArgumentException.class);
        assertThat(publisher().publishPage(EMPTY,1).state()).isEqualTo("NO_PRIMARY_DEVICE");
    }

    @Test void failedPublicationPageRollsBackAndRemainsHiddenUntilResumed() {
        correctedShortPlan(); staged(1);
        boolean reached=false;
        for (int page=0;page<100;page++) if (publisher().publishPage(VEHICLE,1).state().equals("TRIPS")) { reached=true; break; }
        assertThat(reached).isTrue();
        var publication=jdbc.queryForMap("select * from trip_correction_publication");
        var trips=jdbc.queryForList("select * from trip_record");
        jdbc.execute("create function fail_publication() returns trigger language plpgsql as $$ begin raise exception 'test-only publication failure'; end $$");
        jdbc.execute("create trigger fail_publication before update on trip_correction_publication for each row execute function fail_publication()");
        assertThatThrownBy(() -> publisher().publishPage(VEHICLE,1)).hasMessageContaining("publication failure");
        assertThat(jdbc.queryForMap("select * from trip_correction_publication")).isEqualTo(publication);
        assertThat(jdbc.queryForList("select * from trip_record")).isEqualTo(trips);
        assertThat(count("trip_visible_record")).isZero(); assertThat(count("trip_evidence")).isZero();
        jdbc.execute("drop trigger fail_publication on trip_correction_publication");
        published(1); assertThat(count("trip_evidence")).isEqualTo(27);
    }

    @Test void equivalentCorrectionChangesMembershipButNotSemanticRevision() {
        migrate(); local(); commute("short"); drain(processingTime(),100);
        var before=jdbc.queryForMap("select * from trip_record where status='COMPLETED'");
        var payload=(ObjectNode)JSON.readTree(jdbc.queryForObject("select payload::text from telemetry_reading where sequence_number=5",String.class));
        payload.put("sequenceNumber",999); store(new TelemetryValidator(JSON).validate(payload));
        drain(processingTime(),100); planned(1); staged(1); published(1);
        assertThat(jdbc.queryForMap("select * from trip_record where status='COMPLETED'")).isEqualTo(before);
        assertThat(count("trip_evidence")).isEqualTo(27);
        assertThat(jdbc.queryForObject("select count(*) from trip_evidence e join telemetry_reading r on r.id=e.reading_id where r.sequence_number=999",Integer.class)).isEqualTo(1);
    }

    @Test void policyChangeDuringPublicationPausesWithoutExposingPartialProjection() {
        correctedShortPlan(); staged(1); publisher().publishPage(VEHICLE,1);
        var before=jdbc.queryForList("select * from trip_record");
        jdbc.update("update trip_policy set revision=revision+1 where vehicle_id=?",VEHICLE);
        assertThat(publisher().publishPage(VEHICLE,1).state()).isEqualTo("POLICY_CHANGED");
        assertThat(jdbc.queryForList("select * from trip_record")).isEqualTo(before);
        assertThat(count("trip_visible_record")).isZero();
        assertThat(planner().planPage(VEHICLE,processingTime(),1).state()).isEqualTo("PUBLICATION_PENDING");
        // Disposable-fixture restoration only; no automatic policy rollback exists.
        jdbc.update("update trip_policy set revision=revision-1 where vehicle_id=?",VEHICLE);
        published(1); assertThat(count("trip_evidence")).isEqualTo(27);
    }

    @Test void historicalPublicationPreservesLaterTripAndGlobalTailCheckpoint() {
        migrate(); local(); commute("short"); drain(processingTime(),100);
        override(100,3600,1900,12); override(101,3602,1900,12); drain(processingTime(),100);
        var later=jdbc.queryForMap("select * from trip_record order by started_at desc limit 1");
        var progress=jdbc.queryForMap("select cursor_at,cursor_boot,cursor_sequence,checkpoint,current_trip_id from trip_projection_progress");
        override(999,10,1900,12); drain(processingTime(),100); planned(1); staged(1); published(1);
        assertThat(jdbc.queryForMap("select * from trip_record where id=?",later.get("id"))).isEqualTo(later);
        assertThat(jdbc.queryForMap("select cursor_at,cursor_boot,cursor_sequence,checkpoint,current_trip_id from trip_projection_progress")).isEqualTo(progress);
        assertThat(count("trip_record")).isEqualTo(2); assertThat(count("trip_evidence")).isEqualTo(29);
    }

    @Test void publicationRestoresActiveFoldSoForwardProcessingContinuesSameTrip() {
        migrate(); local(); Instant base=processingTime();
        for (int seq=0;seq<3;seq++) store(sample(seq,base.plusSeconds(seq*2)));
        drain(base.plusSeconds(4),100);
        UUID original=jdbc.queryForObject("select id from trip_record where status='ACTIVE'",UUID.class);
        store(sample(999,base.plusSeconds(2))); drain(base.plusSeconds(4),100);
        boolean ready=false;
        for (int page=0;page<100;page++) {
            var result=planner().planPage(VEHICLE,base.plusSeconds(4),1);
            if (result.state().equals("READY")) { ready=true; break; }
            assertThat(result.state()).isIn("TRIPS","INPUTS");
        }
        assertThat(ready).isTrue(); staged(1); published(1);
        assertThat(jdbc.queryForObject("select current_trip_id from trip_projection_progress",UUID.class)).isEqualTo(original);
        store(sample(3,base.plusSeconds(6))); drain(base.plusSeconds(6),100);
        assertThat(jdbc.queryForObject("select id from trip_record where status='ACTIVE'",UUID.class)).isEqualTo(original);
        var summary=JSON.readTree(jdbc.queryForObject("select summary::text from trip_record where id=?",String.class,original));
        assertThat(summary.get("sampleCount").intValue()).isEqualTo(4);
        assertThat(count("trip_record")).isEqualTo(1); assertThat(count("trip_evidence")).isEqualTo(4);
    }

    @Test void publicationRevisionDetectsDecimalChangesSmallerThanDoublePrecision() {
        migrate(); local();
        ObjectNode changed=null;
        for (var input : JSON.readTree(Path.of("../contracts/examples/trip-short-input.json").toFile()).get("readings")) {
            var payload=((ObjectNode)input).deepCopy();
            if (payload.get("sequenceNumber").intValue()==5) {
                payload.put("controlModuleVoltageV",new java.math.BigDecimal("13.00000000000000000001")); changed=payload.deepCopy();
            }
            store(new TelemetryValidator(JSON).validate(payload));
        }
        drain(processingTime(),100);
        long revision=jdbc.queryForObject("select revision from trip_record where status='COMPLETED'",Long.class);
        String sumSql="select (summary->'metrics'->'controlModuleVoltageV'->>'sum')::numeric from trip_record where status='COMPLETED'";
        var sum=jdbc.queryForObject(sumSql,java.math.BigDecimal.class);
        assertThat(changed).isNotNull();
        changed.put("sequenceNumber",999).put("controlModuleVoltageV",new java.math.BigDecimal("13.00000000000000000002"));
        store(new TelemetryValidator(JSON).validate(changed)); drain(processingTime(),100); planned(1); staged(1); published(1);
        assertThat(jdbc.queryForObject("select revision from trip_record where status='COMPLETED'",Long.class)).isEqualTo(revision+1);
        assertThat(jdbc.queryForObject(sumSql,java.math.BigDecimal.class).subtract(sum)).isEqualByComparingTo("0.00000000000000000001");
    }

    @Test void receiptBeginningBeforePublicationButCommittingAfterFenceRemainsPending() throws Exception {
        correctedShortPlan(); staged(1);
        var backend=new java.util.concurrent.atomic.AtomicInteger();
        var started=new java.util.concurrent.CountDownLatch(1);
        var insert=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<String>>();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.queryForMap("select * from trip_vehicle_activity where vehicle_id=? for update",VEHICLE);
            insert.set(java.util.concurrent.CompletableFuture.supplyAsync(() -> new TransactionTemplate(transactions).execute(other -> {
                backend.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class)); started.countDown();
                var payload=sample(1000,Instant.parse("2026-10-20T12:00:20Z")).payload()
                        .put("bootId","123e4567-e89b-42d3-a456-426614174000").put("rpm",2000).put("speedKph",30);
                return store(new TelemetryValidator(JSON).validate(payload));
            })));
            try {
                assertThat(started.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                boolean waiting=false; long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime()<deadline) {
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",Boolean.class,backend.get()));
                    if (waiting) break;
                    Thread.sleep(20);
                }
                assertThat(waiting).isTrue();
                assertThat(publisher().publishPage(VEHICLE,1).state()).isEqualTo("CAPTURE");
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        });
        assertThat(insert.get().get(5,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("accepted");
        assertThat(jdbc.queryForObject("""
                select w.acceptance_order>p.acceptance_fence and w.accepted_at<p.created_at
                from trip_work_item w join telemetry_reading r on r.id=w.reading_id
                join trip_correction_publication p on p.vehicle_id=w.vehicle_id where r.sequence_number=1000
                """,Boolean.class)).isTrue();
        published(1);
        assertThat(jdbc.queryForObject("select w.status from trip_work_item w join telemetry_reading r on r.id=w.reading_id where r.sequence_number=1000",String.class)).isEqualTo("PENDING");
    }

    TripProcessingWorker worker(Instant now) {
        return new TripProcessingWorker(jdbc,transactions,projector(),planner(),replayer(),publisher(),finalizer(),
                java.time.Clock.fixed(now,java.time.ZoneOffset.UTC));
    }
    void failingProgress(String sqlState) {
        jdbc.execute("create function fail_controlled_page() returns trigger language plpgsql as $$ begin raise exception using errcode='"+sqlState+"', message='sensitive example payload must not be recorded'; end $$");
        jdbc.execute("create trigger fail_controlled_page before update on trip_projection_progress for each row execute function fail_controlled_page()");
    }

    @Test void transientFailureRollsBackPageButPersistsBackoffAcrossWorkerRestart() {
        migrate(); local(); commute("short"); failingProgress("40001");
        Instant now=processingTime(); var receipts=jdbc.queryForList("select id,received_at from telemetry_reading order by id");
        assertThat(worker(now).runPage(VEHICLE,FORWARD,now,100).state()).isEqualTo("RETRY_WAIT");
        var first=worker(now).status(VEHICLE).orElseThrow();
        assertThat(first.consecutiveFailures()).isEqualTo(1); assertThat(first.totalFailures()).isEqualTo(1);
        assertThat(first.sqlState()).isEqualTo("40001"); assertThat(first.errorCode()).isEqualTo("TRANSIENT_DATABASE_FAILURE");
        assertThat(first.nextAttemptAt()).isEqualTo(now.plusSeconds(1));
        assertThat(count("trip_record")).isZero(); assertThat(count("trip_projection_progress")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item where status<>'PENDING'",Integer.class)).isZero();
        assertThat(worker(now).runPage(VEHICLE,FORWARD,now,100).state()).isEqualTo("RETRY_WAIT");
        assertThat(worker(now).status(VEHICLE).orElseThrow()).isEqualTo(first);
        assertThat(worker(now.plusSeconds(1)).runPage(VEHICLE,FORWARD,now,100).state()).isEqualTo("RETRY_WAIT");
        assertThat(worker(now).status(VEHICLE).orElseThrow().nextAttemptAt()).isEqualTo(now.plusSeconds(3));
        assertThat(worker(now.plusSeconds(3)).runPage(VEHICLE,PLAN,now,1).state()).isEqualTo("RETRY_STAGE_REQUIRED");
        jdbc.execute("drop trigger fail_controlled_page on trip_projection_progress");
        assertThat(worker(now.plusSeconds(3)).runPage(VEHICLE,FORWARD,now,100).state()).isEqualTo("IDLE");
        var success=worker(now).status(VEHICLE).orElseThrow();
        assertThat(success.consecutiveFailures()).isZero(); assertThat(success.totalFailures()).isEqualTo(2); assertThat(success.errorCode()).isNull();
        assertThat(count("trip_evidence")).isEqualTo(27);
        assertThat(jdbc.queryForList("select id,received_at from telemetry_reading order by id")).isEqualTo(receipts);
    }

    @Test void fifthTransientFailurePausesAndExplicitVersionedResumeDoesNotLoseWork() {
        migrate(); local(); commute("short"); failingProgress("40001"); Instant now=processingTime();
        for (int attempt=1;attempt<=5;attempt++) {
            var result=worker(now).runPage(VEHICLE,FORWARD,processingTime(),100);
            assertThat(result.state()).isEqualTo(attempt<5 ? "RETRY_WAIT" : "PAUSED");
            if (attempt<5) now=worker(now).status(VEHICLE).orElseThrow().nextAttemptAt();
        }
        var paused=worker(now).status(VEHICLE).orElseThrow();
        assertThat(paused.totalFailures()).isEqualTo(5); assertThat(paused.consecutiveFailures()).isEqualTo(5);
        assertThat(worker(now.plusSeconds(3600)).runPage(VEHICLE,FORWARD,processingTime(),100).state()).isEqualTo("PAUSED");
        assertThat(worker(now).resume(VEHICLE,paused.revision()-1)).isFalse();
        jdbc.execute("drop trigger fail_controlled_page on trip_projection_progress");
        assertThat(worker(now).resume(VEHICLE,paused.revision())).isTrue();
        assertThat(worker(now).resume(VEHICLE,paused.revision())).isFalse();
        assertThat(worker(now).runPage(VEHICLE,FORWARD,processingTime(),100).state()).isEqualTo("IDLE");
        assertThat(worker(now).status(VEHICLE).orElseThrow().totalFailures()).isEqualTo(5);
        assertThat(count("trip_evidence")).isEqualTo(27);
    }

    @Test void permanentFailurePausesWithSafeCodeNotSqlOrPayloadAndResumeDoesNotFixCause() {
        migrate(); local(); commute("short"); failingProgress("23514"); Instant now=processingTime();
        assertThat(worker(now).runPage(VEHICLE,FORWARD,now,100).state()).isEqualTo("PAUSED");
        var paused=worker(now).status(VEHICLE).orElseThrow();
        assertThat(paused.errorCode()).isEqualTo("DATABASE_OPERATION_REQUIRES_REVIEW"); assertThat(paused.sqlState()).isEqualTo("23514");
        String stored=jdbc.queryForObject("select to_jsonb(c)::text from trip_processing_control c",String.class);
        assertThat(stored).doesNotContain("sensitive example","raise exception","payload");
        assertThat(worker(now).resume(VEHICLE,paused.revision())).isTrue();
        assertThat(worker(now).runPage(VEHICLE,FORWARD,now,100).state()).isEqualTo("PAUSED");
        assertThat(count("trip_record")).isZero(); assertThat(worker(now).status(VEHICLE).orElseThrow().totalFailures()).isEqualTo(2);
    }

    @Test void processingContentionDefersWithoutConsumingFailureBudget() throws Exception {
        migrate(); local(); commute("short"); Instant now=processingTime();
        try (var connection=data.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement=connection.prepareStatement("select pg_advisory_xact_lock(hashtextextended(cast(? as text),902))")) {
                statement.setString(1,VEHICLE.toString()); statement.execute();
            }
            var result=worker(now).runPage(VEHICLE,FORWARD,now,100);
            assertThat(result.state()).isEqualTo("RETRY_WAIT"); assertThat(result.result()).isEqualTo("BUSY");
            assertThat(worker(now).status(VEHICLE).orElseThrow().totalFailures()).isZero();
            connection.rollback();
        }
        assertThat(worker(now.plusSeconds(1)).runPage(VEHICLE,FORWARD,now,100).state()).isEqualTo("IDLE");
    }

    @Test void expiredLeaseResumesSameStageAndFencesStaleCompletion() {
        migrate(); local(); commute("short"); Instant now=processingTime();
        UUID oldToken=UUID.randomUUID();
        jdbc.update("insert into trip_processing_control(vehicle_id,stage,state,claim_token,lease_until) values (?,'FORWARD','RUNNING',?,?)",VEHICLE,oldToken,Timestamp.from(now.plusSeconds(120)));
        assertThat(worker(now).runPage(VEHICLE,FORWARD,now,100).state()).isEqualTo("BUSY");
        assertThat(worker(now.plusSeconds(120)).runPage(VEHICLE,PLAN,now,1).state()).isEqualTo("RETRY_STAGE_REQUIRED");
        assertThat(worker(now.plusSeconds(120)).runPage(VEHICLE,FORWARD,now,100).state()).isEqualTo("IDLE");
        assertThat(worker(now).status(VEHICLE).orElseThrow().totalFailures()).isEqualTo(1);
        assertThat(jdbc.update("update trip_processing_control set last_result='STALE' where vehicle_id=? and claim_token=?",VEHICLE,oldToken)).isZero();
        assertThat(count("trip_evidence")).isEqualTo(27);
    }

    @Test void correctionHandoffCanSwitchStagesAndPolicyReviewRequiresExplicitResume() {
        correctedShortPlan(); Instant now=processingTime();
        assertThat(worker(now).runPage(VEHICLE,FORWARD,now,1).result()).isEqualTo("CORRECTION_PENDING");
        assertThat(worker(now).runPage(VEHICLE,REPLAY,now,1).result()).isEqualTo("RUNNING");
        jdbc.update("update trip_policy set revision=revision+1 where vehicle_id=?",VEHICLE);
        var result=worker(now).runPage(VEHICLE,REPLAY,now,1);
        assertThat(result.state()).isEqualTo("PAUSED"); assertThat(result.result()).isEqualTo("REVIEW_POLICY_CHANGED");
        assertThat(worker(now).status(VEHICLE).orElseThrow().errorCode()).isEqualTo("POLICY_OR_REVIEW_REQUIRED");
        assertThat(count("trip_evidence")).isEqualTo(27);
    }

    @Test void controlledPublicationFailureKeepsVisibilityGateAndResumesOriginalPublication() {
        correctedShortPlan(); staged(1); boolean reached=false;
        for (int page=0;page<100;page++) if (publisher().publishPage(VEHICLE,1).state().equals("TRIPS")) { reached=true; break; }
        assertThat(reached).isTrue(); var original=jdbc.queryForMap("select * from trip_correction_publication");
        jdbc.execute("create function fail_worker_publish() returns trigger language plpgsql as $$ begin raise exception using errcode='40001'; end $$");
        jdbc.execute("create trigger fail_worker_publish before update on trip_correction_publication for each row execute function fail_worker_publish()");
        Instant now=processingTime();
        assertThat(worker(now).runPage(VEHICLE,PUBLISH,now,1).state()).isEqualTo("RETRY_WAIT");
        assertThat(jdbc.queryForMap("select * from trip_correction_publication")).isEqualTo(original);
        assertThat(count("trip_visible_record")).isZero(); assertThat(count("trip_evidence")).isZero();
        jdbc.execute("drop trigger fail_worker_publish on trip_correction_publication");
        assertThat(worker(now.plusSeconds(1)).runPage(VEHICLE,PUBLISH,now,1).state()).isEqualTo("IDLE");
        assertThat(published(1).publicationId()).isEqualTo(original.get("id")); assertThat(count("trip_evidence")).isEqualTo(27);
    }

    @Test void workerRejectsInvalidCallsAndAmbientTransactionsWithoutMutation() {
        migrate(); local(); Instant now=processingTime();
        assertThat(worker(now).status(VEHICLE)).isEmpty();
        assertThat(worker(now).runPage(UUID.randomUUID(),FORWARD,now,1).state()).isEqualTo("UNKNOWN_VEHICLE");
        assertThatThrownBy(() -> worker(now).runPage(VEHICLE,FORWARD,now,101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> worker(now).runPage(VEHICLE,FORWARD,now,1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("outside an existing transaction");
        assertThat(count("trip_processing_control")).isZero();
        assertThat(worker(now).runPage(EMPTY,FORWARD,now,1).state()).isEqualTo("PAUSED");
        assertThat(worker(now).status(VEHICLE)).isEmpty();
    }

    @Test void expiredWorkerFinishingLateCannotOverwriteRecoveredWorkerStatus() throws Exception {
        migrate(); local(); commute("short"); Instant now=processingTime();
        var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        var delayed=org.mockito.Mockito.mock(TripProjector.class);
        org.mockito.Mockito.when(delayed.processPage(VEHICLE,now,100)).thenAnswer(call -> {
            entered.countDown(); assertThat(release.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return new TripProjector.Page("CAUGHT_UP",0,0);
        });
        var oldWorker=new TripProcessingWorker(jdbc,transactions,delayed,planner(),replayer(),publisher(),finalizer(),java.time.Clock.fixed(now,java.time.ZoneOffset.UTC));
        var oldCall=java.util.concurrent.CompletableFuture.supplyAsync(() -> oldWorker.runPage(VEHICLE,FORWARD,now,100));
        TripProcessingWorker.Status recovered;
        try {
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(worker(now).runPage(VEHICLE,FORWARD,now,100).state()).isEqualTo("BUSY");
            assertThat(worker(now.plusSeconds(120)).runPage(VEHICLE,FORWARD,now,100).state()).isEqualTo("IDLE");
            recovered=worker(now).status(VEHICLE).orElseThrow();
        } finally { release.countDown(); }
        assertThat(oldCall.get(5,java.util.concurrent.TimeUnit.SECONDS).state()).isEqualTo("CLAIM_LOST");
        assertThat(worker(now).status(VEHICLE).orElseThrow()).isEqualTo(recovered);
        assertThat(recovered.totalFailures()).isEqualTo(1); assertThat(count("trip_evidence")).isEqualTo(27);
    }

    @Test void repeatedExpiredLeasesPauseRatherThanRecoveringForever() {
        migrate(); local(); Instant now=processingTime(); UUID token=UUID.randomUUID();
        jdbc.update("""
                insert into trip_processing_control(vehicle_id,stage,state,claim_token,lease_until,consecutive_failures,total_failures)
                values (?,'FORWARD','RUNNING',?,?,4,4)
                """,VEHICLE,token,Timestamp.from(now));
        assertThat(worker(now).runPage(VEHICLE,FORWARD,now,1).state()).isEqualTo("PAUSED");
        var paused=worker(now).status(VEHICLE).orElseThrow();
        assertThat(paused.errorCode()).isEqualTo("LEASE_EXPIRED"); assertThat(paused.totalFailures()).isEqualTo(5);
        assertThat(paused.leaseUntil()).isNull(); assertThat(count("trip_record")).isZero();
    }

    @Test void connectionFailureUsesSafeSqlStateRetryClassification() {
        migrate(); local(); Instant now=processingTime();
        var unavailable=org.mockito.Mockito.mock(TripProjector.class);
        org.mockito.Mockito.when(unavailable.processPage(VEHICLE,now,100)).thenThrow(new org.springframework.jdbc.CannotGetJdbcConnectionException(
                "private connection details",new java.sql.SQLException("private connection details","08006")));
        var tested=new TripProcessingWorker(jdbc,transactions,unavailable,planner(),replayer(),publisher(),finalizer(),java.time.Clock.fixed(now,java.time.ZoneOffset.UTC));
        assertThat(tested.runPage(VEHICLE,FORWARD,now,100).state()).isEqualTo("RETRY_WAIT");
        var status=tested.status(VEHICLE).orElseThrow(); assertThat(status.sqlState()).isEqualTo("08006");
        assertThat(status.errorCode()).isEqualTo("TRANSIENT_DATABASE_FAILURE"); assertThat(status.lastFailureAt()).isEqualTo(now);
        assertThat(jdbc.queryForObject("select to_jsonb(c)::text from trip_processing_control c",String.class)).doesNotContain("private connection details");
    }

    TripProcessingCoordinator coordinator(Instant now) {
        return new TripProcessingCoordinator(jdbc,transactions,worker(now),java.time.Clock.fixed(now,java.time.ZoneOffset.UTC));
    }
    void coordinatedUntilIdle(Instant now,int limit) {
        for (int page=0;page<3000;page++) {
            var result=coordinator(now).processVehicle(VEHICLE,now,limit);
            assertThat(result.visited()).isLessThanOrEqualTo(limit);
            assertThat(result.state()).isNotIn("PAUSED","RETRY_STAGE_REQUIRED");
            if ("NO_DUE_WORK".equals(result.result())) return;
        }
        throw new AssertionError("coordinator fixture exceeded page budget");
    }

    @Test void coordinatorProcessesAcceptedHistoryThroughFinalizationAcrossRestarts() {
        migrate(); local(); commute("short"); Instant now=processingTime();
        var raw=jdbc.queryForList("select id,received_at from telemetry_reading order by id");
        coordinatedUntilIdle(now,1);
        assertThat(jdbc.queryForObject("select finalization from trip_record where status='COMPLETED'",String.class)).isEqualTo("FINAL");
        assertThat(count("trip_evidence")).isEqualTo(27);
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item where status<>'PROCESSED'",Integer.class)).isZero();
        assertThat(jdbc.queryForList("select id,received_at from telemetry_reading order by id")).isEqualTo(raw);
        var trip=jdbc.queryForList("select * from trip_record"); coordinatedUntilIdle(now,1);
        assertThat(jdbc.queryForList("select * from trip_record")).isEqualTo(trip);
    }

    @Test void coordinatorRoutesLateWorkThroughPlanningReplayPublicationBeforeSealing() {
        migrate(); local(); commute("short"); drain(processingTime(),100);
        UUID original=jdbc.queryForObject("select id from trip_record where status='COMPLETED'",UUID.class);
        override(999,10,1900,12); Instant now=processingTime();
        assertThat(coordinator(now).processVehicle(VEHICLE,now,100).stage()).isEqualTo(FORWARD);
        assertThat(coordinator(now).processVehicle(VEHICLE,now,1).stage()).isEqualTo(PLAN);
        coordinatedUntilIdle(now,1);
        assertThat(jdbc.queryForObject("select phase from trip_correction_publication",String.class)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("select id from trip_record where status='COMPLETED'",UUID.class)).isEqualTo(original);
        assertThat(jdbc.queryForObject("select finalization from trip_record where id=?",String.class,original)).isEqualTo("FINAL");
        var summary=JSON.readTree(jdbc.queryForObject("select summary::text from trip_record where id=?",String.class,original));
        assertThat(summary.get("speedAreaKphSeconds").decimalValue()).isEqualByComparingTo("1784");
        assertThat(count("trip_evidence")).isEqualTo(27);
    }

    @Test void coordinatorRevalidatesStageAndResumesInterruptedPublicationWithoutBypass() {
        correctedShortPlan(); staged(1); Instant now=processingTime();
        assertThat(coordinator(now).processVehicle(VEHICLE,now,1).stage()).isEqualTo(PUBLISH);
        assertThat(count("trip_visible_record")).isZero();
        override(1000,20,2000,30); // post-fence input must survive the older publication
        coordinatedUntilIdle(now,1);
        assertThat(jdbc.queryForObject("select count(*) from trip_correction_publication where phase='DONE'",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item where status<>'PROCESSED'",Integer.class)).isZero();
        assertThat(count("trip_evidence")).isEqualTo(27);
    }

    @Test void coordinatorRestartTimerClosesAbandonedActiveTripWithoutBrowserOrNewInput() {
        migrate(); local(); Instant base=processingTime();
        store(sample(0,base)); store(sample(1,base.plusSeconds(2)));
        assertThat(coordinator(base.plusSeconds(2)).processVehicle(VEHICLE,base.plusSeconds(2),100).stage()).isEqualTo(FORWARD);
        assertThat(jdbc.queryForObject("select status from trip_record",String.class)).isEqualTo("ACTIVE");
        assertThat(coordinator(base.plusSeconds(31)).processVehicle(VEHICLE,base.plusSeconds(31),100).stage()).isEqualTo(FORWARD);
        assertThat(jdbc.queryForObject("select status from trip_record",String.class)).isEqualTo("ACTIVE");
        assertThat(coordinator(base.plusSeconds(32)).processVehicle(VEHICLE,base.plusSeconds(32),100).stage()).isEqualTo(FORWARD);
        var trip=jdbc.queryForMap("select * from trip_record where status='COMPLETED'");
        assertThat(trip.get("completion_reason")).isEqualTo("SIGNAL_TIMEOUT");
        assertThat(((Timestamp)trip.get("ended_at")).toInstant()).isEqualTo(base.plusSeconds(2));
        assertThat(JSON.readTree(trip.get("summary").toString()).get("durationSeconds").decimalValue()).isEqualByComparingTo("2");
    }

    @Test void futureOnlyInputRemainsQueuedAndDoesNotPoisonTimeoutOrFinalization() {
        migrate(); local(); commute("short"); store(sample(999,Instant.parse("2099-01-01T00:00:00Z")));
        Instant now=processingTime(); coordinatedUntilIdle(now,1);
        assertThat(jdbc.queryForObject("select w.status from trip_work_item w join telemetry_reading r on r.id=w.reading_id where r.sequence_number=999",String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select finalization from trip_record where status='COMPLETED'",String.class)).isEqualTo("FINAL");
        assertThat(count("trip_evidence")).isEqualTo(27);
    }

    @Test void coordinatorHonorsRetryAndPauseBeforeChoosingOtherStages() {
        migrate(); local(); commute("short"); failingProgress("40001"); Instant now=processingTime();
        assertThat(coordinator(now).processVehicle(VEHICLE,now,100).state()).isEqualTo("RETRY_WAIT");
        assertThat(coordinator(now).processVehicle(VEHICLE,now,100).state()).isEqualTo("RETRY_WAIT");
        jdbc.execute("drop trigger fail_controlled_page on trip_projection_progress");
        coordinatedUntilIdle(now.plusSeconds(1),100);
        assertThat(worker(now).status(VEHICLE).orElseThrow().totalFailures()).isEqualTo(1);
        // New input affecting a sealed trip is retained, but is not automatically unsealed.
        override(999,10,1900,12); var sealed=jdbc.queryForList("select * from trip_record");
        boolean paused=false;
        for (int page=0;page<100;page++) if (coordinator(now.plusSeconds(2)).processVehicle(VEHICLE,now.plusSeconds(2),1).state().equals("PAUSED")) { paused=true; break; }
        assertThat(paused).isTrue();
        assertThat(coordinator(now.plusSeconds(3600)).processVehicle(VEHICLE,now.plusSeconds(3600),100).state()).isEqualTo("PAUSED");
        assertThat(jdbc.queryForList("select * from trip_record")).isEqualTo(sealed);
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item where status='REVIEW'",Integer.class)).isEqualTo(1);
    }

    @Test void durableRoundRobinScanVisitsOneVehiclePerPollAndSurvivesRestart() {
        migrate(); local(); Instant now=processingTime();
        UUID another=UUID.fromString("10000000-0000-4000-8000-000000000003"), device=UUID.randomUUID();
        jdbc.update("insert into vehicle(id,public_id) values (?,?)",another,"round-robin");
        jdbc.update("insert into device(id,vehicle_id,public_id) values (?,?,?)",device,another,"round-robin-device");
        jdbc.update("insert into trip_policy(vehicle_id,primary_device_id) values (?,?)",another,device);
        assertThat(coordinator(now).poll(1).vehicle()).isEqualTo(VEHICLE);
        assertThat(jdbc.queryForObject("select last_vehicle_id from trip_processing_scan",UUID.class)).isEqualTo(VEHICLE);
        assertThat(coordinator(now).poll(1).vehicle()).isEqualTo(another);
        assertThat(coordinator(now).poll(1).state()).isEqualTo("NO_SELECTION");
        assertThat(coordinator(now).poll(1).vehicle()).isEqualTo(VEHICLE);
        assertThat(count("trip_processing_control")).isEqualTo(2); // empty/no-policy vehicle is not scanned
    }

    @Test void pausedVehicleCannotStarveNextVehicleInDurableScan() {
        migrate(); local(); Instant now=processingTime();
        UUID another=UUID.fromString("10000000-0000-4000-8000-000000000003"), device=UUID.randomUUID();
        jdbc.update("insert into vehicle(id,public_id) values (?,?)",another,"independent-work");
        jdbc.update("insert into device(id,vehicle_id,public_id) values (?,?,?)",device,another,"independent-device");
        jdbc.update("insert into trip_policy(vehicle_id,primary_device_id) values (?,?)",another,device);
        jdbc.update("insert into trip_processing_control(vehicle_id,stage,state,last_error_code) values (?,'FORWARD','PAUSED','PROCESSING_INVARIANT')",VEHICLE);
        assertThat(coordinator(now).poll(1).state()).isEqualTo("PAUSED");
        assertThat(coordinator(now).poll(1).vehicle()).isEqualTo(another);
        assertThat(worker(now).status(another)).isPresent();
        assertThat(worker(now).status(VEHICLE).orElseThrow().state()).isEqualTo("PAUSED");
    }

    @Test void coordinatorRejectsOversizedOrAmbientCallsBeforeMovingScanCursor() {
        migrate(); local(); Instant now=processingTime();
        assertThatThrownBy(() -> coordinator(now).poll(101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> coordinator(now).poll(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select last_vehicle_id from trip_processing_scan",UUID.class)).isNull();
        assertThat(count("trip_processing_control")).isZero();
    }

    @Test void separatelyCommittedHistoricChunksRepairTimerTimeoutIntoOneCanonicalTrip() {
        migrate(); local();
        var readings=JSON.readTree(Path.of("../contracts/examples/trip-short-input.json").toFile()).get("readings");
        for (int i=0;i<8;i++) store(new TelemetryValidator(JSON).validate(readings.get(i)));
        Instant now=processingTime();
        assertThat(coordinator(now).processVehicle(VEHICLE,now,100).stage()).isEqualTo(FORWARD);
        UUID original=jdbc.queryForObject("select id from trip_record where status='COMPLETED'",UUID.class);
        assertThat(jdbc.queryForObject("select completion_reason from trip_record where id=?",String.class,original)).isEqualTo("SIGNAL_TIMEOUT");
        for (int i=8;i<readings.size();i++) store(new TelemetryValidator(JSON).validate(readings.get(i)));
        coordinatedUntilIdle(now,1);
        assertThat(jdbc.queryForObject("select id from trip_record where status='COMPLETED'",UUID.class)).isEqualTo(original);
        assertThat(jdbc.queryForObject("select completion_reason from trip_record where id=?",String.class,original)).isEqualTo("ENGINE_OFF");
        var summary=JSON.readTree(jdbc.queryForObject("select summary::text from trip_record where id=?",String.class,original));
        assertThat(summary.get("sampleCount").intValue()).isEqualTo(27);
        assertThat(summary.get("speedAreaKphSeconds").decimalValue()).isEqualByComparingTo("1760");
        assertThat(count("trip_evidence")).isEqualTo(27);
        assertThat(jdbc.queryForObject("select count(*) from trip_correction_publication where phase='DONE'",Integer.class)).isEqualTo(1);
    }

    @Test void discardedHistoricStartCandidateCanBeRecoveredByNextCommittedChunk() {
        migrate(); local(); Instant base=Instant.parse("2026-10-20T12:00:00Z"), now=processingTime();
        store(sample(0,base)); coordinator(now).processVehicle(VEHICLE,now,100);
        UUID original=jdbc.queryForObject("select id from trip_record where status='DISCARDED'",UUID.class);
        store(sample(1,base.plusSeconds(2))); coordinatedUntilIdle(now,1);
        assertThat(jdbc.queryForObject("select id from trip_record where status='COMPLETED'",UUID.class)).isEqualTo(original);
        assertThat(count("trip_evidence")).isEqualTo(2);
        var summary=JSON.readTree(jdbc.queryForObject("select summary::text from trip_record where id=?",String.class,original));
        assertThat(summary.get("sampleCount").intValue()).isEqualTo(2);
    }

    @Test void sourceChangeAfterHistoricTimeoutIsReclassifiedWithoutMixingSources() {
        migrate(); local(); Instant base=Instant.parse("2026-10-20T12:00:00Z"), now=processingTime();
        store(sample(0,base)); store(sample(1,base.plusSeconds(2))); coordinator(now).processVehicle(VEHICLE,now,100);
        for (int seq=2;seq<4;seq++) store(new TelemetryValidator(JSON).validate(sample(seq,base.plusSeconds(seq*2)).payload().put("source","OBD")));
        coordinatedUntilIdle(now,1);
        var trips=jdbc.queryForList("select source,completion_reason from trip_record where status='COMPLETED' order by started_at");
        assertThat(trips).hasSize(2);
        assertThat(trips.getFirst()).containsEntry("source","SIMULATED").containsEntry("completion_reason","SOURCE_CHANGED");
        assertThat(trips.getLast()).containsEntry("source","OBD").containsEntry("completion_reason","SIGNAL_TIMEOUT");
        assertThat(count("trip_evidence")).isEqualTo(4);
    }

    @Test void candidateContinuationSurvivesAnEarlierCorrectionWithOnlyDiscardedSegments() {
        migrate(); local(); Instant base=Instant.parse("2026-10-20T12:00:00Z"), now=processingTime();
        store(sample(0,base)); coordinator(now).processVehicle(VEHICLE,now,100);
        String reboot="123e4567-e89b-42d3-a456-426614174001";
        store(new TelemetryValidator(JSON).validate(sample(1,base.plusSeconds(2)).payload().put("bootId",reboot)));
        coordinatedUntilIdle(now,1);
        assertThat(jdbc.queryForObject("select count(*) from trip_record where status='COMPLETED'",Integer.class)).isZero();
        store(new TelemetryValidator(JSON).validate(sample(2,base.plusSeconds(4)).payload().put("bootId",reboot)));
        coordinatedUntilIdle(now,1);
        var trip=jdbc.queryForMap("select * from trip_record where status='COMPLETED'");
        assertThat(((Timestamp)trip.get("started_at")).toInstant()).isEqualTo(base.plusSeconds(2));
        assertThat(count("trip_evidence")).isEqualTo(2);
    }

    void bulkRunningHistory(int samples) {
        Instant base=Instant.parse("2026-10-20T00:00:00Z");
        jdbc.update("""
                insert into telemetry_reading(id,vehicle_id,device_id,boot_id,sequence_number,recorded_at,source,payload)
                select gen_random_uuid(),?,?,?::uuid,n,?::timestamptz+n*interval '2 seconds','SIMULATED',
                ?::jsonb || jsonb_build_object('sequenceNumber',n,'recordedAt',to_char((?::timestamptz+n*interval '2 seconds') at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"'))
                from generate_series(0,?-1) n
                """,VEHICLE,DEVICE,sample(0,base).bootId(),Timestamp.from(base),sample(0,base).payload().toString(),Timestamp.from(base),samples);
    }
    tools.jackson.databind.JsonNode plan(String sql,Object... args) {
        return JSON.readTree(jdbc.queryForObject("explain (analyze,buffers,format json) "+sql,String.class,args)).get(0).get("Plan");
    }
    long visited(tools.jackson.databind.JsonNode node,String relation) {
        long result=0;
        if (node.has("Relation Name") && node.get("Relation Name").asText().equals(relation))
            result=(node.get("Actual Rows").longValue()+(node.has("Rows Removed by Filter") ? node.get("Rows Removed by Filter").longValue() : 0))*node.get("Actual Loops").longValue();
        for (var child : node.path("Plans")) result+=visited(child,relation);
        return result;
    }

    @Test void forwardPageQueryDoesNotVisitProcessedRawHistoryToFindSmallPendingTail() {
        migrate(); local(); bulkRunningHistory(5000);
        jdbc.execute("analyze telemetry_reading"); jdbc.execute("analyze trip_work_item");
        String pageSql=TripProjector.WORK_PAGE_SQL;
        var backlog=plan(pageSql,VEHICLE,Timestamp.from(processingTime()),10);
        jdbc.update("update trip_work_item w set status='PROCESSED' from telemetry_reading r where r.id=w.reading_id and r.sequence_number<4980");
        jdbc.execute("analyze telemetry_reading"); jdbc.execute("analyze trip_work_item");
        var query=plan(pageSql,VEHICLE,Timestamp.from(processingTime()),10);
        System.out.println("Trip audit full-backlog raw visits="+visited(backlog,"telemetry_reading")+", work visits="+visited(backlog,"trip_work_item"));
        System.out.println("Trip audit pending-tail raw visits="+visited(query,"telemetry_reading")+", work visits="+visited(query,"trip_work_item"));
        assertThat(visited(backlog,"telemetry_reading")).as("raw rows visited for a ten-reading backlog page").isLessThanOrEqualTo(100);
        assertThat(visited(query,"telemetry_reading")).as("raw rows visited for a ten-reading page").isLessThanOrEqualTo(100);
        assertThat(visited(backlog,"trip_work_item")).isLessThanOrEqualTo(100);
    }

    void competingPages(Instant now,int limit,int rounds) throws Exception {
        for (int round=0;round<rounds;round++) {
            var start=new java.util.concurrent.CountDownLatch(1);
            var calls=new java.util.ArrayList<java.util.concurrent.CompletableFuture<TripProcessingCoordinator.Page>>();
            for (int worker=0;worker<2;worker++) calls.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { assertThat(start.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                return coordinator(now).processVehicle(VEHICLE,now,limit);
            }));
            start.countDown();
            for (var call : calls) assertThat(call.get(15,java.util.concurrent.TimeUnit.SECONDS).state()).isNotEqualTo("PAUSED");
        }
    }

    @Test void competingCoordinatorsCannotDuplicateForwardTripsOrAcknowledgments() throws Exception {
        migrate(); local(); commute("short"); Instant now=processingTime();
        competingPages(now,7,8); coordinatedUntilIdle(now,7);
        assertThat(jdbc.queryForObject("select count(*) from trip_record where status='COMPLETED'",Integer.class)).isEqualTo(1);
        assertThat(count("trip_evidence")).isEqualTo(27);
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item where status<>'PROCESSED'",Integer.class)).isZero();
        var summary=JSON.readTree(jdbc.queryForObject("select summary::text from trip_record where status='COMPLETED'",String.class));
        assertThat(summary.get("speedAreaKphSeconds").decimalValue()).isEqualByComparingTo("1760");
        assertThat(jdbc.queryForObject("select count(*) from trip_record where status in ('CANDIDATE','ACTIVE')",Integer.class)).isZero();
    }

    @Test void competingCoordinatorsPublishOnlyOneCorrectionAndPreserveStableIdentity() throws Exception {
        correctedShortPlan(); UUID original=jdbc.queryForObject("select id from trip_record where status='COMPLETED'",UUID.class);
        Instant now=processingTime(); competingPages(now,1,12); coordinatedUntilIdle(now,1);
        assertThat(jdbc.queryForObject("select id from trip_record where status='COMPLETED'",UUID.class)).isEqualTo(original);
        assertThat(jdbc.queryForObject("select count(*) from trip_correction_publication where phase='DONE'",Integer.class)).isEqualTo(1);
        assertThat(count("trip_evidence")).isEqualTo(27);
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item where status<>'PROCESSED'",Integer.class)).isZero();
        var summary=JSON.readTree(jdbc.queryForObject("select summary::text from trip_record where id=?",String.class,original));
        assertThat(summary.get("speedAreaKphSeconds").decimalValue()).isEqualByComparingTo("1784");
    }

    @Test void lockedScanCheckpointSkipsWithoutMovingCursorOrStartingWork() throws Exception {
        migrate(); local(); Instant now=processingTime();
        try (var connection=data.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement=connection.createStatement()) { statement.execute("select * from trip_processing_scan for update"); }
            assertThat(coordinator(now).poll(1).state()).isEqualTo("NO_SELECTION");
            assertThat(count("trip_processing_control")).isZero();
            connection.rollback();
        }
        assertThat(coordinator(now).poll(1).vehicle()).isEqualTo(VEHICLE);
    }

    @Test void scanCommitsBeforeDelayedStageSoAnotherCoordinatorCanVisitNextVehicle() throws Exception {
        migrate(); local(); Instant now=processingTime();
        UUID another=UUID.fromString("10000000-0000-4000-8000-000000000003"),device=UUID.randomUUID();
        jdbc.update("insert into vehicle(id,public_id) values (?,?)",another,"scan-independent");
        jdbc.update("insert into device(id,vehicle_id,public_id) values (?,?,?)",device,another,"scan-independent-device");
        jdbc.update("insert into trip_policy(vehicle_id,primary_device_id) values (?,?)",another,device);
        var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        var delayed=org.mockito.Mockito.mock(TripProcessingWorker.class);
        org.mockito.Mockito.when(delayed.status(VEHICLE)).thenReturn(java.util.Optional.empty());
        org.mockito.Mockito.when(delayed.runPage(VEHICLE,FORWARD,now,1)).thenAnswer(call -> {
            entered.countDown(); assertThat(release.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return new TripProcessingWorker.Page("IDLE","BACKFILLING",0);
        });
        var first=new TripProcessingCoordinator(jdbc,transactions,delayed,java.time.Clock.fixed(now,java.time.ZoneOffset.UTC));
        var pending=java.util.concurrent.CompletableFuture.supplyAsync(() -> first.poll(1));
        try {
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(coordinator(now).poll(1).vehicle()).isEqualTo(another);
        } finally { release.countDown(); }
        assertThat(pending.get(5,java.util.concurrent.TimeUnit.SECONDS).vehicle()).isEqualTo(VEHICLE);
    }

    @Test void longDriveUsesBoundedPagesAndExactSummaryWithoutReferenceEvaluator() {
        migrate(); local(); bulkRunningHistory(2000); Instant now=processingTime(); coordinatedUntilIdle(now,100);
        var trip=jdbc.queryForMap("select * from trip_record where status='COMPLETED'");
        var summary=JSON.readTree(trip.get("summary").toString());
        assertThat(summary.get("sampleCount").intValue()).isEqualTo(2000);
        assertThat(summary.get("metrics").get("rpm").get("sum").decimalValue()).isEqualByComparingTo("3800000");
        assertThat(summary.get("durationSeconds").decimalValue()).isEqualByComparingTo("3998");
        assertThat(summary.get("speedAreaKphSeconds").decimalValue()).isEqualByComparingTo("47976");
        assertThat(summary.get("coveredSeconds").decimalValue()).isEqualByComparingTo("3998");
        assertThat(summary.get("metrics").get("controlModuleVoltageV").get("count").intValue()).isZero();
        assertThat(count("trip_evidence")).isEqualTo(2000);
        assertThat(trip.get("finalization")).isEqualTo("FINAL");
        assertThat(jdbc.queryForObject("select length(checkpoint::text) from trip_projection_progress",Integer.class)).isLessThan(10000);
    }

    @Test void upgradeHydratesOldPendingQueueInBoundedRestartablePagesWithoutChangingWork() {
        Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration").target("909").load().migrate();
        local(); commute("short"); jdbc.update("update trip_backfill_progress set complete=true where vehicle_id=?",VEHICLE);
        String workSql="select reading_id,status,accepted_at,next_attempt_at,attempts,last_error,acceptance_order from trip_work_item order by reading_id";
        var before=jdbc.queryForList(workSql); migrate();
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item where sort_at is null",Integer.class)).isEqualTo(before.size());
        assertThat(backfill().enqueuePage(VEHICLE,2).visited()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item where sort_at is not null",Integer.class)).isEqualTo(2);
        for (int page=0;page<100;page++) { var result=backfill().enqueuePage(VEHICLE,2); assertThat(result.visited()).isLessThanOrEqualTo(2); if (result.complete()) break; }
        assertThat(jdbc.queryForList(workSql)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item where sort_at is null",Integer.class)).isZero();
        coordinatedUntilIdle(processingTime(),7); assertThat(count("trip_evidence")).isEqualTo(27);
    }

    @Test void failedQueueHydrationRollsBackMetadataAndCanResumeAfterRestart() {
        Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration").target("909").load().migrate();
        local(); commute("short"); migrate();
        jdbc.execute("create function fail_order_recovery() returns trigger language plpgsql as $$ begin raise exception 'test queue recovery failure'; end $$");
        jdbc.execute("create trigger fail_order_recovery after update on trip_work_item for each row execute function fail_order_recovery()");
        assertThatThrownBy(() -> backfill().enqueuePage(VEHICLE,2)).hasMessageContaining("queue recovery failure");
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item where sort_at is not null",Integer.class)).isZero();
        jdbc.execute("drop trigger fail_order_recovery on trip_work_item");
        assertThat(backfill().enqueuePage(VEHICLE,2).visited()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item where sort_at is not null",Integer.class)).isEqualTo(2);
    }

    @Test void upgradeForcesLegacyReadyPlanToRediscoverPreviouslyUnfencedArrivals() {
        Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration").target("904").load().migrate();
        local(); commute("short");
        // Seed a legacy projection snapshot without invoking today's projector
        // against an intentionally old schema that lacks its new queue columns.
        UUID oldTrip=UUID.randomUUID();
        jdbc.update("""
                insert into trip_record(id,vehicle_id,primary_device_id,anchor_boot,anchor_sequence,started_at,observed_through,ended_at,confirmed_at,
                source,status,completion_reason,algorithm_version,summary,quality)
                values (?,?,?,'123e4567-e89b-42d3-a456-426614174000',2,'2026-10-20T12:00:04Z','2026-10-20T12:00:56Z',
                '2026-10-20T12:00:56Z','2026-10-20T12:00:58Z','SIMULATED','COMPLETED','ENGINE_OFF','trip-v1-draft','{}','[]')
                """,oldTrip,VEHICLE,DEVICE);
        jdbc.update("""
                insert into trip_projection_progress(vehicle_id,policy_revision,cursor_at,cursor_boot,cursor_sequence,checkpoint)
                values (?,1,'2026-10-20T12:01:00Z','123e4567-e89b-42d3-a456-426614174000',30,?::jsonb)
                """,VEHICLE,JSON.writeValueAsString(dev.autopulse.trip.TripFold.Checkpoint.empty()));
        jdbc.update("update trip_work_item set status='PROCESSED'");
        override(999,10,1900,12);
        jdbc.update("update trip_work_item w set status='REVIEW' from telemetry_reading r where r.id=w.reading_id and r.sequence_number=999");
        var work = jdbc.queryForMap("select reading_id from trip_work_item where status='REVIEW'");
        UUID plan = UUID.randomUUID();
        jdbc.update("""
                insert into trip_correction_plan(id,vehicle_id,requested_work_id,primary_device_id,policy_revision,
                projection_cursor_at,planned_as_of,window_start,window_end,phase)
                values (?,?,?,?,1,?,?,?,?,'READY')
                """,plan,VEHICLE,work.get("reading_id"),DEVICE,
                Timestamp.from(Instant.parse("2026-10-20T12:01:00Z")),Timestamp.from(processingTime()),
                Timestamp.from(Instant.parse("2026-10-20T12:00:04Z")),Timestamp.from(Instant.parse("2026-10-20T12:00:56Z")));
        override(1000,76,1900,12); override(1001,100,1900,12);
        migrate();
        assertThat(replayer().replayPage(VEHICLE,1).state()).isEqualTo("PLAN_NOT_READY");
        var rebuilt = planned(1); assertThat(rebuilt.planId()).isEqualTo(plan);
        assertThat(rebuilt.to()).isEqualTo(Instant.parse("2026-10-20T12:01:40Z"));
        assertThat(staged(1).state()).isEqualTo("STAGED");
        assertThat(count("trip_record")).isEqualTo(1);
    }
}
