package dev.autopulse;

import dev.autopulse.live.LiveStreams;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.*;

/** Opt-in real-browser test with disposable persistence, never the owner's DB. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.flyway.locations=classpath:db/migration,classpath:db/local", "spring.config.import=", "server.shutdown=immediate"})
class DashboardBrowserDemo {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired LiveStreams streams;
    @TempDir Path temporary;

    @Test void browserReceivesPersistedPythonSimulator() throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from telemetry_reading", Integer.class)).isZero();
        jdbc.update("insert into device(id,vehicle_id,public_id) select '30000000-0000-4000-8000-000000000001'::uuid,id,'sim-empty-001' from vehicle where public_id='empty-vehicle'");
        Path output = Path.of("target/dashboard-browser.log").toAbsolutePath();
        var builder = new ProcessBuilder("node", "node_modules/@playwright/test/cli.js", "test")
                .directory(Path.of("../web").toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        Path control = temporary.resolve("network-control");
        try (var proxy = new LoopbackFaultProxy(port, control)) {
        builder.environment().put("AUTOPULSE_BACKEND_URL", "http://127.0.0.1:" + proxy.port());
        builder.environment().put("AUTOPULSE_NETWORK_CONTROL", control.toString());
        builder.environment().put("AUTOPULSE_ISOLATED_DEMO", "true");
        boolean liveDemo = Boolean.getBoolean("autopulse.liveDemo");
        if (liveDemo) builder.environment().put("AUTOPULSE_LIVE_DEMO", "true");
        String python = System.getProperty("autopulse.python", "python");
        builder.environment().put("AUTOPULSE_PYTHON", python.contains("/") || python.contains("\\") ? Path.of(python).toAbsolutePath().normalize().toString() : python);
        Process process = builder.start();
        try {
            assertThat(process.waitFor(180, TimeUnit.SECONDS)).as("browser tests completed").isTrue();
            String evidence = java.nio.file.Files.readString(output);
            System.out.println("DASHBOARD_BROWSER_EVIDENCE\n" + evidence);
            assertThat(process.exitValue()).withFailMessage(evidence).isZero();
            assertThat(jdbc.queryForObject("select count(*) from telemetry_reading", Integer.class)).isEqualTo(liveDemo ? 36 : 5);
            // Ordinary TCP closure may need later writes or lease retirement to
            // become observable. Blocked I/O can retain capacity beyond this lease;
            // that distinct case is tested without blocking the coordinator.
            var previous = new java.util.concurrent.atomic.AtomicInteger(-1);
            org.awaitility.Awaitility.await().pollInterval(1, TimeUnit.SECONDS).atMost(125, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        int active = streams.subscriptions();
                        if (previous.getAndSet(active) != active) System.out.println("STREAM_CLEANUP remaining=" + active);
                        assertThat(active).isZero();
                    });
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroy);
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
        }
        }
    }
}
