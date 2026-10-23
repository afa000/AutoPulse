package dev.autopulse;

import dev.autopulse.telemetry.TelemetryRepository;
import dev.autopulse.telemetry.TelemetryValidator;
import dev.autopulse.trip.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="spring.config.import=")
@Import(TestcontainersConfiguration.class)
class TripApiTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired TelemetryRepository repository;
    @Autowired TelemetryValidator validator;
    @Autowired TripProjector projector;
    @Autowired TripCorrectionPlanner planner;
    @Autowired TripCorrectionReplayer replayer;
    @Autowired TripCorrectionPublisher publisher;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    UUID vehicle,device;
    String publicVehicle,publicDevice;
    final HttpClient client=HttpClient.newHttpClient();
    @BeforeEach void vehicle() {
        vehicle=UUID.randomUUID(); device=UUID.randomUUID(); publicVehicle="trip-"+vehicle; publicDevice="device-"+device;
        jdbc.update("insert into vehicle(id,public_id) values (?,?)",vehicle,publicVehicle);
        jdbc.update("insert into device(id,vehicle_id,public_id) values (?,?,?)",device,vehicle,publicDevice);
        jdbc.update("insert into trip_policy(vehicle_id,primary_device_id) values (?,?)",vehicle,device);
    }
    String path() { return "/api/v1/vehicles/"+publicVehicle+"/trips"; }
    HttpResponse<String> get(String path) throws Exception { return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString()); }
    tools.jackson.databind.JsonNode body(HttpResponse<String> response) { return mapper.readTree(response.body()); }
    void store(ObjectNode reading) { reading.put("vehicleId",publicVehicle).put("deviceId",publicDevice); repository.store(vehicle,device,validator.validate(reading)); }
    void drive() {
        for (var reading : mapper.readTree(Path.of("../contracts/examples/trip-short-input.json").toFile()).get("readings")) store(((ObjectNode)reading).deepCopy());
        for (int page=0;page<100;page++) if (projector.processPage(vehicle,Instant.now().plusSeconds(86400),100).state().equals("CAUGHT_UP")) return;
        fail("drive did not drain");
    }
    UUID tripId() { return jdbc.queryForObject("select id from trip_record where vehicle_id=? and status='COMPLETED' order by started_at,id limit 1",UUID.class,vehicle); }
    void copyTrip(UUID id,Instant start) {
        // Query fixture only: clone a real derived summary with shifted boundaries.
        jdbc.update("""
                insert into trip_record(id,vehicle_id,primary_device_id,anchor_boot,anchor_sequence,started_at,observed_through,ended_at,confirmed_at,
                source,status,completion_reason,algorithm_version,summary,quality)
                select ?,vehicle_id,primary_device_id,?,0,?,?::timestamptz+interval '52 seconds',?::timestamptz+interval '52 seconds',
                ?::timestamptz+interval '54 seconds',source,status,completion_reason,algorithm_version,summary,quality from trip_record where id=?
                """,id,UUID.randomUUID(),Timestamp.from(start),Timestamp.from(start),Timestamp.from(start),Timestamp.from(start),tripId());
    }
    @Test void knownEmptyUnknownAndVehicleIsolationUseStructuredErrors() throws Exception {
        var empty=get(path()); assertThat(empty.statusCode()).isEqualTo(200); assertThat(body(empty).get("items").isEmpty()).isTrue();
        assertThat(body(empty).get("nextCursor").isNull()).isTrue();
        var missing=get("/api/v1/vehicles/missing/trips"); assertThat(missing.statusCode()).isEqualTo(404); assertThat(body(missing).get("code").asText()).isEqualTo("UNKNOWN_VEHICLE");
        assertThat(body(missing).get("correlationId").asText()).isNotBlank();
        drive(); UUID original=tripId();
        jdbc.update("insert into vehicle(id,public_id) values (?,?)",UUID.randomUUID(),"other-"+vehicle);
        var wrong=get("/api/v1/vehicles/other-"+vehicle+"/trips/"+original);
        assertThat(wrong.statusCode()).isEqualTo(404); assertThat(body(wrong).get("code").asText()).isEqualTo("UNKNOWN_TRIP");
    }
    @Test void realDerivedDetailHasExactSummaryCoverageAndNoInternalFields() throws Exception {
        drive(); var response=get(path()+"/"+tripId()); assertThat(response.statusCode()).isEqualTo(200); var trip=body(response);
        assertThat(trip.get("vehicleId").asText()).isEqualTo(publicVehicle); assertThat(trip.get("primaryDeviceId").asText()).isEqualTo(publicDevice);
        assertThat(trip.get("sampleCount").intValue()).isEqualTo(27);
        assertThat(trip.get("distance").get("kilometres").get("numerator").decimalValue()).isEqualByComparingTo("1760");
        assertThat(trip.get("distance").get("kilometres").get("denominator").intValue()).isEqualTo(3600);
        assertThat(trip.get("metrics").get("mafGps").get("count").intValue()).isZero();
        assertThat(trip.get("metrics").get("mafGps").get("sum").isNull()).isTrue();
        assertThat(trip.has("anchor_boot")).isFalse(); assertThat(trip.has("checkpoint")).isFalse();
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("X-Trip-Data-Generation")).contains(jdbc.queryForObject("select generation::text from vehicle_read_generation where vehicle_id=?",String.class,vehicle));
        assertThat(response.headers().firstValue("X-Trip-Processing-State")).contains("CURRENT");
    }
    @Test void preciseTimestampAndUnsignedUuidPaginationHasNoSkipsOrDuplicates() throws Exception {
        drive(); UUID original=tripId(); UUID high=UUID.fromString("80000000-0000-4000-8000-000000000001"),low=UUID.fromString("7fffffff-0000-4000-8000-000000000001");
        Instant start=Instant.parse("2026-10-21T12:00:00.123456Z"); copyTrip(high,start); copyTrip(low,start);
        var first=body(get(path()+"?limit=1")); assertThat(first.get("items").get(0).get("tripId").asText()).isEqualTo(high.toString());
        assertThat(first.get("items").get(0).get("startedAt").asText()).isEqualTo(start.toString());
        var second=body(get(path()+"?limit=1&cursor="+first.get("nextCursor").asText()));
        assertThat(second.get("items").get(0).get("tripId").asText()).isEqualTo(low.toString());
        var third=body(get(path()+"?limit=1&cursor="+second.get("nextCursor").asText()));
        assertThat(third.get("items").get(0).get("tripId").asText()).isEqualTo(original.toString()); assertThat(third.get("nextCursor").isNull()).isTrue();
    }
    @Test void changedProjectionInvalidatesCursorAndBadContextIsRejected() throws Exception {
        drive(); copyTrip(UUID.randomUUID(),Instant.parse("2026-10-21T12:00:00Z"));
        String cursor=body(get(path()+"?limit=1")).get("nextCursor").asText();
        assertThat(get(path()+"?limit=2&cursor="+cursor).statusCode()).isEqualTo(400);
        jdbc.update("update trip_record set revision=revision+1 where id=?",tripId());
        var changed=get(path()+"?limit=1&cursor="+cursor); assertThat(changed.statusCode()).isEqualTo(409); assertThat(body(changed).get("code").asText()).isEqualTo("PAGE_CHANGED");
        for (String query : new String[]{"limit=0","limit=101","limit=abc","limit=1&limit=2","unknown=x","cursor=","cursor=abc"})
            assertThat(get(path()+"?"+query).statusCode()).as(query).isEqualTo(400);
        assertThat(get(path()+"/1-1-1-1-1").statusCode()).isEqualTo(400);
    }
    @Test void activePublicationIsUnavailableNotEmptyAndInputIsReportedPending() throws Exception {
        drive(); UUID original=tripId();
        var reading=(ObjectNode)mapper.readTree(Path.of("../contracts/examples/trip-short-input.json").toFile()).get("readings").get(5).deepCopy(); reading.put("sequenceNumber",999).put("rpm",1900); store(reading);
        assertThat(get(path()).headers().firstValue("X-Trip-Processing-State")).contains("PENDING");
        projector.processPage(vehicle,Instant.now().plusSeconds(86400),100);
        for (int i=0;i<100;i++) if (planner.planPage(vehicle,Instant.now().plusSeconds(86400),100).state().equals("READY")) break;
        for (int i=0;i<100;i++) if (replayer.replayPage(vehicle,100).state().equals("STAGED")) break;
        publisher.publishPage(vehicle,100);
        for (String endpoint : new String[]{path(),path()+"/"+original}) {
            var response=get(endpoint); assertThat(response.statusCode()).isEqualTo(503); assertThat(body(response).get("code").asText()).isEqualTo("PUBLICATION_PENDING");
        }
    }
    @Test void acceptedInputChangesGenerationButDuplicateAndRollbackDoNot() throws Exception {
        drive(); copyTrip(UUID.randomUUID(),Instant.parse("2026-10-21T12:00:00Z"));
        var first=body(get(path()+"?limit=1")); String cursor=first.get("nextCursor").asText();
        var reading=(ObjectNode)mapper.readTree(Path.of("../contracts/examples/trip-short-input.json").toFile()).get("readings").get(5).deepCopy();
        store(reading.deepCopy()); assertThat(get(path()+"?limit=1&cursor="+cursor).statusCode()).isEqualTo(200);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> {
            store(reading.deepCopy().put("sequenceNumber",999)); tx.setRollbackOnly();
        });
        assertThat(get(path()+"?limit=1&cursor="+cursor).statusCode()).isEqualTo(200);
        reading.put("sequenceNumber",999); store(reading);
        assertThat(get(path()+"?limit=1&cursor="+cursor).statusCode()).isEqualTo(409);
    }
    @Test void activeAndSupersededDetailAreExplicitButCandidatesStayInternal() throws Exception {
        drive(); UUID original=tripId(); UUID replacement=UUID.randomUUID(); copyTrip(replacement,Instant.parse("2026-10-21T12:00:00Z"));
        jdbc.update("update trip_record set status='SUPERSEDED' where id=?",original);
        // Audit snapshot belongs to a real plan/publication; exercise normal correction setup.
        jdbc.update("update trip_record set status='COMPLETED' where id=?",original);
        var late=(ObjectNode)mapper.readTree(Path.of("../contracts/examples/trip-short-input.json").toFile()).get("readings").get(5).deepCopy(); late.put("sequenceNumber",999).put("rpm",1900); store(late);
        projector.processPage(vehicle,Instant.now().plusSeconds(86400),100);
        for (int page=0;page<100;page++) if (planner.planPage(vehicle,Instant.now().plusSeconds(86400),100).state().equals("READY")) break;
        for (int page=0;page<100;page++) if (replayer.replayPage(vehicle,100).state().equals("STAGED")) break;
        for (int page=0;page<100;page++) if (publisher.publishPage(vehicle,100).state().equals("DONE")) break;
        UUID publication=jdbc.queryForObject("select id from trip_correction_publication where vehicle_id=?",UUID.class,vehicle);
        jdbc.update("update trip_record set status='SUPERSEDED' where id=?",original);
        jdbc.update("insert into trip_correction_lineage(publication_id,parent_trip_id,child_trip_id,relation) values (?,?,?,'SUPERSEDED')",publication,original,replacement);
        var detail=body(get(path()+"/"+original)); assertThat(detail.get("status").asText()).isEqualTo("SUPERSEDED");
        assertThat(detail.get("supersededBy").get(0).asText()).isEqualTo(replacement.toString());
        assertThat(body(get(path())).get("items").size()).isEqualTo(1);
        jdbc.update("update trip_record set status='CANDIDATE',ended_at=null,confirmed_at=null,completion_reason=null where id=?",replacement);
        assertThat(get(path()+"/"+replacement).statusCode()).isEqualTo(404);
        jdbc.update("update trip_record set status='ACTIVE' where id=?",replacement);
        var active=body(get(path()+"/"+replacement)); assertThat(active.get("endedAt").isNull()).isTrue(); assertThat(active.get("completionReason").isNull()).isTrue();
    }
    @Test void publicationStartingDuringReadCannotMixSnapshotGateAndRows() throws Exception {
        drive();
        var reading=(ObjectNode)mapper.readTree(Path.of("../contracts/examples/trip-short-input.json").toFile()).get("readings").get(5).deepCopy();
        store(reading.put("sequenceNumber",999).put("rpm",1900)); projector.processPage(vehicle,Instant.now().plusSeconds(86400),100);
        for (int i=0;i<100;i++) if (planner.planPage(vehicle,Instant.now().plusSeconds(86400),100).state().equals("READY")) break;
        for (int i=0;i<100;i++) if (replayer.replayPage(vehicle,100).state().equals("STAGED")) break;
        var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        var registry=new dev.autopulse.vehicle.VehicleRegistry(jdbc) {
            @Override public java.util.Optional<UUID> vehicle(String id) {
                var result=super.vehicle(id); // Establish the actual PostgreSQL snapshot before publication commits.
                assertThat(jdbc.queryForObject("show transaction_isolation",String.class)).isEqualTo("repeatable read");
                entered.countDown();
                try { assertThat(release.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                return result;
            }
        };
        var queries=new TripQueries(jdbc,mapper,registry,new TripCursor(),transactions);
        var pending=java.util.concurrent.CompletableFuture.supplyAsync(() -> queries.list(publicVehicle,20,null));
        try {
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(publisher.publishPage(vehicle,100).state()).isEqualTo("CAPTURE");
        } finally { release.countDown(); }
        var old=pending.get(5,java.util.concurrent.TimeUnit.SECONDS);
        assertThat(old.body().items()).hasSize(1);
        assertThat(old.body().items().getFirst().distance().kilometres().numerator()).isEqualByComparingTo("1760");
        assertThat(get(path()).statusCode()).isEqualTo(503);
    }
}
