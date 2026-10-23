package dev.autopulse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.config.import=")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class LocalProfileMigrationTests {
    @Autowired JdbcTemplate jdbc;

    @Test void realLocalStartupSeedsAfterLatestMigrationWithoutApplyingV900() {
        assertThat(jdbc.queryForObject("select max(version::int) from flyway_schema_history", Integer.class)).isEqualTo(933);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='900'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from vehicle", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from device", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from trip_policy", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from trip_backfill_progress", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from telemetry_reading", Integer.class)).isZero();
    }
}
