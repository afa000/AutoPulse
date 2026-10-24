package dev.autopulse;

import dev.autopulse.telemetry.*;
import dev.autopulse.trip.*;
import java.math.*;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="spring.config.import=")
@Import(TestcontainersConfiguration.class)
class TripAggregateBuilderTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired TelemetryValidator validator;
    @Autowired TripBackfill backfill;
    @Autowired ReplayIdentityBackfill replay;
    @Autowired TripProjector projector;
    @Autowired TripFinalizer finalizer;
    @Autowired FinalTripEvidence evidence;
    @Autowired RetainedTripHistory retainedHistory;
    @Autowired HistoryQueries publicHistory;
    @Autowired OwnedVehicleHistory ownedHistory;
    UUID vehicle,device;
    String publicVehicle,publicDevice;
    static final Instant BASE=Instant.parse("2026-10-20T12:00:00Z"),AS_OF=Instant.parse("2026-12-01T12:00:00Z");
    @BeforeEach void setup() {
        vehicle=UUID.randomUUID(); device=UUID.randomUUID(); publicVehicle="aggregate-"+vehicle; publicDevice="aggregate-"+device;
        jdbc.update("insert into vehicle(id,public_id) values (?,?)",vehicle,publicVehicle);
        jdbc.update("insert into device(id,vehicle_id,public_id) values (?,?,?)",device,vehicle,publicDevice);
        jdbc.update("insert into trip_policy(vehicle_id,primary_device_id) values (?,?)",vehicle,device);
    }
    TripAggregateBuilder builder() { return new TripAggregateBuilder(jdbc,transactions); }
    TripAggregateVerifier verifier() { return new TripAggregateVerifier(jdbc,mapper,transactions); }
    UUID archivedTrip() {
        UUID trip=drive(true,"SIMULATED"); evidence.copyPage(vehicle,trip,500); finish(vehicle,trip,500);
        assertThat(verify(vehicle,trip,500)).isEqualTo("VERIFIED"); return trip;
    }
    HistoryRequest request(UUID trip,int from,int to,int limit,String resolution) {
        return new HistoryRequest(BASE.plusSeconds(from),BASE.plusSeconds(to),limit,trip,null,resolution);
    }
    UUID extra(UUID targetDevice,String targetPublicDevice,Instant at,int sequence,String source) {
        var input=((ObjectNode)mapper.readTree(Path.of("../contracts/examples/telemetry-sample.json").toFile()))
                .put("vehicleId",publicVehicle).put("deviceId",targetPublicDevice).put("bootId",UUID.randomUUID().toString())
                .put("recordedAt",at.toString()).put("sequenceNumber",sequence).put("source",source);
        var r=validator.validate(input); UUID id=UUID.randomUUID();
        jdbc.update("insert into telemetry_reading(id,vehicle_id,device_id,boot_id,sequence_number,recorded_at,received_at,source,payload) values (?,?,?,?,?,?,?,?,?::jsonb)",
                id,vehicle,targetDevice,r.bootId(),new BigDecimal(r.sequenceNumber()),Timestamp.from(r.recordedAt()),Timestamp.from(AS_OF),source,r.payload().toString());
        return id;
    }
    String verify(UUID owner,UUID trip,int size) {
        for (int i=0;i<100;i++) {
            var page=verifier().verifyPage(owner,trip,size);
            assertThat(page.visited()).isBetween(0,size); assertThat(page.compared()).isBetween(0,size);
            if (List.of("VERIFIED","FAILED").contains(page.state())) return page.state();
        }
        throw new AssertionError("Verification did not finish");
    }
    UUID drive(boolean seal,String source) {
        return drive(seal,source,BASE);
    }
    UUID drive(boolean seal,String source,Instant origin) {
        int[] times={0,2,20,40,59,60,80,100,120,140,160,180,200,220,240,260,280,299,300,302,304};
        UUID boot=UUID.randomUUID(); int sequence=0;
        for (int second : times) {
            var input=((ObjectNode)mapper.readTree(Path.of("../contracts/examples/telemetry-sample.json").toFile()))
                    .put("vehicleId",publicVehicle).put("deviceId",publicDevice).put("bootId",boot.toString())
                    .put("sequenceNumber",new BigInteger("1234567890123456789012345678901234567890").add(BigInteger.valueOf(sequence++)))
                    .put("recordedAt",origin.plusSeconds(second).toString()).put("source",source);
            for (String field : TripRules.METRICS) input.putNull(field);
            input.put("rpm",second>=302?0:1000);
            if (second==0) input.put("speedKph",1).put("coolantTempC",new BigDecimal("-20.0000000000000000001"));
            if (second==2) input.put("speedKph",3).put("coolantTempC",new BigDecimal("-19.9999999999999999999"));
            if (second==60) input.put("speedKph",10);
            if (second==300) input.put("speedKph",0);
            var r=validator.validate(input);
            jdbc.update("""
                    insert into telemetry_reading(id,vehicle_id,device_id,boot_id,sequence_number,recorded_at,received_at,source,payload)
                    values (?,?,?,?,?,?,?,?,?::jsonb)
                    """,UUID.randomUUID(),vehicle,device,boot,new BigDecimal(r.sequenceNumber()),Timestamp.from(r.recordedAt()),
                    Timestamp.from(origin.plus(Duration.ofDays(1))),source,r.payload().toString());
        }
        assertThat(backfill.enqueuePage(vehicle,100).complete()).isTrue(); assertThat(replay.copyPage(vehicle,100).complete()).isTrue();
        boolean caught=false;
        for (int i=0;i<100;i++) if (projector.processPage(vehicle,AS_OF,100).state().equals("CAUGHT_UP")) { caught=true; break; }
        assertThat(caught).isTrue();
        if (seal) {
            int finalized=0;
            for (int i=0;i<20 && finalized==0;i++) finalized+=finalizer.finalizePage(vehicle,AS_OF,100).finalized();
            assertThat(finalized).isEqualTo(1);
        }
        UUID trip=jdbc.queryForObject("select id from trip_record where vehicle_id=? and status='COMPLETED' and started_at=?",UUID.class,vehicle,Timestamp.from(origin));
        assertThat(jdbc.queryForObject("select count(*) from trip_evidence where trip_id=?",Integer.class,trip)).isEqualTo(20);
        return trip;
    }
    void finish(UUID owner,UUID trip,int size) {
        boolean done=false; int visited=0;
        for (int i=0;i<100;i++) {
            var page=builder().buildPage(owner,trip,size); assertThat(page.visited()).isBetween(0,size);
            visited+=page.visited(); if (page.complete()) { done=true; break; }
        }
        assertThat(done).isTrue(); assertThat(visited).isEqualTo(20);
    }
    BigDecimal metric(UUID trip,int resolution,int second,String field,String stat) {
        return jdbc.queryForObject("select (metrics->?->>?)::numeric from trip_aggregate_bucket where trip_id=? and resolution_seconds=? and bucket_start=?",
                BigDecimal.class,field,stat,trip,resolution,Timestamp.from(BASE.plusSeconds(second)));
    }
    String sourceState() {
        var parts=new ArrayList<String>();
        for (String table : List.of("telemetry_reading","telemetry_replay_identity","trip_record","trip_work_item","trip_projection_progress","trip_vehicle_activity","vehicle_read_generation"))
            parts.add(jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text from "+table+" t where vehicle_id=?",String.class,vehicle));
        parts.add(jdbc.queryForObject("select jsonb_agg(to_jsonb(t) order by t.reading_id)::text from trip_retained_evidence t join trip_record r on r.id=t.trip_id where r.vehicle_id=?",String.class,vehicle));
        return parts.toString();
    }
    @Test void persistsExactNullAwareBucketsAndWeightedStatisticsWithoutChangingSources() {
        UUID trip=drive(true,"SIMULATED"); assertThat(evidence.copyPage(vehicle,trip,500).complete()).isTrue(); String before=sourceState();
        var page=builder().buildPage(vehicle,trip,500);
        assertThat(page.complete()).isTrue(); assertThat(page.visited()).isEqualTo(20); assertThat(page.bucketWrites()).isEqualTo(8);
        assertThat(jdbc.queryForList("select sample_count from trip_aggregate_bucket where trip_id=? and resolution_seconds=60 order by bucket_start",Long.class,trip))
                .containsExactly(5L,3L,3L,3L,4L,2L);
        assertThat(jdbc.queryForList("select sample_count from trip_aggregate_bucket where trip_id=? and resolution_seconds=300 order by bucket_start",Long.class,trip))
                .containsExactly(18L,2L);
        assertThat(metric(trip,60,0,"speedKph","sum")).isEqualByComparingTo("4");
        assertThat(metric(trip,60,0,"speedKph","count")).isEqualByComparingTo("2");
        assertThat(metric(trip,60,0,"coolantTempC","min")).isEqualByComparingTo("-20.0000000000000000001");
        assertThat(metric(trip,60,0,"coolantTempC","max")).isEqualByComparingTo("-19.9999999999999999999");
        assertThat(metric(trip,300,0,"coolantTempC","sum")).isEqualByComparingTo("-40");
        for (String stat : List.of("sum","min","max")) {
            assertThat(metric(trip,60,120,"speedKph",stat)).isNull();
            assertThat(metric(trip,300,0,"controlModuleVoltageV",stat)).isNull();
        }
        assertThat(metric(trip,60,120,"speedKph","count")).isEqualByComparingTo("0");
        assertThat(metric(trip,60,300,"speedKph","sum")).isEqualByComparingTo("0");
        BigDecimal weighted=jdbc.queryForObject("select sum((metrics->'speedKph'->>'sum')::numeric)/sum((metrics->'speedKph'->>'count')::numeric) from trip_aggregate_bucket where trip_id=? and resolution_seconds=60",BigDecimal.class,trip);
        assertThat(weighted).isEqualByComparingTo("3.5"); // Not average(2,10,0)=4.
        for (String field : TripRules.METRICS) for (String stat : List.of("count","sum")) {
            BigDecimal one=jdbc.queryForObject("select sum((metrics->?->>?)::numeric) from trip_aggregate_bucket where trip_id=? and resolution_seconds=60",BigDecimal.class,field,stat,trip);
            BigDecimal five=jdbc.queryForObject("select sum((metrics->?->>?)::numeric) from trip_aggregate_bucket where trip_id=? and resolution_seconds=300",BigDecimal.class,field,stat,trip);
            if (one==null) assertThat(five).isNull(); else assertThat(five).isEqualByComparingTo(one);
        }
        assertThat(sourceState()).isEqualTo(before);
        assertThat(builder().buildPage(vehicle,trip,1)).isEqualTo(new TripAggregateBuilder.Page(0,0,true,false));
    }
    @Test void pagePartitionAndServiceRestartProduceIdenticalContributions() {
        UUID first=drive(true,"SIMULATED"),owner=vehicle; evidence.copyPage(vehicle,first,500); finish(vehicle,first,500);
        setup(); UUID second=drive(true,"SIMULATED"); evidence.copyPage(vehicle,second,500); finish(vehicle,second,1);
        String columns="resolution_seconds,bucket_start,sample_count,first_observed_at,last_observed_at,source,metrics";
        assertThat(jdbc.queryForList("select "+columns+" from trip_aggregate_bucket where trip_id=? except select "+columns+" from trip_aggregate_bucket where trip_id=?",first,second)).isEmpty();
        assertThat(jdbc.queryForList("select "+columns+" from trip_aggregate_bucket where trip_id=? except select "+columns+" from trip_aggregate_bucket where trip_id=?",second,first)).isEmpty();
        assertThatThrownBy(() -> builder().buildPage(owner,second,1)).hasMessageContaining("Unknown");
    }
    @Test void refusesNonfinalMissingOrPartialEvidenceAndInvalidBoundsWithoutProgress() {
        UUID trip=drive(false,"SIMULATED");
        assertThatThrownBy(() -> builder().buildPage(vehicle,trip,10)).hasMessageContaining("FINAL COMPLETED");
        for (int n : new int[]{0,501}) assertThatThrownBy(() -> builder().buildPage(vehicle,trip,n)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().buildPage(null,trip,1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().buildPage(vehicle,null,1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().buildPage(vehicle,UUID.randomUUID(),1)).hasMessageContaining("Unknown");
        int sealed=0; for (int i=0;i<20 && sealed==0;i++) sealed+=finalizer.finalizePage(vehicle,AS_OF,100).finalized();
        assertThat(sealed).isEqualTo(1);
        assertThatThrownBy(() -> builder().buildPage(vehicle,trip,10)).hasMessageContaining("complete retained evidence");
        evidence.copyPage(vehicle,trip,1);
        assertThatThrownBy(() -> builder().buildPage(vehicle,trip,10)).hasMessageContaining("complete retained evidence");
        assertThat(jdbc.queryForObject("select count(*) from trip_aggregate_progress where trip_id=?",Integer.class,trip)).isZero();
    }
    @Test void secondResolutionFailureRollsBackFirstResolutionAndCursorThenRecovers() {
        UUID trip=drive(true,"SIMULATED"); evidence.copyPage(vehicle,trip,500);
        var first=builder().buildPage(vehicle,trip,1); assertThat(first.complete()).isFalse();
        var before=jdbc.queryForList("select * from trip_aggregate_bucket where trip_id=? order by resolution_seconds,bucket_start",trip);
        var progress=jdbc.queryForMap("select * from trip_aggregate_progress where trip_id=?",trip);
        jdbc.execute("create function test_aggregate_failure() returns trigger language plpgsql as $$ begin if NEW.resolution_seconds=300 then raise exception 'aggregate fixture outage'; end if; return NEW; end $$");
        jdbc.execute("create trigger test_aggregate_failure after insert or update on trip_aggregate_bucket for each row execute function test_aggregate_failure()");
        try {
            assertThatThrownBy(() -> builder().buildPage(vehicle,trip,500)).hasMessageContaining("aggregate fixture outage");
            assertThat(jdbc.queryForList("select * from trip_aggregate_bucket where trip_id=? order by resolution_seconds,bucket_start",trip)).isEqualTo(before);
            assertThat(jdbc.queryForMap("select * from trip_aggregate_progress where trip_id=?",trip)).isEqualTo(progress);
        } finally { jdbc.execute("drop trigger test_aggregate_failure on trip_aggregate_bucket"); jdbc.execute("drop function test_aggregate_failure()"); }
        assertThat(builder().buildPage(vehicle,trip,500).complete()).isTrue();
        assertThat(jdbc.queryForObject("select sum(sample_count) from trip_aggregate_bucket where trip_id=? and resolution_seconds=60",Long.class,trip)).isEqualTo(20);
    }
    @Test void completeBucketsAndProgressCannotBeRewrittenOrDeleted() {
        UUID trip=drive(true,"SIMULATED"); evidence.copyPage(vehicle,trip,500); finish(vehicle,trip,500);
        assertThatThrownBy(() -> jdbc.update("update trip_aggregate_bucket set sample_count=sample_count where trip_id=?",trip)).hasMessageContaining("already complete");
        assertThatThrownBy(() -> jdbc.update("delete from trip_aggregate_bucket where trip_id=?",trip)).hasMessageContaining("cannot be deleted");
        assertThatThrownBy(() -> jdbc.update("update trip_aggregate_progress set complete=false where trip_id=?",trip)).hasMessageContaining("cannot be deleted or reset");
        assertThatThrownBy(() -> jdbc.update("delete from trip_aggregate_progress where trip_id=?",trip)).hasMessageContaining("cannot be deleted or reset");
    }
    @Test void incompleteBucketGuardsRejectInvalidStatisticsAndOwnership() {
        UUID trip=drive(true,"SIMULATED"); evidence.copyPage(vehicle,trip,500); builder().buildPage(vehicle,trip,1);
        var before=jdbc.queryForList("select * from trip_aggregate_bucket where trip_id=? order by resolution_seconds,bucket_start",trip);
        assertThatThrownBy(() -> jdbc.update("update trip_aggregate_bucket set metrics=metrics-'rpm' where trip_id=?",trip)).hasMessageContaining("all nine metric objects");
        assertThatThrownBy(() -> jdbc.update("update trip_aggregate_bucket set metrics=jsonb_set(metrics,'{controlModuleVoltageV,sum}','0'::jsonb) where trip_id=?",trip)).hasMessageContaining("empty aggregate metric");
        assertThatThrownBy(() -> jdbc.update("update trip_aggregate_bucket set metrics=jsonb_set(metrics,'{rpm,count}','99999'::jsonb) where trip_id=?",trip)).hasMessageContaining("count out of range");
        assertThatThrownBy(() -> jdbc.update("update trip_aggregate_bucket set source='OBD' where trip_id=?",trip)).hasMessageContaining("ownership is immutable");
        assertThatThrownBy(() -> jdbc.update("update trip_aggregate_bucket set first_observed_at=bucket_start-interval '1 microsecond' where trip_id=?",trip))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForList("select * from trip_aggregate_bucket where trip_id=? order by resolution_seconds,bucket_start",trip)).isEqualTo(before);
        assertThat(builder().buildPage(vehicle,trip,500).complete()).isTrue();
    }
    @Test void busyBuilderCreatesNoProgressAndRecoversAfterLockRelease() throws Exception {
        UUID trip=drive(true,"SIMULATED"); evidence.copyPage(vehicle,trip,500);
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        try (var executor=Executors.newSingleThreadExecutor()) {
            var holder=executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                jdbc.execute("select pg_advisory_xact_lock(hashtextextended('"+trip+"',917))"); locked.countDown();
                try { if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            try {
                assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
                assertThat(builder().buildPage(vehicle,trip,1)).isEqualTo(new TripAggregateBuilder.Page(0,0,false,true));
                assertThat(jdbc.queryForObject("select count(*) from trip_aggregate_progress where trip_id=?",Integer.class,trip)).isZero();
            } finally { release.countDown(); }
            holder.get(10,TimeUnit.SECONDS);
        }
        finish(vehicle,trip,500);
    }
    @Test void contributionsPreserveVehicleDeviceAndSourceOwnership() {
        UUID first=drive(true,"SIMULATED"),firstVehicle=vehicle,firstDevice=device;
        evidence.copyPage(vehicle,first,500); finish(vehicle,first,500);
        setup(); UUID second=drive(true,"OBD"); evidence.copyPage(vehicle,second,500); finish(vehicle,second,500);
        assertThat(jdbc.queryForObject("select count(*) from trip_aggregate_bucket where trip_id=? and vehicle_id=? and device_id=? and source='SIMULATED'",Integer.class,first,firstVehicle,firstDevice)).isEqualTo(8);
        assertThat(jdbc.queryForObject("select count(*) from trip_aggregate_bucket where trip_id=? and vehicle_id=? and device_id=? and source='OBD'",Integer.class,second,vehicle,device)).isEqualTo(8);
    }
    @Test void independentVerificationResumesOneMemberAndBucketAtATimeWithoutChangingInputs() {
        UUID trip=drive(true,"SIMULATED"); evidence.copyPage(vehicle,trip,500); finish(vehicle,trip,1);
        String before=sourceState();
        var buckets=jdbc.queryForList("select * from trip_aggregate_bucket where trip_id=? order by resolution_seconds,bucket_start",trip);
        assertThat(verify(vehicle,trip,1)).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("select visited_rows from trip_aggregate_verification where trip_id=?",Long.class,trip)).isEqualTo(20);
        assertThat(jdbc.queryForObject("select compared_buckets from trip_aggregate_verification where trip_id=?",Long.class,trip)).isEqualTo(8);
        assertThat(jdbc.queryForList("select sample_count from trip_aggregate_reference where trip_id=? and resolution_seconds=60 order by bucket_start",Long.class,trip))
                .containsExactly(5L,3L,3L,3L,4L,2L);
        assertThat(jdbc.queryForObject("select (metrics->'coolantTempC'->>'min')::numeric from trip_aggregate_reference where trip_id=? and resolution_seconds=300 and bucket_start=?",
                BigDecimal.class,trip,Timestamp.from(BASE))).isEqualByComparingTo("-20.0000000000000000001");
        assertThat(verifier().verifyPage(vehicle,trip,500)).isEqualTo(new TripAggregateVerifier.Page(0,0,"VERIFIED",false));
        assertThat(sourceState()).isEqualTo(before);
        assertThat(jdbc.queryForList("select * from trip_aggregate_bucket where trip_id=? order by resolution_seconds,bucket_start",trip)).isEqualTo(buckets);
    }
    @Test void independentVerificationIsPagePartitionAndSourceIndependent() {
        UUID first=drive(true,"SIMULATED"); evidence.copyPage(vehicle,first,500); finish(vehicle,first,500);
        assertThat(verify(vehicle,first,500)).isEqualTo("VERIFIED");
        setup(); UUID second=drive(true,"OBD"); evidence.copyPage(vehicle,second,500); finish(vehicle,second,1);
        assertThat(verify(vehicle,second,3)).isEqualTo("VERIFIED");
        String columns="resolution_seconds,bucket_start,sample_count,first_observed_at,last_observed_at,metrics";
        assertThat(jdbc.queryForList("select "+columns+" from trip_aggregate_reference where trip_id=? except select "+columns+" from trip_aggregate_reference where trip_id=?",first,second)).isEmpty();
        assertThat(jdbc.queryForList("select "+columns+" from trip_aggregate_reference where trip_id=? except select "+columns+" from trip_aggregate_reference where trip_id=?",second,first)).isEmpty();
    }
    @Test void independentVerificationDetectsWellShapedWrongStatisticsAndKeepsFailureDurable() {
        UUID trip=drive(true,"SIMULATED"); evidence.copyPage(vehicle,trip,500); builder().buildPage(vehicle,trip,1);
        // Legal partial-build SQL: shape checks cannot know that every RPM was shifted by one.
        jdbc.update("""
                update trip_aggregate_bucket set metrics=jsonb_set(metrics,'{rpm}',jsonb_build_object(
                'count',(metrics->'rpm'->>'count')::bigint,
                'sum',(metrics->'rpm'->>'sum')::numeric+(metrics->'rpm'->>'count')::numeric,
                'min',(metrics->'rpm'->>'min')::numeric+1,'max',(metrics->'rpm'->>'max')::numeric+1)) where trip_id=?
                """,trip);
        assertThat(builder().buildPage(vehicle,trip,500).complete()).isTrue();
        assertThat(verify(vehicle,trip,3)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select failure_code from trip_aggregate_verification where trip_id=?",String.class,trip)).isEqualTo("CONTENT_MISMATCH");
        assertThat(verifier().verifyPage(vehicle,trip,1)).isEqualTo(new TripAggregateVerifier.Page(0,0,"FAILED",false));
        assertThatThrownBy(() -> retainedHistory.read(vehicle,request(trip,0,360,20,"1m"),null)).hasMessageContaining("independently VERIFIED");
        assertThatThrownBy(() -> jdbc.update("update trip_aggregate_verification set state='ACCUMULATE',failure_code=null where trip_id=?",trip)).hasMessageContaining("cannot be reassigned or reset");
    }
    @Test void independentVerificationFindsMissingBucketsEvenWhenBuildCountersClaimCompletion() {
        UUID trip=drive(true,"SIMULATED"); evidence.copyPage(vehicle,trip,500);
        // Disposable privileged fixture representing an incorrectly sealed builder, not a production repair path.
        jdbc.update("insert into trip_aggregate_progress(trip_id,visited_rows,complete) values (?,20,true)",trip);
        assertThat(verify(vehicle,trip,500)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select failure_code from trip_aggregate_verification where trip_id=?",String.class,trip)).isEqualTo("MISSING_BUCKET");
    }
    @Test void independentVerificationFindsExtraBucketsOutsideExpectedKeySet() {
        UUID trip=drive(true,"SIMULATED"); evidence.copyPage(vehicle,trip,500); builder().buildPage(vehicle,trip,1);
        jdbc.update("""
                insert into trip_aggregate_bucket(trip_id,resolution_seconds,bucket_start,vehicle_id,device_id,source,sample_count,first_observed_at,last_observed_at,metrics)
                select trip_id,resolution_seconds,bucket_start+interval '1 day',vehicle_id,device_id,source,sample_count,
                first_observed_at+interval '1 day',last_observed_at+interval '1 day',metrics from trip_aggregate_bucket
                where trip_id=? and resolution_seconds=60
                """,trip);
        builder().buildPage(vehicle,trip,500);
        assertThat(verify(vehicle,trip,1)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select failure_code from trip_aggregate_verification where trip_id=?",String.class,trip)).isEqualTo("UNEXPECTED_BUCKET");
    }
    @Test void verifierRefusesUnreadyWrongVehicleAndInvalidRequestsWithoutProgress() {
        UUID trip=drive(false,"SIMULATED");
        assertThatThrownBy(() -> verifier().verifyPage(vehicle,trip,1)).hasMessageContaining("FINAL COMPLETED");
        int sealed=0; for (int i=0;i<20 && sealed==0;i++) sealed+=finalizer.finalizePage(vehicle,AS_OF,100).finalized();
        assertThat(sealed).isEqualTo(1);
        assertThatThrownBy(() -> verifier().verifyPage(vehicle,trip,1)).hasMessageContaining("complete retained evidence");
        evidence.copyPage(vehicle,trip,1);
        assertThatThrownBy(() -> verifier().verifyPage(vehicle,trip,1)).hasMessageContaining("complete retained evidence");
        evidence.copyPage(vehicle,trip,500);
        assertThatThrownBy(() -> verifier().verifyPage(vehicle,trip,1)).hasMessageContaining("complete aggregate build");
        builder().buildPage(vehicle,trip,1);
        assertThatThrownBy(() -> verifier().verifyPage(vehicle,trip,1)).hasMessageContaining("complete aggregate build");
        for (int size : new int[]{0,501}) assertThatThrownBy(() -> verifier().verifyPage(vehicle,trip,size)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> verifier().verifyPage(null,trip,1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> verifier().verifyPage(vehicle,null,1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> verifier().verifyPage(UUID.randomUUID(),trip,1)).hasMessageContaining("Unknown");
        assertThatThrownBy(() -> verifier().verifyPage(vehicle,UUID.randomUUID(),1)).hasMessageContaining("Unknown");
        assertThat(jdbc.queryForObject("select count(*) from trip_aggregate_verification where trip_id=?",Integer.class,trip)).isZero();
    }
    @Test void verifierReferenceOutageRollsBackPageAndCursorThenResumes() {
        UUID trip=drive(true,"SIMULATED"); evidence.copyPage(vehicle,trip,500); finish(vehicle,trip,500);
        verifier().verifyPage(vehicle,trip,1);
        var before=jdbc.queryForList("select * from trip_aggregate_reference where trip_id=? order by resolution_seconds,bucket_start",trip);
        var progress=jdbc.queryForMap("select * from trip_aggregate_verification where trip_id=?",trip);
        jdbc.execute("create function test_reference_failure() returns trigger language plpgsql as $$ begin if NEW.resolution_seconds=300 then raise exception 'reference fixture outage'; end if; return NEW; end $$");
        jdbc.execute("create trigger test_reference_failure after insert or update on trip_aggregate_reference for each row execute function test_reference_failure()");
        try {
            assertThatThrownBy(() -> verifier().verifyPage(vehicle,trip,500)).hasMessageContaining("reference fixture outage");
            assertThat(jdbc.queryForList("select * from trip_aggregate_reference where trip_id=? order by resolution_seconds,bucket_start",trip)).isEqualTo(before);
            assertThat(jdbc.queryForMap("select * from trip_aggregate_verification where trip_id=?",trip)).isEqualTo(progress);
        } finally { jdbc.execute("drop trigger test_reference_failure on trip_aggregate_reference"); jdbc.execute("drop function test_reference_failure()"); }
        assertThat(verify(vehicle,trip,1)).isEqualTo("VERIFIED");
    }
    @Test void verifierSealsReferenceBeforeComparisonAndTerminalProgressIsImmutable() {
        UUID trip=drive(true,"SIMULATED"); evidence.copyPage(vehicle,trip,500); finish(vehicle,trip,500);
        assertThat(verifier().verifyPage(vehicle,trip,500).state()).isEqualTo("COMPARE");
        assertThatThrownBy(() -> jdbc.update("update trip_aggregate_reference set sample_count=sample_count where trip_id=?",trip)).hasMessageContaining("sealed or absent");
        assertThatThrownBy(() -> jdbc.update("delete from trip_aggregate_reference where trip_id=?",trip)).hasMessageContaining("cannot be deleted");
        assertThatThrownBy(() -> jdbc.update("update trip_aggregate_verification set state='ACCUMULATE' where trip_id=?",trip)).hasMessageContaining("cannot be reassigned or reset");
        assertThat(verify(vehicle,trip,500)).isEqualTo("VERIFIED");
        assertThatThrownBy(() -> jdbc.update("update trip_aggregate_verification set compared_buckets=0 where trip_id=?",trip)).hasMessageContaining("cannot be reassigned or reset");
        assertThatThrownBy(() -> jdbc.update("delete from trip_aggregate_verification where trip_id=?",trip)).hasMessageContaining("cannot be deleted");
    }
    @Test void busyVerifierCreatesNoProgressAndRecoversAfterLockRelease() throws Exception {
        UUID trip=drive(true,"SIMULATED"); evidence.copyPage(vehicle,trip,500); finish(vehicle,trip,500);
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        try (var executor=Executors.newSingleThreadExecutor()) {
            var holder=executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                jdbc.execute("select pg_advisory_xact_lock(hashtextextended('"+trip+"',918))"); locked.countDown();
                try { if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            try {
                assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
                assertThat(verifier().verifyPage(vehicle,trip,1)).isEqualTo(new TripAggregateVerifier.Page(0,0,"BUSY",true));
                assertThat(jdbc.queryForObject("select count(*) from trip_aggregate_verification where trip_id=?",Integer.class,trip)).isZero();
            } finally { release.countDown(); }
            holder.get(10,TimeUnit.SECONDS);
        }
        assertThat(verify(vehicle,trip,500)).isEqualTo("VERIFIED");
    }
    @Test void independentVerificationPreservesMicrosecondsAndFloorsPreEpochUtcBuckets() {
        Instant origin=Instant.parse("1969-12-31T23:59:59.999999Z");
        UUID trip=drive(true,"SIMULATED",origin); evidence.copyPage(vehicle,trip,500); finish(vehicle,trip,500);
        assertThat(verify(vehicle,trip,1)).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("select min(bucket_start) from trip_aggregate_reference where trip_id=? and resolution_seconds=60",Timestamp.class,trip).toInstant())
                .isEqualTo(Instant.parse("1969-12-31T23:59:00Z"));
        assertThat(jdbc.queryForObject("select min(bucket_start) from trip_aggregate_reference where trip_id=? and resolution_seconds=300",Timestamp.class,trip).toInstant())
                .isEqualTo(Instant.parse("1969-12-31T23:55:00Z"));
        assertThat(jdbc.queryForObject("select min(first_observed_at) from trip_aggregate_reference where trip_id=?",Timestamp.class,trip).toInstant()).isEqualTo(origin);
        assertThat(jdbc.queryForObject("select max(last_observed_at) from trip_aggregate_reference where trip_id=?",Timestamp.class,trip).toInstant()).isEqualTo(origin.plusSeconds(302));
    }
    @Test void verifierComparisonCompletionFailureRollsBackCursorAndRecovers() {
        UUID trip=drive(true,"SIMULATED"); evidence.copyPage(vehicle,trip,500); finish(vehicle,trip,500);
        verifier().verifyPage(vehicle,trip,500);
        assertThat(verifier().verifyPage(vehicle,trip,1).compared()).isEqualTo(1);
        var progress=jdbc.queryForMap("select * from trip_aggregate_verification where trip_id=?",trip);
        jdbc.execute("create function test_verification_failure() returns trigger language plpgsql as $$ begin if NEW.state='VERIFIED' then raise exception 'verification fixture outage'; end if; return NEW; end $$");
        jdbc.execute("create trigger test_verification_failure after update on trip_aggregate_verification for each row execute function test_verification_failure()");
        try {
            assertThatThrownBy(() -> verifier().verifyPage(vehicle,trip,500)).hasMessageContaining("verification fixture outage");
            assertThat(jdbc.queryForMap("select * from trip_aggregate_verification where trip_id=?",trip)).isEqualTo(progress);
        } finally { jdbc.execute("drop trigger test_verification_failure on trip_aggregate_verification"); jdbc.execute("drop function test_verification_failure()"); }
        assertThat(verify(vehicle,trip,1)).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("select compared_buckets from trip_aggregate_verification where trip_id=?",Long.class,trip)).isEqualTo(8);
    }
    @Test void retainedFullBucketsMatchPublicRawDerivedHistoryWithoutRecountingLedgerOrSources() {
        UUID trip=archivedTrip(); String before=sourceState();
        for (String resolution : List.of("1m","5m")) {
            var query=request(trip,0,600,20,resolution);
            var page=retainedHistory.read(vehicle,query,null);
            assertThat(page.points()).isEqualTo(publicHistory.history(publicVehicle,query).body().points());
            assertThat(page.points().stream().mapToLong(HistoryQueries.AggregatePoint::sampleCount).sum()).isEqualTo(20);
            assertThat(page.storedBuckets()).isEqualTo(resolution.equals("1m")?6:2);
            assertThat(page.clippedBuckets()).isZero(); assertThat(page.clippedCandidates()).isZero(); assertThat(page.nextCursor()).isNull();
        }
        assertThat(sourceState()).isEqualTo(before);
    }
    @Test void retainedEdgesRecomputeExactSubsetInsteadOfReturningWholeBucketStatistics() {
        UUID trip=archivedTrip(); var query=request(trip,1,61,20,"1m");
        var page=retainedHistory.read(vehicle,query,null);
        assertThat(page.points()).isEqualTo(publicHistory.history(publicVehicle,query).body().points());
        assertThat(page.points()).hasSize(2); assertThat(page.storedBuckets()).isZero(); assertThat(page.clippedBuckets()).isEqualTo(2);
        assertThat(page.clippedCandidates()).isEqualTo(5);
        var first=page.points().getFirst();
        assertThat(first.partial()).isTrue(); assertThat(first.sampleCount()).isEqualTo(4);
        assertThat(first.metrics().get("speedKph").sum()).isEqualByComparingTo("3");
        assertThat(first.metrics().get("speedKph").count()).isEqualTo(1);
        assertThat(first.metrics().get("coolantTempC").min()).isEqualByComparingTo("-19.9999999999999999999");
        assertThat(first.firstObservedAt()).isEqualTo(BASE.plusSeconds(2)); assertThat(first.lastObservedAt()).isEqualTo(BASE.plusSeconds(59));
        assertThat(page.points().getLast().sampleCount()).isEqualTo(1);
        var five=retainedHistory.read(vehicle,request(trip,1,301,20,"5m"),null);
        assertThat(five.points()).isEqualTo(publicHistory.history(publicVehicle,request(trip,1,301,20,"5m")).body().points());
        assertThat(five.points().getLast().metrics().get("speedKph").sum()).isEqualByComparingTo("0");
    }
    @Test void retainedHalfOpenBoundsPreserveInclusiveFinalEvidenceOnlyWhenRequested() {
        UUID trip=archivedTrip();
        for (Instant end : List.of(BASE.plusSeconds(302),BASE.plusSeconds(302).plusNanos(1000))) {
            var query=new HistoryRequest(BASE,end,20,trip,null,"1m");
            var page=retainedHistory.read(vehicle,query,null);
            assertThat(page.points()).isEqualTo(publicHistory.history(publicVehicle,query).body().points());
            assertThat(page.points().stream().mapToLong(HistoryQueries.AggregatePoint::sampleCount).sum()).isEqualTo(end.equals(BASE.plusSeconds(302))?19:20);
            assertThat(page.points().getLast().lastObservedAt()).isEqualTo(end.equals(BASE.plusSeconds(302))?BASE.plusSeconds(300):BASE.plusSeconds(302));
        }
        var one=retainedHistory.read(vehicle,request(trip,2,3,20,"1m"),null).points().getFirst();
        assertThat(one.sampleCount()).isEqualTo(1); assertThat(one.metrics().get("speedKph").sum()).isEqualByComparingTo("3");
        assertThat(one.metrics().get("mafGps").count()).isZero(); assertThat(one.metrics().get("mafGps").sum()).isNull();
    }
    @Test void retainedPagingHasNoSkipsAndContinuationsBindAllFilters() {
        UUID trip=archivedTrip(); var query=request(trip,1,360,1,"1m");
        var first=retainedHistory.read(vehicle,query,null); var initialCursor=first.nextCursor(); assertThat(initialCursor).isNotNull();
        assertThatThrownBy(() -> retainedHistory.read(UUID.randomUUID(),query,initialCursor)).hasMessageContaining("does not match");
        for (HistoryRequest changed : List.of(request(UUID.randomUUID(),1,360,1,"1m"),request(trip,2,360,1,"1m"),request(trip,1,361,1,"1m"),
                request(trip,1,360,2,"1m"),request(trip,1,360,1,"5m")))
            assertThatThrownBy(() -> retainedHistory.read(vehicle,changed,initialCursor)).hasMessageContaining("does not match");
        var points=new ArrayList<HistoryQueries.AggregatePoint>(first.points());
        var cursor=initialCursor;
        for (int i=0;cursor!=null && i<10;i++) {
            var page=retainedHistory.read(vehicle,query,cursor); points.addAll(page.points()); cursor=page.nextCursor();
        }
        assertThat(cursor).isNull();
        assertThat(points).isEqualTo(retainedHistory.read(vehicle,request(trip,1,360,20,"1m"),null).points());
        assertThat(points.stream().map(HistoryQueries.AggregatePoint::bucketStart).distinct().count()).isEqualTo(6);
    }
    @Test void retainedSparseClippedPageMayBeEmptyButKeepsLookaheadAndDoesNotInventZeros() {
        UUID trip=archivedTrip();
        assertThat(retainedHistory.read(vehicle,request(trip,3,19,20,"1m"),null).points()).isEmpty();
        var query=new HistoryRequest(BASE.plusSeconds(59).plusNanos(1000),BASE.plusSeconds(360),1,trip,null,"1m");
        var page=retainedHistory.read(vehicle,query,null);
        assertThat(page.points()).isEmpty(); assertThat(page.nextCursor()).isNotNull();
        assertThat(retainedHistory.read(vehicle,query,page.nextCursor()).points().getFirst().bucketStart()).isEqualTo(BASE.plusSeconds(60));
        assertThat(retainedHistory.read(vehicle,request(trip,600,900,1,"1m"),null).points()).isEmpty();
    }
    @Test void retainedReadersRefuseUnverifiedNonfinalWrongVehicleAndUnsupportedRequests() {
        UUID trip=drive(false,"SIMULATED"); var query=request(trip,0,360,20,"1m");
        assertThatThrownBy(() -> retainedHistory.read(vehicle,query,null)).hasMessageContaining("FINAL COMPLETED");
        int sealed=0; for (int i=0;i<20 && sealed==0;i++) sealed+=finalizer.finalizePage(vehicle,AS_OF,100).finalized();
        assertThat(sealed).isEqualTo(1);
        assertThatThrownBy(() -> retainedHistory.read(vehicle,query,null)).hasMessageContaining("independently VERIFIED");
        evidence.copyPage(vehicle,trip,500); finish(vehicle,trip,500);
        assertThatThrownBy(() -> retainedHistory.read(vehicle,query,null)).hasMessageContaining("independently VERIFIED");
        verifier().verifyPage(vehicle,trip,500); // COMPARE is not yet VERIFIED.
        assertThatThrownBy(() -> retainedHistory.read(vehicle,query,null)).hasMessageContaining("independently VERIFIED");
        assertThatThrownBy(() -> retainedHistory.read(UUID.randomUUID(),query,null)).hasMessageContaining("Unknown");
        assertThatThrownBy(() -> retainedHistory.read(vehicle,request(UUID.randomUUID(),0,360,20,"1m"),null)).hasMessageContaining("Unknown");
        for (HistoryRequest invalid : List.of(request(trip,0,360,20,"raw"),new HistoryRequest(BASE,BASE.plusSeconds(60),20,null,null,"1m"),
                new HistoryRequest(BASE,BASE.plusSeconds(60),20,trip,"public-cursor","1m")))
            assertThatThrownBy(() -> retainedHistory.read(vehicle,invalid,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> retainedHistory.read(null,query,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> retainedHistory.read(vehicle,null,null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void newUnlinkedLateReadingCannotChangeRetainedOwnershipOrInvalidateItsInternalCursor() {
        UUID trip=archivedTrip(); var query=request(trip,1,360,1,"1m");
        var first=retainedHistory.read(vehicle,query,null);
        var input=((ObjectNode)mapper.readTree(Path.of("../contracts/examples/telemetry-sample.json").toFile()))
                .put("vehicleId",publicVehicle).put("deviceId",publicDevice).put("bootId",UUID.randomUUID().toString())
                .put("recordedAt",BASE.plusSeconds(3).toString()).put("sequenceNumber",999).put("source","SIMULATED");
        var r=validator.validate(input);
        jdbc.update("insert into telemetry_reading(id,vehicle_id,device_id,boot_id,sequence_number,recorded_at,received_at,source,payload) values (?,?,?,?,?,?,?,?,?::jsonb)",
                UUID.randomUUID(),vehicle,device,r.bootId(),new BigDecimal(r.sequenceNumber()),Timestamp.from(r.recordedAt()),Timestamp.from(AS_OF),"SIMULATED",r.payload().toString());
        assertThat(retainedHistory.read(vehicle,query,null).points()).isEqualTo(first.points());
        assertThat(retainedHistory.read(vehicle,query,first.nextCursor()).points()).hasSize(1);
        var clipped=retainedHistory.read(vehicle,request(trip,1,61,20,"1m"),null);
        assertThat(clipped.clippedCandidates()).isEqualTo(6); // Includes late metadata in the work budget, never in trip statistics.
        assertThat(clipped.points().stream().mapToLong(HistoryQueries.AggregatePoint::sampleCount).sum()).isEqualTo(5);
    }
    @Test void clippedDensityFailsClosedBeforeFilteringUnownedCandidatesWhileFullBucketsRemainCheap() {
        UUID trip=archivedTrip(); UUID boot=UUID.randomUUID();
        // Disposable retained-ledger fixture: valid original identities may outlive their raw rows.
        // None of these extra identities belongs to the sealed trip's retained membership.
        jdbc.update("""
                insert into telemetry_replay_identity(device_id,boot_id,sequence_number,reading_id,vehicle_id,recorded_at,received_at,source,payload)
                select device_id,?,g,gen_random_uuid(),vehicle_id,?::timestamptz+g*interval '1 microsecond',received_at,source,
                    payload||jsonb_build_object('bootId',?::text,'sequenceNumber',g,'recordedAt',?::timestamptz+g*interval '1 microsecond')
                from (select * from telemetry_replay_identity where vehicle_id=? limit 1) seed cross join generate_series(1,10001) g
                """,boot,Timestamp.from(BASE.plusSeconds(30)),boot.toString(),Timestamp.from(BASE.plusSeconds(30)),vehicle);
        var error=catchThrowableOfType(() -> retainedHistory.read(vehicle,request(trip,1,61,20,"1m"),null),dev.autopulse.shared.ApiFailure.class);
        assertThat(error.code).isEqualTo("HISTORY_DENSITY_LIMIT");
        assertThat(catchThrowableOfType(() -> ownedHistory.read(publicVehicle,request(null,0,360,20,"1m"),null),dev.autopulse.shared.ApiFailure.class).code)
                .isEqualTo("HISTORY_DENSITY_LIMIT");
        assertThat(retainedHistory.read(vehicle,request(trip,0,360,20,"1m"),null).clippedCandidates()).isZero();
        jdbc.execute("analyze telemetry_replay_identity");
        String plan=jdbc.queryForObject("explain (analyze,buffers,format json) select reading_id from telemetry_replay_identity where vehicle_id=? and device_id=? and recorded_at>=? and recorded_at<? order by recorded_at,reading_id limit 11",
                String.class,vehicle,device,Timestamp.from(BASE.plusSeconds(30).plusNanos(5000000)),Timestamp.from(BASE.plusSeconds(30).plusNanos(9000000)));
        // With both range indexes available PostgreSQL can legitimately choose either
        // for this one-device fixture. Require a real range-index plan, not one name.
        assertThat(plan).containsAnyOf("replay_device_history_range","replay_vehicle_history_range").doesNotContain("Seq Scan","payload");
        String vehiclePlan=jdbc.queryForObject("explain (analyze,buffers,format json) select reading_id from telemetry_replay_identity where vehicle_id=? and recorded_at>=? and recorded_at<? order by recorded_at,reading_id limit 11",
                String.class,vehicle,Timestamp.from(BASE.plusSeconds(30).plusNanos(5000000)),Timestamp.from(BASE.plusSeconds(30).plusNanos(9000000)));
        assertThat(vehiclePlan).contains("replay_vehicle_history_range").doesNotContain("Seq Scan","payload");
        assertThat(mapper.readTree(vehiclePlan).get(0).get("Plan").get("Actual Rows").intValue()).isEqualTo(11);
    }
    @Test void retainedClipsMatchRawDerivedHistoryAcrossPreEpochMicrosecondBoundary() {
        Instant origin=Instant.parse("1969-12-31T23:59:59.999999Z");
        UUID trip=drive(true,"OBD",origin); evidence.copyPage(vehicle,trip,500); finish(vehicle,trip,500); verify(vehicle,trip,500);
        var query=new HistoryRequest(origin,origin.plusSeconds(302).plusNanos(1000),20,trip,null,"5m");
        var page=retainedHistory.read(vehicle,query,null);
        assertThat(page.points()).isEqualTo(publicHistory.history(publicVehicle,query).body().points());
        assertThat(page.points().getFirst().bucketStart()).isEqualTo(origin);
        assertThat(page.points().getFirst().sources()).containsExactly("OBD");
        assertThat(page.points().stream().mapToLong(HistoryQueries.AggregatePoint::sampleCount).sum()).isEqualTo(20);
    }
    @Test void wholeVehicleOwnershipMovesOnlyVerifiedMembersAndNeverDoublesRawCopies() {
        UUID trip=drive(true,"SIMULATED"); var query=request(null,0,600,20,"1m");
        var raw=ownedHistory.read(publicVehicle,query,null);
        assertThat(raw.rawOwned()).isEqualTo(21); assertThat(raw.archiveOwned()).isZero();
        evidence.copyPage(vehicle,trip,500); finish(vehicle,trip,500); verifier().verifyPage(vehicle,trip,500);
        assertThat(ownedHistory.read(publicVehicle,query,null).rawOwned()).isEqualTo(21); // COMPARE is not ownership.
        verify(vehicle,trip,500); String before=sourceState();
        for (String resolution : List.of("1m","5m")) {
            var q=request(null,0,600,20,resolution); var page=ownedHistory.read(publicVehicle,q,null);
            assertThat(page.rawOwned()).isEqualTo(1); assertThat(page.archiveOwned()).isEqualTo(20);
            assertThat(page.rawCandidates()).isEqualTo(21); assertThat(page.ledgerCandidates()).isEqualTo(21);
            assertThat(page.unownedLedgerCandidates()).isEqualTo(1);
            assertThat(page.points()).isEqualTo(publicHistory.history(publicVehicle,q).body().points());
            assertThat(page.points().stream().mapToLong(HistoryQueries.AggregatePoint::sampleCount).sum()).isEqualTo(21);
        }
        assertThat(sourceState()).isEqualTo(before);
    }
    @Test void wholeVehicleOwnershipIncludesUnlinkedLateAndSecondaryDeviceReadingsExactlyOnce() {
        archivedTrip(); extra(device,publicDevice,BASE.plusSeconds(3),900,"SIMULATED");
        UUID secondary=UUID.randomUUID(); String secondaryPublic="secondary-"+secondary;
        jdbc.update("insert into device(id,vehicle_id,public_id) values (?,?,?)",secondary,vehicle,secondaryPublic);
        extra(secondary,secondaryPublic,BASE.plusSeconds(3),901,"OBD");
        var query=request(null,0,360,20,"1m"); var page=ownedHistory.read(publicVehicle,query,null);
        assertThat(page.rawOwned()).isEqualTo(3); assertThat(page.archiveOwned()).isEqualTo(20);
        assertThat(page.points()).isEqualTo(publicHistory.history(publicVehicle,query).body().points());
        assertThat(page.points().getFirst().deviceCount()).isEqualTo(2);
        assertThat(page.points().getFirst().sources()).containsExactly("OBD","SIMULATED");
        assertThat(page.points().stream().mapToLong(HistoryQueries.AggregatePoint::sampleCount).sum()).isEqualTo(23);
    }
    @Test void wholeVehicleCombinesTwoFinalTripsInOneBucketWithoutAveragesOfAverages() {
        archivedTrip(); UUID second=drive(true,"OBD",BASE.plusSeconds(310));
        evidence.copyPage(vehicle,second,500); finish(vehicle,second,500); verify(vehicle,second,500);
        var query=request(null,0,1000,20,"5m"); var page=ownedHistory.read(publicVehicle,query,null);
        assertThat(page.archiveOwned()).isEqualTo(40); assertThat(page.rawOwned()).isEqualTo(2);
        assertThat(page.points()).isEqualTo(publicHistory.history(publicVehicle,query).body().points());
        var shared=page.points().stream().filter(p -> p.bucketStart().equals(BASE.plusSeconds(300))).findFirst().orElseThrow();
        assertThat(shared.sampleCount()).isEqualTo(20); assertThat(shared.deviceCount()).isEqualTo(1);
        assertThat(shared.sources()).containsExactly("OBD","SIMULATED");
        assertThat(shared.metrics().get("speedKph").sum()).isEqualByComparingTo("14");
        assertThat(shared.metrics().get("speedKph").count()).isEqualTo(4);
    }
    @Test void wholeVehicleClippedPagesPreserveMicrosecondsNullsAndStaticPagination() {
        archivedTrip(); var query=new HistoryRequest(BASE.plusSeconds(1),BASE.plusSeconds(302).plusNanos(1000),1,null,null,"1m");
        var points=new ArrayList<HistoryQueries.AggregatePoint>(); OwnedVehicleHistory.Cursor cursor=null;
        long raw=0,archive=0;
        for (int i=0;i<10;i++) {
            var page=ownedHistory.read(publicVehicle,query,cursor); points.addAll(page.points()); raw+=page.rawOwned(); archive+=page.archiveOwned();
            cursor=page.nextCursor(); if (cursor==null) break;
        }
        assertThat(cursor).isNull(); assertThat(raw).isZero(); assertThat(archive).isEqualTo(19);
        var full=new HistoryRequest(query.from(),query.to(),20,null,null,"1m");
        assertThat(points).isEqualTo(publicHistory.history(publicVehicle,full).body().points());
        assertThat(points.getFirst().metrics().get("speedKph").sum()).isEqualByComparingTo("3");
        assertThat(points.getFirst().metrics().get("mafGps").sum()).isNull();
        assertThat(points.getLast().lastObservedAt()).isEqualTo(BASE.plusSeconds(302));
        assertThat(points.stream().map(HistoryQueries.AggregatePoint::bucketStart).distinct().count()).isEqualTo(6);
    }
    @Test void wholeVehicleCursorBindsFiltersAndInvalidatesAfterNewRawInput() {
        UUID trip=archivedTrip(); var query=request(null,0,600,1,"1m"); var cursor=ownedHistory.read(publicVehicle,query,null).nextCursor();
        assertThat(cursor).isNotNull();
        for (var changed : List.of(request(null,1,600,1,"1m"),request(null,0,601,1,"1m"),request(null,0,600,2,"1m"),request(null,0,600,1,"5m")))
            assertThatThrownBy(() -> ownedHistory.read(publicVehicle,changed,cursor)).hasMessageContaining("does not match");
        assertThatThrownBy(() -> ownedHistory.read(publicVehicle,request(trip,0,600,1,"1m"),null)).isInstanceOf(IllegalArgumentException.class);
        extra(device,publicDevice,BASE.plusSeconds(3),902,"SIMULATED");
        assertThat(catchThrowableOfType(() -> ownedHistory.read(publicVehicle,query,cursor),dev.autopulse.shared.ApiFailure.class).code).isEqualTo("PAGE_CHANGED");
    }
    @Test void wholeVehicleArchiveHandoffDuringPagingDoesNotSkipOrDuplicateIdentities() {
        UUID trip=drive(true,"SIMULATED"); evidence.copyPage(vehicle,trip,500); finish(vehicle,trip,500);
        var query=request(null,0,600,1,"1m"); var first=ownedHistory.read(publicVehicle,query,null);
        assertThat(first.archiveOwned()).isZero(); verify(vehicle,trip,500);
        var points=new ArrayList<>(first.points()); var cursor=first.nextCursor();
        for (int i=0;cursor!=null && i<10;i++) {
            var page=ownedHistory.read(publicVehicle,query,cursor); assertThat(page.generation()).isEqualTo(first.generation());
            points.addAll(page.points()); cursor=page.nextCursor();
        }
        assertThat(cursor).isNull(); assertThat(points).isEqualTo(publicHistory.history(publicVehicle,request(null,0,600,20,"1m")).body().points());
    }
    @Test void wholeVehicleFailsClosedForLedgerIdentityWithoutEitherOwner() {
        archivedTrip(); UUID boot=UUID.randomUUID();
        jdbc.update("""
                insert into telemetry_replay_identity(device_id,boot_id,sequence_number,reading_id,vehicle_id,recorded_at,received_at,source,payload)
                select device_id,?,999,gen_random_uuid(),vehicle_id,recorded_at,received_at,source,
                    payload||jsonb_build_object('bootId',?::text,'sequenceNumber',999)
                from telemetry_replay_identity where vehicle_id=? order by recorded_at limit 1
                """,boot,boot.toString(),vehicle);
        assertThat(catchThrowableOfType(() -> ownedHistory.read(publicVehicle,request(null,0,600,20,"1m"),null),dev.autopulse.shared.ApiFailure.class).code)
                .isEqualTo("HISTORY_OWNERSHIP_GAP");
    }
    @Test void wholeVehicleRejectsAlteredRawEvidenceEvenWhenMovedOutOfLedgerWindow() {
        UUID trip=archivedTrip(); UUID id=jdbc.queryForObject("select m.reading_id from trip_retained_evidence m join telemetry_replay_identity e on e.reading_id=m.reading_id where m.trip_id=? and e.recorded_at=?",UUID.class,trip,Timestamp.from(BASE));
        // Disposable privileged corruption fixture, not an application update/repair path.
        jdbc.update("update telemetry_reading set payload=jsonb_set(payload,'{rpm}','1001'::jsonb) where id=?",id);
        assertThat(catchThrowableOfType(() -> ownedHistory.read(publicVehicle,request(null,0,600,20,"1m"),null),dev.autopulse.shared.ApiFailure.class).code)
                .isEqualTo("HISTORY_OWNERSHIP_MISMATCH");
        jdbc.update("update telemetry_reading set recorded_at=? where id=?",Timestamp.from(BASE.plusSeconds(400)),id);
        assertThat(catchThrowableOfType(() -> ownedHistory.read(publicVehicle,request(null,360,600,20,"1m"),null),dev.autopulse.shared.ApiFailure.class).code)
                .isEqualTo("HISTORY_OWNERSHIP_MISMATCH");
    }
    @Test void wholeVehicleKnownEmptyUnknownAndPreEpochVehicleIsolationRemainDistinct() {
        var query=request(null,0,600,20,"1m");
        assertThat(ownedHistory.read(publicVehicle,query,null).points()).isEmpty();
        assertThat(catchThrowableOfType(() -> ownedHistory.read("unknown-"+UUID.randomUUID(),query,null),dev.autopulse.shared.ApiFailure.class).code)
                .isEqualTo("UNKNOWN_VEHICLE");
        Instant origin=Instant.parse("1969-12-31T23:59:59.999999Z");
        UUID trip=drive(true,"OBD",origin); evidence.copyPage(vehicle,trip,500); finish(vehicle,trip,500); verify(vehicle,trip,500);
        var old=new HistoryRequest(origin,origin.plusSeconds(302).plusNanos(1000),20,null,null,"5m");
        assertThat(ownedHistory.read(publicVehicle,old,null).points()).isEqualTo(publicHistory.history(publicVehicle,old).body().points());
        String firstVehicle=publicVehicle; setup();
        assertThat(ownedHistory.read(publicVehicle,old,null).points()).isEmpty();
        assertThat(ownedHistory.read(firstVehicle,old,null).archiveOwned()).isEqualTo(20);
        assertThatThrownBy(() -> ownedHistory.read(firstVehicle,request(null,0,600,20,"raw"),null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ownedHistory.read(firstVehicle,null,null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void verifiedWholeBucketsReuseAllContributionsWithoutArchiveSensorRecalculation() {
        archivedTrip(); String before=sourceState();
        var buckets=jdbc.queryForList("select * from trip_aggregate_bucket where vehicle_id=? order by trip_id,resolution_seconds,bucket_start",vehicle);
        for (String resolution : List.of("1m","5m")) {
            var query=request(null,0,600,20,resolution); var page=ownedHistory.read(publicVehicle,query,null);
            assertThat(page.reusedContributions()).isEqualTo(resolution.equals("1m")?6:2);
            assertThat(page.archiveRecomputed()).isZero(); assertThat(page.archiveOwned()).isEqualTo(20); assertThat(page.rawOwned()).isEqualTo(1);
            assertThat(page.points()).isEqualTo(publicHistory.history(publicVehicle,query).body().points());
            assertThat(page.points().getFirst().metrics().get("coolantTempC").min()).isEqualByComparingTo("-20.0000000000000000001");
            assertThat(page.points().getFirst().metrics().get("mafGps").sum()).isNull();
        }
        assertThat(sourceState()).isEqualTo(before);
        assertThat(jdbc.queryForList("select * from trip_aggregate_bucket where vehicle_id=? order by trip_id,resolution_seconds,bucket_start",vehicle)).isEqualTo(buckets);
    }
    @Test void reuseSelectsOnlyCompleteUtcBucketsAndRecomputesBothClippedEdgesExactly() {
        archivedTrip();
        var minute=new HistoryRequest(BASE.plusSeconds(1),BASE.plusSeconds(302).plusNanos(1000),20,null,null,"1m");
        var page=ownedHistory.read(publicVehicle,minute,null);
        assertThat(page.reusedContributions()).isEqualTo(4); assertThat(page.archiveRecomputed()).isEqualTo(6);
        assertThat(page.archiveOwned()).isEqualTo(19); assertThat(page.points()).isEqualTo(publicHistory.history(publicVehicle,minute).body().points());
        var five=new HistoryRequest(minute.from(),minute.to(),20,null,null,"5m"); var both=ownedHistory.read(publicVehicle,five,null);
        assertThat(both.reusedContributions()).isZero(); assertThat(both.archiveRecomputed()).isEqualTo(19);
        assertThat(both.points()).isEqualTo(publicHistory.history(publicVehicle,five).body().points());
        var one=new HistoryRequest(BASE,minute.to(),20,null,null,"5m"); var mixed=ownedHistory.read(publicVehicle,one,null);
        assertThat(mixed.reusedContributions()).isEqualTo(1); assertThat(mixed.archiveRecomputed()).isEqualTo(2);
        assertThat(mixed.points()).isEqualTo(publicHistory.history(publicVehicle,one).body().points());
    }
    @Test void reuseWorksAcrossCursorPageBoundariesRatherThanTreatingEveryPageAsClipped() {
        archivedTrip(); var query=new HistoryRequest(BASE.plusSeconds(1),BASE.plusSeconds(302).plusNanos(1000),1,null,null,"1m");
        var points=new ArrayList<HistoryQueries.AggregatePoint>(); OwnedVehicleHistory.Cursor cursor=null;
        int reused=0; long recomputed=0;
        for (int i=0;i<10;i++) {
            var page=ownedHistory.read(publicVehicle,query,cursor); reused+=page.reusedContributions(); recomputed+=page.archiveRecomputed();
            points.addAll(page.points()); cursor=page.nextCursor(); if (cursor==null) break;
        }
        assertThat(cursor).isNull(); assertThat(reused).isEqualTo(4); assertThat(recomputed).isEqualTo(6);
        assertThat(points).isEqualTo(ownedHistory.read(publicVehicle,new HistoryRequest(query.from(),query.to(),20,null,null,"1m"),null).points());
        assertThat(points.getFirst().partial()).isTrue(); assertThat(points.get(1).partial()).isFalse(); assertThat(points.getLast().partial()).isTrue();
    }
    @Test void reusedSharedBucketContributionsComposeExactWeightedStatsAndDistinctProvenance() {
        archivedTrip(); UUID second=drive(true,"OBD",BASE.plusSeconds(310)); evidence.copyPage(vehicle,second,500); finish(vehicle,second,500); verify(vehicle,second,500);
        var query=request(null,0,1000,20,"5m"); var page=ownedHistory.read(publicVehicle,query,null);
        assertThat(page.reusedContributions()).isEqualTo(4); assertThat(page.archiveRecomputed()).isZero();
        assertThat(page.archiveOwned()).isEqualTo(40); assertThat(page.rawOwned()).isEqualTo(2);
        assertThat(page.points()).isEqualTo(publicHistory.history(publicVehicle,query).body().points());
        var shared=page.points().get(1);
        assertThat(shared.metrics().get("speedKph").count()).isEqualTo(4); assertThat(shared.metrics().get("speedKph").sum()).isEqualByComparingTo("14");
        assertThat(shared.deviceCount()).isEqualTo(1); assertThat(shared.sources()).containsExactly("OBD","SIMULATED");
    }
    @Test void claimedVerifiedArchiveWithoutRequiredFullContributionFailsRatherThanFallingBack() {
        UUID trip=drive(true,"SIMULATED"); evidence.copyPage(vehicle,trip,500);
        // Disposable privileged checkpoint-corruption fixture, not a legitimate verifier/repair path.
        // No archive, final evidence or bucket guard is disabled and no raw row is deleted.
        jdbc.update("insert into trip_aggregate_progress(trip_id,visited_rows,complete) values (?,20,true)",trip);
        jdbc.update("insert into trip_aggregate_verification(trip_id,state,visited_rows) values (?,'VERIFIED',20)",trip);
        var error=catchThrowableOfType(() -> ownedHistory.read(publicVehicle,request(null,0,600,20,"1m"),null),dev.autopulse.shared.ApiFailure.class);
        assertThat(error.code).isEqualTo("HISTORY_OWNERSHIP_GAP"); assertThat(error).hasMessageContaining("contribution");
        assertThat(jdbc.queryForObject("select count(*) from telemetry_reading where vehicle_id=?",Integer.class,vehicle)).isEqualTo(21);
    }
    List<UUID> visibleEvidence(UUID trip) {
        return jdbc.queryForList("select reading_id from trip_evidence where trip_id=? order by reading_id",UUID.class,trip);
    }
    boolean readyEvidence(UUID trip) {
        return jdbc.queryForObject("select exists(select 1 from trip_verified_archive where trip_id=?)",Boolean.class,trip);
    }
    @Test void finalEvidenceSwitchesOnlyAfterCompleteIndependentVerificationWithoutChangingIdsOrGeneration() {
        UUID trip=drive(true,"SIMULATED"); var original=visibleEvidence(trip);
        long generation=jdbc.queryForObject("select generation from vehicle_read_generation where vehicle_id=?",Long.class,vehicle);
        assertThat(original).hasSize(20); assertThat(readyEvidence(trip)).isFalse();
        evidence.copyPage(vehicle,trip,1); assertThat(readyEvidence(trip)).isFalse(); assertThat(visibleEvidence(trip)).isEqualTo(original);
        evidence.copyPage(vehicle,trip,500); builder().buildPage(vehicle,trip,1);
        assertThat(readyEvidence(trip)).isFalse(); assertThat(visibleEvidence(trip)).isEqualTo(original);
        builder().buildPage(vehicle,trip,500); assertThat(readyEvidence(trip)).isFalse();
        assertThat(verifier().verifyPage(vehicle,trip,500).state()).isEqualTo("COMPARE");
        assertThat(readyEvidence(trip)).isFalse(); String before=sourceState();
        assertThat(verify(vehicle,trip,500)).isEqualTo("VERIFIED"); assertThat(readyEvidence(trip)).isTrue();
        assertThat(visibleEvidence(trip)).isEqualTo(original);
        assertThat(jdbc.queryForObject("select count(distinct reading_id)=count(*) from trip_evidence where trip_id=?",Boolean.class,trip)).isTrue();
        assertThat(jdbc.queryForObject("select generation from vehicle_read_generation where vehicle_id=?",Long.class,vehicle)).isEqualTo(generation);
        assertThat(sourceState()).isEqualTo(before);
    }
    @Test void verifiedFinalMembershipUsesImmutableLedgerTimeRatherThanMutableRawMetadata() {
        UUID trip=archivedTrip(); var original=visibleEvidence(trip); var summary=jdbc.queryForMap("select * from trip_record where id=?",trip);
        UUID first=jdbc.queryForObject("select reading_id from telemetry_replay_identity where vehicle_id=? and recorded_at=?",UUID.class,vehicle,Timestamp.from(BASE));
        // Disposable privileged raw corruption; no trigger/guard disabled or evidence deleted.
        jdbc.update("update telemetry_reading set recorded_at=? where id=?",Timestamp.from(BASE.plusSeconds(400)),first);
        assertThat(visibleEvidence(trip)).isEqualTo(original).contains(first);
        assertThat(jdbc.queryForObject("select count(*) from trip_reading_link l join telemetry_reading r on r.id=l.reading_id join trip_record t on t.id=l.trip_id where l.trip_id=? and r.recorded_at between t.started_at and t.ended_at",Integer.class,trip)).isEqualTo(19);
        assertThat(jdbc.queryForMap("select * from trip_record where id=?",trip)).isEqualTo(summary);
        assertThat(jdbc.queryForObject("select count(*) from trip_reading_link where trip_id=?",Integer.class,trip)).isEqualTo(20);
    }
    @Test void verifiedEvidenceStillHonorsVehicleWidePublicationVisibilityAndApiGate() {
        UUID trip=archivedTrip(),plan=UUID.randomUUID(),replayId=UUID.randomUUID(),publication=UUID.randomUUID(); var original=visibleEvidence(trip);
        UUID work=jdbc.queryForObject("select reading_id from trip_work_item where vehicle_id=? order by reading_id limit 1",UUID.class,vehicle);
        // Valid disposable publication metadata to exercise visibility, not a correction of this final trip.
        jdbc.update("insert into trip_correction_plan(id,vehicle_id,requested_work_id,primary_device_id,policy_revision,projection_cursor_at,planned_as_of,window_start,window_end,phase) values (?,?,?,?,1,?,?,?,?,'READY')",
                plan,vehicle,work,device,Timestamp.from(BASE.plusSeconds(304)),Timestamp.from(AS_OF),Timestamp.from(BASE),Timestamp.from(BASE.plusSeconds(304)));
        jdbc.update("insert into trip_correction_replay(id,plan_id,input_generation,window_start,window_end,planned_as_of,read_from,read_to,status,checkpoint) values (?,?,0,?,?,?,?,?,'STAGED','{}'::jsonb)",
                replayId,plan,Timestamp.from(BASE),Timestamp.from(BASE.plusSeconds(304)),Timestamp.from(AS_OF),Timestamp.from(BASE),Timestamp.from(BASE.plusSeconds(304)));
        jdbc.update("insert into trip_correction_publication(id,vehicle_id,plan_id,replay_id,acceptance_fence,phase) values (?,?,?,?,0,'CAPTURE')",publication,vehicle,plan,replayId);
        assertThat(readyEvidence(trip)).isTrue(); // Eligibility is not public visibility.
        assertThat(visibleEvidence(trip)).isEmpty();
        var error=catchThrowableOfType(() -> publicHistory.history(publicVehicle,request(trip,0,600,20,"1m")),dev.autopulse.shared.ApiFailure.class);
        assertThat(error.code).isEqualTo("PUBLICATION_PENDING");
        assertThat(catchThrowableOfType(() -> retainedHistory.read(vehicle,request(trip,0,600,20,"1m"),null),dev.autopulse.shared.ApiFailure.class).code).isEqualTo("PUBLICATION_PENDING");
        jdbc.update("update trip_correction_publication set phase='ABANDONED' where id=?",publication);
        assertThat(visibleEvidence(trip)).isEqualTo(original);
    }
    @Test void finalEvidenceReadHandoffKeepsGuardedRawProtectionAndImmutableAssociations() {
        UUID trip=archivedTrip(); UUID reading=visibleEvidence(trip).getFirst();
        assertThat(jdbc.queryForList("select conrelid::regclass::text from pg_constraint where contype='f' and confrelid='telemetry_reading'::regclass order by conrelid::regclass::text",String.class))
                .containsExactly("trip_correction_reading");
        assertThat(jdbc.queryForObject("select count(*) from pg_trigger where tgrelid='telemetry_reading'::regclass and tgname='guarded_final_raw_deletion' and tgenabled='O'",Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("delete from telemetry_reading where id=?",reading)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from trip_reading_link where reading_id=?",reading)).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("update trip_reading_link set trip_id=trip_id where reading_id=?",reading)).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("delete from trip_retained_evidence where reading_id=?",reading)).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("update telemetry_replay_identity set recorded_at=recorded_at where reading_id=?",reading)).hasMessageContaining("immutable");
        assertThat(visibleEvidence(trip)).hasSize(20);
    }
    @Test void inconsistentPrivilegedVerifiedCountersCannotSwitchFinalEvidenceOwner() {
        UUID trip=drive(true,"SIMULATED"); evidence.copyPage(vehicle,trip,500); var original=visibleEvidence(trip);
        // Malformed checkpoint fixture: a VERIFIED label alone is insufficient eligibility.
        jdbc.update("insert into trip_aggregate_progress(trip_id,visited_rows,complete) values (?,19,true)",trip);
        jdbc.update("insert into trip_aggregate_verification(trip_id,state,visited_rows) values (?,'VERIFIED',20)",trip);
        assertThat(readyEvidence(trip)).isFalse(); assertThat(visibleEvidence(trip)).isEqualTo(original);
        assertThat(ownedHistory.read(publicVehicle,request(null,0,600,20,"1m"),null).archiveOwned()).isZero();
        assertThatThrownBy(() -> retainedHistory.read(vehicle,request(trip,0,600,20,"1m"),null)).hasMessageContaining("independently VERIFIED");
    }
}
