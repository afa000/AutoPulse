package dev.autopulse;

import dev.autopulse.database.LocalFixtures;
import dev.autopulse.live.LiveStreams;
import dev.autopulse.telemetry.ReplayIdentityBackfill;
import dev.autopulse.trip.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in demo: actual Python, actual backend restart, actual UI and pruning. */
@Testcontainers
class TripMilestoneDemo {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17");
    @TempDir Path temporary;
    static final Instant BASE=Instant.parse("2026-10-20T12:00:00Z");
    static final List<String> VEHICLES=List.of("venue-2021","partition-vehicle","short-vehicle","tiny-vehicle","edge-vehicle","gap-vehicle","tie-vehicle");
    ConfigurableApplicationContext start() {
        // Command-line properties override .env/defaults; never resolve owner DB settings.
        return new SpringApplicationBuilder(AutoPulseApplication.class).run(
                "--spring.config.import=", "--spring.datasource.url="+POSTGRES.getJdbcUrl(),
                "--spring.datasource.username="+POSTGRES.getUsername(),"--spring.datasource.password="+POSTGRES.getPassword(),
                "--spring.flyway.locations=classpath:db/migration","--server.port=0","--server.shutdown=immediate",
                "--autopulse.trip-processing.enabled=false","--autopulse.retention.enabled=false","--autopulse.retention.dry-run=true");
    }
    Process seedServer(Path ready,Path log) throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        return new ProcessBuilder(java,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),TripDemoSeedServer.class.getName(),
                "--autopulse.isolated-demo=true","--autopulse.demo-ready="+ready,
                "--spring.config.import=","--spring.datasource.url="+POSTGRES.getJdbcUrl(),
                "--spring.datasource.username="+POSTGRES.getUsername(),"--spring.datasource.password="+POSTGRES.getPassword(),
                "--spring.flyway.locations=classpath:db/migration","--server.port=0","--server.shutdown=immediate",
                "--autopulse.trip-processing.enabled=false","--autopulse.retention.enabled=false","--autopulse.retention.dry-run=true")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    static UUID vehicle(JdbcTemplate jdbc,String publicId) { return jdbc.queryForObject("select id from vehicle where public_id=?",UUID.class,publicId); }
    static int count(JdbcTemplate jdbc,String table) { return jdbc.queryForObject("select count(*) from "+table,Integer.class); }
    static String url(ConfigurableApplicationContext app) { return "http://127.0.0.1:"+app.getEnvironment().getProperty("local.server.port"); }
    void register(JdbcTemplate jdbc,String publicId) {
        UUID id=UUID.nameUUIDFromBytes(publicId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID device=UUID.nameUUIDFromBytes(("sim-"+publicId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("insert into vehicle(id,public_id) values (?,?)",id,publicId);
        jdbc.update("insert into device(id,vehicle_id,public_id) values (?,?,?)",device,id,"sim-"+publicId);
        jdbc.update("insert into trip_policy(vehicle_id,primary_device_id) values (?,?)",id,device);
    }
    void python(String server,String mode) throws Exception {
        Path output=temporary.resolve("python-"+mode+"-"+UUID.randomUUID()+".log");
        Process p=new ProcessBuilder(System.getProperty("autopulse.python","python"),"../scripts/demo_trips.py",
                "--server-url",server,"--captures",temporary.resolve("captures").toString(),"--mode",mode,"--isolated-demo")
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(p.waitFor(60,TimeUnit.SECONDS)).as("finite Python sender completed").isTrue();
            String result=Files.readString(output); assertThat(p.exitValue()).withFailMessage(result).isZero();
            System.out.println("TRIP_PYTHON_EVIDENCE "+result);
        } finally { if(p.isAlive()) p.destroyForcibly().waitFor(10,TimeUnit.SECONDS); }
    }
    void process(ConfigurableApplicationContext app,UUID vehicle) {
        var coordinator=app.getBean(TripProcessingCoordinator.class);
        Instant asOf=Instant.now().plus(Duration.ofDays(2));
        for(int i=0;i<1000;i++) {
            var page=coordinator.processVehicle(vehicle,asOf,37);
            assertThat(page.visited()).isLessThanOrEqualTo(37);
            assertThat(page.state()).isNotIn("PAUSED","RETRY_STAGE_REQUIRED");
            if("NO_DUE_WORK".equals(page.result())) return;
        }
        throw new AssertionError("projection exceeded finite page budget");
    }
    Map<String,Object> trip(JdbcTemplate jdbc,String publicId) {
        return jdbc.queryForMap("select t.* from trip_record t join vehicle v on v.id=t.vehicle_id where v.public_id=? and t.status='COMPLETED'",publicId);
    }
    void verify(ConfigurableApplicationContext app) {
        var jdbc=app.getBean(JdbcTemplate.class); var mapper=app.getBean(ObjectMapper.class);
        var cases=mapper.readTree(Path.of("../contracts/examples/trip-rule-fixtures.json").toFile());
        for(var c:cases.get("commutes")) {
            String name=c.get("name").asText(); var t=trip(jdbc,name.equals("normal")?"venue-2021":"short-vehicle");
            var s=mapper.readTree(t.get("summary").toString()); var expected=c.get("expected");
            assertThat(t.get("finalization")).isEqualTo("FINAL");
            assertThat(((java.sql.Timestamp)t.get("started_at")).toInstant()).isEqualTo(BASE.plusSeconds(expected.get("startOffsetSeconds").asLong()));
            assertThat(((java.sql.Timestamp)t.get("ended_at")).toInstant()).isEqualTo(BASE.plusSeconds(expected.get("endOffsetSeconds").asLong()));
            assertThat(s.get("sampleCount").asInt()).isEqualTo(expected.get("sampleCount").asInt());
            for(String metric:List.of("rpm","speedKph","coolantTempC","engineLoadPct")) {
                var actual=s.get("metrics").get(metric); var e=expected.get("metrics").get(metric);
                assertThat(actual.get("count").asLong()).isEqualTo(e.get("count").asLong());
                assertThat(actual.get("sum").decimalValue()).isEqualByComparingTo(e.get("sum").decimalValue());
            }
            assertThat(s.get("speedAreaKphSeconds").decimalValue()).isEqualByComparingTo(expected.get("speedAreaKphSeconds").decimalValue());
        }
        assertThat(trip(jdbc,"partition-vehicle").get("summary")).isEqualTo(trip(jdbc,"venue-2021").get("summary"));
        var tiny=mapper.readTree(trip(jdbc,"tiny-vehicle").get("summary").toString());
        assertThat(tiny.get("sampleCount").asInt()).isEqualTo(4); assertThat(tiny.get("speedAreaKphSeconds").decimalValue()).isEqualByComparingTo("144");
        assertThat(tiny.get("durationSeconds").decimalValue()).isEqualByComparingTo("6");
        for(String metric:List.of("rpm","speedKph","coolantTempC","engineLoadPct")) {
            var actual=tiny.get("metrics").get(metric); var expected=cases.get("tiny").get("expected").get("metrics").get(metric);
            assertThat(actual.get("count").asLong()).isEqualTo(expected.get("count").asLong());
            assertThat(actual.get("sum").decimalValue()).isEqualByComparingTo(expected.get("sum").decimalValue());
        }
        assertThat(tiny.get("metrics").get("mafGps").get("sum").isNull()).isTrue();
        var edge=trip(jdbc,"edge-vehicle"); var e=mapper.readTree(edge.get("summary").toString());
        assertThat(e.get("sampleCount").asInt()).isEqualTo(5); assertThat(e.get("speedAreaKphSeconds").decimalValue()).isEqualByComparingTo("72");
        assertThat(edge.get("quality").toString()).contains("UNKNOWN_RPM","AGENT_RESTART");
        var gaps=jdbc.queryForList("select completion_reason,summary from trip_record where vehicle_id=? order by started_at",vehicle(jdbc,"gap-vehicle"));
        assertThat(gaps).hasSize(2); assertThat(gaps.get(0).get("completion_reason")).isEqualTo("SIGNAL_TIMEOUT");
        assertThat(mapper.readTree(gaps.get(0).get("summary").toString()).get("speedAreaKphSeconds").decimalValue()).isEqualByComparingTo("72");
        assertThat(mapper.readTree(gaps.get(1).get("summary").toString()).get("speedAreaKphSeconds").decimalValue()).isEqualByComparingTo("108");
        var tie=mapper.readTree(trip(jdbc,"tie-vehicle").get("summary").toString());
        assertThat(tie.get("sampleCount").asInt()).isEqualTo(3); assertThat(tie.get("speedAreaKphSeconds").decimalValue()).isEqualByComparingTo("108");
        assertThat(jdbc.queryForObject("select count(*) from trip_record where vehicle_id=?",Integer.class,LocalFixtures.EMPTY)).isZero();
        System.out.println("TRIP_RULE_EVIDENCE normal=241/23400 short=27/1760 tiny=4/144 edge=5/72 gap=72+108 tie=3/108 partitionInvariant=true");
    }
    @Test void realSenderRestartProjectionBrowserAndRetention() throws Exception {
        List<Map<String,Object>> receipts;
        Path ready=temporary.resolve("seed-ready"), seedLog=Path.of("target/trip-seed-server.log").toAbsolutePath();
        Process first=seedServer(ready,seedLog);
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
            while(first.isAlive() && !Files.exists(ready) && System.nanoTime()<deadline) Thread.sleep(40);
            assertThat(Files.exists(ready)).withFailMessage(Files.readString(seedLog)).isTrue();
            var data=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
            var jdbc=new JdbcTemplate(data);
            assertThat(count(jdbc,"telemetry_reading")).isZero();
            new LocalFixtures(jdbc,new DataSourceTransactionManager(data)).seed();
            for(String name:VEHICLES) if(!name.equals("venue-2021")) register(jdbc,name);
            register(jdbc,"live-vehicle");
            python("http://127.0.0.1:"+Files.readString(ready),"seed");
            assertThat(count(jdbc,"telemetry_reading")).isEqualTo(557); assertThat(count(jdbc,"trip_record")).isZero();
            assertThat(count(jdbc,"trip_work_item")).isEqualTo(557);
            assertThat(jdbc.queryForObject("select count(*) from trip_work_item where status='PENDING'",Integer.class)).isEqualTo(557);
            assertThat(count(jdbc,"trip_projection_progress")).isZero();
            receipts=jdbc.queryForList("select id,received_at from telemetry_reading order by id");
        } finally {
            first.destroyForcibly(); assertThat(first.waitFor(10,TimeUnit.SECONDS)).as("seed backend JVM terminated").isTrue();
        }
        System.out.println("TRIP_RESTART_EVIDENCE childJvmTerminated=true committedReadings=557 projectedTripsBeforeCrash=0");
        // No in-memory callback or application service survives the child JVM.
        LiveStreams finishedStreams;
        try(var app=start()) {
            finishedStreams=app.getBean(LiveStreams.class);
            var jdbc=app.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForList("select id,received_at from telemetry_reading order by id")).isEqualTo(receipts);
            assertThat(count(jdbc,"trip_record")).isZero();
            for(String name:VEHICLES) {
                UUID v=vehicle(jdbc,name); app.getBean(ReplayIdentityBackfill.class).copyPage(v,500); process(app,v);
            }
            verify(app);
            var trips=jdbc.queryForList("select * from trip_record order by id");
            python(url(app),"replay");
            for(String name:VEHICLES) process(app,vehicle(jdbc,name));
            assertThat(jdbc.queryForList("select * from trip_record order by id")).isEqualTo(trips);
            assertThat(jdbc.queryForList("select id,received_at from telemetry_reading order by id")).isEqualTo(receipts);
            browser(app);
        }
        org.awaitility.Awaitility.await().atMost(10,TimeUnit.SECONDS).untilAsserted(() -> assertThat(finishedStreams.subscriptions()).isZero());
        System.out.println("TRIP_CLEANUP_EVIDENCE applicationClosed=true streamReservations=0");
    }
    void browser(ConfigurableApplicationContext app) throws Exception {
        var jdbc=app.getBean(JdbcTemplate.class); UUID main=LocalFixtures.VEHICLE;
        var before=trip(jdbc,"venue-2021"); Path control=temporary.resolve("trip-control"), network=temporary.resolve("network-control");
        Path output=Path.of("target/trip-milestone-browser.log").toAbsolutePath();
        int port=Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
        try(var proxy=new LoopbackFaultProxy(port,network)) {
            var builder=new ProcessBuilder("node","node_modules/@playwright/test/cli.js","test")
                    .directory(Path.of("../web").toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().put("AUTOPULSE_ISOLATED_DEMO","true"); builder.environment().put("AUTOPULSE_TRIP_MILESTONE","true");
            builder.environment().put("AUTOPULSE_BACKEND_URL","http://127.0.0.1:"+proxy.port());
            builder.environment().put("AUTOPULSE_TRIP_CONTROL",control.toString()); builder.environment().put("AUTOPULSE_NETWORK_CONTROL",network.toString());
            String py=System.getProperty("autopulse.python","python");
            builder.environment().put("AUTOPULSE_PYTHON",py.contains("/")||py.contains("\\")?Path.of(py).toAbsolutePath().normalize().toString():py);
            Process p=builder.start(); boolean pruned=false; long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(210);
            try {
                long nextTick=0;
                while(p.isAlive() && System.nanoTime()<deadline) {
                    if(!pruned && Files.exists(control) && Files.readString(control).equals("prune")) {
                        var retention=new RetentionPreview(jdbc,app.getBean(PlatformTransactionManager.class),new RetentionPreview.Options(true,false,30,96),
                                Clock.fixed(Instant.now().plus(Duration.ofDays(100)),ZoneOffset.UTC));
                        var maintenance=new RetentionMaintenance(jdbc,app.getBean(FinalTripEvidence.class),app.getBean(TripAggregateBuilder.class),app.getBean(TripAggregateVerifier.class),retention);
                        RetentionPreview.Cursor cursor=null; int removed=0; boolean complete=false;
                        for(int i=0;i<150;i++) {
                            var page=maintenance.runPage(main,37,cursor); assertThat(page.state()).isNotIn("BUSY","FAILED");
                            removed+=page.deleted(); cursor=page.nextCursor(); if(page.complete()) { complete=true; break; }
                        }
                        assertThat(complete).isTrue(); assertThat(removed).isEqualTo(241);
                        assertThat(trip(jdbc,"venue-2021")).isEqualTo(before);
                        var generation=jdbc.queryForMap("select * from vehicle_read_generation where vehicle_id=?",main);
                        python(url(app),"replay");
                        assertThat(jdbc.queryForMap("select * from vehicle_read_generation where vehicle_id=?",main)).isEqualTo(generation);
                        assertThat(jdbc.queryForObject("select count(*) from telemetry_reading where vehicle_id=?",Integer.class,main)).isEqualTo(10);
                        Files.writeString(Path.of(control+".ack"),"pruned"); pruned=true;
                        System.out.println("TRIP_RETENTION_EVIDENCE deleted=241 protectedRaw=10 summaryUnchanged=true replayNoResurrection=true");
                    }
                    if(System.nanoTime()>nextTick) {
                        app.getBean(TripProcessingCoordinator.class).processVehicle(vehicle(jdbc,"live-vehicle"),Instant.now(),20);
                        nextTick=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(200);
                    }
                    Thread.sleep(40);
                }
                assertThat(p.isAlive()).as("finite browser suite completed").isFalse();
                String log=Files.readString(output); System.out.println("TRIP_BROWSER_EVIDENCE\n"+log);
                assertThat(p.exitValue()).withFailMessage(log).isZero(); assertThat(pruned).isTrue();
                assertThat(jdbc.queryForObject("select count(*) from telemetry_reading where vehicle_id=?",Integer.class,vehicle(jdbc,"live-vehicle"))).isEqualTo(3);
                assertThat(jdbc.queryForObject("select count(*) from trip_projection_progress where vehicle_id=?",Integer.class,vehicle(jdbc,"live-vehicle"))).isEqualTo(1);
                assertThat(jdbc.queryForObject("select count(*) from trip_work_item where vehicle_id=? and status='PROCESSED'",Integer.class,vehicle(jdbc,"live-vehicle"))).isEqualTo(3);
                assertThat(jdbc.queryForObject("select count(*) from telemetry_reading where vehicle_id=?",Integer.class,LocalFixtures.EMPTY)).isZero();
            } finally {
                if(p.isAlive()) { p.descendants().forEach(ProcessHandle::destroy); p.destroyForcibly().waitFor(10,TimeUnit.SECONDS); }
            }
        }
    }
}
