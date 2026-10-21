package dev.autopulse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/local")
class AutoPulseApplicationTests {
	@Autowired JdbcTemplate jdbc;

	@Test
	void contextLoads() {
		assertThat(jdbc.queryForObject("select count(*) from vehicle", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("select count(*) from telemetry_reading", Integer.class)).isZero();
	}

}
