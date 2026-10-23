package dev.autopulse;

import dev.autopulse.maintenance.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties={"spring.config.import=","autopulse.maintenance.processing-enabled=true"})
@Import(TestcontainersConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MaintenanceStartupTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired MaintenanceService service;
    @Autowired MaintenanceSchedule schedule;
    @Autowired ObjectMapper mapper;
    @Test void optInManagedPollProcessesCommittedManualWorkWithoutBrowserAndStopsWithContext() {
        UUID vehicle=UUID.randomUUID();String publicId="maint-startup-"+vehicle;
        jdbc.update("insert into vehicle(id,public_id) values(?,?)",vehicle,publicId);
        var input=mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("expectedRevision",0).put("applicability","UNCONFIRMED")
                .put("usageSchedule","UNSET").put("distanceUnit","MI").put("timeZone","UTC").put("normalOilSpecificationConfirmed",false);
        service.saveProfile(publicId,input);assertThat(schedule).isNotNull();
        await().atMost(10,TimeUnit.SECONDS).untilAsserted(()->{
            assertThat(jdbc.queryForObject("select status from maintenance_work where vehicle_id=?",String.class,vehicle)).isEqualTo("PROCESSED");
            assertThat(jdbc.queryForObject("select count(*) from maintenance_recommendation where vehicle_id=?",Integer.class,vehicle)).isZero();
        });
        schedule.close();var another=input.deepCopy().put("requestId",UUID.randomUUID().toString()).put("expectedRevision",1);
        service.saveProfile(publicId,another);schedule.tick();
        assertThat(jdbc.queryForObject("select count(*) from maintenance_work where vehicle_id=? and status='PENDING'",Integer.class,vehicle)).isEqualTo(1);
    }
}
