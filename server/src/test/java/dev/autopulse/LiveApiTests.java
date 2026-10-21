package dev.autopulse;

import dev.autopulse.ingestion.IngestionService;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.flyway.locations=classpath:db/migration,classpath:db/local", "spring.config.import="})
class LiveApiTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired IngestionService ingestion;
    @Autowired PlatformTransactionManager transactions;
    final HttpClient http = HttpClient.newHttpClient();
    @BeforeEach void clean() { jdbc.update("delete from telemetry_reading"); }

    ObjectNode sample(int sequence) {
        return ((ObjectNode) mapper.readTree(Path.of("../contracts/examples/telemetry-sample.json").toFile()))
                .put("sequenceNumber", sequence);
    }
    void ingest(ObjectNode sample) { ingestion.ingest(mapper.createArrayNode().add(sample), UUID.randomUUID()); }
    URI uri(String vehicle) { return URI.create("http://127.0.0.1:" + port + "/api/v1/vehicles/" + vehicle + "/live"); }

    class Stream implements AutoCloseable {
        final InputStream body;
        final ArrayBlockingQueue<String> data = new ArrayBlockingQueue<>(32);
        final Thread reader;
        Stream(String slug) throws Exception {
            var response = http.send(HttpRequest.newBuilder(uri(slug)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("content-type").orElseThrow()).startsWith("text/event-stream");
            body = response.body();
            reader = Thread.startVirtualThread(() -> {
                try (var lines = new BufferedReader(new InputStreamReader(body, java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) if (line.startsWith("data:")) data.offer(line.substring(5).strip());
                } catch (java.io.IOException closed) { /* normal stream shutdown */ }
            });
        }
        String next() throws Exception {
            String value = data.poll(5, TimeUnit.SECONDS);
            assertThat(value).as("SSE JSON received").isNotNull();
            return value;
        }
        public void close() throws Exception { body.close(); reader.join(1000); }
    }

    @Test void unknownEmptyInitialUpdateReconnectAndIndependentVehicle() throws Exception {
        var unknown = http.send(HttpRequest.newBuilder(uri("missing")).header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).contains("UNKNOWN_VEHICLE", "correlationId");
        try (var one = new Stream("venue-2021"); var other = new Stream("empty-vehicle")) {
            assertThat(one.next()).contains("vehicleId"); assertThat(other.next()).contains("empty-vehicle");
            ingest(sample(1));
            assertThat(mapper.readTree(one.next()).get("reading").get("sequenceNumber").intValue()).isEqualTo(1);
            assertThat(other.data.poll(400, TimeUnit.MILLISECONDS)).isNull();
        }
        try (var reconnected = new Stream("venue-2021")) {
            assertThat(mapper.readTree(reconnected.next()).get("reading").get("sequenceNumber").intValue()).isEqualTo(1);
        }
    }

    @Test void duplicateOlderRollbackTieAndConcurrentCommitSemantics() throws Exception {
        ingest(sample(1));
        try (var stream = new Stream("venue-2021")) {
            String initial = stream.next();
            ingest(sample(1)); // duplicate
            ingest(sample(9).put("rpm", -1)); // rejected: no notification
            ingest(sample(2).put("recordedAt", "2026-09-10T12:29:00Z")); // accepted older
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                ingest(sample(100).put("recordedAt", "2026-09-10T13:00:00Z"));
                status.setRollbackOnly();
            });
            assertThat(stream.data.poll(600, TimeUnit.MILLISECONDS)).isNull();
            assertThat(jdbc.queryForObject("select count(*) from telemetry_reading where sequence_number=100", Integer.class)).isZero();
            // Full boot UUID tie-breaker beats a globally larger sequence number.
            ingest(sample(0).put("bootId", "ffffffff-ffff-4fff-8fff-ffffffffffff"));
            String tied = stream.next();
            assertThat(mapper.readTree(tied).get("reading").get("sequenceNumber").intValue()).isZero();
            jdbc.update("insert into device(id,vehicle_id,public_id) select 'ffffffff-ffff-4fff-8fff-ffffffffffff'::uuid,id,'sim-device-002' from vehicle where public_id='venue-2021'");
            ingest(sample(0).put("deviceId", "sim-device-002").put("bootId", "00000000-0000-4000-8000-000000000001"));
            assertThat(mapper.readTree(stream.next()).get("reading").get("deviceId").stringValue()).isEqualTo("sim-device-002");
            var a = Thread.startVirtualThread(() -> ingest(sample(3).put("recordedAt", "2026-09-10T12:31:00.000001Z")));
            var b = Thread.startVirtualThread(() -> ingest(sample(4).put("recordedAt", "2026-09-10T12:31:00.000002Z")));
            a.join(); b.join();
            assertThat(jdbc.queryForObject("select count(*) from telemetry_reading where sequence_number in (3,4)", Integer.class)).isEqualTo(2);
            String next = stream.next();
            if (mapper.readTree(next).get("reading").get("sequenceNumber").intValue() == 3) next = stream.next();
            assertThat(mapper.readTree(next).get("reading").get("sequenceNumber").intValue()).isEqualTo(4);
            assertThat(initial).contains("receivedAt");
        }
    }
}
