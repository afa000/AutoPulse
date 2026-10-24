package dev.autopulse;

import dev.autopulse.diagnostic.*;
import dev.autopulse.vehicle.VehicleRegistry;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/** Fresh disposable contexts keep global polling assertions independent of other test queues. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.config.import=",
        "autopulse.diagnostics.processing-enabled=false","autopulse.maintenance.processing-enabled=false"})
@Import(TestcontainersConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DiagnosticRecoveryTests {
    record Fixture(UUID vehicle,String publicVehicle,String publicDevice) {}
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired DiagnosticValidator validator;
    @Autowired DiagnosticProjector projector;
    @Autowired DiagnosticQueries queries;
    @Autowired VehicleRegistry registry;
    @Autowired PlatformTransactionManager transactions;
    final HttpClient http=HttpClient.newHttpClient();
    Instant base;
    JsonNode fixtures;

    @BeforeEach void loadFixtures() {
        base=Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        fixtures=mapper.readTree(Path.of("../contracts/examples/diagnostic-contract-fixtures.json").toFile());
    }
    Fixture vehicle() {
        UUID vehicle=UUID.randomUUID(),device=UUID.randomUUID();
        String pv="recovery-"+vehicle,pd="recovery-"+device;
        jdbc.update("insert into vehicle(id,public_id) values(?,?)",vehicle,pv);
        jdbc.update("insert into device(id,vehicle_id,public_id) values(?,?,?)",device,vehicle,pd);
        return new Fixture(vehicle,pv,pd);
    }
    ObjectNode sample(Fixture f,int index) {
        var p=((ObjectNode)fixtures.get("lifecycle").get(index)).deepCopy();
        var offset=Duration.between(Instant.parse(fixtures.get("baseTime").asText()),Instant.parse(p.get("capturedAt").asText()));
        return p.put("vehicleId",f.publicVehicle()).put("deviceId",f.publicDevice()).put("capturedAt",base.plus(offset).toString());
    }
    JsonNode accept(ObjectNode p) throws Exception {
        var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/device/diagnostic-snapshots"))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(p.toString())).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return mapper.readTree(response.body());
    }
    DiagnosticProjector freshWorker() { return new DiagnosticProjector(jdbc,mapper,transactions); }
    void drain(Fixture f,Instant at) {
        var worker=freshWorker();
        for(int i=0;i<20;i++) {
            var page=worker.processPage(f.vehicle(),at);
            if(page.state().startsWith("IDLE")) return;
            assertThat(page.state()).isEqualTo("PROGRESS");
        }
        fail("Finite diagnostic recovery budget exhausted");
    }
    int count(Fixture f,String table) {
        return jdbc.queryForObject("select count(*) from "+table+" where vehicle_id=?",Integer.class,f.vehicle());
    }
    void failTransitionPages(Fixture f,Instant at,int attempts) {
        String name="diagnostic_recovery_fail_"+f.vehicle().toString().replace("-","");
        jdbc.execute("create function "+name+"() returns trigger language plpgsql as $$ begin if NEW.vehicle_id='"+f.vehicle()+"'::uuid then raise exception 'injected disposable recovery failure'; end if; return NEW; end; $$");
        jdbc.execute("create trigger "+name+" before insert on diagnostic_transition for each row execute function "+name+"()");
        try {
            for(int i=0;i<attempts;i++) assertThat(projector.processPage(f.vehicle(),at.plusSeconds(61L*i)).state()).isEqualTo("RETRY_OR_REVIEW");
        } finally {
            jdbc.execute("drop trigger "+name+" on diagnostic_transition");
            jdbc.execute("drop function "+name+"()");
        }
    }
    void assertRecoveredHistory(Fixture f) {
        assertThat(count(f,"diagnostic_event")).isEqualTo(3);
        assertThat(count(f,"diagnostic_application")).isEqualTo(6);
        assertThat(count(f,"diagnostic_transition")).isEqualTo(6);
        assertThat(jdbc.queryForList("select decision from diagnostic_application where vehicle_id=?",String.class,f.vehicle())).containsOnly("APPLIED");
        assertThat(jdbc.queryForList("select state from diagnostic_event where vehicle_id=?",String.class,f.vehicle())).containsOnly("NOT_OBSERVED");
        assertThat(jdbc.queryForList("select positive_count from diagnostic_event where vehicle_id=?",Long.class,f.vehicle())).containsOnly(1L);
        assertThat(jdbc.queryForList("select status from diagnostic_work where vehicle_id=?",String.class,f.vehicle())).containsOnly("PROCESSED");
        // The confirmed positive and its later absence must also survive in downstream advisory work.
        assertThat(jdbc.queryForList("select evidence->>'kind' from maintenance_work where vehicle_id=? and kind='DIAGNOSTIC' order by id",String.class,f.vehicle()))
                .containsExactly("PRESENT","NOT_OBSERVED");
        assertThat(queries.list(f.publicVehicle(),20,null,null,null).processingState()).isEqualTo("CURRENT");
    }

    @Test void retryAtEveryCategoryPreservesPositiveThenAbsenceHistoryWithoutDoubleCounting() throws Exception {
        for(int completedParts=0;completedParts<3;completedParts++) {
            var f=vehicle();var positive=sample(f,2);var original=accept(positive);accept(sample(f,9));
            Instant attempt=Instant.now();
            for(int i=0;i<completedParts;i++) assertThat(projector.processPage(f.vehicle(),attempt).state()).isEqualTo("PROGRESS");
            failTransitionPages(f,attempt,1);
            assertThat(freshWorker().processPage(f.vehicle(),attempt.plusSeconds(1)).state()).isEqualTo("IDLE");
            assertThat(count(f,"diagnostic_event")).isEqualTo(completedParts);
            assertThat(queries.list(f.publicVehicle(),20,null,null,null).processingState()).isEqualTo("PENDING");
            drain(f,attempt.plusSeconds(61));assertRecoveredHistory(f);
            var replay=accept(positive);
            assertThat(replay.get("result").asText()).isEqualTo("DUPLICATE");
            assertThat(replay.get("receivedAt")).isEqualTo(original.get("receivedAt"));
            assertThat(replay.get("snapshotId")).isEqualTo(original.get("snapshotId"));
            drain(f,attempt.plusSeconds(62));assertRecoveredHistory(f);
        }
    }
    @Test void globalPollSkipsBackoffVehicleWithoutStarvingAnotherVehicle() throws Exception {
        var blocked=vehicle();accept(sample(blocked,2));accept(sample(blocked,9));
        Instant attempt=Instant.now();failTransitionPages(blocked,attempt,1);
        var other=vehicle();var receipt=accept(sample(other,2));
        Instant pollAt=Instant.now().plusSeconds(1);
        for(int i=0;i<3;i++) {
            var page=projector.poll(pollAt);
            assertThat(page.state()).isEqualTo("PROGRESS");
            assertThat(page.snapshotId().toString()).isEqualTo(receipt.get("snapshotId").asText());
        }
        assertThat(count(other,"diagnostic_event")).isEqualTo(3);
        assertThat(count(blocked,"diagnostic_event")).isZero();
        assertThat(projector.poll(pollAt).state()).isEqualTo("IDLE");
        drain(blocked,attempt.plusSeconds(61));assertRecoveredHistory(blocked);
    }
    @Test void reviewPausesTheWholeVehicleEvenIfAnOlderSnapshotArrivesAndOtherVehiclesStillProgress() throws Exception {
        var blocked=vehicle();accept(sample(blocked,2));accept(sample(blocked,9));
        Instant attempt=Instant.now();failTransitionPages(blocked,attempt,3);
        accept(sample(blocked,0)); // An older newly accepted head must not bypass existing REVIEW work.
        assertThat(freshWorker().processPage(blocked.vehicle(),attempt.plusSeconds(200)).state()).isEqualTo("REVIEW");
        assertThat(count(blocked,"diagnostic_application")).isZero();
        assertThat(queries.list(blocked.publicVehicle(),20,null,null,null).processingState()).isEqualTo("REVIEW");
        var other=vehicle();var receipt=accept(sample(other,2));
        for(int i=0;i<3;i++) assertThat(projector.poll(attempt.plusSeconds(200)).snapshotId().toString()).isEqualTo(receipt.get("snapshotId").asText());
        assertThat(count(other,"diagnostic_event")).isEqualTo(3);
        assertThat(projector.poll(attempt.plusSeconds(200)).state()).isEqualTo("IDLE");
        assertThat(count(blocked,"diagnostic_event")).isZero();
    }
    @Test void equalCaptureTimesKeepNativeBootAndSequenceOrderDuringBackoff() throws Exception {
        for(boolean bootTie:List.of(false,true)) {
            var f=vehicle();var positive=sample(f,2);var absence=sample(f,9).put("capturedAt",positive.get("capturedAt").asText());
            if(!bootTie) {
                positive.put("bootId","00000000-0000-4000-8000-000000000001").put("diagnosticSequence",Long.MAX_VALUE);
                absence.put("bootId","ffffffff-ffff-4fff-8fff-ffffffffffff").put("diagnosticSequence",0);
            }
            accept(positive);accept(absence);Instant attempt=Instant.now();failTransitionPages(f,attempt,1);
            assertThat(projector.processPage(f.vehicle(),attempt.plusSeconds(1)).state()).isEqualTo("IDLE");
            drain(f,attempt.plusSeconds(61));assertRecoveredHistory(f);
        }
    }
    @Test void notYetEligibleOriginalReceiptAlsoBlocksLaterSameVehicleWork() throws Exception {
        var blocked=vehicle();Instant at=Instant.now().truncatedTo(ChronoUnit.MICROS);
        new DiagnosticStore(jdbc,registry,transactions,Clock.fixed(at.plusSeconds(60),ZoneOffset.UTC)).accept(validator.validate(sample(blocked,2)));
        accept(sample(blocked,9));
        assertThat(projector.processPage(blocked.vehicle(),at.plusSeconds(1)).state()).isEqualTo("IDLE");
        assertThat(projector.poll(at.plusSeconds(1)).state()).isEqualTo("IDLE");
        assertThat(count(blocked,"diagnostic_application")).isZero();
        drain(blocked,at.plusSeconds(61));assertRecoveredHistory(blocked);
    }
}
