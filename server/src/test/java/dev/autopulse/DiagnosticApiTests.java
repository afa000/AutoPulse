package dev.autopulse;

import dev.autopulse.diagnostic.*;
import dev.autopulse.vehicle.VehicleRegistry;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="spring.config.import=")
@Import(TestcontainersConfiguration.class)
class DiagnosticApiTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired DiagnosticValidator validator;
    @Autowired DiagnosticStore store;
    @Autowired DiagnosticProjector projector;
    @Autowired DiagnosticQueries queries;
    @Autowired DiagnosticCatalog catalog;
    @Autowired VehicleRegistry registry;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationContext context;
    final HttpClient client=HttpClient.newHttpClient();
    UUID vehicle,device;
    String publicVehicle,publicDevice;
    Instant base;
    JsonNode fixtures;
    @BeforeEach void fixture() {
        vehicle=UUID.randomUUID();device=UUID.randomUUID();publicVehicle="diag-"+vehicle;publicDevice="sensor-"+device;
        jdbc.update("insert into vehicle(id,public_id) values(?,?)",vehicle,publicVehicle);
        jdbc.update("insert into device(id,vehicle_id,public_id) values(?,?,?)",device,vehicle,publicDevice);
        base=Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(60);
        fixtures=mapper.readTree(Path.of("../contracts/examples/diagnostic-contract-fixtures.json").toFile());
    }
    ObjectNode sample(int index) {
        ObjectNode p=((ObjectNode)fixtures.get("lifecycle").get(index)).deepCopy();
        Instant original=Instant.parse(p.get("capturedAt").asText());
        p.put("deviceId",publicDevice).put("vehicleId",publicVehicle).put("capturedAt",base.plus(Duration.between(Instant.parse(fixtures.get("baseTime").asText()),original)).toString());
        return p;
    }
    String path() { return "/api/v1/vehicles/"+publicVehicle+"/diagnostics"; }
    HttpResponse<String> post(String value) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/device/diagnostic-snapshots")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(value)).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode accept(ObjectNode p) throws Exception { var r=post(p.toString());assertThat(r.statusCode()).withFailMessage(r.body()).isEqualTo(200);return mapper.readTree(r.body()); }
    HttpResponse<String> get(String path) throws Exception { return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString()); }
    JsonNode body(HttpResponse<String> r) { return mapper.readTree(r.body()); }
    void drain() { drain(Instant.now()); }
    void drain(Instant at) {
        for(int i=0;i<1000;i++) {
            var p=projector.processPage(vehicle,at);
            assertThat(p.state()).isNotIn("REVIEW","RETRY_OR_REVIEW");
            if(p.state().startsWith("IDLE")) return;
        }
        fail("bounded diagnostic drain exceeded finite budget");
    }
    int count(String table) { return jdbc.queryForObject("select count(*) from "+table+" where vehicle_id=?",Integer.class,vehicle); }
    @Test void sharedValidInvalidAndStrictDecoderCasesAgreeWithPythonContract() throws Exception {
        for(var input:fixtures.get("lifecycle")) assertThat(validator.decode(input.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))).isNotNull();
        for(var input:fixtures.get("validExtra")) assertThat(validator.validate(input.get("payload"))).isNotNull();
        for(var invalid:fixtures.get("invalid")) {
            var p=((ObjectNode)invalid.get("payload")).deepCopy();p.put("deviceId",publicDevice).put("vehicleId",publicVehicle);
            assertThat(post(p.toString()).statusCode()).as(invalid.get("name").asText()).isEqualTo(400);
        }
        String raw=sample(0).toString();
        for(String malformed:List.of(raw.replace("\"snapshotVersion\":1","\"snapshotVersion\":4294967297"),
                raw.replace("\"snapshotVersion\":1","\"snapshotVersion\":1,\"snapshotVersion\":1"),raw+" {}",
                raw.replace("\"diagnosticSequence\":0","\"diagnosticSequence\":0.0"))) assertThat(post(malformed).statusCode()).isEqualTo(400);
        assertThatThrownBy(()->validator.decode(raw.getBytes(java.nio.charset.StandardCharsets.UTF_16))).isInstanceOf(dev.autopulse.shared.ApiFailure.class);
        assertThatThrownBy(()->validator.decode(new byte[]{(byte)0xff})).isInstanceOf(dev.autopulse.shared.ApiFailure.class);
        var wrongMedia=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/device/diagnostic-snapshots"))
            .header("Content-Type","text/plain").POST(HttpRequest.BodyPublishers.ofString(raw)).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(wrongMedia.statusCode()).isEqualTo(415);
        assertThat(count("diagnostic_snapshot")).isZero();assertThat(count("diagnostic_work")).isZero();
    }
    @Test void lifecycleMatchesHandCyclesCountsUnknownReadsRecurrenceAndReplay() throws Exception {
        for(var index:fixtures.get("deliveryOrder")) { accept(sample(index.asInt()));drain(); }
        assertThat(count("diagnostic_snapshot")).isEqualTo(13);assertThat(count("diagnostic_work")).isEqualTo(13);
        assertThat(count("diagnostic_event")).isEqualTo(5);assertThat(count("diagnostic_application")).isEqualTo(39);
        assertThat(jdbc.queryForObject("select count(*) from diagnostic_application where vehicle_id=? and decision='HISTORICAL_ONLY'",Integer.class,vehicle)).isEqualTo(3);
        for(var expected:fixtures.get("expected").get("cycles")) {
            var event=jdbc.queryForMap("select * from diagnostic_event where vehicle_id=? and category=? and code=? and cycle_number=?",vehicle,expected.get("category").asText(),expected.get("code").asText(),expected.get("cycle").asLong());
            assertThat(((Number)event.get("positive_count")).intValue()).isEqualTo(expected.get("positiveObservationCount").asInt());
            assertThat(((java.sql.Timestamp)event.get("first_observed_at")).toInstant()).isEqualTo(base.plusSeconds(expected.get("firstOffset").asLong()));
            assertThat(((java.sql.Timestamp)event.get("last_observed_at")).toInstant()).isEqualTo(base.plusSeconds(expected.get("lastPositiveOffset").asLong()));
            assertThat(event.get("state")).isEqualTo(expected.get("absentOffset").isNull()?"PRESENT":"NOT_OBSERVED");
        }
        var r=get(path());assertThat(r.statusCode()).isEqualTo(200);var page=body(r);assertThat(page.get("items").size()).isEqualTo(5);
        assertThat(page.get("processingState").asText()).isEqualTo("CURRENT");
        for(var e:page.get("items")) {
            assertThat(e.get("source").asText()).isEqualTo("SIMULATED");
            assertThat(e.get("definition").get("verification").asText()).isEqualTo(e.get("code").asText().equals("P1999")?"UNKNOWN":"VERIFIED");
            var detail=get(path()+"/"+e.get("eventId").asText());assertThat(detail.statusCode()).isEqualTo(200);
            assertThat(body(detail).get("recentTransitions").isEmpty()).isFalse();
        }
        try {
            java.nio.file.Files.writeString(Path.of("target/diagnostic-http-examples.json"),mapper.writeValueAsString(Map.of(
                "eventList",page,"eventDetail",body(get(path()+"/"+page.get("items").get(0).get("eventId").asText())))));
        } catch(java.io.IOException failure) { throw new AssertionError("could not export ignored HTTP contract evidence",failure); }
    }
    @Test void acceptedCommitHasDurableUnprocessedWorkAndFreshWorkerResumesEveryPart() throws Exception {
        var receipt=accept(sample(2));assertThat(receipt.get("result").asText()).isEqualTo("ACCEPTED");
        assertThat(count("diagnostic_event")).isZero();assertThat(count("diagnostic_work")).isEqualTo(1);
        assertThat(context.getBeansOfType(DiagnosticSchedule.class)).isEmpty();
        assertThat(body(get(path())).get("processingState").asText()).isEqualTo("PENDING");
        assertThat(projector.processPage(vehicle,Instant.now()).nextPart()).isEqualTo(1);
        for(int i=0;i<2;i++) assertThat(new DiagnosticProjector(jdbc,mapper,transactions).processPage(vehicle,Instant.now()).state()).isEqualTo("PROGRESS");
        assertThat(count("diagnostic_event")).isEqualTo(3);
        assertThat(jdbc.queryForList("select positive_count from diagnostic_event where vehicle_id=?",Long.class,vehicle)).containsOnly(1L);
        assertThat(body(get(path())).get("processingState").asText()).isEqualTo("CURRENT");
    }
    @Test void conflictAssociationSizeFutureAndExactReplayPreserveReceipt() throws Exception {
        ObjectNode p=sample(2);var first=accept(p);var duplicate=accept(p);
        assertThat(duplicate.get("result").asText()).isEqualTo("DUPLICATE");
        assertThat(duplicate.get("receivedAt")).isEqualTo(first.get("receivedAt"));assertThat(duplicate.get("snapshotId")).isEqualTo(first.get("snapshotId"));
        var changed=p.deepCopy();((ObjectNode)changed.get("ecus").get(0).get("mil")).put("illuminated",false);
        assertThat(post(changed.toString()).statusCode()).isEqualTo(409);
        var unknown=p.deepCopy().put("deviceId","unknown-device");assertThat(post(unknown.toString()).statusCode()).isEqualTo(404);
        UUID other=UUID.randomUUID();jdbc.update("insert into vehicle(id,public_id) values(?,?)",other,"other-"+other);
        assertThat(post(p.deepCopy().put("vehicleId","other-"+other).toString()).statusCode()).isEqualTo(409);
        assertThat(post(sample(0).put("capturedAt",Instant.now().plusSeconds(180).toString()).toString()).statusCode()).isEqualTo(400);
        String raw=sample(0).toString();assertThat(post(raw+" ".repeat(65536-raw.length())).statusCode()).isEqualTo(200);
        assertThat(post(raw+" ".repeat(65537-raw.length())).statusCode()).isEqualTo(413);
        assertThat(count("diagnostic_snapshot")).isEqualTo(2);
    }
    @Test void unsupportedPartialMilOffAndOldAbsenceCannotInventResolution() throws Exception {
        accept(sample(2));drain();accept(sample(4));drain();accept(sample(6));drain();
        assertThat(jdbc.queryForList("select state from diagnostic_event where vehicle_id=?",String.class,vehicle)).containsOnly("PRESENT");
        var state=body(get(path())).get("items").get(0);assertThat(state.get("currentReadOutcome").asText()).isEqualTo("FAILED");
        assertThat(state.get("mil").get("illuminated").isNull()).isTrue();assertThat(state.get("freshness").asText()).isEqualTo("UNKNOWN");
        accept(sample(0).put("diagnosticSequence",100));drain(); // older complete empty remains historical-only
        assertThat(jdbc.queryForList("select state from diagnostic_event where vehicle_id=?",String.class,vehicle)).containsOnly("PRESENT");
        accept(sample(9));drain();
        assertThat(jdbc.queryForList("select state from diagnostic_event where vehicle_id=?",String.class,vehicle)).containsOnly("NOT_OBSERVED");
    }
    @Test void knownEmptyUnsupportedLatestAndVehicleIsolationStayExplicit() throws Exception {
        var empty=body(get(path()));assertThat(empty.get("items").isEmpty()).isTrue();assertThat(empty.get("latestSnapshot").isNull()).isTrue();
        var unsupported=sample(0);var ecu=(ObjectNode)unsupported.get("ecus").get(0);ecu.put("outcome","UNSUPPORTED");
        ((ObjectNode)ecu.get("mil")).put("outcome","UNSUPPORTED").putNull("illuminated");
        for(var c:ecu.get("categories")) ((ObjectNode)c).put("outcome","UNSUPPORTED");
        accept(unsupported);drain();var result=body(get(path()));
        assertThat(result.get("items").isEmpty()).isTrue();assertThat(result.get("latestSnapshot").get("freshness").asText()).isEqualTo("UNKNOWN");
        assertThat(get("/api/v1/vehicles/missing/diagnostics").statusCode()).isEqualTo(404);
        accept(sample(2));drain();String id=body(get(path())).get("items").get(0).get("eventId").asText();
        UUID other=UUID.randomUUID();jdbc.update("insert into vehicle(id,public_id) values(?,?)",other,"other-"+other);
        assertThat(get("/api/v1/vehicles/other-"+other+"/diagnostics/"+id).statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/vehicles/"+publicVehicle+"/telemetry/latest").statusCode()).isEqualTo(204);
    }
    @Test void precisePagingBindsFiltersGenerationAndLimits() throws Exception {
        accept(sample(2));drain();var ids=new HashSet<String>();String cursor=null;
        do {
            var r=get(path()+"?limit=1"+(cursor==null?"":"&cursor="+cursor));assertThat(r.statusCode()).isEqualTo(200);var page=body(r);
            assertThat(page.get("items").size()).isEqualTo(1);assertThat(ids.add(page.get("items").get(0).get("eventId").asText())).isTrue();
            cursor=page.get("nextCursor").isNull()?null:page.get("nextCursor").asText();
        } while(cursor!=null);
        assertThat(ids).hasSize(3);
        String old=body(get(path()+"?limit=1")).get("nextCursor").asText();
        assertThat(get(path()+"?limit=1&category=CONFIRMED&cursor="+old).statusCode()).isEqualTo(400);
        assertThat(get(path()+"?limit=101").statusCode()).isEqualTo(400);
        assertThat(get(path()+"?limit=1&limit=1").statusCode()).isEqualTo(400);
        accept(sample(3));assertThat(get(path()+"?limit=1&cursor="+old).statusCode()).isEqualTo(409);
        assertThat(body(get(path()+"?category=CONFIRMED&observationState=PRESENT")).get("items").size()).isEqualTo(1);
    }
    @Test void concurrentDuplicateAcceptanceAndWorkersDoNotMultiplyObservations() throws Exception {
        String payload=sample(2).toString();
        try(var threads=Executors.newFixedThreadPool(2)) {
            var a=threads.submit(()->post(payload));var b=threads.submit(()->post(payload));
            var results=List.of(body(a.get(15,TimeUnit.SECONDS)),body(b.get(15,TimeUnit.SECONDS)));
            assertThat(results.stream().map(n->n.get("result").asText()).toList()).containsExactlyInAnyOrder("ACCEPTED","DUPLICATE");
            assertThat(results.get(0).get("snapshotId")).isEqualTo(results.get(1).get("snapshotId"));
            var one=threads.submit(()->projector.processPage(vehicle,Instant.now()));var two=threads.submit(()->projector.processPage(vehicle,Instant.now()));
            assertThat(one.get(15,TimeUnit.SECONDS).state()).isIn("PROGRESS","IDLE_OR_BUSY");
            assertThat(two.get(15,TimeUnit.SECONDS).state()).isIn("PROGRESS","IDLE_OR_BUSY");
        }
        drain();assertThat(count("diagnostic_snapshot")).isEqualTo(1);assertThat(count("diagnostic_event")).isEqualTo(3);
        assertThat(jdbc.queryForList("select positive_count from diagnostic_event where vehicle_id=?",Long.class,vehicle)).containsOnly(1L);
    }
    @Test void projectionFailureRollsBackAllEvidenceAndRetryResumesWithoutDoubleCount() throws Exception {
        accept(sample(2));
        jdbc.execute("CREATE OR REPLACE FUNCTION diagnostic_test_fail() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN IF NEW.vehicle_id='"+vehicle+"'::uuid THEN RAISE EXCEPTION 'injected disposable failure'; END IF; RETURN NEW; END; $$");
        jdbc.execute("CREATE TRIGGER diagnostic_test_fail BEFORE INSERT ON diagnostic_transition FOR EACH ROW EXECUTE FUNCTION diagnostic_test_fail()");
        Instant now=Instant.now();
        try {
            assertThat(projector.processPage(vehicle,now).state()).isEqualTo("RETRY_OR_REVIEW");
            assertThat(count("diagnostic_event")).isZero();assertThat(count("diagnostic_scope")).isZero();assertThat(count("diagnostic_application")).isZero();
            assertThat(jdbc.queryForObject("select next_part from diagnostic_work where vehicle_id=?",Integer.class,vehicle)).isZero();
        } finally { jdbc.execute("DROP TRIGGER diagnostic_test_fail ON diagnostic_transition");jdbc.execute("DROP FUNCTION diagnostic_test_fail()"); }
        drain(now.plusSeconds(61));assertThat(count("diagnostic_event")).isEqualTo(3);
        assertThat(jdbc.queryForList("select positive_count from diagnostic_event where vehicle_id=?",Long.class,vehicle)).containsOnly(1L);
    }
    @Test void snapshotAndQueueAcceptAtomicallyAndImmutableHistorySurvivesRawRetentionIndependently() throws Exception {
        jdbc.execute("CREATE OR REPLACE FUNCTION diagnostic_test_accept_fail() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN IF NEW.vehicle_id='"+vehicle+"'::uuid THEN RAISE EXCEPTION 'injected queue failure'; END IF; RETURN NEW; END; $$");
        jdbc.execute("CREATE TRIGGER diagnostic_test_accept_fail BEFORE INSERT ON diagnostic_work FOR EACH ROW EXECUTE FUNCTION diagnostic_test_accept_fail()");
        try { assertThat(post(sample(2).toString()).statusCode()).isEqualTo(503);assertThat(count("diagnostic_snapshot")).isZero();assertThat(count("diagnostic_vehicle")).isZero(); }
        finally { jdbc.execute("DROP TRIGGER diagnostic_test_accept_fail ON diagnostic_work");jdbc.execute("DROP FUNCTION diagnostic_test_accept_fail()"); }
        accept(sample(2));drain();
        assertThatThrownBy(()->jdbc.update("update diagnostic_snapshot set source='OBD' where vehicle_id=?",vehicle)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("delete from diagnostic_snapshot where vehicle_id=?",vehicle)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("delete from diagnostic_transition where vehicle_id=?",vehicle)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where contype='f' and conrelid::regclass::text like 'diagnostic_%' and confrelid='telemetry_reading'::regclass",Integer.class)).isZero();
        assertThat(count("diagnostic_event")).isEqualTo(3);
    }
    @Test void activePartialCodeGrowthHasAnExplicitBoundAndReviewDoesNotPublishHalfAPage() throws Exception {
        for(int batch=0;batch<4;batch++) {
            var p=sample(0).put("diagnosticSequence",batch+100).put("capturedAt",base.plusSeconds(batch+1).toString());
            var ecu=(ObjectNode)p.get("ecus").get(0);ecu.put("outcome","PARTIAL");var c=(ObjectNode)ecu.get("categories").get(1);c.put("outcome","PARTIAL");
            var codes=mapper.createArrayNode();for(int i=0;i<32;i++) codes.add(String.format("P%04X",batch*32+i));c.set("codes",codes);
            accept(p);
            if(batch<3) drain();else { assertThat(projector.processPage(vehicle,Instant.now()).state()).isEqualTo("PROGRESS");assertThat(projector.processPage(vehicle,Instant.now()).state()).isEqualTo("REVIEW"); }
        }
        assertThat(count("diagnostic_event")).isEqualTo(96);assertThat(body(get(path())).get("processingState").asText()).isEqualTo("REVIEW");
        assertThat(jdbc.queryForObject("select count(*) from diagnostic_application where vehicle_id=?",Integer.class,vehicle)).isEqualTo(10);
    }
    @Test void staleAndOriginallyFutureAbsenceCannotResolveAndDuplicateUsesOriginalReceiptClock() {
        var first=sample(2);var firstStore=new DiagnosticStore(jdbc,registry,transactions,Clock.fixed(base.plusSeconds(5),ZoneOffset.UTC));
        var original=firstStore.accept(validator.validate(first));drain(base.plusSeconds(6));
        var stale=sample(0).put("diagnosticSequence",200).put("capturedAt",base.plusSeconds(8).toString());
        new DiagnosticStore(jdbc,registry,transactions,Clock.fixed(base.plusSeconds(309),ZoneOffset.UTC)).accept(validator.validate(stale));drain(base.plusSeconds(310));
        assertThat(jdbc.queryForList("select state from diagnostic_event where vehicle_id=?",String.class,vehicle)).containsOnly("PRESENT");
        var view=new DiagnosticQueries(jdbc,registry,catalog,mapper,transactions,Clock.fixed(base.plusSeconds(310),ZoneOffset.UTC));
        assertThat(view.list(publicVehicle,20,null,null,null).items()).allMatch(e->e.freshness().equals("STALE"));
        var future=sample(0).put("diagnosticSequence",201).put("capturedAt",base.plusSeconds(320).toString());
        new DiagnosticStore(jdbc,registry,transactions,Clock.fixed(base.plusSeconds(200),ZoneOffset.UTC)).accept(validator.validate(future));
        assertThat(projector.processPage(vehicle,base.plusSeconds(319)).state()).isEqualTo("IDLE");
        drain(base.plusSeconds(321));
        assertThat(jdbc.queryForList("select state from diagnostic_event where vehicle_id=?",String.class,vehicle)).containsOnly("PRESENT");
        var duplicate=new DiagnosticStore(jdbc,registry,transactions,Clock.fixed(base.minusSeconds(1000),ZoneOffset.UTC)).accept(validator.validate(first));
        assertThat(duplicate.receivedAt()).isEqualTo(original.receivedAt());assertThat(duplicate.result()).isEqualTo("DUPLICATE");
    }
    @Test void microsecondsBootAndSequenceTiesUseNativeOrderWithoutFalseOlderResolution() throws Exception {
        var p=sample(2).put("capturedAt",base.plusSeconds(4).plusNanos(1000).toString());accept(p);drain();
        var older=sample(0).put("diagnosticSequence",100).put("capturedAt",base.plusSeconds(4).toString());accept(older);drain();
        assertThat(jdbc.queryForList("select state from diagnostic_event where vehicle_id=?",String.class,vehicle)).containsOnly("PRESENT");
        var lowerBoot=sample(0).put("bootId","00000000-0000-4000-8000-000000000001").put("capturedAt",p.get("capturedAt").asText());accept(lowerBoot);drain();
        assertThat(jdbc.queryForList("select state from diagnostic_event where vehicle_id=?",String.class,vehicle)).containsOnly("PRESENT");
        var higherBoot=sample(0).put("bootId","ffffffff-ffff-4fff-8fff-ffffffffffff").put("capturedAt",p.get("capturedAt").asText());accept(higherBoot);drain();
        assertThat(jdbc.queryForList("select state from diagnostic_event where vehicle_id=?",String.class,vehicle)).containsOnly("NOT_OBSERVED");
        var original=body(get(path())).get("items").get(0).get("lastObservedAt").asText();
        assertThat(Instant.parse(original)).isEqualTo(base.plusSeconds(4).plusNanos(1000));
    }
    @Test void deviceAndSourceScopesCannotResolveEachOther() throws Exception {
        accept(sample(10));drain();
        UUID second=UUID.randomUUID();String secondPublic="second-"+second;
        jdbc.update("insert into device(id,vehicle_id,public_id) values(?,?,?)",second,vehicle,secondPublic);
        accept(sample(10).put("deviceId",secondPublic));drain();
        accept(sample(10).put("diagnosticSequence",999).put("source","OBD"));drain();
        assertThat(count("diagnostic_event")).isEqualTo(6);
        accept(sample(0).put("diagnosticSequence",998).put("capturedAt",base.plusSeconds(25).toString()));drain();
        assertThat(jdbc.queryForObject("select count(*) from diagnostic_event where vehicle_id=? and state='PRESENT'",Integer.class,vehicle)).isEqualTo(4);
    }
    @Test void repeatedDatabaseFailureIsVisibleReviewAndCompletedHistoryCannotBeRewritten() throws Exception {
        accept(sample(2));
        jdbc.execute("CREATE OR REPLACE FUNCTION diagnostic_test_retry_fail() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN IF NEW.vehicle_id='"+vehicle+"'::uuid THEN RAISE EXCEPTION 'injected retry failure'; END IF; RETURN NEW; END; $$");
        jdbc.execute("CREATE TRIGGER diagnostic_test_retry_fail BEFORE INSERT ON diagnostic_transition FOR EACH ROW EXECUTE FUNCTION diagnostic_test_retry_fail()");
        Instant now=Instant.now();
        try {
            for(int i=0;i<3;i++) assertThat(projector.processPage(vehicle,now.plusSeconds(61L*i)).state()).isEqualTo("RETRY_OR_REVIEW");
            assertThat(jdbc.queryForObject("select status from diagnostic_work where vehicle_id=?",String.class,vehicle)).isEqualTo("REVIEW");
            assertThat(body(get(path())).get("processingState").asText()).isEqualTo("REVIEW");
            assertThat(count("diagnostic_event")).isZero();
        } finally { jdbc.execute("DROP TRIGGER diagnostic_test_retry_fail ON diagnostic_transition");jdbc.execute("DROP FUNCTION diagnostic_test_retry_fail()"); }
        // Explicit operator-style retry in this disposable test only, after removing the cause.
        jdbc.update("update diagnostic_work set status='PENDING' where vehicle_id=?",vehicle);drain(now.plusSeconds(200));
        assertThatThrownBy(()->jdbc.update("update diagnostic_event set cycle_number=cycle_number+1 where vehicle_id=?",vehicle)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("update diagnostic_scope set ecu_id='OTHER' where vehicle_id=?",vehicle)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("delete from diagnostic_work where vehicle_id=?",vehicle)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void representativeFilteredPagingUsesAnIndexedBoundedPrefixAndDetailFlagsTruncatedTransitions() throws Exception {
        accept(sample(2));drain();
        var row=jdbc.queryForMap("select * from diagnostic_event where vehicle_id=? and category='CONFIRMED'",vehicle);
        // Query-only synthetic history, not a claim that the event engine produced these cycles.
        jdbc.update("""
            insert into diagnostic_event(id,vehicle_id,scope_id,category,code,cycle_number,state,positive_count,first_observed_at,last_observed_at,first_snapshot_id,last_positive_snapshot_id)
            select gen_random_uuid(),?,?,'CONFIRMED','P0117',i,'NOT_OBSERVED',1,?,?,?,? from generate_series(2,1001) i
            """,vehicle,row.get("scope_id"),row.get("first_observed_at"),row.get("last_observed_at"),row.get("first_snapshot_id"),row.get("last_positive_snapshot_id"));
        jdbc.execute("ANALYZE diagnostic_event");
        String explain=jdbc.queryForObject("EXPLAIN (ANALYZE,FORMAT JSON) select id from diagnostic_event where vehicle_id=? and category='CONFIRMED' and state='NOT_OBSERVED' order by last_observed_at desc,id desc limit 11",String.class,vehicle);
        var plan=mapper.readTree(explain).get(0).get("Plan");
        assertThat(plan.get("Actual Rows").asInt()).isEqualTo(11);
        assertThat(explain).contains("Index");
        assertThat(body(get(path()+"?limit=10&category=CONFIRMED&observationState=NOT_OBSERVED")).get("items").size()).isEqualTo(10);
        UUID event=(UUID)row.get("id");
        // Give this detail a bounded recent-history slice using distinct immutable snapshot references.
        for(int i=0;i<101;i++) {
            var receipt=accept(sample(0).put("diagnosticSequence",1000+i));
            jdbc.update("insert into diagnostic_transition(vehicle_id,event_id,snapshot_id,kind,captured_at) values(?,?,?,'OBSERVED',?)",vehicle,event,UUID.fromString(receipt.get("snapshotId").asText()),row.get("last_observed_at"));
        }
        var detail=body(get(path()+"/"+event));assertThat(detail.get("recentTransitions").size()).isEqualTo(100);
        assertThat(detail.get("hasEarlierTransitions").asBoolean()).isTrue();
    }
}
