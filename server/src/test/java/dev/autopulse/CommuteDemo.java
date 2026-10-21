package dev.autopulse;

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

/** Explicit opt-in: -Dtest=CommuteDemo -Dautopulse.python=path/to/python. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.flyway.locations=classpath:db/migration,classpath:db/local", "spring.config.import="})
class CommuteDemo {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path temporary;

    @Test void pythonHttpToJavaAndPostgresWithInterruptedAckAndReplay() throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from telemetry_reading", Integer.class)).isZero();
        Path output = temporary.resolve("evidence.json");
        Process process = new ProcessBuilder(System.getProperty("autopulse.python", "python"),
                "../scripts/demo_commute.py", "--server-url", "http://127.0.0.1:" + port,
                "--capture", temporary.resolve("commute.jsonl").toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("Python demo completed").isTrue();
            String evidence = java.nio.file.Files.readString(output);
            assertThat(process.exitValue()).withFailMessage(evidence).isZero();
            System.out.println("COMMUTE_EVIDENCE " + evidence);
            assertThat(jdbc.queryForObject("select count(*) from telemetry_reading", Integer.class)).isEqualTo(254);
            assertThat(jdbc.queryForObject("select count(distinct (device_id, boot_id, sequence_number)) from telemetry_reading", Integer.class)).isEqualTo(254);
            assertThat(jdbc.queryForObject("select count(*) from telemetry_reading where sequence_number=251 and rpm=0 and maf_gps is null and mil_on=false", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select sequence_number::int from telemetry_reading order by recorded_at desc limit 1", Integer.class)).isEqualTo(250);
            assertThat(jdbc.queryForObject("select count(*) from telemetry_reading where sequence_number=254", Integer.class)).isZero();
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }
}
