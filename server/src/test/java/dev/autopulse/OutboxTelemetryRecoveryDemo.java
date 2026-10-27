package dev.autopulse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.time.Instant;
import java.time.Duration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import dev.autopulse.diagnostic.DiagnosticProjector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** Explicit disposable integration gate: -Dtest=OutboxTelemetryRecoveryDemo. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.flyway.locations=classpath:db/migration,classpath:db/local", "spring.config.import=", "autopulse.trips.processing-enabled=false", "autopulse.diagnostics.processing-enabled=false"})
class OutboxTelemetryRecoveryDemo {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired DiagnosticProjector projector;
    @TempDir Path temporary;

    Process run(String phase, Path database, Path marker, UUID boot, Path output) throws Exception {
        return new ProcessBuilder(System.getProperty("autopulse.python", "python"), "../scripts/outbox_checkpoint_probe.py",
                "--server-url", "http://127.0.0.1:" + port, "--database", database.toString(), "--marker", marker.toString(),
                "--boot", boot.toString(), "--phase", phase, "--isolated-demo")
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    }

    @Test void killedPythonRetriesExactIdentityAcrossThreeRealHttpBoundaries() throws Exception {
        for (String phase : new String[]{"before", "committed", "receipt"}) {
            UUID boot = UUID.randomUUID();
            Path database = temporary.resolve(phase + ".sqlite"), marker = temporary.resolve(phase + ".ready"), output = temporary.resolve(phase + ".log");
            Process child = run(phase, database, marker, boot, output);
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (!Files.exists(marker) && child.isAlive() && System.nanoTime() < deadline) Thread.sleep(25);
                assertThat(Files.exists(marker)).withFailMessage(() -> "Child never reached " + phase + ": " + read(output)).isTrue();
            } finally {
                child.destroyForcibly();
                assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
            }
            int before = jdbc.queryForObject("select count(*) from telemetry_reading where boot_id=?", Integer.class, boot);
            assertThat(before).isEqualTo(phase.equals("before") ? 0 : 1);
            Process retry = run("resume", database, marker, boot, output);
            try {
                assertThat(retry.waitFor(30, TimeUnit.SECONDS)).isTrue();
                String evidence = read(output);
                assertThat(retry.exitValue()).withFailMessage(evidence).isZero();
                assertThat(evidence).contains("OUTCOME settled", "REMAINING diagnostic", phase.equals("before") ? "\"accepted\":1" : "\"duplicate\":1");
                assertThat(jdbc.queryForObject("select count(*) from telemetry_reading where boot_id=?", Integer.class, boot)).isEqualTo(1);
                assertThat(jdbc.queryForObject("select count(*) from telemetry_reading where boot_id=? and sequence_number=77 and rpm=1900 and mil_on=false", Integer.class, boot)).isEqualTo(1);
                System.out.println("OUTBOX_CRASH_EVIDENCE " + phase + " " + evidence);
            } finally {
                if (retry.isAlive()) retry.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test void realMixedReceiptSettlesOnlyAcceptedAndDuplicateRows() throws Exception {
        UUID boot = UUID.randomUUID();
        Path output = temporary.resolve("mixed.log");
        Process child = run("mixed", temporary.resolve("mixed.sqlite"), temporary.resolve("unused"), boot, output);
        try {
            assertThat(child.waitFor(30, TimeUnit.SECONDS)).isTrue();
            String evidence = read(output);
            assertThat(child.exitValue()).withFailMessage(evidence).isZero();
            assertThat(evidence).contains("MIXED blocked 1 blocked IDENTITY_CONFLICT");
            assertThat(jdbc.queryForObject("select count(*) from telemetry_reading where boot_id=?", Integer.class, boot)).isEqualTo(3);
            assertThat(jdbc.queryForObject("select count(*) from telemetry_reading where boot_id=? and sequence_number=77 and rpm=5", Integer.class, boot)).isEqualTo(1);
            System.out.println("OUTBOX_MIXED_EVIDENCE " + evidence);
        } finally {
            if (child.isAlive()) child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }

    @Test void diagnosticLostReceiptKeepsOriginalServerReceiptOnRetry() throws Exception {
        UUID boot = UUID.randomUUID();
        Path database = temporary.resolve("diagnostic.sqlite"), marker = temporary.resolve("diagnostic.ready");
        Path initialOutput = temporary.resolve("diagnostic-initial.log"), retryOutput = temporary.resolve("diagnostic-retry.log");
        Process child = run("diagnostic-committed", database, marker, boot, initialOutput);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!Files.exists(marker) && child.isAlive() && System.nanoTime() < deadline) Thread.sleep(25);
            assertThat(Files.exists(marker)).withFailMessage(() -> read(initialOutput)).isTrue();
        } finally {
            child.destroyForcibly();
            assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
        }
        String initialText = read(initialOutput).strip().substring("INITIAL_RECEIPT ".length());
        JsonNode initial = mapper.readTree(initialText);
        Process retry = run("diagnostic-resume", database, marker, boot, retryOutput);
        try {
            assertThat(retry.waitFor(30, TimeUnit.SECONDS)).isTrue();
            String evidence = read(retryOutput);
            assertThat(retry.exitValue()).withFailMessage(evidence).isZero();
            assertThat(evidence).contains("OUTCOME settled", "REMAINING telemetry", "DIAGNOSTIC_RECEIPT ");
            String receiptText = evidence.lines().filter(line -> line.startsWith("DIAGNOSTIC_RECEIPT ")).findFirst().orElseThrow().substring("DIAGNOSTIC_RECEIPT ".length());
            JsonNode duplicate = mapper.readTree(receiptText);
            assertThat(initial.get("result").asText()).isEqualTo("ACCEPTED");
            assertThat(duplicate.get("result").asText()).isEqualTo("DUPLICATE");
            assertThat(duplicate.get("snapshotId")).isEqualTo(initial.get("snapshotId"));
            assertThat(duplicate.get("receivedAt")).isEqualTo(initial.get("receivedAt"));
            assertThat(jdbc.queryForObject("select count(*) from diagnostic_snapshot where boot_id=?", Integer.class, boot)).isEqualTo(1);
            System.out.println("OUTBOX_DIAGNOSTIC_REPLAY_EVIDENCE receipt_preserved=true snapshots=1");
        } finally {
            if (retry.isAlive()) retry.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }

    @Test void collectorSurvivesOutageAndRestartForBothStreams() throws Exception {
        Path output = temporary.resolve("collection.log");
        Process child = new ProcessBuilder(System.getProperty("autopulse.python", "python"), "../scripts/outbox_collection_probe.py",
                "--server-url", "http://127.0.0.1:" + port, "--database", temporary.resolve("collection.sqlite").toString(), "--isolated-demo")
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(child.waitFor(100, TimeUnit.SECONDS)).withFailMessage(() -> "Outage probe timed out: " + read(output)).isTrue();
            String evidence = read(output);
            assertThat(child.exitValue()).withFailMessage(evidence).isZero();
            JsonNode result = mapper.readTree(evidence), manifest = result.get("manifest");
            assertThat(result.get("remaining").asInt()).isZero();
            assertThat(result.get("offline_attempts").asInt()).isPositive();
            assertThat(result.get("lost_responses").asInt()).isEqualTo(1);
            assertThat(result.get("duplicate_receipts").asInt()).isEqualTo(1);
            assertThat(result.get("tiny_capacity_paused").asBoolean()).isTrue();
            assertThat(manifest.size()).isEqualTo(20);
            assertThat(result.get("baseline_manifest").size()).isEqualTo(4);
            UUID baselineBoot = UUID.fromString(result.get("baseline_manifest").get(0).get("payload").get("bootId").asText());
            assertThat(jdbc.queryForObject("select count(*) from telemetry_reading where boot_id=?", Integer.class, baselineBoot)).isEqualTo(3);
            assertThat(jdbc.queryForObject("select count(*) from diagnostic_snapshot where boot_id=?", Integer.class, baselineBoot)).isEqualTo(1);
            for (JsonNode item : result.get("baseline_manifest")) {
                JsonNode payload = item.get("payload");
                boolean diagnostic = item.get("kind").asText().equals("diagnostic");
                String table = diagnostic ? "diagnostic_snapshot" : "telemetry_reading";
                int sequence = payload.get(diagnostic ? "diagnosticSequence" : "sequenceNumber").asInt();
                assertThat(jdbc.queryForObject("select count(*) from " + table + " where boot_id=? and sequence_number=? and payload=?::jsonb", Integer.class, UUID.fromString(payload.get("bootId").asText()), sequence, payload.toString())).isEqualTo(1);
            }
            for (JsonNode item : manifest) {
                JsonNode payload = item.get("payload");
                boolean diagnostic = item.get("kind").asText().equals("diagnostic");
                String table = diagnostic ? "diagnostic_snapshot" : "telemetry_reading";
                int sequence = payload.get(diagnostic ? "diagnosticSequence" : "sequenceNumber").asInt();
                UUID boot = UUID.fromString(payload.get("bootId").asText());
                assertThat(jdbc.queryForObject("select count(*) from " + table + " where boot_id=? and sequence_number=? and payload=?::jsonb", Integer.class, boot, sequence, payload.toString())).isEqualTo(1);
            }
            UUID first = UUID.fromString(manifest.get(0).get("payload").get("bootId").asText());
            UUID second = UUID.fromString(manifest.get(10).get("payload").get("bootId").asText());
            assertThat(first).isNotEqualTo(second);
            assertThat(jdbc.queryForObject("select count(*) from telemetry_reading where boot_id in (?,?)", Integer.class, first, second)).isEqualTo(10);
            assertThat(jdbc.queryForObject("select count(*) from diagnostic_snapshot where boot_id in (?,?)", Integer.class, first, second)).isEqualTo(10);
            assertThat(jdbc.queryForObject("select count(*) from diagnostic_snapshot where boot_id in (?,?) and payload->'ecus'->0->>'outcome'='PARTIAL'", Integer.class, first, second)).isEqualTo(2);
            UUID vehicle = jdbc.queryForObject("select id from vehicle where public_id='venue-2021'", UUID.class);
            for (int i=0; i<100; i++) {
                var page = projector.processPage(vehicle, Instant.now().plusSeconds(2));
                assertThat(page.state()).isNotIn("REVIEW", "RETRY_OR_REVIEW");
                if (page.state().startsWith("IDLE")) break;
            }
            assertThat(jdbc.queryForObject("select count(*) from diagnostic_application a join diagnostic_snapshot s on s.id=a.snapshot_id where s.boot_id in (?,?) and a.decision='STALE_HISTORICAL'", Integer.class, first, second)).isEqualTo(30);
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/vehicles/venue-2021/telemetry/latest")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode latest = mapper.readTree(response.body());
            String measuredAt = latest.get("reading").get("recordedAt").asText();
            assertThat(measuredAt).startsWith("2026-10-20T12:00:08");
            assertThat(Duration.between(Instant.parse(measuredAt), Instant.parse(latest.get("receivedAt").asText())).getSeconds()).isGreaterThan(300);
            var diagnostics = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/vehicles/venue-2021/diagnostics")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(diagnostics.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(diagnostics.body()).get("latestSnapshot").get("freshness").asText()).isEqualTo("STALE");
            System.out.println("OUTBOX_COLLECTION_EVIDENCE baseline=4 observations=" + manifest.size() + " lostResponse=1 duplicate=1 staleApplications=30 partialSnapshots=2 tinyPause=true remaining=0");
        } finally {
            if (child.isAlive()) child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static String read(Path path) {
        try { return Files.exists(path) ? Files.readString(path) : ""; }
        catch (Exception error) { return error.toString(); }
    }
}
