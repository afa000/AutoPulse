package dev.autopulse;

import dev.autopulse.ingestion.TelemetryController;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/local")
class TelemetryApiTests {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    final HttpClient client = HttpClient.newHttpClient();
    final String batchPath = "/api/v1/device/telemetry/batches";
    String testBoot;

    @BeforeEach void cleanReadings() {
        // Raw fixture cleanup must not erase the durable identity ledger.
        // Each case represents a fresh boot; retries within the case reuse it.
        testBoot=java.util.UUID.randomUUID().toString();
        jdbc.update("delete from trip_work_item");
        jdbc.update("delete from telemetry_reading");
    }

    ObjectNode sample(int sequence, String timestamp) {
        ObjectNode input = (ObjectNode) mapper.readTree(Path.of("../contracts/examples/telemetry-sample.json").toFile());
        return input.put("bootId",testBoot).put("sequenceNumber", sequence).put("recordedAt", timestamp);
    }
    ObjectNode sample(int sequence) { return sample(sequence, "2026-09-10T12:30:00Z"); }
    String batch(JsonNode... readings) {
        ObjectNode envelope = mapper.createObjectNode();
        var items = envelope.putArray("readings");
        for (var reading : readings) items.add(reading);
        return envelope.toString();
    }
    HttpResponse<String> post(String body) throws Exception { return post(body, "application/json"); }
    HttpResponse<String> post(String body, String media) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + batchPath))
                .header("Content-Type", media).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode body(HttpResponse<String> response) { return mapper.readTree(response.body()); }
    int count() { return jdbc.queryForObject("select count(*) from telemetry_reading", Integer.class); }
    JsonNode latest() throws Exception { return body(get("/api/v1/vehicles/venue-2021/telemetry/latest")).get("reading"); }

    @Test void normalBatchAndDelayedOlderMeasurement() throws Exception {
        var response = post(batch(sample(2, "2026-09-10T12:31:00Z"), sample(1)));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).get("accepted").intValue()).isEqualTo(2);
        assertThat(count()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item", Integer.class)).isEqualTo(2);
        assertThat(latest().get("sequenceNumber").intValue()).isEqualTo(2);
        post(batch(sample(3, "2026-09-10T12:29:00Z")));
        assertThat(latest().get("sequenceNumber").intValue()).isEqualTo(2);
    }

    @Test void exactReplayAndChangedContentConflict() throws Exception {
        post(batch(sample(1)));
        var replay = body(post(batch(sample(1))));
        assertThat(replay.get("duplicate").intValue()).isEqualTo(1);
        var conflict = body(post(batch(sample(1).put("rpm", 999))));
        assertThat(conflict.get("results").get(0).get("code").stringValue()).isEqualTo("IDENTITY_CONFLICT");
        assertThat(latest().get("rpm").doubleValue()).isEqualTo(1900);
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from trip_work_item", Integer.class)).isEqualTo(1);
    }

    @Test void retainedIdentityMixedBatchDoesNotResurrectRemovedRawFixture() throws Exception {
        assertThat(body(post(batch(sample(1)))).get("accepted").intValue()).isEqualTo(1);
        var identity=jdbc.queryForMap("select * from telemetry_replay_identity where boot_id=?::uuid",testBoot);
        // This disposable, unprojected fixture is not an operational retention job.
        jdbc.update("delete from trip_work_item"); jdbc.update("delete from telemetry_reading");
        var response=post(batch(sample(1),sample(1).put("rpm",999),sample(2)));
        assertThat(response.statusCode()).isEqualTo(200);
        var result=body(response);
        assertThat(result.get("accepted").intValue()).isEqualTo(1);
        assertThat(result.get("duplicate").intValue()).isEqualTo(1);
        assertThat(result.get("rejected").intValue()).isEqualTo(1);
        assertThat(result.get("results").get(1).get("code").stringValue()).isEqualTo("IDENTITY_CONFLICT");
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select sequence_number::int from telemetry_reading",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForMap("select * from telemetry_replay_identity where boot_id=?::uuid and sequence_number=1",testBoot)).isEqualTo(identity);
    }

    @Test void mixedBatchAcknowledgesEachOriginalIndex() throws Exception {
        post(batch(sample(1)));
        var result = body(post(batch(sample(1), sample(3).put("rpm", -1), sample(2))));
        assertThat(result.get("accepted").intValue()).isEqualTo(1);
        assertThat(result.get("duplicate").intValue()).isEqualTo(1);
        assertThat(result.get("rejected").intValue()).isEqualTo(1);
        assertThat(result.get("results").get(1).get("index").intValue()).isEqualTo(1);
        assertThat(result.get("results").get(1).get("errors").get(0).get("field").stringValue()).isEqualTo("rpm");
        assertThat(count()).isEqualTo(2);
    }

    @Test void concurrentDuplicatesHaveOneDatabaseRow() {
        String request = batch(sample(1));
        List<CompletableFuture<JsonNode>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) calls.add(CompletableFuture.supplyAsync(() -> {
            try { var response = post(request); assertThat(response.statusCode()).isEqualTo(200); return body(response); }
            catch (Exception error) { throw new RuntimeException(error); }
        }));
        var results = calls.stream().map(CompletableFuture::join).toList();
        assertThat(results.stream().mapToInt(item -> item.get("accepted").intValue()).sum()).isEqualTo(1);
        assertThat(results.stream().mapToInt(item -> item.get("duplicate").intValue()).sum()).isEqualTo(7);
        assertThat(count()).isEqualTo(1);
    }

    @Test void associationErrorsAndNoDataStatesAreClear() throws Exception {
        var result = body(post(batch(sample(1).put("vehicleId", "missing"), sample(2).put("deviceId", "missing"), sample(3).put("vehicleId", "empty-vehicle"))));
        assertThat(result.get("results").get(0).get("code").stringValue()).isEqualTo("UNKNOWN_VEHICLE");
        assertThat(result.get("results").get(1).get("code").stringValue()).isEqualTo("UNKNOWN_DEVICE");
        assertThat(result.get("results").get(2).get("code").stringValue()).isEqualTo("DEVICE_VEHICLE_MISMATCH");
        assertThat(count()).isZero();
        assertThat(get("/api/v1/vehicles/empty-vehicle/telemetry/latest").statusCode()).isEqualTo(204);
        assertThat(get("/api/v1/vehicles/missing/telemetry/latest").statusCode()).isEqualTo(404);
    }

    @Test void nullZeroFalseSurviveStorageWithoutCarryingOldValues() throws Exception {
        post(batch(sample(1).put("rpm", 2000)));
        ObjectNode next = sample(2, "2026-09-10T12:31:00Z").put("speedKph", 0).put("milOn", false);
        next.remove("rpm");
        post(batch(next));
        var reading = latest();
        assertThat(reading.get("rpm").isNull()).isTrue();
        assertThat(reading.get("speedKph").doubleValue()).isZero();
        assertThat(reading.get("milOn").booleanValue()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from telemetry_reading where rpm is null and speed_kph=0 and mil_on=false", Integer.class)).isEqualTo(1);
    }

    @Test void requestErrorsAndCorrelationAreBounded() throws Exception {
        for (String invalid : List.of("{", "{}", "{\"readings\":[]}", "{\"readings\":[{}]} {}")) {
            var response = post(invalid);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(body(response).get("correlationId").stringValue()).isEqualTo(response.headers().firstValue("X-Correlation-Id").orElseThrow());
        }
        assertThat(post("x".repeat(TelemetryController.MAX_BYTES + 1)).statusCode()).isEqualTo(413);
        assertThat(post(batch(sample(1)), "text/plain").statusCode()).isEqualTo(415);
        assertThat(count()).isZero();
    }

    @Test void systemicFailureRollsBackAndIsRetriable() throws Exception {
        jdbc.execute("CREATE FUNCTION test_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.sequence_number=99 THEN RAISE EXCEPTION 'test outage'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER test_fail_trigger BEFORE INSERT ON telemetry_reading FOR EACH ROW EXECUTE FUNCTION test_fail()");
        try {
            var response = post(batch(sample(1), sample(99)));
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(count()).isZero();
            assertThat(jdbc.queryForObject("select count(*) from trip_work_item", Integer.class)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER test_fail_trigger ON telemetry_reading");
            jdbc.execute("DROP FUNCTION test_fail()");
        }
        assertThat(body(post(batch(sample(1), sample(99)))).get("accepted").intValue()).isEqualTo(2);
    }

    @Test void bootTieBreakerIsDeterministic() throws Exception {
        var first = sample(99).put("bootId", "00000000-0000-4000-8000-000000000001");
        var second = sample(0).put("bootId", "00000000-0000-4000-8000-000000000002");
        post(batch(second, first));
        assertThat(latest().get("bootId").stringValue()).endsWith("0002");
    }

    @Test void decimalDifferencesAreIdentityConflicts() throws Exception {
        String first = batch(sample(1)).replace("1900", "1900.0000000000000001");
        String changed = batch(sample(1)).replace("1900", "1900.0000000000000002");
        assertThat(body(post(first)).get("accepted").intValue()).isEqualTo(1);
        assertThat(get("/api/v1/vehicles/venue-2021/telemetry/latest").body()).contains("1900.0000000000000001");
        assertThat(body(post(changed)).get("results").get(0).get("code").stringValue()).isEqualTo("IDENTITY_CONFLICT");
    }

    @Test void microsecondOrderingCanonicalReplayAndFinerPrecisionRejection() throws Exception {
        var newer = sample(0, "2026-09-10T08:30:00.000002-04:00");
        var older = sample(1, "2026-09-10T12:30:00.000001Z");
        var tooFine = sample(2, "2026-09-10T12:30:00.0000002Z");
        var result = body(post(batch(newer, older, tooFine)));
        assertThat(result.get("accepted").intValue()).isEqualTo(2);
        assertThat(result.get("rejected").intValue()).isEqualTo(1);
        assertThat(latest().get("sequenceNumber").intValue()).isZero();
        assertThat(latest().get("recordedAt").stringValue()).isEqualTo("2026-09-10T12:30:00.000002Z");
        var received = jdbc.queryForObject("select received_at::text from telemetry_reading where sequence_number=0", String.class);
        newer.put("recordedAt", "2026-09-10t12:30:00.000002z");
        assertThat(body(post(batch(newer))).get("duplicate").intValue()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select received_at::text from telemetry_reading where sequence_number=0", String.class)).isEqualTo(received);
        newer.put("recordedAt", "2026-09-10T12:30:00.000003Z");
        assertThat(body(post(batch(newer))).get("results").get(0).get("code").stringValue()).isEqualTo("IDENTITY_CONFLICT");
        assertThat(count()).isEqualTo(2);
    }

    @Test void exactNumericBoundsAndStorageLimitsRejectOnlyInvalidItems() throws Exception {
        // Replace existing tokens, not duplicate keys or pre-rounded doubles.
        String invalidNegative = sample(1).toString().replace("1900", "-1e-400");
        String invalidPercent = sample(2).put("engineLoadPct", 100).toString()
                .replace("\"engineLoadPct\":100", "\"engineLoadPct\":100.0000000000000001");
        String overflow = sample(3).toString().replace("1900", "1e400");
        String underflow = sample(4).toString().replace("1900", "1e-400");
        String request = "{\"readings\":[" + sample(0).put("coolantTempC", -20) + "," + invalidNegative + "," + invalidPercent + "," + overflow + "," + underflow + "," + sample(5) + "]}";
        var response = post(request);
        assertThat(response.statusCode()).isEqualTo(200);
        var result = body(response);
        assertThat(result.get("accepted").intValue()).isEqualTo(2);
        assertThat(result.get("rejected").intValue()).isEqualTo(4);
        assertThat(count()).isEqualTo(2);
    }

    @Test void representableSubnormalAndUpperDoubleEdgeSurviveJdbc() throws Exception {
        String subnormal = sample(1).toString().replace("1900", "3e-324");
        String upper = sample(2).toString().replace("1900", "1.7976931348623158e308");
        var response = post("{\"readings\":[" + subnormal + "," + upper + "]}");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).get("accepted").intValue()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select rpm from telemetry_reading where sequence_number=1", Double.class)).isGreaterThan(0);
        assertThat(count()).isEqualTo(2);
    }
}
