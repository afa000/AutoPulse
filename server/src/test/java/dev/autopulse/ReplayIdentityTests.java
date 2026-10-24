package dev.autopulse;

import dev.autopulse.ingestion.IngestionService;
import dev.autopulse.live.TelemetryCommitted;
import dev.autopulse.telemetry.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties="spring.config.import=")
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
class ReplayIdentityTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired TelemetryValidator validator;
    @Autowired TelemetryRepository repository;
    @Autowired IngestionService ingestion;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationEvents events;
    UUID vehicle,device,boot;
    String publicVehicle,publicDevice;

    @BeforeEach void setup() {
        vehicle=UUID.randomUUID(); device=UUID.randomUUID(); boot=UUID.randomUUID();
        publicVehicle="replay-"+vehicle; publicDevice="replay-"+device;
        jdbc.update("insert into vehicle(id,public_id) values (?,?)",vehicle,publicVehicle);
        jdbc.update("insert into device(id,vehicle_id,public_id) values (?,?,?)",device,vehicle,publicDevice);
    }
    ObjectNode sample(int sequence) {
        return ((ObjectNode)mapper.readTree(Path.of("../contracts/examples/telemetry-sample.json").toFile()))
                .put("vehicleId",publicVehicle).put("deviceId",publicDevice).put("bootId",boot.toString())
                .put("sequenceNumber",sequence).put("recordedAt","2026-10-20T12:00:00.123456Z");
    }
    String store(ObjectNode node) { return repository.store(vehicle,device,validator.validate(node)); }
    int count(String table) { return jdbc.queryForObject("select count(*) from "+table+" where vehicle_id=?",Integer.class,vehicle); }
    void removeRawFixture() {
        // Only this test's unprojected synthetic rows in disposable PostgreSQL.
        // Not a retention implementation or an owner-database deletion recipe.
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.update("delete from trip_work_item where vehicle_id=?",vehicle);
            jdbc.update("delete from telemetry_reading where vehicle_id=?",vehicle);
        });
    }
    List<String> state() {
        var result=new ArrayList<String>();
        for (String table : List.of("telemetry_replay_identity","telemetry_replay_backfill","telemetry_reading",
                "trip_work_item","trip_vehicle_activity","vehicle_read_generation"))
            result.add(jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text from "+table+" t where vehicle_id=?",String.class,vehicle));
        return result;
    }
    @Test void retainedRetryAfterRawRemovalDoesNotResurrectOrPublishOrChangeReceipts() {
        var input=sample(1);
        assertThat(ingestion.ingest(mapper.createArrayNode().add(input),UUID.randomUUID()).accepted()).isEqualTo(1);
        removeRawFixture(); var before=state();
        var result=ingestion.ingest(mapper.createArrayNode().add(input).add(input.deepCopy().put("rpm",999)),UUID.randomUUID());
        assertThat(result.accepted()).isZero(); assertThat(result.duplicate()).isEqualTo(1); assertThat(result.rejected()).isEqualTo(1);
        assertThat(result.results().get(1).code()).isEqualTo("IDENTITY_CONFLICT");
        assertThat(events.stream(TelemetryCommitted.class).count()).isEqualTo(1);
        assertThat(state()).isEqualTo(before);
        assertThat(new TelemetryRepository(new NamedParameterJdbcTemplate(jdbc),mapper)
                .store(vehicle,device,validator.validate(input))).isEqualTo("duplicate");
        assertThat(repository.latest(vehicle)).isEmpty(); // No fake raw/live history restoration.
    }
    @Test void retainedComparisonPreservesCanonicalAndExactDecimalSemantics() {
        var input=sample(1).put("sequenceNumber",new BigInteger("1234567890123456789012345678901234567890"))
                .put("rpm",new BigDecimal("1.00")).put("speedKph",0).put("milOn",false).putNull("mafGps");
        assertThat(store(input)).isEqualTo("accepted"); removeRawFixture(); var before=state();
        var equal=input.deepCopy().put("rpm",new BigDecimal("1.0"))
                .put("recordedAt","2026-10-20T08:00:00.123456-04:00").put("bootId",boot.toString().toUpperCase(Locale.ROOT));
        equal.remove("mafGps"); equal.put("ignoredExtraField","not canonical evidence");
        assertThat(store(equal)).isEqualTo("duplicate");
        for (var changed : List.of(input.deepCopy().put("rpm",new BigDecimal("1.000000000000000000000000000000000000001")),
                input.deepCopy().putNull("rpm"),input.deepCopy().putNull("speedKph"),input.deepCopy().put("milOn",true),
                input.deepCopy().put("source","OBD"),input.deepCopy().put("recordedAt","2026-10-20T12:00:00.123457Z")))
            assertThat(store(changed)).isEqualTo("conflict");
        assertThat(state()).isEqualTo(before);
    }
    List<String> race(List<ObjectNode> nodes) throws Exception {
        try (var executor=Executors.newFixedThreadPool(nodes.size())) {
            var go=new CountDownLatch(1); var futures=new ArrayList<Future<String>>();
            for (var node : nodes) futures.add(executor.submit(() -> { if (!go.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("start timed out"); return store(node); }));
            go.countDown(); var results=new ArrayList<String>();
            for (var future : futures) results.add(future.get(15,TimeUnit.SECONDS));
            return results;
        }
    }
    @Test void concurrentFirstSubmissionsHaveExactlyOneAcceptedIdentity() throws Exception {
        var nodes=new ArrayList<ObjectNode>();
        for (int i=0;i<8;i++) nodes.add(sample(1).put("rpm",i%2==0?1000:2000));
        var statuses=race(nodes);
        assertThat(Collections.frequency(statuses,"accepted")).isEqualTo(1);
        assertThat(Collections.frequency(statuses,"duplicate")).isEqualTo(3);
        assertThat(Collections.frequency(statuses,"conflict")).isEqualTo(4);
        assertThat(count("telemetry_reading")).isEqualTo(1); assertThat(count("telemetry_replay_identity")).isEqualTo(1);
        assertThat(count("trip_work_item")).isEqualTo(1);
    }
    @Test void concurrentRetainedSubmissionsNeverRecreateRawOrWork() throws Exception {
        assertThat(store(sample(1))).isEqualTo("accepted"); removeRawFixture(); var before=state();
        var nodes=new ArrayList<ObjectNode>();
        for (int i=0;i<8;i++) nodes.add(i%2==0?sample(1):sample(1).put("rpm",999));
        var statuses=race(nodes);
        assertThat(Collections.frequency(statuses,"duplicate")).isEqualTo(4);
        assertThat(Collections.frequency(statuses,"conflict")).isEqualTo(4);
        assertThat(state()).isEqualTo(before);
    }
    void waitingWriter(boolean rollback, boolean scaled) throws Exception {
        var release=new CountDownLatch(1); var inserted=new CountDownLatch(1);
        try (var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
                String result=store(sample(1)); inserted.countDown();
                try { if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("fixture timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                if (rollback) tx.setRollbackOnly(); return result;
            }));
            try {
                assertThat(inserted.await(5,TimeUnit.SECONDS)).isTrue();
                var second=executor.submit(() -> {
                    if (!scaled) return store(sample(1));
                    var reading=validator.validate(sample(1));
                    int insertedRows=jdbc.update("""
                            insert into telemetry_reading(id,vehicle_id,device_id,boot_id,sequence_number,recorded_at,source,payload)
                            values (?,?,?,?,?,?,'SIMULATED',?::jsonb)
                            on conflict (device_id,boot_id,sequence_number) do nothing
                            """,UUID.randomUUID(),vehicle,device,boot,new BigDecimal("1.00"),
                            java.sql.Timestamp.from(reading.recordedAt()),reading.payload().toString());
                    return insertedRows==0?"duplicate":"accepted";
                });
                // Observe a real database wait, not an assumed sleep-based ordering.
                await().atMost(5,TimeUnit.SECONDS).until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from pg_locks where locktype='advisory' and not granted)",Boolean.class)));
                release.countDown();
                assertThat(first.get(10,TimeUnit.SECONDS)).isEqualTo("accepted");
                assertThat(second.get(10,TimeUnit.SECONDS)).isEqualTo(rollback?"accepted":"duplicate");
            } finally { release.countDown(); }
        }
        assertThat(count("telemetry_reading")).isEqualTo(1); assertThat(count("telemetry_replay_identity")).isEqualTo(1);
        assertThat(count("trip_work_item")).isEqualTo(1);
    }
    @Test void waiterSeesCommittedLedgerBeforeAttemptingAnotherInsert() throws Exception { waitingWriter(false,false); }
    @Test void waiterMayAcceptIdentityAfterFirstWriterRollsBack() throws Exception { waitingWriter(true,false); }
    @Test void integralNumericScaleVariantsShareDeviceGateAndNativeIdentity() throws Exception { waitingWriter(false,true); }
    @Test void ignoredRawPrimaryKeyCollisionDoesNotReserveAnotherIdentity() {
        assertThat(store(sample(1))).isEqualTo("accepted");
        UUID existing=jdbc.queryForObject("select id from telemetry_reading where vehicle_id=?",UUID.class,vehicle);
        var reading=validator.validate(sample(2));
        assertThat(jdbc.update("""
                insert into telemetry_reading(id,vehicle_id,device_id,boot_id,sequence_number,recorded_at,source,payload)
                values (?,?,?,?,2,?,'SIMULATED',?::jsonb) on conflict (id) do nothing
                """,existing,vehicle,device,boot,java.sql.Timestamp.from(reading.recordedAt()),reading.payload().toString())).isZero();
        assertThat(count("telemetry_replay_identity")).isEqualTo(1);
        assertThat(count("trip_work_item")).isEqualTo(1);
        assertThat(store(sample(2))).isEqualTo("accepted");
    }
    @Test void largeSameDeviceTransactionUsesOneGateLockNotOnePerReading() {
        var reading=validator.validate(sample(1));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.update("""
                    insert into telemetry_reading(id,vehicle_id,device_id,boot_id,sequence_number,recorded_at,source,payload)
                    select gen_random_uuid(),?,?,?,n,?,'SIMULATED',jsonb_set(?::jsonb,'{sequenceNumber}',to_jsonb(n))
                    from generate_series(1,2000) n
                    """,vehicle,device,boot,java.sql.Timestamp.from(reading.recordedAt()),reading.payload().toString());
            assertThat(jdbc.queryForObject("select count(*) from pg_locks where pid=pg_backend_pid() and locktype='advisory' and granted",Integer.class)).isEqualTo(1);
            assertThat(count("telemetry_reading")).isEqualTo(2000); assertThat(count("telemetry_replay_identity")).isEqualTo(2000);
            tx.setRollbackOnly();
        });
        assertThat(count("telemetry_replay_identity")).isZero(); assertThat(count("trip_work_item")).isZero();
    }
}
