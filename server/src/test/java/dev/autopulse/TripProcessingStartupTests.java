package dev.autopulse;

import dev.autopulse.telemetry.TelemetryRepository;
import dev.autopulse.telemetry.TelemetryValidator;
import dev.autopulse.trip.TripProcessingSchedule;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;
import static dev.autopulse.database.LocalFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import java.util.concurrent.TimeUnit;

/** Real opt-in Spring scheduling, only inside this disposable test context. */
@SpringBootTest(properties={"spring.config.import=","autopulse.trip-processing.enabled=true",
        "autopulse.trip-processing.poll-delay-ms=100","autopulse.trip-processing.page-size=100"})
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class TripProcessingStartupTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired TelemetryRepository repository;
    @Autowired TelemetryValidator validator;
    @Autowired ObjectMapper mapper;
    @Autowired TripProcessingSchedule schedule;

    @Test void actualReadyAndScheduledTicksDiscoverCommittedDriveWithoutManualWorkerCall() {
        assertThat(schedule).isNotNull();
        var readings=mapper.readTree(Path.of("../contracts/examples/trip-short-input.json").toFile()).get("readings");
        for (var reading : readings) assertThat(repository.store(VEHICLE,DEVICE,validator.validate(reading))).isEqualTo("accepted");
        await().atMost(10,TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("select count(*) from trip_work_item where status='PENDING'",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from trip_record where status='COMPLETED'",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from trip_evidence",Integer.class)).isEqualTo(27);
        });
        assertThat(jdbc.queryForObject("select vehicle_id from trip_projection_progress",UUID.class)).isEqualTo(VEHICLE);
        assertThat(jdbc.queryForObject("select finalization from trip_record where status='COMPLETED'",String.class)).isEqualTo("PROVISIONAL");
        assertThat(jdbc.queryForObject("select count(*) from telemetry_reading",Integer.class)).isEqualTo(readings.size());
    }
}
