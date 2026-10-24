package dev.autopulse;

import dev.autopulse.database.LocalFixtures;
import dev.autopulse.telemetry.ReplayIdentityBackfill;
import dev.autopulse.trip.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/** Opt-in permanent regression across actual ingestion, analytics HTTP and Chromium. */
@Testcontainers
class NumericAnalyticsDemo {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17");
    @TempDir Path temporary;
    static final List<String> VEHICLES=List.of("venue-2021","negative-vehicle","cancellation-vehicle");
    @Test void exactLargeTotalsSurviveTheRealHttpBrowserAndRetentionBoundary() throws Exception {
        try(var app=new SpringApplicationBuilder(AutoPulseApplication.class).run(
                "--spring.config.import=","--spring.datasource.url="+POSTGRES.getJdbcUrl(),
                "--spring.datasource.username="+POSTGRES.getUsername(),"--spring.datasource.password="+POSTGRES.getPassword(),
                "--spring.flyway.locations=classpath:db/migration","--server.port=0","--server.shutdown=immediate",
                "--autopulse.trip-processing.enabled=false","--autopulse.retention.enabled=false","--autopulse.retention.dry-run=true",
                "--autopulse.history.owned-aggregates-enabled=true")) {
            var jdbc=app.getBean(JdbcTemplate.class); var tx=app.getBean(PlatformTransactionManager.class);
            var mapper=app.getBean(ObjectMapper.class); new LocalFixtures(jdbc,tx).seed();
            String server="http://127.0.0.1:"+app.getEnvironment().getProperty("local.server.port");
            var client=HttpClient.newHttpClient();
            for(String name:VEHICLES) {
                UUID vehicle,device;
                String publicDevice;
                if(name.equals("venue-2021")) { vehicle=LocalFixtures.VEHICLE; publicDevice="sim-device-001"; }
                else {
                    vehicle=UUID.randomUUID(); device=UUID.randomUUID(); publicDevice="sim-"+name;
                    jdbc.update("insert into vehicle(id,public_id) values (?,?)",vehicle,name);
                    jdbc.update("insert into device(id,vehicle_id,public_id) values (?,?,?)",device,vehicle,publicDevice);
                    jdbc.update("insert into trip_policy(vehicle_id,primary_device_id) values (?,?)",vehicle,device);
                }
                var readings=mapper.createArrayNode(); int index=0;
                for(var input:mapper.readTree(Path.of("../contracts/examples/trip-short-input.json").toFile()).get("readings")) {
                    var row=((ObjectNode)input).deepCopy().put("vehicleId",name).put("deviceId",publicDevice);
                    boolean negative=name.equals("negative-vehicle") || name.equals("cancellation-vehicle") && index>15;
                    row.put("coolantTempC",new BigDecimal(negative?"-1e308":"1e308"));
                    row.put("speedKph",new BigDecimal("1e308")); readings.add(row); index++;
                }
                var request=HttpRequest.newBuilder(URI.create(server+"/api/v1/device/telemetry/batches"))
                        .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("readings",readings)))).build();
                for(int replay=0;replay<2;replay++) {
                    var accepted=client.send(request,HttpResponse.BodyHandlers.ofString());
                    assertThat(accepted.statusCode()).withFailMessage(accepted.body()).isEqualTo(200);
                    assertThat(mapper.readTree(accepted.body()).get(replay==0?"accepted":"duplicate").asInt()).isEqualTo(31);
                }
                app.getBean(ReplayIdentityBackfill.class).copyPage(vehicle,500);
                boolean done=false;
                for(int page=0;page<1000;page++) if(app.getBean(TripProcessingCoordinator.class).processVehicle(vehicle,Instant.now().plusSeconds(172800),37).result().equals("NO_DUE_WORK")) { done=true; break; }
                assertThat(done).isTrue();
                var trip=jdbc.queryForMap("select * from trip_record where vehicle_id=? and status='COMPLETED'",vehicle);
                assertThat(trip.get("finalization")).isEqualTo("FINAL");
                var response=client.send(HttpRequest.newBuilder(URI.create(server+"/api/v1/vehicles/"+name+"/trips/"+trip.get("id"))).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                var sum=mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(response.body()).get("metrics").get("coolantTempC").get("sum").decimalValue();
                assertThat(sum).isEqualByComparingTo(name.equals("venue-2021")?"2.7e309":name.equals("negative-vehicle")?"-2.7e309":"1e308");
                assertThat(mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(response.body())
                        .get("distance").get("kilometres").get("numerator").decimalValue()).isEqualByComparingTo("5.2e309");
                assertThat(jdbc.queryForObject("select count(*) from telemetry_reading where vehicle_id=?",Integer.class,vehicle)).isEqualTo(31);
            }
            Path control=temporary.resolve("numeric-control"),output=Path.of("target/numeric-analytics-browser.log").toAbsolutePath();
            var builder=new ProcessBuilder("node","node_modules/@playwright/test/cli.js","test")
                    .directory(Path.of("../web").toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().put("AUTOPULSE_ISOLATED_DEMO","true"); builder.environment().put("AUTOPULSE_NUMERIC_DEMO","true");
            builder.environment().put("AUTOPULSE_BACKEND_URL",server); builder.environment().put("AUTOPULSE_NUMERIC_CONTROL",control.toString());
            Process browser=builder.start(); boolean pruned=false;
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(150);
            try {
                while(browser.isAlive() && System.nanoTime()<deadline) {
                    if(!pruned && Files.exists(control)) {
                        for(String name:VEHICLES) {
                            UUID vehicle=jdbc.queryForObject("select id from vehicle where public_id=?",UUID.class,name);
                            var before=jdbc.queryForList("select * from trip_record where vehicle_id=?",vehicle);
                            var retention=new RetentionPreview(jdbc,tx,new RetentionPreview.Options(true,false,30,96),Clock.fixed(Instant.now().plusSeconds(8640000),ZoneOffset.UTC));
                            var maintenance=new RetentionMaintenance(jdbc,app.getBean(FinalTripEvidence.class),app.getBean(TripAggregateBuilder.class),app.getBean(TripAggregateVerifier.class),retention);
                            RetentionPreview.Cursor cursor=null; int deleted=0; boolean complete=false;
                            for(int p=0;p<150;p++) {
                                var result=maintenance.runPage(vehicle,37,cursor); assertThat(result.state()).isNotIn("BUSY","FAILED");
                                deleted+=result.deleted(); cursor=result.nextCursor(); if(result.complete()) { complete=true; break; }
                            }
                            assertThat(complete).isTrue(); assertThat(deleted).isEqualTo(27);
                            assertThat(jdbc.queryForList("select * from trip_record where vehicle_id=?",vehicle)).isEqualTo(before);
                        }
                        Files.writeString(Path.of(control+".ack"),"pruned"); pruned=true;
                    }
                    Thread.sleep(40);
                }
                assertThat(browser.isAlive()).as("finite numeric browser completed").isFalse();
                System.out.println("NUMERIC_BROWSER_EVIDENCE\n"+Files.readString(output));
                assertThat(browser.exitValue()).withFailMessage(Files.readString(output)).isZero(); assertThat(pruned).isTrue();
            } finally {
                browser.descendants().forEach(ProcessHandle::destroy);
                if(browser.isAlive()) browser.destroyForcibly().waitFor(10,TimeUnit.SECONDS);
            }
        }
    }
}
