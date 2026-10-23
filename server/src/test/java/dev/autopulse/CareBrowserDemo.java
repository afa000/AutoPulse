package dev.autopulse;

import dev.autopulse.database.LocalFixtures;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

/** Opt-in checkpoint-4 UI proof, not the checkpoint-5 Python/JVM-restart demonstration. */
@Testcontainers
class CareBrowserDemo {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17");
    @Test void actualBackendFormsEvidenceAndActions() throws Exception {
        // Explicit disposable URL/credentials and empty config import prevent owner .env access.
        try(var app=new SpringApplicationBuilder(AutoPulseApplication.class).run(
                "--spring.config.import=","--spring.datasource.url="+POSTGRES.getJdbcUrl(),
                "--spring.datasource.username="+POSTGRES.getUsername(),"--spring.datasource.password="+POSTGRES.getPassword(),
                "--spring.flyway.locations=classpath:db/migration","--server.port=0","--server.shutdown=immediate",
                "--autopulse.trip-processing.enabled=false","--autopulse.retention.enabled=false","--autopulse.retention.dry-run=true",
                "--autopulse.history.owned-aggregates-enabled=false","--autopulse.diagnostics.processing-enabled=true","--autopulse.maintenance.processing-enabled=true")) {
            var jdbc=app.getBean(JdbcTemplate.class);
            new LocalFixtures(jdbc,app.getBean(PlatformTransactionManager.class)).seed();
            Path output=Path.of("target/care-browser.log").toAbsolutePath();
            var builder=new ProcessBuilder("node","node_modules/@playwright/test/cli.js","test")
                    .directory(Path.of("../web").toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().put("AUTOPULSE_ISOLATED_DEMO","true");
            builder.environment().put("AUTOPULSE_CARE_DEMO","true");
            builder.environment().put("AUTOPULSE_BACKEND_URL","http://127.0.0.1:"+app.getEnvironment().getProperty("local.server.port"));
            Process browser=builder.start();
            try {
                assertThat(browser.waitFor(150,TimeUnit.SECONDS)).as("finite care browser completed").isTrue();
                String evidence=Files.readString(output);System.out.println("CARE_BROWSER_EVIDENCE\n"+evidence);
                assertThat(browser.exitValue()).withFailMessage(evidence).isZero();
                assertThat(jdbc.queryForObject("select count(*) from maintenance_entry",Integer.class)).isEqualTo(3);
                assertThat(jdbc.queryForObject("select count(*) from maintenance_entry_revision",Integer.class)).isEqualTo(4);
                assertThat(jdbc.queryForObject("select count(*) from diagnostic_snapshot",Integer.class)).isEqualTo(3);
                assertThat(jdbc.queryForObject("select count(*) from diagnostic_event where state='PRESENT'",Integer.class)).isEqualTo(2);
            } finally {
                browser.descendants().forEach(ProcessHandle::destroy);
                if(browser.isAlive()) browser.destroyForcibly().waitFor(10,TimeUnit.SECONDS);
            }
        }
    }
}
