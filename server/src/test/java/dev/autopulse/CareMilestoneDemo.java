package dev.autopulse;

import dev.autopulse.database.LocalFixtures;
import dev.autopulse.diagnostic.DiagnosticProjector;
import dev.autopulse.maintenance.MaintenanceProjector;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** Actual Python simulator/HTTP, committed-work child-JVM crash, staged real UI. */
@Testcontainers
class CareMilestoneDemo {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17");
    @TempDir Path temporary;
    Instant base=Instant.now().minusSeconds(100).truncatedTo(ChronoUnit.MICROS);
    Path capture;
    String[] options() {
        return new String[]{"--spring.config.import=","--spring.datasource.url="+POSTGRES.getJdbcUrl(),
            "--spring.datasource.username="+POSTGRES.getUsername(),"--spring.datasource.password="+POSTGRES.getPassword(),
            "--spring.flyway.locations=classpath:db/migration","--server.port=0","--server.shutdown=immediate",
            "--autopulse.trip-processing.enabled=false","--autopulse.retention.enabled=false","--autopulse.retention.dry-run=true",
            "--autopulse.history.owned-aggregates-enabled=false","--autopulse.diagnostics.processing-enabled=false","--autopulse.maintenance.processing-enabled=false"};
    }
    ConfigurableApplicationContext start() { return new SpringApplicationBuilder(AutoPulseApplication.class).run(options()); }
    static String url(ConfigurableApplicationContext app) { return "http://127.0.0.1:"+app.getEnvironment().getProperty("local.server.port"); }
    void python(String server,String mode) throws Exception {
        Path output=temporary.resolve("python-"+mode+"-"+UUID.randomUUID()+".log");
        var args=new ArrayList<>(List.of(System.getProperty("autopulse.python","python"),"../scripts/demo_care.py",
            "--server-url",server,"--capture",capture.toString(),"--mode",mode,"--isolated-demo"));
        if(mode.equals("baseline"))args.addAll(List.of("--base",base.toString()));
        Process p=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(p.waitFor(30,TimeUnit.SECONDS)).as("finite real Python sender completed").isTrue();
            String log=Files.readString(output);assertThat(p.exitValue()).withFailMessage(log).isZero();
            System.out.println("CARE_PYTHON_EVIDENCE "+log);
        } finally { if(p.isAlive())p.destroyForcibly().waitFor(10,TimeUnit.SECONDS); }
    }
    void drain(ConfigurableApplicationContext app) {
        var diagnostic=app.getBean(DiagnosticProjector.class);var maintenance=app.getBean(MaintenanceProjector.class);
        boolean done=false;
        for(int i=0;i<200;i++) {var p=diagnostic.processPage(LocalFixtures.VEHICLE,Instant.now());if(p.state().startsWith("IDLE")){done=true;break;}assertThat(p.state()).isEqualTo("PROGRESS");}
        assertThat(done).as("finite diagnostic work budget").isTrue();done=false;
        for(int i=0;i<200;i++) {var p=maintenance.processPage(LocalFixtures.VEHICLE,Instant.now().plusMillis(10));if(p.state().startsWith("IDLE")){done=true;break;}assertThat(p.state()).isIn("PROGRESS","CALENDAR");}
        assertThat(done).as("finite advisory work budget").isTrue();
        var jdbc=app.getBean(JdbcTemplate.class);
        assertThat(jdbc.queryForObject("select count(*) from diagnostic_work where status<>'PROCESSED'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from maintenance_work where status<>'PROCESSED'",Integer.class)).isZero();
    }
    static int count(JdbcTemplate jdbc,String table) { return jdbc.queryForObject("select count(*) from "+table,Integer.class); }
    static List<Map<String,Object>> rows(JdbcTemplate jdbc,String table) { return jdbc.queryForList("select t.* from "+table+" t order by to_jsonb(t)::text"); }
    void verifyFinal(ConfigurableApplicationContext app) {
        var jdbc=app.getBean(JdbcTemplate.class);var mapper=app.getBean(ObjectMapper.class);
        var expected=mapper.readTree(Path.of("../contracts/examples/diagnostic-contract-fixtures.json").toFile()).get("expected");
        assertThat(count(jdbc,"diagnostic_snapshot")).isEqualTo(13);assertThat(count(jdbc,"diagnostic_event")).isEqualTo(5);
        assertThat(count(jdbc,"diagnostic_application")).isEqualTo(39);
        assertThat(jdbc.queryForObject("select count(*) from diagnostic_application where decision='HISTORICAL_ONLY'",Integer.class)).isEqualTo(3);
        for(var e:expected.get("cycles")) {
            var actual=jdbc.queryForMap("select * from diagnostic_event where category=? and code=? and cycle_number=?",e.get("category").asText(),e.get("code").asText(),e.get("cycle").asLong());
            assertThat(((Number)actual.get("positive_count")).longValue()).isEqualTo(e.get("positiveObservationCount").asLong());
            assertThat(((java.sql.Timestamp)actual.get("first_observed_at")).toInstant()).isEqualTo(base.plusSeconds(e.get("firstOffset").asLong()));
            assertThat(((java.sql.Timestamp)actual.get("last_observed_at")).toInstant()).isEqualTo(base.plusSeconds(e.get("lastPositiveOffset").asLong()));
            assertThat(actual.get("state")).isEqualTo(e.get("absentOffset").isNull()?"PRESENT":"NOT_OBSERVED");
            if(!e.get("absentOffset").isNull())assertThat(jdbc.queryForObject("select captured_at from diagnostic_transition where event_id=? and kind='NOT_OBSERVED'",java.sql.Timestamp.class,actual.get("id")).toInstant()).isEqualTo(base.plusSeconds(e.get("absentOffset").asLong()));
        }
        assertThat(count(jdbc,"telemetry_reading")).isEqualTo(37);
        assertThat(jdbc.queryForObject("select count(*) from maintenance_recommendation where kind='DIAGNOSTIC'",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from maintenance_recommendation where kind='TEMPERATURE' and state='EXPIRED'",Integer.class)).isEqualTo(1);
        var thermalExpected=mapper.readTree(Path.of("../contracts/examples/synthetic-temperature-fixture.json").toFile()).get("expected");
        for(var boundary:Map.of("OPEN","opensAtOffsetSeconds","UNKNOWN","unknownAtOffsetSeconds","RECOVER","recoversAtOffsetSeconds").entrySet()) {
            var evidence=mapper.readTree(jdbc.queryForObject("select evidence::text from maintenance_rule_decision where rule_version='synthetic-temperature-v1' and decision=?",String.class,boundary.getKey()));
            assertThat(Instant.parse(evidence.get("capturedAt").asText())).isEqualTo(base.plusSeconds(thermalExpected.get(boundary.getValue()).asLong()));
        }
        assertThat(jdbc.queryForObject("select count(*) from maintenance_rule_decision where rule_version='synthetic-temperature-v1' and decision='HISTORICAL_ONLY'",Integer.class)).isEqualTo(17);
        assertThat(jdbc.queryForObject("select count(*) from maintenance_recommendation where kind='OIL_FILTER' and state='COMPLETED'",Integer.class)).isEqualTo(1);
        assertThat(count(jdbc,"maintenance_entry")).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from diagnostic_event where vehicle_id=?",Integer.class,LocalFixtures.EMPTY)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from maintenance_recommendation where vehicle_id=?",Integer.class,LocalFixtures.EMPTY)).isZero();
        System.out.println("CARE_FINAL_EVIDENCE snapshots=13 applications=39 historicalDiagnosticParts=3 events=5 positiveCounts=3,2,3,2,2 diagnosticAdvisories=3 thermalReadings=36+1staleControl thermalHistorical=17 opens=32s unknown=34s recovers=70s thermalAdvisories=1 oilAdvisories=1 manualRecords=3 isolated=true");
    }
    @Test void simulatorCommittedWorkSurvivesActualJvmCrashReplayAndBrowserLifecycle() throws Exception {
        capture=temporary.resolve("care-capture.json");
        try(var app=start()) {
            var jdbc=app.getBean(JdbcTemplate.class);new LocalFixtures(jdbc,app.getBean(PlatformTransactionManager.class)).seed();
            python(url(app),"baseline");drain(app);browser(app,"baseline");
            assertThat(count(jdbc,"diagnostic_event")).isZero();assertThat(count(jdbc,"maintenance_recommendation")).isZero();
        }
        // A different JVM accepts positives while every worker is disabled.
        Path ready=temporary.resolve("child-ready"),log=Path.of("target/care-seed-server.log").toAbsolutePath();
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var command=new ArrayList<>(List.of(java,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),TripDemoSeedServer.class.getName(),"--autopulse.isolated-demo=true","--autopulse.demo-ready="+ready));
        command.addAll(List.of(options()));
        Process child=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        var direct=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        List<Map<String,Object>> snapshots,readings;
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
            while(child.isAlive()&&!Files.exists(ready)&&System.nanoTime()<deadline)Thread.sleep(40);
            assertThat(Files.exists(ready)).withFailMessage(Files.readString(log)).isTrue();
            python("http://127.0.0.1:"+Files.readString(ready),"seed");
            assertThat(count(direct,"diagnostic_snapshot")).isEqualTo(4);assertThat(count(direct,"telemetry_reading")).isEqualTo(17);
            assertThat(count(direct,"diagnostic_event")).isZero();assertThat(count(direct,"maintenance_recommendation")).isZero();
            assertThat(direct.queryForObject("select count(*) from diagnostic_work where status='PENDING'",Integer.class)).isEqualTo(3);
            assertThat(direct.queryForObject("select count(*) from maintenance_work where status='PENDING'",Integer.class)).isEqualTo(16);
            snapshots=rows(direct,"diagnostic_snapshot");readings=rows(direct,"telemetry_reading");
        } finally { child.destroyForcibly();assertThat(child.waitFor(10,TimeUnit.SECONDS)).as("accepting backend JVM terminated").isTrue(); }
        System.out.println("CARE_CRASH_EVIDENCE childJvmTerminated=true committedSnapshots=4 committedTelemetry=17 pendingDiagnostic=3 pendingMaintenance=16 events=0 advisories=0");
        try(var app=start()) {
            var jdbc=app.getBean(JdbcTemplate.class);
            assertThat(rows(jdbc,"diagnostic_snapshot")).isEqualTo(snapshots);assertThat(rows(jdbc,"telemetry_reading")).isEqualTo(readings);
            python(url(app),"replay-seed");
            assertThat(rows(jdbc,"diagnostic_snapshot")).isEqualTo(snapshots);assertThat(rows(jdbc,"telemetry_reading")).isEqualTo(readings);
            drain(app);
            assertThat(count(jdbc,"diagnostic_event")).isEqualTo(3);assertThat(count(jdbc,"maintenance_recommendation")).isEqualTo(2);
            browser(app,"lifecycle");verifyFinal(app);
            var before=new LinkedHashMap<String,List<Map<String,Object>>>();
            for(String table:List.of("diagnostic_snapshot","diagnostic_event","diagnostic_application","diagnostic_transition","telemetry_reading","maintenance_recommendation","maintenance_recommendation_revision","maintenance_rule_decision"))before.put(table,rows(jdbc,table));
            python(url(app),"replay");drain(app);
            before.forEach((table,value)->assertThat(rows(jdbc,table)).as("replay keeps "+table+" including original receipts/evidence").isEqualTo(value));
            System.out.println("CARE_REPLAY_EVIDENCE diagnosticDuplicates=15 telemetryDuplicates=37 originalReceiptsUnchanged=true noSemanticDuplicates=true");
        }
    }
    void browser(ConfigurableApplicationContext app,String phase) throws Exception {
        Path control=temporary.resolve("browser-"+phase),output=Path.of("target/care-milestone-"+phase+"-browser.log").toAbsolutePath();
        var builder=new ProcessBuilder("node","node_modules/@playwright/test/cli.js","test")
            .directory(Path.of("../web").toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("AUTOPULSE_ISOLATED_DEMO","true");builder.environment().put("AUTOPULSE_CARE_MILESTONE","true");
        builder.environment().put("AUTOPULSE_CARE_PHASE",phase);builder.environment().put("AUTOPULSE_CARE_CONTROL",control.toString());
        builder.environment().put("AUTOPULSE_BACKEND_URL",url(app));
        Process p=builder.start();String previous="";var jdbc=app.getBean(JdbcTemplate.class);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
        try {
            while(p.isAlive()&&System.nanoTime()<deadline) {
                if(Files.exists(control)) {
                    String step=Files.readString(control);
                    if(!step.equals(previous)&&Set.of("uncertain","absence","recurrence","first-low","recovery","manual","service").contains(step)) {
                        var diagnosticBefore=rows(jdbc,"diagnostic_event");
                        if(!Set.of("manual","service").contains(step))python(url(app),step);
                        drain(app);
                        if(Set.of("manual","service").contains(step))assertThat(rows(jdbc,"diagnostic_event")).isEqualTo(diagnosticBefore);
                        Files.writeString(Path.of(control+".ack"),step);previous=step;
                    }
                }
                Thread.sleep(40);
            }
            assertThat(p.isAlive()).as("finite staged browser completed").isFalse();
            String result=Files.readString(output);System.out.println("CARE_"+phase.toUpperCase()+"_BROWSER_EVIDENCE\n"+result);
            assertThat(p.exitValue()).withFailMessage(result).isZero();
            if(phase.equals("lifecycle"))assertThat(previous).isEqualTo("service");
        } finally { p.descendants().forEach(ProcessHandle::destroy);if(p.isAlive())p.destroyForcibly().waitFor(10,TimeUnit.SECONDS); }
    }
}
