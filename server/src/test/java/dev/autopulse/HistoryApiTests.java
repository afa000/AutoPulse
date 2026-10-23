package dev.autopulse;

import dev.autopulse.telemetry.TelemetryRepository;
import dev.autopulse.telemetry.TelemetryValidator;
import dev.autopulse.telemetry.ReplayIdentityBackfill;
import dev.autopulse.trip.*;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="spring.config.import=")
@Import(TestcontainersConfiguration.class)
class HistoryApiTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired TelemetryRepository repository;
    @Autowired TelemetryValidator validator;
    @Autowired TripProjector projector;
    @Autowired TripCorrectionPlanner planner;
    @Autowired TripCorrectionReplayer replayer;
    @Autowired TripCorrectionPublisher publisher;
    @Autowired RawHistoryCursor historyCursors;
    @Autowired TripQueries tripQueries;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    UUID vehicle,device;
    String publicVehicle,publicDevice;
    final Instant base=Instant.parse("2026-10-20T12:00:00Z");
    final HttpClient client=HttpClient.newHttpClient();
    @BeforeEach void setup() {
        vehicle=UUID.randomUUID(); device=UUID.randomUUID(); publicVehicle="history-"+vehicle; publicDevice="sensor-"+device;
        jdbc.update("insert into vehicle(id,public_id) values (?,?)",vehicle,publicVehicle);
        jdbc.update("insert into device(id,vehicle_id,public_id) values (?,?,?)",device,vehicle,publicDevice);
        jdbc.update("insert into trip_policy(vehicle_id,primary_device_id) values (?,?)",vehicle,device);
    }
    static String encode(String text) { return URLEncoder.encode(text,StandardCharsets.UTF_8); }
    String path() { return "/api/v1/vehicles/"+publicVehicle+"/telemetry/history"; }
    String bounds(Instant from,Instant to) { return "?from="+encode(from.toString())+"&to="+encode(to.toString()); }
    String range() { return bounds(base,base.plusSeconds(62)); }
    HttpResponse<String> get(String query) throws Exception { return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+query)).GET().build(),HttpResponse.BodyHandlers.ofString()); }
    JsonNode body(HttpResponse<String> response) { return mapper.readTree(response.body()); }
    ObjectNode sample(BigInteger sequence,Instant at) {
        return ((ObjectNode)mapper.readTree(Path.of("../contracts/examples/telemetry-sample.json").toFile()))
                .put("deviceId",publicDevice).put("vehicleId",publicVehicle).put("sequenceNumber",sequence).put("recordedAt",at.toString());
    }
    void store(ObjectNode input) { repository.store(vehicle,device,validator.validate(input)); }
    void drive() {
        for (var value : mapper.readTree(Path.of("../contracts/examples/trip-short-input.json").toFile()).get("readings"))
            store(((ObjectNode)value).deepCopy().put("deviceId",publicDevice).put("vehicleId",publicVehicle));
        for (int page=0;page<100;page++) if (projector.processPage(vehicle,Instant.now().plusSeconds(86400),100).state().equals("CAUGHT_UP")) return;
        fail("drive did not drain");
    }
    UUID trip() { return jdbc.queryForObject("select id from trip_record where vehicle_id=? and status='COMPLETED'",UUID.class,vehicle); }

    void drainHistoryWork() {
        for (int i=0;i<100;i++) if (projector.processPage(vehicle,Instant.now().plusSeconds(86400),100).state().equals("CAUGHT_UP")) return;
        fail("history fixture did not drain");
    }
    UUID sealHistoryTrip() {
        UUID trip=trip(); boolean sealed=false;
        for (int i=0;i<100;i++) {
            if (new TripFinalizer(jdbc,transactions).finalizePage(vehicle,Instant.now().plusSeconds(86400),100).finalized()==1) { sealed=true; break; }
        }
        assertThat(sealed).isTrue(); return trip;
    }
    void prepareHistoryArchive(UUID trip) {
        assertThat(new FinalTripEvidence(jdbc,transactions).copyPage(vehicle,trip,500).complete()).isTrue();
        assertThat(new TripAggregateBuilder(jdbc,transactions).buildPage(vehicle,trip,500).complete()).isTrue();
    }
    void verifyHistoryArchive(UUID trip) {
        for (int i=0;i<100;i++) {
            var page=new TripAggregateVerifier(jdbc,mapper,transactions).verifyPage(vehicle,trip,500);
            assertThat(page.state()).isNotEqualTo("FAILED"); if (page.state().equals("VERIFIED")) return;
        }
        fail("history archive did not verify");
    }
    List<JsonNode> pointList(JsonNode page) { var result=new ArrayList<JsonNode>(); page.get("points").forEach(result::add); return result; }
    @Test void actualPruningPublishesExpiredAvailabilityAndGenerationOverHttp() throws Exception {
        drive(); UUID trip=sealHistoryTrip();
        new ReplayIdentityBackfill(jdbc,transactions).copyPage(vehicle,500);
        new TripBackfill(jdbc,transactions).enqueuePage(vehicle,100);
        jdbc.update("insert into trip_processing_control(vehicle_id,stage,state) values (?,'FORWARD','IDLE')",vehicle);
        prepareHistoryArchive(trip); verifyHistoryArchive(trip);
        String request=path()+range()+"&tripId="+trip;
        var rawFirst=body(get(request+"&limit=1"));
        var buckets=body(get(request+"&resolution=1m")).get("points");
        var wholeBuckets=body(get(path()+range()+"&resolution=5m")).get("points");
        var p=new RetentionPreview(jdbc,transactions,new RetentionPreview.Options(true,false,30,96),
                java.time.Clock.fixed(Instant.now().plusSeconds(100L*86400),java.time.ZoneOffset.UTC));
        assertThat(p.prunePage(vehicle,100,null).deleted()).isEqualTo(27);
        var expired=get(request); assertThat(expired.statusCode()).isEqualTo(200);
        assertThat(body(expired).get("availability").get("rawState").asText()).isEqualTo("EXPIRED");
        assertThat(body(expired).get("points").size()).isZero();
        assertThat(get(request+"&limit=1&cursor="+rawFirst.get("nextCursor").asText()).statusCode()).isEqualTo(409);
        assertThat(body(get(request+"&resolution=1m")).get("points")).isEqualTo(buckets);
        var whole=get(path()+range()+"&resolution=5m");
        assertThat(body(whole).get("points")).isEqualTo(wholeBuckets);
        assertThat(body(whole).get("availability").get("rawState").asText()).isEqualTo("PARTIALLY_EXPIRED");
    }
    @Test void filteredRawCursorSurvivesVerifiedSourceHandoffWithoutGenerationChange() throws Exception {
        drive(); UUID trip=sealHistoryTrip(); String request=path()+range()+"&tripId="+trip;
        var expected=body(get(request)); var first=body(get(request+"&limit=7"));
        var summary=jdbc.queryForMap("select * from trip_record where id=?",trip);
        var actual=pointList(first); String cursor=first.get("nextCursor").asText();
        prepareHistoryArchive(trip); verifyHistoryArchive(trip);
        for (int i=0;i<20 && cursor!=null;i++) {
            var response=get(request+"&limit=7&cursor="+cursor); assertThat(response.statusCode()).isEqualTo(200);
            var page=body(response); actual.addAll(pointList(page));
            assertThat(page.get("generation")).isEqualTo(first.get("generation"));
            assertThat(page.get("availability")).isEqualTo(first.get("availability"));
            cursor=page.get("nextCursor").isNull()?null:page.get("nextCursor").asText();
        }
        assertThat(cursor).isNull(); assertThat(actual).isEqualTo(pointList(expected)).hasSize(27);
        assertThat(jdbc.queryForMap("select * from trip_record where id=?",trip)).isEqualTo(summary);
    }
    @Test void filteredAggregateCursorsSurviveHandoffForBothResolutions() throws Exception {
        for (int second=0;second<=624;second+=2)
            store(sample(BigInteger.valueOf(second),base.plusSeconds(second)).put("rpm",second>=620?0:1900));
        drainHistoryWork(); UUID trip=sealHistoryTrip();
        String request=path()+bounds(base,base.plusSeconds(626))+"&tripId="+trip;
        var expected=new LinkedHashMap<String,JsonNode>(); var first=new LinkedHashMap<String,JsonNode>();
        for (String resolution : List.of("1m","5m")) {
            expected.put(resolution,body(get(request+"&resolution="+resolution)));
            first.put(resolution,body(get(request+"&resolution="+resolution+"&limit=1")));
            assertThat(first.get(resolution).get("nextCursor").isNull()).isFalse();
        }
        prepareHistoryArchive(trip); verifyHistoryArchive(trip);
        for (String resolution : first.keySet()) {
            var actual=pointList(first.get(resolution)); String cursor=first.get(resolution).get("nextCursor").asText();
            for (int i=0;i<20 && cursor!=null;i++) {
                var response=get(request+"&resolution="+resolution+"&limit=1&cursor="+cursor);
                assertThat(response.statusCode()).isEqualTo(200); var page=body(response); actual.addAll(pointList(page));
                assertThat(page.get("generation")).isEqualTo(first.get(resolution).get("generation"));
                cursor=page.get("nextCursor").isNull()?null:page.get("nextCursor").asText();
            }
            assertThat(cursor).isNull(); assertThat(actual).isEqualTo(pointList(expected.get(resolution)));
        }
    }
    @Test void filteredHistoryUsesRawUntilIndependentVerificationThenLedgerForAllReadKinds() {
        drive(); UUID trip=sealHistoryTrip(); var statements=new ArrayList<String>();
        var tracing=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
            @Override public <T> List<T> query(String sql,org.springframework.jdbc.core.RowMapper<T> row,Object... args) {
                statements.add(sql); return super.query(sql,row,args);
            }
        };
        var history=new HistoryQueries(tracing,mapper,tripQueries,historyCursors,transactions);
        var request=new HistoryRequest(base,base.plusSeconds(62),100,trip,null,"raw");
        var original=history.history(publicVehicle,request).body();
        assertThat(statements).anySatisfy(sql -> assertThat(sql).contains("from telemetry_reading"));
        new FinalTripEvidence(jdbc,transactions).copyPage(vehicle,trip,1);
        prepareHistoryArchive(trip);
        statements.clear(); assertThat(history.history(publicVehicle,request).body()).isEqualTo(original);
        assertThat(statements).anySatisfy(sql -> assertThat(sql).contains("from telemetry_reading"));
        verifyHistoryArchive(trip); statements.clear();
        assertThat(history.history(publicVehicle,request).body()).isEqualTo(original);
        for (String resolution : List.of("1m","5m")) history.history(publicVehicle,new HistoryRequest(base,base.plusSeconds(62),100,trip,null,resolution));
        assertThat(statements).anySatisfy(sql -> assertThat(sql).contains("from trip_ledger_reading"));
        assertThat(statements).allSatisfy(sql -> assertThat(sql).doesNotContain("telemetry_reading"));
    }
    @Test void verifiedFilteredMetricsAndReceiptsStayCanonicalAfterPrivilegedRawDrift() throws Exception {
        drive(); UUID trip=sealHistoryTrip(); prepareHistoryArchive(trip); verifyHistoryArchive(trip);
        String request=path()+range()+"&tripId="+trip; var expected=new LinkedHashMap<String,JsonNode>();
        for (String resolution : List.of("raw","1m","5m")) expected.put(resolution,body(get(request+"&resolution="+resolution)).get("points"));
        var original=jdbc.queryForMap("select * from trip_record where id=?",trip);
        UUID first=jdbc.queryForObject("select e.reading_id from trip_retained_evidence e join telemetry_replay_identity l on l.reading_id=e.reading_id where e.trip_id=? order by l.recorded_at limit 1",UUID.class,trip);
        // Deliberate disposable privileged corruption; no guard disabled or row deleted.
        jdbc.update("update telemetry_reading set recorded_at=recorded_at+interval '400 seconds',received_at=received_at+interval '1 second',payload=jsonb_set(payload,'{rpm}','999999'::jsonb) where id=?",first);
        for (String resolution : expected.keySet()) {
            var response=get(request+"&resolution="+resolution); assertThat(response.statusCode()).isEqualTo(200);
            assertThat(body(response).get("points")).isEqualTo(expected.get(resolution));
        }
        var unfiltered=body(get(path()+bounds(base.plusSeconds(4),base.plusSeconds(5))));
        assertThat(unfiltered.get("points").isEmpty()).isTrue(); // Default whole-vehicle raw path is unchanged.
        assertThat(jdbc.queryForMap("select * from trip_record where id=?",trip)).isEqualTo(original);
        assertThatThrownBy(() -> jdbc.update("delete from telemetry_reading where id=?",first)).hasMessageContaining("foreign key constraint");
    }
    @Test void verifiedTripCursorsStillBindFiltersAndDetectAcceptedChanges() throws Exception {
        drive(); UUID trip=sealHistoryTrip(); prepareHistoryArchive(trip); verifyHistoryArchive(trip);
        String request=path()+range()+"&tripId="+trip+"&limit=2";
        String cursor=body(get(request)).get("nextCursor").asText();
        for (String mismatch : List.of(path()+range()+"&limit=2",path()+range()+"&tripId="+trip+"&limit=3",request+"&resolution=1m"))
            assertThat(get(mismatch+"&cursor="+cursor).statusCode()).isEqualTo(400);
        UUID other=UUID.randomUUID(); String otherPublic="verified-other-"+other;
        jdbc.update("insert into vehicle(id,public_id) values (?,?)",other,otherPublic);
        assertThat(get("/api/v1/vehicles/"+otherPublic+"/telemetry/history"+range()+"&tripId="+trip).statusCode()).isEqualTo(404);
        store(sample(BigInteger.valueOf(999),base.plusSeconds(120)));
        var stale=get(request+"&cursor="+cursor); assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(body(stale).get("code").asText()).isEqualTo("PAGE_CHANGED");
        var current=body(get(request)); assertThat(current.get("availability").get("rawState").asText()).isEqualTo("AVAILABLE");
        assertThat(current.get("availability").get("rawUnavailableBefore").isNull()).isTrue();
        assertThat(current.get("availability").get("incomplete").asBoolean()).isTrue();
    }
    @Test void verifiedTripHttpHistoryStillHonorsPublicationGate() throws Exception {
        drive(); UUID trip=sealHistoryTrip(); prepareHistoryArchive(trip); verifyHistoryArchive(trip);
        UUID plan=UUID.randomUUID(),replay=UUID.randomUUID(),publication=UUID.randomUUID();
        UUID work=jdbc.queryForObject("select reading_id from trip_work_item where vehicle_id=? limit 1",UUID.class,vehicle);
        jdbc.update("insert into trip_correction_plan(id,vehicle_id,requested_work_id,primary_device_id,policy_revision,projection_cursor_at,planned_as_of,window_start,window_end,phase) values (?,?,?,?,1,?,?,?,?,'READY')",
                plan,vehicle,work,device,Timestamp.from(base.plusSeconds(62)),Timestamp.from(Instant.now()),Timestamp.from(base),Timestamp.from(base.plusSeconds(62)));
        jdbc.update("insert into trip_correction_replay(id,plan_id,input_generation,window_start,window_end,planned_as_of,read_from,read_to,status,checkpoint) values (?,?,0,?,?,?,?,?,'STAGED','{}'::jsonb)",
                replay,plan,Timestamp.from(base),Timestamp.from(base.plusSeconds(62)),Timestamp.from(Instant.now()),Timestamp.from(base),Timestamp.from(base.plusSeconds(62)));
        jdbc.update("insert into trip_correction_publication(id,vehicle_id,plan_id,replay_id,acceptance_fence,phase) values (?,?,?,?,0,'CAPTURE')",publication,vehicle,plan,replay);
        for (String resolution : List.of("raw","1m","5m")) {
            var response=get(path()+range()+"&tripId="+trip+"&resolution="+resolution);
            assertThat(response.statusCode()).isEqualTo(503); assertThat(body(response).get("code").asText()).isEqualTo("PUBLICATION_PENDING");
            assertThat(body(response).has("points")).isFalse();
        }
        jdbc.update("update trip_correction_publication set phase='ABANDONED' where id=?",publication);
        assertThat(body(get(path()+range()+"&tripId="+trip)).get("points").size()).isEqualTo(27);
    }
    @Test void verifiedTripWirePrecisionAndClippedAllNullMetricsArePreserved() throws Exception {
        var huge=new BigInteger("1234567890123456789012345678901234567890");
        Instant first=base.plusNanos(123456000);
        for (int i=0;i<4;i++) {
            var input=sample(huge.add(BigInteger.valueOf(i)),first.plusSeconds(i*2)).put("rpm",new java.math.BigDecimal(i<2?"1900.00000000000000000001":"0"));
            if (i==0) input.put("coolantTempC",new java.math.BigDecimal("-20.00000000000000000001")); else input.putNull("coolantTempC");
            store(input);
        }
        drainHistoryWork(); UUID trip=sealHistoryTrip();
        String request=path()+bounds(first,first.plusSeconds(5))+"&tripId="+trip;
        var expected=body(get(request)).get("points"); prepareHistoryArchive(trip); verifyHistoryArchive(trip);
        var response=get(request); assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("1900.00000000000000000001",huge.toString(),first.toString());
        assertThat(body(response).get("points")).isEqualTo(expected).hasSize(3);
        String cursor=body(get(request+"&limit=1")).get("nextCursor").asText();
        assertThat(get(request+"&limit=1&cursor="+cursor).statusCode()).isEqualTo(200);
        var clipped=body(get(path()+bounds(first.plusNanos(1000),first.plusSeconds(5))+"&tripId="+trip+"&resolution=1m")).get("points").get(0);
        assertThat(clipped.get("sampleCount").asInt()).isEqualTo(2);
        var coolant=clipped.get("metrics").get("coolantTempC"); assertThat(coolant.get("count").asInt()).isZero();
        for (String stat : List.of("sum","min","max")) assertThat(coolant.get(stat).isNull()).isTrue();
    }
    @Test void defaultModeKeepsRawDerivedAggregatesAndDoesNotConsumeOrphanLedgerRows() throws Exception {
        store(sample(BigInteger.ONE,base.plusSeconds(2))); UUID boot=UUID.randomUUID();
        jdbc.update("""
                insert into telemetry_replay_identity(device_id,boot_id,sequence_number,reading_id,vehicle_id,recorded_at,received_at,source,payload)
                select device_id,?,999,gen_random_uuid(),vehicle_id,recorded_at,received_at,source,
                    payload||jsonb_build_object('bootId',?::text,'sequenceNumber',999)
                from telemetry_replay_identity where vehicle_id=?
                """,boot,boot.toString(),vehicle);
        var response=get(path()+range()+"&resolution=1m"); assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).get("points").get(0).get("sampleCount").asLong()).isEqualTo(1);
    }
    @Test void rawBoundsAreHalfOpenAndWirePrecisionAndOriginalReceiptsArePreserved() throws Exception {
        var exact=sample(BigInteger.ONE,base.plusNanos(123456000)).put("rpm",new java.math.BigDecimal("1900.00000000000000000001"));
        store(exact); store(sample(BigInteger.TWO,base.plusNanos(123457000)));
        var response=get(path()+bounds(base.plusNanos(123456000),base.plusNanos(123457000)));
        assertThat(response.statusCode()).isEqualTo(200); var page=body(response); assertThat(page.get("points").size()).isEqualTo(1);
        var point=page.get("points").get(0); assertThat(point.get("kind").asText()).isEqualTo("raw");
        var parsed=mapper.reader().with(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(response.body());
        assertThat(parsed.get("points").get(0).get("reading").get("rpm").decimalValue()).isEqualByComparingTo("1900.00000000000000000001");
        assertThat(point.get("reading").get("recordedAt").asText()).isEqualTo(base.plusNanos(123456000).toString());
        Instant receipt=jdbc.queryForObject("select received_at from telemetry_reading where vehicle_id=? and sequence_number=1",Timestamp.class,vehicle).toInstant();
        assertThat(Instant.parse(point.get("receivedAt").asText())).isEqualTo(receipt);
        assertThat(page.get("availability").get("availableResolutions").get(0).asText()).isEqualTo("raw");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }
    @Test void tiesAcrossBootsAndDevicesAndHugeSequencesPageWithoutSkipping() throws Exception {
        var huge=new BigInteger("1234567890123456789012345678901234567890");
        store(sample(BigInteger.ONE,base)); store(sample(huge,base));
        store(sample(BigInteger.ZERO,base).put("bootId","ffffffff-ffff-4fff-8fff-ffffffffffff"));
        UUID secondary=UUID.randomUUID(); String other="other-"+secondary;
        jdbc.update("insert into device(id,vehicle_id,public_id) values (?,?,?)",secondary,vehicle,other);
        repository.store(vehicle,secondary,validator.validate(sample(BigInteger.ZERO,base).put("deviceId",other)));
        var expected=jdbc.queryForList("select payload::text from telemetry_reading where vehicle_id=? order by recorded_at,device_id,boot_id,sequence_number",String.class,vehicle);
        var actual=new ArrayList<JsonNode>(); String cursor=null;
        for (int i=0;i<10;i++) {
            var response=get(path()+range()+"&limit=1"+(cursor==null ? "" : "&cursor="+cursor)); assertThat(response.statusCode()).isEqualTo(200);
            var page=body(response); for (var point : page.get("points")) actual.add(point.get("reading"));
            cursor=page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText(); if (cursor==null) break;
            assertThat(cursor.length()).isLessThan(2048);
        }
        assertThat(actual).isEqualTo(expected.stream().map(mapper::readTree).toList());
        assertThat(actual).anyMatch(value -> value.get("sequenceNumber").bigIntegerValue().equals(huge));
    }
    @Test void cursorBindsNormalizedBoundsLimitAndTripAndDetectsAcceptedChanges() throws Exception {
        store(sample(BigInteger.ZERO,base)); store(sample(BigInteger.ONE,base.plusSeconds(2)));
        String cursor=body(get(path()+range()+"&limit=1")).get("nextCursor").asText();
        String equivalent="?from="+encode("2026-10-20T13:00:00+01:00")+"&to="+encode("2026-10-20T13:01:02+01:00");
        assertThat(get(path()+equivalent+"&limit=1&cursor="+cursor).statusCode()).isEqualTo(200);
        assertThat(get(path()+range()+"&limit=2&cursor="+cursor).statusCode()).isEqualTo(400);
        assertThat(get(path()+bounds(base,base.plusSeconds(61))+"&limit=1&cursor="+cursor).statusCode()).isEqualTo(400);
        store(sample(BigInteger.ZERO,base)); assertThat(get(path()+range()+"&limit=1&cursor="+cursor).statusCode()).isEqualTo(200);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> { store(sample(BigInteger.TWO,base.plusSeconds(4))); tx.setRollbackOnly(); });
        assertThat(get(path()+range()+"&limit=1&cursor="+cursor).statusCode()).isEqualTo(200);
        store(sample(BigInteger.TWO,base.plusSeconds(4))); var changed=get(path()+range()+"&limit=1&cursor="+cursor);
        assertThat(changed.statusCode()).isEqualTo(409); assertThat(body(changed).get("code").asText()).isEqualTo("PAGE_CHANGED");
    }
    @Test void tripFilterIncludesClosedBoundaryButNotShutdownWaitingOrLosingTies() throws Exception {
        drive(); UUID trip=trip();
        // A lower-boot tie is accepted raw history, but not canonical trip evidence.
        store(sample(BigInteger.valueOf(999),base.plusSeconds(10)));
        var response=get(path()+range()+"&tripId="+trip); assertThat(response.statusCode()).isEqualTo(200); var points=body(response).get("points");
        assertThat(points.size()).isEqualTo(27);
        assertThat(points.get(0).get("reading").get("recordedAt").asText()).isEqualTo(base.plusSeconds(4).toString());
        assertThat(points.get(26).get("reading").get("recordedAt").asText()).isEqualTo(base.plusSeconds(56).toString());
        assertThat(body(get(path()+range()+"&resolution=1m&tripId="+trip)).get("points").get(0).get("sampleCount").asInt()).isEqualTo(27);
        assertThat(body(get(path()+bounds(base.plusSeconds(56),base.plusSeconds(56).plusNanos(1000))+"&tripId="+trip)).get("points").size()).isEqualTo(1);
        assertThat(body(get(path()+bounds(base,base.plusSeconds(56))+"&tripId="+trip)).get("points").size()).isEqualTo(26);
        String filtered=body(get(path()+range()+"&tripId="+trip+"&limit=1")).get("nextCursor").asText();
        assertThat(get(path()+range()+"&limit=1&cursor="+filtered).statusCode()).isEqualTo(400);
        assertThat(get(path()+range()+"&tripId="+UUID.randomUUID()).statusCode()).isEqualTo(404);
        String other="isolated-"+vehicle; jdbc.update("insert into vehicle(id,public_id) values (?,?)",UUID.randomUUID(),other);
        assertThat(get("/api/v1/vehicles/"+other+"/telemetry/history"+range()+"&tripId="+trip).statusCode()).isEqualTo(404);
    }
    @Test void emptyUnknownIsolationAndUnsupportedQueriesUseStructuredErrors() throws Exception {
        var empty=get(path()+range()); assertThat(empty.statusCode()).isEqualTo(200); assertThat(body(empty).get("points").isEmpty()).isTrue();
        assertThat(body(empty).get("availability").get("rawState").asText()).isEqualTo("AVAILABLE");
        assertThat(get("/api/v1/vehicles/missing/telemetry/history"+range()).statusCode()).isEqualTo(404);
        for (String invalid : List.of("", "?from="+base, bounds(base,base), bounds(base.plusSeconds(2),base),bounds(base,base.plusSeconds(86401)),
                "?from=2026-10-20T12:00:00.1234567Z&to=2026-10-20T12:01:00Z",range()+"&resolution=1h",range()+"&resolution=RAW",range()+"&limit=0",range()+"&limit=1001",range()+"&limit=abc",
                range()+"&limit=1&limit=2",range()+"&cursor=",range()+"&extra=x",range()+"&tripId=1-1-1-1-1")) {
            var response=get(path()+invalid); assertThat(response.statusCode()).as(invalid).isEqualTo(400); assertThat(body(response).get("correlationId").asText()).isNotBlank();
        }
    }
    @Test void publicationGateAndSupersededFilterNeverLookLikeEmptyHistory() throws Exception {
        drive(); UUID original=trip();
        var late=(ObjectNode)mapper.readTree(Path.of("../contracts/examples/trip-short-input.json").toFile()).get("readings").get(5).deepCopy();
        store(late.put("vehicleId",publicVehicle).put("deviceId",publicDevice).put("sequenceNumber",999).put("rpm",2000));
        projector.processPage(vehicle,Instant.now().plusSeconds(86400),100);
        for (int i=0;i<100;i++) if (planner.planPage(vehicle,Instant.now().plusSeconds(86400),100).state().equals("READY")) break;
        for (int i=0;i<100;i++) if (replayer.replayPage(vehicle,100).state().equals("STAGED")) break;
        publisher.publishPage(vehicle,100);
        assertThat(get(path()+range()).statusCode()).isEqualTo(503); assertThat(get(path()+range()+"&tripId="+original).statusCode()).isEqualTo(503);
        assertThat(get(path()+range()+"&resolution=1m").statusCode()).isEqualTo(503);
        for (int i=0;i<100;i++) if (publisher.publishPage(vehicle,100).state().equals("DONE")) break;
        jdbc.update("update trip_record set status='SUPERSEDED' where id=?",original);
        var superseded=get(path()+range()+"&tripId="+original); assertThat(superseded.statusCode()).isEqualTo(409);
        assertThat(get(path()+range()+"&resolution=5m&tripId="+original).statusCode()).isEqualTo(409);
    }
    @Test void acceptedInsertDuringResponseCannotMixGenerationAndRawRows() throws Exception {
        concurrentRead("raw");
    }
    @Test void acceptedInsertDuringAggregationCannotMixGenerationAndSummaries() throws Exception {
        concurrentRead("1m");
    }
    void concurrentRead(String resolution) throws Exception {
        store(sample(BigInteger.ZERO,base)); store(sample(BigInteger.ONE,base.plusSeconds(120)));
        var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        var registry=new dev.autopulse.vehicle.VehicleRegistry(jdbc) {
            @Override public Optional<UUID> vehicle(String id) {
                var value=super.vehicle(id); assertThat(jdbc.queryForObject("show transaction_isolation",String.class)).isEqualTo("repeatable read");
                entered.countDown();
                try { assertThat(release.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                return value;
            }
        };
        var tripReads=new TripQueries(jdbc,mapper,registry,new TripCursor(),transactions);
        var history=new HistoryQueries(jdbc,mapper,tripReads,historyCursors,transactions);
        var query=new HistoryRequest(base,base.plusSeconds(180),1,null,null,resolution);
        var pending=java.util.concurrent.CompletableFuture.supplyAsync(() -> history.history(publicVehicle,query));
        try { assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); store(sample(BigInteger.TWO,base.plusSeconds(4))); }
        finally { release.countDown(); }
        var old=pending.get(5,java.util.concurrent.TimeUnit.SECONDS).body(); assertThat(old.points()).hasSize(1); assertThat(old.nextCursor()).isNotNull();
        String request=path()+bounds(base,base.plusSeconds(180))+"&resolution="+resolution;
        assertThat(get(request+"&limit=1&cursor="+old.nextCursor()).statusCode()).isEqualTo(409);
        var fresh=body(get(request));
        if (resolution.equals("raw")) assertThat(fresh.get("points").size()).isEqualTo(3);
        else {
            assertThat(((HistoryQueries.AggregatePoint)old.points().getFirst()).sampleCount()).isEqualTo(1);
            assertThat(fresh.get("points").size()).isEqualTo(2); assertThat(fresh.get("points").get(0).get("sampleCount").asInt()).isEqualTo(2);
        }
    }
    @Test void aggregateEdgesNullCoverageAndExactSumsAreNotRoundedOrZeroFilled() throws Exception {
        store(sample(BigInteger.ZERO,base.plusSeconds(1))); // outside request
        store(sample(BigInteger.ONE,base.plusSeconds(2)).put("rpm",new java.math.BigDecimal("1000.00000000000000000001")));
        store(sample(BigInteger.TWO,base.plusSeconds(59)).putNull("rpm"));
        store(sample(BigInteger.valueOf(3),base.plusSeconds(60)).put("rpm",2000));
        store(sample(BigInteger.valueOf(4),base.plusSeconds(61))); // exclusive end
        var response=get(path()+bounds(base.plusSeconds(2),base.plusSeconds(61))+"&resolution=1m");
        assertThat(response.statusCode()).isEqualTo(200);
        var page=mapper.reader().with(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(response.body());
        assertThat(page.get("points").size()).isEqualTo(2);
        var first=page.get("points").get(0); var second=page.get("points").get(1);
        assertThat(first.get("kind").asText()).isEqualTo("aggregate"); assertThat(first.has("reading")).isFalse();
        assertThat(first.get("bucketStart").asText()).isEqualTo(base.plusSeconds(2).toString());
        assertThat(first.get("bucketEnd").asText()).isEqualTo(base.plusSeconds(60).toString());
        assertThat(first.get("partial").asBoolean()).isTrue(); assertThat(second.get("partial").asBoolean()).isTrue();
        assertThat(second.get("bucketEnd").asText()).isEqualTo(base.plusSeconds(61).toString());
        assertThat(first.get("sampleCount").asInt()).isEqualTo(2); assertThat(second.get("sampleCount").asInt()).isEqualTo(1);
        var rpm=first.get("metrics").get("rpm"); assertThat(rpm.get("count").asInt()).isEqualTo(1);
        for (String field : List.of("sum","min","max")) assertThat(rpm.get(field).decimalValue()).isEqualByComparingTo("1000.00000000000000000001");
        var unavailable=first.get("metrics").get("mafGps"); assertThat(unavailable.get("count").asInt()).isZero();
        for (String field : List.of("sum","min","max")) assertThat(unavailable.get(field).isNull()).isTrue();
        assertThat(first.get("firstObservedAt").asText()).isEqualTo(base.plusSeconds(2).toString());
        assertThat(first.get("lastObservedAt").asText()).isEqualTo(base.plusSeconds(59).toString());
        assertThat(page.get("availability").get("availableResolutions").size()).isEqualTo(3);
    }
    @Test void aggregatePagingSkipsEmptySpansButNeverSkipsOrRepeatsABucket() throws Exception {
        for (int i : new int[]{0,30,60,1200,1260,1800}) store(sample(BigInteger.valueOf(i),base.plusSeconds(i)));
        for (String resolution : List.of("1m","5m")) {
            String request=path()+bounds(base,base.plusSeconds(1801))+"&resolution="+resolution;
            var whole=body(get(request)); var actual=new ArrayList<JsonNode>(); String cursor=null;
            for (int page=0;page<10;page++) {
                var response=get(request+"&limit=1"+(cursor==null ? "" : "&cursor="+cursor)); assertThat(response.statusCode()).isEqualTo(200);
                var value=body(response); assertThat(value.get("points").size()).isEqualTo(1); actual.add(value.get("points").get(0));
                cursor=value.get("nextCursor").isNull() ? null : value.get("nextCursor").asText(); if (cursor==null) break;
            }
            assertThat(cursor).isNull(); var expected=new ArrayList<JsonNode>(); whole.get("points").forEach(expected::add);
            assertThat(actual).isEqualTo(expected);
            assertThat(actual.stream().mapToInt(p -> p.get("sampleCount").asInt()).sum()).isEqualTo(6);
        }
    }
    @Test void aggregateCursorBindsResolutionAndGenerationAndTripMembership() throws Exception {
        drive(); UUID trip=trip();
        store(sample(BigInteger.valueOf(999),base.plusSeconds(120)));
        String request=path()+bounds(base,base.plusSeconds(180))+"&limit=1";
        String token=body(get(request+"&resolution=1m")).get("nextCursor").asText();
        for (String extra : List.of("&resolution=raw","&resolution=5m","&resolution=1m&tripId="+trip)) {
            var mismatch=get(request+extra+"&cursor="+token); assertThat(mismatch.statusCode()).isEqualTo(400);
            assertThat(body(mismatch).get("code").asText()).isEqualTo("INVALID_CURSOR");
        }
        var filtered=body(get(request+"&resolution=1m&tripId="+trip)); assertThat(filtered.get("points").size()).isEqualTo(1);
        assertThat(filtered.get("points").get(0).get("sampleCount").asInt()).isEqualTo(27); assertThat(filtered.get("nextCursor").isNull()).isTrue();
        store(sample(BigInteger.valueOf(1000),base.plusSeconds(30)));
        var changed=get(request+"&resolution=1m&cursor="+token); assertThat(changed.statusCode()).isEqualTo(409);
        assertThat(body(changed).get("code").asText()).isEqualTo("PAGE_CHANGED");
        String raw=body(get(request)).get("nextCursor").asText(); assertThat(get(request+"&resolution=1m&cursor="+raw).statusCode()).isEqualTo(400);
    }
    @Test void aggregateProvenanceDoesNotHideConflictingDevicesOrEqualTimeTies() throws Exception {
        store(sample(BigInteger.ZERO,base).put("rpm",1000));
        store(sample(BigInteger.ONE,base).put("rpm",2000));
        UUID other=UUID.randomUUID(); String otherPublic="secondary-"+other;
        jdbc.update("insert into device(id,vehicle_id,public_id) values (?,?,?)",other,vehicle,otherPublic);
        repository.store(vehicle,other,validator.validate(sample(BigInteger.ZERO,base).put("deviceId",otherPublic).put("source","OBD").put("rpm",3000)));
        var point=body(get(path()+range()+"&resolution=5m")).get("points").get(0);
        assertThat(point.get("sampleCount").asInt()).isEqualTo(3); assertThat(point.get("deviceCount").asInt()).isEqualTo(2);
        assertThat(point.get("sources").toString()).isEqualTo("[\"OBD\",\"SIMULATED\"]");
        assertThat(point.get("metrics").get("rpm").get("sum").asInt()).isEqualTo(6000);
        assertThat(point.get("metrics").get("rpm").get("count").asInt()).isEqualTo(3);
    }
    @Test void aggregateRangeLimitsEmptyAndVehicleIsolationAreExplicit() throws Exception {
        store(sample(BigInteger.ONE,base));
        for (String resolution : List.of("1m","5m")) {
            assertThat(get(path()+bounds(base,base.plusSeconds(31L*86400))+"&resolution="+resolution).statusCode()).isEqualTo(200);
            assertThat(get(path()+bounds(base,base.plusSeconds(31L*86400+1))+"&resolution="+resolution).statusCode()).isEqualTo(400);
            assertThat(get("/api/v1/vehicles/unknown/telemetry/history"+range()+"&resolution="+resolution).statusCode()).isEqualTo(404);
            var empty=body(get(path()+bounds(base.plusSeconds(60),base.plusSeconds(120))+"&resolution="+resolution));
            assertThat(empty.get("points").isEmpty()).isTrue(); assertThat(empty.get("nextCursor").isNull()).isTrue();
            String other="other-vehicle-"+resolution+vehicle; jdbc.update("insert into vehicle(id,public_id) values (?,?)",UUID.randomUUID(),other);
            var isolated=body(get("/api/v1/vehicles/"+other+"/telemetry/history"+range()+"&resolution="+resolution));
            assertThat(isolated.get("points").isEmpty()).isTrue();
        }
    }
    @Test void representativeRawAndAggregatePlansUseBoundedIndexedInputPages() {
        // Real accepted inserts/triggers in a disposable DB; no owner tables or planner overrides.
        bulkHistory(5000,"1 second");
        jdbc.execute("analyze telemetry_reading");
        record Sql(String text,Object[] args) {}
        var captured=new ArrayList<Sql>();
        var tracing=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
            @Override public <T> List<T> query(String sql,org.springframework.jdbc.core.RowMapper<T> row,Object... args) {
                if (sql.startsWith("select r.id,r.payload") || sql.startsWith("with selected as materialized")) captured.add(new Sql(sql,args));
                return super.query(sql,row,args);
            }
        };
        var history=new HistoryQueries(tracing,mapper,tripQueries,historyCursors,transactions);
        history.history(publicVehicle,new HistoryRequest(base.plusSeconds(4900),base.plusSeconds(5000),10,null,null));
        history.history(publicVehicle,new HistoryRequest(base.plusSeconds(4900),base.plusSeconds(5000),1,null,null,"1m"));
        assertThat(captured).hasSize(2);
        for (int i=0;i<captured.size();i++) {
            var statement=captured.get(i);
            JsonNode plan=mapper.readTree(jdbc.queryForObject("explain (analyze,buffers,format json) "+statement.text(),String.class,statement.args())).get(0).get("Plan");
            var scans=new ArrayList<JsonNode>(); telemetryScans(plan,scans); assertThat(scans).hasSize(1);
            JsonNode scan=scans.getFirst();
            assertThat(scan.get("Node Type").asText()).contains("Index");
            long visits=(scan.get("Actual Rows").asLong()+scan.path("Rows Removed by Filter").asLong())*scan.get("Actual Loops").asLong();
            assertThat(visits).isLessThanOrEqualTo(i==0 ? 11 : 20);
        }
    }
    @Test void denseBucketFailsWithoutPartialStatisticsAndANarrowerRangeRecovers() throws Exception {
        bulkHistory(100001,"1 microsecond");
        var overflow=get(path()+range()+"&resolution=1m&limit=1");
        assertThat(overflow.statusCode()).isEqualTo(503); assertThat(body(overflow).get("code").asText()).isEqualTo("HISTORY_DENSITY_LIMIT");
        assertThat(body(overflow).has("points")).isFalse();
        var narrow=get(path()+bounds(base,base.plusNanos(1000))+"&resolution=1m&limit=1");
        assertThat(narrow.statusCode()).isEqualTo(200); assertThat(body(narrow).get("points").get(0).get("sampleCount").asInt()).isEqualTo(1);
    }
    @Test void coarserBucketsCombineCountsAndSumsInsteadOfAveragingAverages() throws Exception {
        store(sample(BigInteger.ZERO,base).put("rpm",0));
        for (int i=1;i<=3;i++) store(sample(BigInteger.valueOf(i),base.plusSeconds(60+i)).put("rpm",3000));
        var fine=body(get(path()+bounds(base,base.plusSeconds(300))+"&resolution=1m")).get("points");
        var coarse=body(get(path()+bounds(base,base.plusSeconds(300))+"&resolution=5m")).get("points").get(0);
        assertThat(fine.size()).isEqualTo(2); assertThat(coarse.get("partial").asBoolean()).isFalse();
        var rpm=coarse.get("metrics").get("rpm"); assertThat(rpm.get("count").asInt()).isEqualTo(4); assertThat(rpm.get("sum").asInt()).isEqualTo(9000);
        assertThat(rpm.get("sum").decimalValue().divide(rpm.get("count").decimalValue())).isEqualByComparingTo("2250");
    }
    void bulkHistory(int samples,String interval) {
        // interval comes only from these fixed test cases, never a user parameter.
        UUID boot=UUID.randomUUID();
        // Commit bounded batches, like ingestion; avoid a huge same-row trigger
        // update chain inside a single artificial 100001-reading transaction.
        for (int start=0;start<samples;start+=500) {
        jdbc.update("""
                insert into telemetry_reading(id,vehicle_id,device_id,boot_id,sequence_number,recorded_at,source,payload)
                select gen_random_uuid(),?,?,?::uuid,n,?::timestamptz+n*?::interval,'SIMULATED',
                ?::jsonb || jsonb_build_object('sequenceNumber',n,'recordedAt',to_char((?::timestamptz+n*?::interval) at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'))
                from generate_series(?,?-1) n
                """,vehicle,device,boot.toString(),Timestamp.from(base),interval,sample(BigInteger.ZERO,base).put("bootId",boot.toString()).toString(),Timestamp.from(base),interval,start,Math.min(samples,start+500));
        }
    }
    static void telemetryScans(JsonNode plan,List<JsonNode> scans) {
        if (plan.has("Relation Name") && plan.get("Relation Name").asText().equals("telemetry_reading")) scans.add(plan);
        if (plan.has("Plans")) for (JsonNode child : plan.get("Plans")) telemetryScans(child,scans);
    }
}
