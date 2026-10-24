package dev.autopulse;

import dev.autopulse.maintenance.*;
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
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="spring.config.import=")
@Import(TestcontainersConfiguration.class)
class MaintenanceApiTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MaintenanceService service;
    @Autowired MaintenanceInput input;
    @Autowired OilRule oil;
    @Autowired MaintenanceProjector projector;
    @Autowired DiagnosticProjector diagnostics;
    @Autowired VehicleRegistry registry;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationContext context;
    UUID vehicle,device,boot;
    String pv,pd;
    Instant now;
    final HttpClient http=HttpClient.newHttpClient();
    @BeforeEach void isolate() {
        vehicle=UUID.randomUUID();device=UUID.randomUUID();boot=UUID.randomUUID();pv="maint-"+vehicle;pd="maint-"+device;
        now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.update("insert into vehicle(id,public_id) values(?,?)",vehicle,pv);jdbc.update("insert into device(id,vehicle_id,public_id) values(?,?,?)",device,vehicle,pd);
    }
    String path(String part) {return "/api/v1/vehicles/"+pv+"/"+part;}
    HttpResponse<String> request(String method,String path,String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json")
                .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode get(String part) throws Exception { var r=request("GET",path(part),null);assertThat(r.statusCode()).withFailMessage(r.body()).isEqualTo(200);return mapper.readTree(r.body()); }
    JsonNode write(String method,String part,ObjectNode p) throws Exception { var r=request(method,path(part),p.toString());assertThat(r.statusCode()).withFailMessage(r.body()).isEqualTo(200);return mapper.readTree(r.body()); }
    ObjectNode profile(String schedule,long revision) { return mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("expectedRevision",revision)
            .put("applicability","US_2021_VENUE_G16").put("usageSchedule",schedule).put("distanceUnit","MI").put("timeZone","America/New_York").put("normalOilSpecificationConfirmed",true); }
    ObjectNode entry(String kind,Instant at,String value) { var p=mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("expectedRevision",0)
            .put("kind",kind).put("observedAt",at.toString()).put("odometerValue",value).put("odometerUnit","MI").putNull("notes").putNull("correctionReason");
        if(kind.equals("SERVICE"))p.put("serviceType","OIL_FILTER");else p.putNull("serviceType");return p; }
    void drain(Instant at) {
        if(at.isBefore(Instant.now()))at=Instant.now().plusMillis(10);
        // A successful-path SQL failure must show its database cause, not only the production retry state.
        var failFastJdbc=new JdbcTemplate(jdbc.getDataSource()) {
            @Override protected org.springframework.dao.DataAccessException translateException(String task,String sql,java.sql.SQLException failure) {
                throw new AssertionError("Unexpected SQL failure in successful-path disposable fixture: "+sql,failure);
            }
        };
        for(int i=0;i<1000;i++) {var page=new MaintenanceProjector(failFastJdbc,mapper,service,transactions).processPage(vehicle,at);
            assertThat(page.state()).isNotIn("RETRY_OR_REVIEW","REVIEW");if(page.state().startsWith("IDLE"))return;}
        fail("finite maintenance drain exceeded budget");
    }
    int count(String table) { return jdbc.queryForObject("select count(*) from "+table+" where vehicle_id=?",Integer.class,vehicle); }
    @Test void missingProfileHistoryAndEmptyEventsAreUnknownAndScheduleIsOff() throws Exception {
        assertThat(get("service-profile").get("profile").isNull()).isTrue();assertThat(get("service-profile").get("oilAssessment").get("state").asText()).isEqualTo("UNKNOWN");
        assertThat(get("recommendations").get("items").isEmpty()).isTrue();assertThat(count("maintenance_work")).isZero();
        assertThat(context.getBeansOfType(MaintenanceSchedule.class)).isEmpty();
    }
    @Test void exactCommandReplayPreservesOriginalReceiptAndConflictNeverMutates() throws Exception {
        var p=profile("NORMAL",0);var first=write("PUT","service-profile",p);assertThat(write("PUT","service-profile",p)).isEqualTo(first);
        assertThat(count("maintenance_profile")).isEqualTo(1);assertThat(count("maintenance_command")).isEqualTo(1);
        assertThat(request("PUT",path("service-profile"),p.deepCopy().put("usageSchedule","SEVERE").toString()).statusCode()).isEqualTo(409);
        assertThat(request("PUT",path("service-profile"),profile("SEVERE",0).toString()).statusCode()).isEqualTo(409);
        write("PUT","service-profile",profile("SEVERE",1));assertThat(write("PUT","service-profile",p)).isEqualTo(first);
        assertThat(get("service-profile").get("profile").get("usageSchedule").asText()).isEqualTo("SEVERE");
    }
    @Test void backdatedEntryDoesNotRollCurrentOdometerAndCorrectionPreservesEveryRevision() throws Exception {
        write("PUT","service-profile",profile("NORMAL",0));var newest=entry("ODOMETER",now.minusSeconds(10),"20000");var created=write("POST","maintenance-records",newest);
        write("POST","maintenance-records",entry("SERVICE",now.minus(Duration.ofDays(50)),"10000"));
        assertThat(get("service-profile").get("oilAssessment").get("currentOdometer").get("odometerValue").asText()).isEqualTo("20000");
        var correction=newest.deepCopy().put("requestId",UUID.randomUUID().toString()).put("expectedRevision",1).put("odometerValue","19000").put("correctionReason","Corrected transcription");
        String detail="maintenance-records/"+created.get("id").asText();write("PATCH",detail,correction);assertThat(write("PATCH",detail,correction).get("revision").asLong()).isEqualTo(2);
        var history=get(detail);assertThat(history.get("recentRevisions").size()).isEqualTo(2);assertThat(history.get("item").get("odometerValue").asText()).isEqualTo("19000");
        assertThat(request("POST",path("maintenance-records"),entry("ODOMETER",now.minus(Duration.ofDays(70)),"30000").toString()).statusCode()).isEqualTo(409);
        assertThat(request("PATCH",path(detail),correction.deepCopy().put("requestId",UUID.randomUUID().toString()).put("expectedRevision",1).toString()).statusCode()).isEqualTo(409);
    }
    @Test void strictManualValidationRejectsUnknownNumbersDatesFutureAndCommands() throws Exception {
        var base=entry("ODOMETER",now.minusSeconds(10),"100");
        for(var bad:List.of(base.deepCopy().put("odometerValue",100),base.deepCopy().put("clearDtc",true),base.deepCopy().put("observedAt","2026-02-30T00:00:00Z"),
                base.deepCopy().put("odometerValue","-1"),base.deepCopy().put("odometerValue","1e4"),base.deepCopy().put("odometerValue","1.0001"),
                base.deepCopy().put("observedAt",now.plusSeconds(120).toString()),base.deepCopy().put("notes","\nsecret"),base.deepCopy().put("expectedRevision",false)))
            assertThat(request("POST",path("maintenance-records"),bad.toString()).statusCode()).isEqualTo(400);
        assertThat(request("PUT",path("service-profile"),profile("NORMAL",0).put("timeZone","Mars/Olympus").toString()).statusCode()).isEqualTo(400);
        assertThat(request("POST",path("maintenance-records"),base.toString()+" {}").statusCode()).isEqualTo(400);
        assertThat(request("POST",path("maintenance-records")," ".repeat(8193)).statusCode()).isEqualTo(413);
        assertThat(count("maintenance_entry")).isZero();assertThat(count("maintenance_command")).isZero();
    }
    @Test void matchingOilServiceCompletesOnlyOilAdvisoryAndCannotClearDiagnosticEvent() throws Exception {
        write("PUT","service-profile",profile("NORMAL",0));write("POST","maintenance-records",entry("SERVICE",now.minus(Duration.ofDays(60)),"10000"));
        write("POST","maintenance-records",entry("ODOMETER",now.minusSeconds(50),"18000"));
        diagnostic(2,now.minusSeconds(40));drainDiagnostics();drain(now.plusSeconds(1));
        var oilRow=jdbc.queryForMap("select * from maintenance_recommendation where vehicle_id=? and kind='OIL_FILTER'",vehicle);
        assertThat(oilRow.get("state")).isEqualTo("OPEN");int diag=countKind("DIAGNOSTIC");assertThat(diag).isEqualTo(1);
        var forbidden=mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("expectedRevision",1).put("state","COMPLETED");
        assertThat(request("PATCH",path("recommendations/"+oilRow.get("id")),forbidden.toString()).statusCode()).isEqualTo(409);
        var serviceReceipt=write("POST","maintenance-records",entry("SERVICE",now.minusSeconds(5),"18000"));drain(now.plusSeconds(2));
        assertThat(jdbc.queryForObject("select state from maintenance_recommendation where id=?",String.class,oilRow.get("id"))).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select state from maintenance_recommendation where vehicle_id=? and kind='DIAGNOSTIC'",String.class,vehicle)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("select count(*) from diagnostic_event where vehicle_id=? and state='PRESENT'",Integer.class,vehicle)).isEqualTo(3);
        assertThat(get("service-profile").get("oilAssessment").get("state").asText()).isEqualTo("NOT_DUE");
        var evidence=get("recommendations/"+oilRow.get("id"));assertThat(evidence.get("recentRevisions").size()).isEqualTo(2);
        assertThat(evidence.get("item").get("evidence").get("evaluationCause").get("entryId").asText()).isEqualTo(serviceReceipt.get("id").asText());
        var export=mapper.createObjectNode();export.set("profile",get("service-profile"));export.set("entryList",get("maintenance-records"));export.set("entryDetail",get("maintenance-records/"+serviceReceipt.get("id").asText()));
        export.set("recommendationList",get("recommendations"));export.set("recommendationDetail",evidence);export.set("receipt",serviceReceipt);
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/maintenance-http-examples.json").toFile(),export);
    }
    int countKind(String kind) {return jdbc.queryForObject("select count(*) from maintenance_recommendation where vehicle_id=? and kind=?",Integer.class,vehicle,kind);}
    void diagnostic(int index,Instant at) throws Exception {
        var p=((ObjectNode)mapper.readTree(Path.of("../contracts/examples/diagnostic-contract-fixtures.json").toFile()).get("lifecycle").get(index)).deepCopy();
        p.put("vehicleId",pv).put("deviceId",pd).put("capturedAt",at.toString());
        var r=request("POST","/api/v1/device/diagnostic-snapshots",p.toString());assertThat(r.statusCode()).withFailMessage(r.body()).isEqualTo(200);
    }
    void drainDiagnostics() {for(int i=0;i<100;i++)if(diagnostics.processPage(vehicle,Instant.now().plusSeconds(1)).state().startsWith("IDLE"))return;fail("diagnostic budget");}
    @Test void diagnosticFixtureProducesExactlyThreeAdvisoryCyclesAndUserActionsNeverResolveFaults() throws Exception {
        var f=mapper.readTree(Path.of("../contracts/examples/diagnostic-contract-fixtures.json").toFile());
        for(var index:f.get("deliveryOrder")) {
            var p=f.get("lifecycle").get(index.asInt());long offset=Duration.between(Instant.parse(f.get("baseTime").asText()),Instant.parse(p.get("capturedAt").asText())).toSeconds();
            diagnostic(index.asInt(),now.minusSeconds(120).plusSeconds(offset));drainDiagnostics();drain(now.plusSeconds(1));
        }
        assertThat(countKind("DIAGNOSTIC")).isEqualTo(3);assertThat(countKind("OIL_FILTER")).isZero();
        var row=jdbc.queryForMap("select * from maintenance_recommendation where vehicle_id=? and kind='DIAGNOSTIC' and state='OPEN' limit 1",vehicle);
        var p=mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("expectedRevision",1).put("state","ACKNOWLEDGED");
        var action=write("PATCH","recommendations/"+row.get("id"),p);assertThat(write("PATCH","recommendations/"+row.get("id"),p)).isEqualTo(action);
        p.put("requestId",UUID.randomUUID().toString()).put("expectedRevision",2).put("state","DISMISSED");write("PATCH","recommendations/"+row.get("id"),p);
        drain(now.plusSeconds(2));assertThat(countKind("DIAGNOSTIC")).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from diagnostic_event where vehicle_id=? and state='PRESENT'",Integer.class,vehicle)).isGreaterThan(0);
        assertThat(get("recommendations/"+row.get("id")).get("recentRevisions").size()).isEqualTo(3);
    }
    ObjectNode telemetry(int seq,Instant at,JsonNode temp) {
        var p=((ObjectNode)mapper.readTree(Path.of("../contracts/examples/telemetry-sample.json").toFile())).deepCopy();
        p.put("vehicleId",pv).put("deviceId",pd).put("bootId",boot.toString()).put("sequenceNumber",seq).put("recordedAt",at.toString());p.set("coolantTempC",temp);return p;
    }
    JsonNode telemetryPost(ObjectNode p) throws Exception {
        var batch=mapper.createObjectNode();batch.putArray("readings").add(p);var r=request("POST","/api/v1/device/telemetry/batches",batch.toString());assertThat(r.statusCode()).withFailMessage(r.body()).isEqualTo(200);return mapper.readTree(r.body());
    }
    @Test void actualTelemetryQueueThermalFixtureReplayRecoveryAndLateInputAreDurable() throws Exception {
        var fixture=mapper.readTree(Path.of("../contracts/examples/synthetic-temperature-fixture.json").toFile());int seq=0;
        for(var r:fixture.get("readings")) {
            int offset=r.get("offsetSeconds").asInt();var p=telemetry(++seq,now.minusSeconds(120).plusSeconds(offset),r.get("coolantTempC"));
            assertThat(telemetryPost(p).get("accepted").asInt()).isEqualTo(1);assertThat(telemetryPost(p).get("duplicate").asInt()).isEqualTo(1);drain(now.plusSeconds(1));
            if(offset==32)assertThat(countKind("TEMPERATURE")).isEqualTo(1);
            if(offset==34) {
                assertThat(jdbc.queryForObject("select state from maintenance_recommendation where vehicle_id=? and kind='TEMPERATURE'",String.class,vehicle)).isEqualTo("OPEN");
                assertThat(get("recommendations").get("items").get(0).get("currentObservation").get("freshness").asText()).isEqualTo("UNKNOWN");
            }
        }
        assertThat(countKind("TEMPERATURE")).isEqualTo(1);assertThat(jdbc.queryForObject("select state from maintenance_recommendation where vehicle_id=? and kind='TEMPERATURE'",String.class,vehicle)).isEqualTo("EXPIRED");
        assertThat(count("maintenance_rule_decision")).isEqualTo(36);
        telemetryPost(telemetry(++seq,now.minusSeconds(100),mapper.valueToTree(120)));drain(now.plusSeconds(1));
        assertThat(jdbc.queryForObject("select decision from maintenance_rule_decision where vehicle_id=? order by work_id desc limit 1",String.class,vehicle)).isEqualTo("HISTORICAL_ONLY");
        assertThat(countKind("DIAGNOSTIC")).isZero();
    }
    @Test void concurrentDuplicateManualSubmissionsAndWorkerReconstructionDoNotDuplicate() throws Exception {
        var p=entry("SERVICE",now.minusSeconds(10),"10000");var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await();return request("POST",path("maintenance-records"),p.toString());});
            var b=pool.submit(()->{start.await();return request("POST",path("maintenance-records"),p.toString());});start.countDown();
            var ra=a.get(10,TimeUnit.SECONDS);var rb=b.get(10,TimeUnit.SECONDS);assertThat(ra.statusCode()).isEqualTo(200);assertThat(rb.statusCode()).isEqualTo(200);assertThat(mapper.readTree(ra.body())).isEqualTo(mapper.readTree(rb.body()));
        }
        assertThat(count("maintenance_entry")).isEqualTo(1);assertThat(count("maintenance_work")).isEqualTo(1);drain(now.plusSeconds(1));assertThat(count("maintenance_rule_decision")).isEqualTo(1);
    }
    @Test void wrongVehicleLookupsAndCursorBindingsDoNotLeakAndChangedGenerationRestarts() throws Exception {
        var a=write("POST","maintenance-records",entry("ODOMETER",now.minusSeconds(20),"10000"));write("POST","maintenance-records",entry("ODOMETER",now.minusSeconds(10),"10001"));
        var page=get("maintenance-records?limit=1");String cursor=page.get("nextCursor").asText();assertThat(get("maintenance-records?limit=1&cursor="+cursor).get("items").size()).isEqualTo(1);
        assertThat(request("GET",path("recommendations?limit=1&cursor="+cursor),null).statusCode()).isEqualTo(400);
        String wrong="/api/v1/vehicles/venue-2021/maintenance-records/"+a.get("id").asText();assertThat(request("GET",wrong,null).statusCode()).isEqualTo(404);
        assertThat(request("GET",path("maintenance-records?limit=101"),null).statusCode()).isEqualTo(400);
        assertThat(request("GET",path("maintenance-records?limit=1&limit=2"),null).statusCode()).isEqualTo(400);
        write("POST","maintenance-records",entry("ODOMETER",now.minusSeconds(5),"10002"));assertThat(request("GET",path("maintenance-records?limit=1&cursor="+cursor),null).statusCode()).isEqualTo(409);
    }
    @Test void projectionFailureRollsBackDecisionRecommendationAndWorkThenFreshWorkerResumes() throws Exception {
        write("PUT","service-profile",profile("SEVERE",0));write("POST","maintenance-records",entry("SERVICE",now.minus(Duration.ofDays(60)),"10000"));drain(now.plusSeconds(1));
        write("POST","maintenance-records",entry("ODOMETER",now.minusSeconds(10),"15000"));int before=count("maintenance_rule_decision");
        String trigger="maint_fail_"+vehicle.toString().replace("-","");
        jdbc.execute("create function "+trigger+"() returns trigger language plpgsql as $$ begin if NEW.vehicle_id='"+vehicle+"'::uuid then raise exception 'injected'; end if; return NEW; end; $$");
        jdbc.execute("create trigger "+trigger+" before insert on maintenance_recommendation_revision for each row execute function "+trigger+"()");
        Instant attempt=Instant.now().plusSeconds(1);
        try {assertThat(projector.processPage(vehicle,attempt).state()).isEqualTo("RETRY_OR_REVIEW");assertThat(countKind("OIL_FILTER")).isZero();assertThat(count("maintenance_rule_decision")).isEqualTo(before);}
        finally {jdbc.execute("drop trigger "+trigger+" on maintenance_recommendation_revision");jdbc.execute("drop function "+trigger+"()");}
        drain(attempt.plusSeconds(61));assertThat(countKind("OIL_FILTER")).isEqualTo(1);
    }
    @Test void commandFailureDoesNotAcknowledgeHalfEntryAndHistoryGuardsRejectRewrites() throws Exception {
        var p=entry("ODOMETER",now.minusSeconds(10),"10000");String fn="maint_cmd_"+vehicle.toString().replace("-","");
        jdbc.execute("create function "+fn+"() returns trigger language plpgsql as $$ begin if NEW.vehicle_id='"+vehicle+"'::uuid then raise exception 'injected'; end if; return NEW; end; $$");
        jdbc.execute("create trigger "+fn+" before insert on maintenance_command for each row execute function "+fn+"()");
        try {assertThat(request("POST",path("maintenance-records"),p.toString()).statusCode()).isEqualTo(503);assertThat(count("maintenance_entry")).isZero();assertThat(count("maintenance_work")).isZero();}
        finally {jdbc.execute("drop trigger "+fn+" on maintenance_command");jdbc.execute("drop function "+fn+"()");}
        write("POST","maintenance-records",p);drain(now.plusSeconds(1));
        assertThatThrownBy(()->jdbc.update("update maintenance_entry_revision set payload='{}'::jsonb where vehicle_id=?",vehicle)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("delete from maintenance_command where vehicle_id=?",vehicle)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("update maintenance_work set status='PENDING' where vehicle_id=?",vehicle)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void correctedNotDueThenGenuineDueIsNewCycleButDismissAndRefreshNeverDuplicate() throws Exception {
        write("PUT","service-profile",profile("SEVERE",0));write("POST","maintenance-records",entry("SERVICE",now.minus(Duration.ofDays(40)),"10000"));
        var observation=entry("ODOMETER",now.minusSeconds(20),"15000");var receipt=write("POST","maintenance-records",observation);drain(now.plusSeconds(1));
        assertThat(countKind("OIL_FILTER")).isEqualTo(1);
        var correction=observation.deepCopy().put("requestId",UUID.randomUUID().toString()).put("expectedRevision",1).put("odometerValue","14000").put("correctionReason","Corrected transcription");
        write("PATCH","maintenance-records/"+receipt.get("id").asText(),correction);drain(now.plusSeconds(1));
        assertThat(jdbc.queryForObject("select state from maintenance_recommendation where vehicle_id=? and kind='OIL_FILTER'",String.class,vehicle)).isEqualTo("EXPIRED");
        write("POST","maintenance-records",entry("ODOMETER",now.minusSeconds(5),"15000"));drain(now.plusSeconds(1));assertThat(countKind("OIL_FILTER")).isEqualTo(2);
        var open=jdbc.queryForMap("select id,revision from maintenance_recommendation where vehicle_id=? and state='OPEN'",vehicle);
        var dismiss=mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("expectedRevision",1).put("state","DISMISSED");
        write("PATCH","recommendations/"+open.get("id"),dismiss);
        drain(Instant.now().plusSeconds(70));drain(Instant.now().plusSeconds(140));assertThat(countKind("OIL_FILTER")).isEqualTo(2);
    }
    @Test void failedHeadWorkBlocksFollowingItemsAndThreeFailuresBecomeExplicitReview() throws Exception {
        write("PUT","service-profile",profile("NORMAL",0));write("POST","maintenance-records",entry("SERVICE",now.minus(Duration.ofDays(40)),"10000"));drain(now.plusSeconds(1));
        write("POST","maintenance-records",entry("ODOMETER",now.minusSeconds(20),"18000"));write("POST","maintenance-records",entry("ODOMETER",now.minusSeconds(10),"18001"));
        String fn="maint_retry_"+vehicle.toString().replace("-","");
        jdbc.execute("create function "+fn+"() returns trigger language plpgsql as $$ begin if NEW.vehicle_id='"+vehicle+"'::uuid then raise exception 'injected'; end if; return NEW; end; $$");
        jdbc.execute("create trigger "+fn+" before insert on maintenance_rule_decision for each row execute function "+fn+"()");
        Instant attempt=Instant.now().plusSeconds(1);
        try {
            for(int retry=0;retry<3;retry++)assertThat(projector.processPage(vehicle,attempt.plusSeconds(retry*61L)).state()).isEqualTo("RETRY_OR_REVIEW");
            assertThat(jdbc.queryForObject("select count(*) from maintenance_work where vehicle_id=? and status='REVIEW'",Integer.class,vehicle)).isEqualTo(1);
            assertThat(projector.processPage(vehicle,attempt.plusSeconds(200)).state()).isEqualTo("REVIEW");
            assertThat(projector.poll(attempt.plusSeconds(200)).state()).isNotEqualTo("REVIEW");
            assertThat(countKind("OIL_FILTER")).isZero();assertThat(get("recommendations").get("processingState").asText()).isEqualTo("REVIEW");
        } finally {jdbc.execute("drop trigger "+fn+" on maintenance_rule_decision");jdbc.execute("drop function "+fn+"()");}
    }
    @Test void syntheticRecoveryThenRecurrenceAndNonSimulatedInputsAreIndependent() throws Exception {
        for(int second=0;second<=30;second+=2){telemetryPost(telemetry(second,now.minusSeconds(120).plusSeconds(second),mapper.valueToTree(120)));drain(now.plusSeconds(1));}
        assertThat(countKind("TEMPERATURE")).isEqualTo(1);
        for(int second:new int[]{32,34}){telemetryPost(telemetry(second,now.minusSeconds(120).plusSeconds(second),mapper.valueToTree(90)));drain(now.plusSeconds(1));}
        for(int second=36;second<=66;second+=2){telemetryPost(telemetry(second,now.minusSeconds(120).plusSeconds(second),mapper.valueToTree(120)));drain(now.plusSeconds(1));}
        assertThat(countKind("TEMPERATURE")).isEqualTo(2);
        var nonSim=telemetry(68,now.minusSeconds(52),mapper.valueToTree(90)).put("source","OBD");telemetryPost(nonSim);drain(now.plusSeconds(1));
        assertThat(jdbc.queryForObject("select count(*) from maintenance_recommendation where vehicle_id=? and kind='TEMPERATURE' and state='OPEN'",Integer.class,vehicle)).isEqualTo(1);
        // Disposable unprojected raw cleanup only, not an operational retention permit.
        jdbc.update("delete from trip_work_item where vehicle_id=?",vehicle);jdbc.update("delete from telemetry_reading where vehicle_id=?",vehicle);
        assertThat(count("maintenance_rule_decision")).isGreaterThan(0);assertThat(countKind("TEMPERATURE")).isEqualTo(2);
        assertThat(get("recommendations").get("items").get(0).get("evidence").get("synthetic").asBoolean()).isTrue();
    }
    @Test void queuedManualInputsPreserveTheDueThenServiceHistoryAcrossWorkerDowntime() throws Exception {
        write("PUT","service-profile",profile("SEVERE",0));
        write("POST","maintenance-records",entry("SERVICE",now.minus(Duration.ofDays(40)),"10000"));
        write("POST","maintenance-records",entry("ODOMETER",now.minusSeconds(30),"15000"));
        var secondService=write("POST","maintenance-records",entry("SERVICE",now.minusSeconds(10),"15000"));
        // Nothing projected until every command is committed: a later service must not erase queued earlier evidence.
        assertThat(countKind("OIL_FILTER")).isZero();drain(Instant.now().plusSeconds(1));
        assertThat(countKind("OIL_FILTER")).isEqualTo(1);
        var row=jdbc.queryForMap("select id,state,evidence from maintenance_recommendation where vehicle_id=? and kind='OIL_FILTER'",vehicle);
        assertThat(row.get("state")).isEqualTo("COMPLETED");
        assertThat(mapper.readTree(row.get("evidence").toString()).get("evaluationCause").get("entryId").asText()).isEqualTo(secondService.get("id").asText());
        assertThat(get("recommendations/"+row.get("id")).get("recentRevisions").size()).isEqualTo(2);
    }
}
