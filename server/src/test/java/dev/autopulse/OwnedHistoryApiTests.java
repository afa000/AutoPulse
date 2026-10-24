package dev.autopulse;

import dev.autopulse.telemetry.*;
import dev.autopulse.trip.*;
import dev.autopulse.vehicle.VehicleRegistry;
import java.math.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"spring.config.import=","autopulse.history.owned-aggregates-enabled=true"})
@Import(TestcontainersConfiguration.class)
class OwnedHistoryApiTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired TelemetryRepository repository;
    @Autowired TelemetryValidator validator;
    @Autowired TripBackfill backfill;
    @Autowired ReplayIdentityBackfill replay;
    @Autowired TripProjector projector;
    @Autowired TripFinalizer finalizer;
    @Autowired FinalTripEvidence evidence;
    @Autowired TripAggregateBuilder builder;
    @Autowired TripAggregateVerifier verifier;
    @Autowired HistoryQueries baseline;
    @Autowired OwnedHistoryCursor cursors;
    @Autowired OwnedVehicleHistory owned;
    @Autowired TripQueries tripQueries;
    UUID vehicle,device;
    String publicVehicle,publicDevice;
    final Instant base=Instant.parse("2026-10-20T12:00:00Z");
    final HttpClient client=HttpClient.newHttpClient();
    @BeforeEach void setup() {
        vehicle=UUID.randomUUID(); device=UUID.randomUUID(); publicVehicle="owned-api-"+vehicle; publicDevice="sensor-"+device;
        jdbc.update("insert into vehicle(id,public_id) values (?,?)",vehicle,publicVehicle);
        jdbc.update("insert into device(id,vehicle_id,public_id) values (?,?,?)",device,vehicle,publicDevice);
        jdbc.update("insert into trip_policy(vehicle_id,primary_device_id) values (?,?)",vehicle,device);
    }
    ObjectNode input(int sequence,Instant at) {
        return ((ObjectNode)mapper.readTree(Path.of("../contracts/examples/telemetry-sample.json").toFile()))
                .put("vehicleId",publicVehicle).put("deviceId",publicDevice).put("recordedAt",at.toString())
                .put("sequenceNumber",new BigInteger("1234567890123456789012345678901234567890").add(BigInteger.valueOf(sequence)));
    }
    void store(ObjectNode input) { assertThat(repository.store(vehicle,device,validator.validate(input))).isEqualTo("accepted"); }
    @Test void optInCachedPublicHistorySurvivesActualPruningWithExplicitAvailability() throws Exception {
        drive(true);
        jdbc.update("insert into trip_processing_control(vehicle_id,stage,state) values (?,'FORWARD','IDLE')",vehicle);
        var request=new HistoryRequest(base,base.plusSeconds(306),100,null,null,"1m");
        var adapter=new PublicOwnedHistory(tripQueries,owned,cursors,transactions,jdbc);
        var before=adapter.history(publicVehicle,request).body();
        var p=new RetentionPreview(jdbc,transactions,new RetentionPreview.Options(true,false,30,96),
                java.time.Clock.fixed(Instant.now().plusSeconds(100L*86400),java.time.ZoneOffset.UTC));
        assertThat(p.prunePage(vehicle,100,null).deleted()).isPositive();
        var after=adapter.history(publicVehicle,request).body();
        assertThat(after.points()).isEqualTo(before.points());
        assertThat(after.availability().rawState()).isEqualTo("PARTIALLY_EXPIRED");
        assertThat(after.generation()).isNotEqualTo(before.generation());
    }
    UUID drive(boolean verify) {
        int[] seconds={0,2,20,40,59,60,80,100,120,140,160,180,200,220,240,260,280,299,300,302,304}; int sequence=0;
        for (int second : seconds) {
            var node=input(sequence++,base.plusSeconds(second));
            for (String field : TripRules.METRICS) node.putNull(field);
            node.put("rpm",second>=302?0:1000);
            if (second==0) node.put("speedKph",1).put("coolantTempC",new BigDecimal("-20.0000000000000000001"));
            if (second==2) node.put("speedKph",3).put("coolantTempC",new BigDecimal("-19.9999999999999999999"));
            if (second==60) node.put("speedKph",10);
            if (second==300) node.put("speedKph",0);
            store(node);
        }
        backfill.enqueuePage(vehicle,100); replay.copyPage(vehicle,100);
        Instant asOf=Instant.now().plus(Duration.ofDays(1)); boolean done=false;
        for (int i=0;i<100;i++) if (projector.processPage(vehicle,asOf,100).state().equals("CAUGHT_UP")) { done=true; break; }
        assertThat(done).isTrue(); int sealed=0;
        for (int i=0;i<20 && sealed==0;i++) sealed+=finalizer.finalizePage(vehicle,asOf,100).finalized();
        assertThat(sealed).isEqualTo(1);
        UUID trip=jdbc.queryForObject("select id from trip_record where vehicle_id=?",UUID.class,vehicle);
        evidence.copyPage(vehicle,trip,500); builder.buildPage(vehicle,trip,500);
        if (verify) verify(trip); return trip;
    }
    void verify(UUID trip) {
        for (int i=0;i<10;i++) if (verifier.verifyPage(vehicle,trip,500).state().equals("VERIFIED")) return;
        fail("Verification did not finish");
    }
    HistoryRequest query(int limit) { return new HistoryRequest(base.plusSeconds(1),base.plusSeconds(302).plusNanos(1000),limit,null,null,"1m"); }
    static String encode(String text) { return URLEncoder.encode(text,StandardCharsets.UTF_8); }
    String path() { return "/api/v1/vehicles/"+publicVehicle+"/telemetry/history"; }
    String params(HistoryRequest q) { return "?from="+encode(q.from().toString())+"&to="+encode(q.to().toString())+"&limit="+q.limit()+"&resolution="+q.resolution()+(q.tripId()==null?"":"&tripId="+q.tripId()); }
    HttpResponse<String> get(String path) throws Exception { return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString()); }
    JsonNode body(HttpResponse<String> r) { return mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(r.body()); }
    JsonNode expected(HistoryRequest q) { return mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(mapper.writeValueAsString(baseline.history(publicVehicle,q).body().points())); }
    @Test void optInHttpPointsPreserveExactStatisticsCoverageAndTruthfulAvailability() throws Exception {
        drive(true); store(input(900,base.plusSeconds(3)));
        UUID secondary=UUID.randomUUID(); String other="other-"+secondary;
        jdbc.update("insert into device(id,vehicle_id,public_id) values (?,?,?)",secondary,vehicle,other);
        repository.store(vehicle,secondary,validator.validate(input(901,base.plusSeconds(3)).put("deviceId",other).put("source","OBD")));
        var q=query(20); var response=get(path()+params(q)); assertThat(response.statusCode()).isEqualTo(200);
        var json=body(response); assertThat(json.get("points")).isEqualTo(expected(q));
        assertThat(json.get("points").get(0).get("deviceCount").intValue()).isEqualTo(2);
        assertThat(json.get("availability").get("rawState").asText()).isEqualTo("AVAILABLE");
        assertThat(json.get("availability").get("rawUnavailableBefore").isNull()).isTrue();
        assertThat(json.get("availability").get("incomplete").booleanValue()).isTrue();
        assertThat(response.headers().firstValue("X-Trip-Processing-State")).contains("PENDING");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(json.has("archiveOwned")).isFalse();
    }
    @Test void signedHttpPaginationMatchesWholeQueryAndRejectsTamperingOrFilterChanges() throws Exception {
        drive(true); var q=query(1); var points=mapper.createArrayNode(); String token=null,firstToken=null;
        for (int i=0;i<10;i++) {
            var response=get(path()+params(q)+(token==null?"":"&cursor="+token)); assertThat(response.statusCode()).isEqualTo(200);
            var json=body(response); json.get("points").forEach(points::add);
            token=json.get("nextCursor").isNull()?null:json.get("nextCursor").asText(); if (firstToken==null) firstToken=token;
            if (token==null) break; assertThat(token).hasSize(142);
        }
        assertThat(token).isNull(); assertThat(points).isEqualTo(expected(query(20)));
        assertThat(get(path()+params(query(2))+"&cursor="+firstToken).statusCode()).isEqualTo(400);
        assertThat(get(path()+params(q)+"&cursor="+firstToken.substring(0,firstToken.length()-1)+"!").statusCode()).isEqualTo(400);
        var changed=new HistoryRequest(q.from().plusNanos(1000),q.to(),1,null,null,"1m");
        assertThat(get(path()+params(changed)+"&cursor="+firstToken).statusCode()).isEqualTo(400);
    }
    @Test void acceptedInputInvalidatesSignedCursorAndFreshPageReportsNewGeneration() throws Exception {
        drive(true); var q=query(1); var first=body(get(path()+params(q))); String token=first.get("nextCursor").asText();
        store(input(900,base.plusSeconds(3)));
        var response=get(path()+params(q)+"&cursor="+token); assertThat(response.statusCode()).isEqualTo(409);
        assertThat(body(response).get("code").asText()).isEqualTo("PAGE_CHANGED");
        var fresh=body(get(path()+params(q))); assertThat(fresh.get("generation").asText()).isNotEqualTo(first.get("generation").asText());
        assertThat(fresh.get("availability").get("incomplete").booleanValue()).isTrue();
    }
    @Test void verifiedHandoffBetweenHttpPagesKeepsGenerationAndCanonicalValues() throws Exception {
        UUID trip=drive(false); var q=query(1); var first=body(get(path()+params(q))); verify(trip);
        String token=first.get("nextCursor").asText(); var points=mapper.createArrayNode(); first.get("points").forEach(points::add);
        for (int i=0;i<10 && token!=null;i++) {
            var response=get(path()+params(q)+"&cursor="+token); assertThat(response.statusCode()).isEqualTo(200); var page=body(response);
            assertThat(page.get("generation")).isEqualTo(first.get("generation")); page.get("points").forEach(points::add);
            token=page.get("nextCursor").isNull()?null:page.get("nextCursor").asText();
        }
        assertThat(token).isNull(); assertThat(points).isEqualTo(expected(query(20)));
    }
    @Test void rawAndTripFilteredRoutesKeepOldCursorKindsAndCannotCrossUseTokens() throws Exception {
        UUID trip=drive(true); var q=query(1); String token=body(get(path()+params(q))).get("nextCursor").asText();
        var raw=new HistoryRequest(q.from(),q.to(),1,null,null,"raw"); var rawResponse=get(path()+params(raw));
        assertThat(rawResponse.statusCode()).isEqualTo(200); assertThat(body(rawResponse).get("points").get(0).get("kind").asText()).isEqualTo("raw");
        String rawToken=body(rawResponse).get("nextCursor").asText();
        assertThat(get(path()+params(q)+"&cursor="+rawToken).statusCode()).isEqualTo(400);
        assertThat(get(path()+params(raw)+"&cursor="+token).statusCode()).isEqualTo(400);
        var filtered=new HistoryRequest(q.from(),q.to(),1,trip,null,"1m");
        assertThat(get(path()+params(filtered)).statusCode()).isEqualTo(200);
        assertThat(get(path()+params(filtered)+"&cursor="+token).statusCode()).isEqualTo(400);
        assertThat(get(path()+params(q)+"&cursor="+baseline.history(publicVehicle,q).body().nextCursor()).statusCode()).isEqualTo(400);
    }
    @Test void orphanOrMismatchedEvidenceReturnsExplicit503RatherThanSuccess() throws Exception {
        drive(true); UUID boot=UUID.randomUUID();
        jdbc.update("""
                insert into telemetry_replay_identity(device_id,boot_id,sequence_number,reading_id,vehicle_id,recorded_at,received_at,source,payload)
                select device_id,?,999,gen_random_uuid(),vehicle_id,recorded_at,received_at,source,
                payload||jsonb_build_object('bootId',?::text,'sequenceNumber',999) from telemetry_replay_identity where vehicle_id=? and recorded_at=?
                """,boot,boot.toString(),vehicle,Timestamp.from(base.plusSeconds(2)));
        var gap=get(path()+params(query(20))); assertThat(gap.statusCode()).isEqualTo(503);
        assertThat(body(gap).get("code").asText()).isEqualTo("HISTORY_OWNERSHIP_GAP"); assertThat(body(gap).has("points")).isFalse();
        setup(); drive(true);
        jdbc.update("update telemetry_reading set payload=jsonb_set(payload,'{rpm}','1001'::jsonb) where vehicle_id=? and recorded_at=?",vehicle,Timestamp.from(base.plusSeconds(2)));
        var mismatch=get(path()+params(query(20))); assertThat(mismatch.statusCode()).isEqualTo(503);
        assertThat(body(mismatch).get("code").asText()).isEqualTo("HISTORY_OWNERSHIP_MISMATCH");
    }
    @Test void emptyUnknownInvalidAndFiveMinuteRequestsKeepContractShape() throws Exception {
        var q=query(20); var empty=get(path()+params(q)); assertThat(empty.statusCode()).isEqualTo(200);
        assertThat(body(empty).get("points").isEmpty()).isTrue(); assertThat(body(empty).get("nextCursor").isNull()).isTrue();
        assertThat(get("/api/v1/vehicles/unknown-"+UUID.randomUUID()+"/telemetry/history"+params(q)).statusCode()).isEqualTo(404);
        assertThat(get(path()+params(q)+"&limit=1").statusCode()).isEqualTo(400);
        drive(true); var five=new HistoryRequest(q.from(),q.to(),20,null,null,"5m");
        assertThat(body(get(path()+params(five))).get("points")).isEqualTo(expected(five));
    }
    @Test void denseMetadataFailsBeforeUnownedFilteringWithNoPartialHttpPoints() throws Exception {
        drive(true); UUID boot=UUID.randomUUID();
        jdbc.update("""
                insert into telemetry_replay_identity(device_id,boot_id,sequence_number,reading_id,vehicle_id,recorded_at,received_at,source,payload)
                select device_id,?,g,gen_random_uuid(),vehicle_id,recorded_at,received_at,source,
                    payload||jsonb_build_object('bootId',?::text,'sequenceNumber',g)
                from (select * from telemetry_replay_identity where vehicle_id=? and recorded_at=? limit 1) seed cross join generate_series(1,10001) g
                """,boot,boot.toString(),vehicle,Timestamp.from(base.plusSeconds(2)));
        var response=get(path()+params(query(20))); assertThat(response.statusCode()).isEqualTo(503);
        assertThat(body(response).get("code").asText()).isEqualTo("HISTORY_DENSITY_LIMIT"); assertThat(body(response).has("points")).isFalse();
    }
    @Test void publicAdapterPointsGenerationAndProcessingUseOneSnapshotDuringIngestion() throws Exception {
        drive(true); var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var once=new AtomicBoolean();
        var registry=new VehicleRegistry(jdbc) {
            @Override public Optional<UUID> vehicle(String id) {
                var found=super.vehicle(id);
                if (once.compareAndSet(false,true)) {
                    entered.countDown();
                    try { if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                }
                return found;
            }
        };
        var trips=new TripQueries(jdbc,mapper,registry,new TripCursor(),transactions);
        var adapter=new PublicOwnedHistory(trips,new OwnedVehicleHistory(jdbc,mapper,trips,transactions),cursors,transactions,jdbc);
        var pending=CompletableFuture.supplyAsync(() -> adapter.history(publicVehicle,query(1)));
        try { assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); store(input(900,base.plusSeconds(3))); }
        finally { release.countDown(); }
        var old=pending.get(10,TimeUnit.SECONDS); assertThat(old.processingState()).isEqualTo("CURRENT");
        assertThat(old.body().availability().incomplete()).isFalse();
        assertThat(((HistoryQueries.AggregatePoint)old.body().points().getFirst()).sampleCount()).isEqualTo(4);
        assertThat(get(path()+params(query(1))+"&cursor="+old.body().nextCursor()).statusCode()).isEqualTo(409);
    }
    @Test void fullBucketHttpResponsesReuseVerifiedTotalsWithoutExposingInternalDiagnostics() throws Exception {
        drive(true); var query=new HistoryRequest(base,base.plusSeconds(600),20,null,null,"1m");
        var internal=owned.read(publicVehicle,query,null);
        assertThat(internal.reusedContributions()).isEqualTo(6); assertThat(internal.archiveRecomputed()).isZero();
        var response=get(path()+params(query)); assertThat(response.statusCode()).isEqualTo(200); var json=body(response);
        assertThat(json.get("points")).isEqualTo(expected(query));
        assertThat(json.has("reusedContributions")).isFalse(); assertThat(json.has("archiveRecomputed")).isFalse();
        assertThat(json.get("availability").get("rawState").asText()).isEqualTo("AVAILABLE");
    }
}
