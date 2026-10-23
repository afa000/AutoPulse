package dev.autopulse;

import dev.autopulse.diagnostic.*;
import dev.autopulse.database.LocalFixtures;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import java.util.concurrent.TimeUnit;

@SpringBootTest(properties={"spring.config.import=","autopulse.diagnostics.processing-enabled=true"})
@Import(TestcontainersConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class DiagnosticStartupTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired DiagnosticValidator validator;
    @Autowired DiagnosticStore store;
    @Autowired DiagnosticSchedule schedule;
    @Test void optInReadyWorkerRecoversCommittedQueueWithoutBrowserOrNewInput() {
        UUID vehicle=UUID.randomUUID(),device=UUID.randomUUID();String pv="startup-"+vehicle,pd="startup-"+device;
        jdbc.update("insert into vehicle(id,public_id) values(?,?)",vehicle,pv);
        jdbc.update("insert into device(id,vehicle_id,public_id) values(?,?,?)",device,vehicle,pd);
        var payload=((ObjectNode)mapper.readTree(Path.of("../contracts/examples/diagnostic-contract-fixtures.json").toFile()).get("lifecycle").get(2)).deepCopy();
        payload.put("vehicleId",pv).put("deviceId",pd).put("capturedAt",Instant.now().minusSeconds(10).toString().replaceAll("(\\.\\d{6})\\d+Z$","$1Z"));
        assertThat(schedule).isNotNull();store.accept(validator.validate(payload));
        await().atMost(10,TimeUnit.SECONDS).untilAsserted(()->{
            assertThat(jdbc.queryForObject("select status from diagnostic_work where vehicle_id=?",String.class,vehicle)).isEqualTo("PROCESSED");
            assertThat(jdbc.queryForObject("select count(*) from diagnostic_event where vehicle_id=?",Integer.class,vehicle)).isEqualTo(3);
        });
    }
}
