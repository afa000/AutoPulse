package dev.autopulse;

import dev.autopulse.telemetry.*;
import dev.autopulse.trip.*;
import java.math.BigInteger;
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
class FinalTripEvidenceTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired TelemetryValidator validator;
    @Autowired TelemetryRepository repository;
    @Autowired TripBackfill backfill;
    @Autowired ReplayIdentityBackfill replayBackfill;
    @Autowired TripProjector projector;
    @Autowired TripFinalizer finalizer;
    UUID vehicle,device;
    String publicVehicle,publicDevice;
    static final Instant BASE=Instant.parse("2026-10-20T12:00:00Z"),AS_OF=Instant.parse("2026-12-01T12:00:00Z");
    @BeforeEach void setup() {
        vehicle=UUID.randomUUID(); device=UUID.randomUUID();
        publicVehicle="evidence-"+vehicle; publicDevice="evidence-"+device;
        jdbc.update("insert into vehicle(id,public_id) values (?,?)",vehicle,publicVehicle);
        jdbc.update("insert into device(id,vehicle_id,public_id) values (?,?,?)",device,vehicle,publicDevice);
        jdbc.update("insert into trip_policy(vehicle_id,primary_device_id) values (?,?)",vehicle,device);
    }
    FinalTripEvidence copier() { return new FinalTripEvidence(jdbc,transactions); }
    UUID drive(boolean seal) { return drive(seal,false); }
    UUID drive(boolean seal,boolean legacyTrailingLink) {
        return drive(seal,legacyTrailingLink,true);
    }
    UUID drive(boolean seal,boolean legacyTrailingLink,boolean identityReady) {
        for (var node : mapper.readTree(Path.of("../contracts/examples/trip-short-input.json").toFile()).get("readings")) {
            var r=validator.validate(((ObjectNode)node).deepCopy().put("vehicleId",publicVehicle).put("deviceId",publicDevice));
            jdbc.update("""
                    insert into telemetry_reading(id,vehicle_id,device_id,boot_id,sequence_number,recorded_at,received_at,source,payload)
                    values (?,?,?,?,?,?,?,'SIMULATED',?::jsonb)
                    """,UUID.randomUUID(),vehicle,device,r.bootId(),new java.math.BigDecimal(r.sequenceNumber()),
                    Timestamp.from(r.recordedAt()),Timestamp.from(BASE.plus(Duration.ofDays(1))),r.payload().toString());
        }
        assertThat(backfill.enqueuePage(vehicle,100).complete()).isTrue();
        if (identityReady) assertThat(replayBackfill.copyPage(vehicle,100).complete()).isTrue();
        boolean caught=false;
        for (int i=0;i<100;i++) if (projector.processPage(vehicle,AS_OF,100).state().equals("CAUGHT_UP")) { caught=true; break; }
        assertThat(caught).isTrue();
        if (legacyTrailingLink) {
            // Today's projector cleans trailing links. Manufacture a legal legacy
            // extra link while PROVISIONAL, then use real finalization unchanged.
            assertThat(jdbc.update("""
                    insert into trip_reading_link(reading_id,trip_id)
                    select r.id,t.id from telemetry_reading r join trip_record t on t.vehicle_id=r.vehicle_id
                    where t.vehicle_id=? and t.status='COMPLETED' and r.recorded_at>t.ended_at
                      and not exists(select 1 from trip_reading_link l where l.reading_id=r.id)
                    order by r.recorded_at limit 1
                    """,vehicle)).isEqualTo(1);
        }
        if (seal) {
            int sealed=0;
            for (int i=0;i<20 && sealed==0;i++) sealed+=finalizer.finalizePage(vehicle,AS_OF,100).finalized();
            assertThat(sealed).isEqualTo(1);
        }
        return jdbc.queryForObject("select id from trip_record where vehicle_id=? and status='COMPLETED'",UUID.class,vehicle);
    }
    List<UUID> retained(UUID trip) { return jdbc.queryForList("select reading_id from trip_retained_evidence where trip_id=? order by reading_id",UUID.class,trip); }
    List<UUID> original(UUID trip) { return jdbc.queryForList("select reading_id from trip_evidence where trip_id=? order by reading_id",UUID.class,trip); }
    String sourceFingerprint() {
        var values=new ArrayList<String>();
        for (String table : List.of("telemetry_reading","telemetry_replay_identity","telemetry_replay_backfill","trip_work_item",
                "trip_record","trip_projection_progress","trip_backfill_progress","trip_vehicle_activity","vehicle_read_generation"))
            values.add(jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text from "+table+" t where vehicle_id=?",String.class,vehicle));
        values.add(jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(l) order by l.reading_id),'[]'::jsonb)::text from trip_reading_link l join trip_record t on t.id=l.trip_id where t.vehicle_id=?",String.class,vehicle));
        return values.toString();
    }
    JdbcTemplate observed(java.util.List<String> queries) {
        return new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())) {
            @Override public <T> T queryForObject(String sql,Class<T> type,Object... args) {
                queries.add(sql); return super.queryForObject(sql,type,args);
            }
        };
    }
    @Test void linkedEvidenceIdentityHandoffPreservesExactMembersWithoutArchivingOrChangingGeneration() {
        UUID trip=drive(false,false,false); var expected=original(trip); String generation=jdbc.queryForObject("select generation::text from vehicle_read_generation where vehicle_id=?",String.class,vehicle);
        var raw=jdbc.queryForList("select * from telemetry_reading where vehicle_id=? order by id",vehicle);
        var tripBefore=jdbc.queryForMap("select * from trip_record where id=?",trip);
        var links=jdbc.queryForList("select * from trip_reading_link where trip_id=? order by reading_id",trip);
        assertThat(expected).hasSize(27); assertThat(replayBackfill.copyPage(vehicle,500).complete()).isTrue();
        assertThat(original(trip)).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        assertThat(retained(trip)).isEmpty(); assertThat(jdbc.queryForObject("select count(*) from trip_verified_archive where trip_id=?",Integer.class,trip)).isZero();
        assertThat(jdbc.queryForObject("select generation::text from vehicle_read_generation where vehicle_id=?",String.class,vehicle)).isEqualTo(generation);
        assertThat(jdbc.queryForMap("select * from trip_record where id=?",trip)).isEqualTo(tripBefore);
        assertThat(jdbc.queryForList("select * from trip_reading_link where trip_id=? order by reading_id",trip)).isEqualTo(links);
        assertThat(jdbc.queryForList("select * from telemetry_reading where vehicle_id=? order by id",vehicle)).isEqualTo(raw);
    }
    @Test void linkedEvidenceSourceIsPerVehicleAndMissingOrIncompleteCheckpointKeepsRaw() {
        UUID legacyTrip=drive(false,false,false); UUID legacyVehicle=vehicle; UUID legacyMember=original(legacyTrip).getFirst();
        setup(); UUID readyTrip=drive(false); UUID readyMember=original(readyTrip).getFirst(); var readyExpected=original(readyTrip);
        // Privileged disposable edits distinguish the physical sources; no guards are disabled.
        jdbc.update("update telemetry_reading set recorded_at=? where id in (?,?)",Timestamp.from(BASE.plusSeconds(3600)),legacyMember,readyMember);
        assertThat(original(legacyTrip)).hasSize(26).doesNotContain(legacyMember);
        assertThat(original(readyTrip)).containsExactlyElementsOf(readyExpected);
        jdbc.update("delete from telemetry_replay_backfill where vehicle_id=?",legacyVehicle);
        assertThat(original(legacyTrip)).hasSize(26).doesNotContain(legacyMember);
        assertThat(original(readyTrip)).hasSize(27).doesNotContain(legacyMember);
        assertThat(jdbc.queryForObject("select count(*) from trip_retained_evidence where trip_id in (?,?)",Integer.class,legacyTrip,readyTrip)).isZero();
    }
    @Test void ledgerLinkedEvidencePreservesActiveProvisionalAndRebuildingVisibility() {
        UUID trip=drive(false); var expected=original(trip);
        jdbc.update("update telemetry_reading set recorded_at=? where id=?",Timestamp.from(BASE.plusSeconds(3600)),expected.getFirst());
        assertThat(original(trip)).containsExactlyElementsOf(expected); // COMPLETED but still PROVISIONAL, not archive-ready.
        jdbc.update("update trip_record set status='ACTIVE',ended_at=null,confirmed_at=null,completion_reason=null where id=?",trip);
        assertThat(original(trip)).containsExactlyElementsOf(expected);
        jdbc.update("update trip_record set status='REBUILDING' where id=?",trip);
        assertThat(original(trip)).isEmpty();
        jdbc.update("update trip_record set status='ACTIVE' where id=?",trip);
        assertThat(original(trip)).containsExactlyElementsOf(expected);
        assertThat(jdbc.queryForObject("select count(*) from trip_verified_archive where trip_id=?",Integer.class,trip)).isZero();
        assertThat(retained(trip)).isEmpty();
    }
    @Test void linkedToVerifiedRetainedEvidenceHandoffNeverDuplicatesOrLosesClosedMembers() {
        UUID trip=drive(true,true); var expected=original(trip); String before=sourceFingerprint();
        copier().copyPage(vehicle,trip,1); assertThat(original(trip)).containsExactlyElementsOf(expected);
        assertThat(copier().copyPage(vehicle,trip,500).complete()).isTrue(); assertThat(original(trip)).containsExactlyElementsOf(expected);
        assertThat(new TripAggregateBuilder(jdbc,transactions).buildPage(vehicle,trip,500).complete()).isTrue();
        var verifier=new TripAggregateVerifier(jdbc,mapper,transactions);
        assertThat(verifier.verifyPage(vehicle,trip,500).state()).isEqualTo("COMPARE");
        assertThat(original(trip)).containsExactlyElementsOf(expected);
        assertThat(verifier.verifyPage(vehicle,trip,500).state()).isEqualTo("VERIFIED");
        assertThat(original(trip)).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        assertThat(original(trip)).hasSize(27); assertThat(sourceFingerprint()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from trip_verified_archive where trip_id=?",Integer.class,trip)).isEqualTo(1);
        // Verified per-trip eligibility remains independent of this vehicle checkpoint.
        // The raw arm must not duplicate the retained arm when registration is missing.
        jdbc.update("delete from telemetry_replay_backfill where vehicle_id=?",vehicle);
        assertThat(original(trip)).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
    }
    @Test void readyCopyUsesCanonicalBoundsWhileOriginalRawGuardsRemain() {
        UUID trip=drive(true,true); var expected=original(trip); String before=sourceFingerprint();
        var queries=new ArrayList<String>();
        var page=new FinalTripEvidence(observed(queries),transactions).copyPage(vehicle,trip,500);
        assertThat(page.complete()).isTrue(); assertThat(page.retained()).isEqualTo(27);
        assertThat(retained(trip)).isEqualTo(expected); assertThat(sourceFingerprint()).isEqualTo(before);
        assertThat(queries).anySatisfy(sql -> assertThat(sql).contains("select recorded_at from trip_ledger_reading"));
        assertThat(queries).allSatisfy(sql -> assertThat(sql).doesNotContain("from telemetry_reading"));
        assertThatThrownBy(() -> jdbc.update("delete from telemetry_reading where id=?",expected.getFirst())).hasMessageContaining("foreign key constraint");
        assertThatThrownBy(() -> jdbc.update("delete from trip_reading_link where reading_id=?",expected.getFirst())).hasMessageContaining("finalized trip evidence is immutable");
    }
    @Test void incompleteIdentityCheckpointKeepsRawBoundClassificationWithoutMarkingReady() {
        UUID trip=drive(true,false,false); String before=sourceFingerprint(); var queries=new ArrayList<String>();
        var page=new FinalTripEvidence(observed(queries),transactions).copyPage(vehicle,trip,500);
        assertThat(page.complete()).isTrue(); assertThat(page.retained()).isEqualTo(27);
        assertThat(queries).anySatisfy(sql -> assertThat(sql).contains("select recorded_at from telemetry_reading"));
        assertThat(queries).allSatisfy(sql -> assertThat(sql).doesNotContain("from trip_ledger_reading"));
        assertThat(sourceFingerprint()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select complete from telemetry_replay_backfill where vehicle_id=?",Boolean.class,vehicle)).isFalse();
    }
    @Test void identityCompletionBetweenOneLinkCopyPagesPreservesCursorAndClosedMembership() {
        UUID trip=drive(true,true,false); var expected=original(trip); copier().copyPage(vehicle,trip,1);
        var progress=jdbc.queryForMap("select * from trip_retained_evidence_progress where trip_id=?",trip);
        var copied=retained(trip); var tripBefore=jdbc.queryForMap("select * from trip_record where id=?",trip);
        var raw=jdbc.queryForList("select * from telemetry_reading where vehicle_id=? order by id",vehicle);
        assertThat(replayBackfill.copyPage(vehicle,500).complete()).isTrue();
        assertThat(jdbc.queryForMap("select * from trip_retained_evidence_progress where trip_id=?",trip)).isEqualTo(progress);
        assertThat(retained(trip)).isEqualTo(copied);
        boolean complete=false;
        for (int i=0;i<100;i++) {
            var page=copier().copyPage(vehicle,trip,1); assertThat(page.visited()).isBetween(0,1);
            if (page.complete()) { complete=true; break; }
        }
        assertThat(complete).isTrue(); assertThat(retained(trip)).isEqualTo(expected); assertThat(retained(trip)).hasSize(27);
        assertThat(jdbc.queryForMap("select * from trip_record where id=?",trip)).isEqualTo(tripBefore);
        assertThat(jdbc.queryForList("select * from telemetry_reading where vehicle_id=? order by id",vehicle)).isEqualTo(raw);
        assertThat(jdbc.queryForObject("select retained_rows from trip_retained_evidence_progress where trip_id=?",Integer.class,trip)).isEqualTo(27);
    }
    @Test void canonicalInRangeMemberCannotBeSkippedByMovingItsRawTimestampOutsideTrip() {
        UUID trip=drive(true); UUID id=original(trip).getFirst();
        // Privileged disposable corruption only; no trigger is disabled.
        jdbc.update("update telemetry_reading set recorded_at=? where id=?",Timestamp.from(BASE.plusSeconds(3600)),id);
        String before=sourceFingerprint();
        assertThatThrownBy(() -> copier().copyPage(vehicle,trip,500)).hasMessageContaining("exact original closed membership");
        assertThat(retained(trip)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from trip_retained_evidence_progress where trip_id=?",Integer.class,trip)).isZero();
        assertThat(sourceFingerprint()).isEqualTo(before);
    }
    @Test void canonicalOutsideConfirmationLinkCannotBeInventedAsMemberByRawTimestampEdit() {
        UUID trip=drive(true,true); var expected=original(trip);
        UUID outside=jdbc.queryForObject("select l.reading_id from trip_reading_link l join telemetry_replay_identity e on e.reading_id=l.reading_id join trip_record t on t.id=l.trip_id where t.id=? and e.recorded_at>t.ended_at limit 1",UUID.class,trip);
        jdbc.update("update telemetry_reading set recorded_at=? where id=?",Timestamp.from(BASE.plusSeconds(10)),outside);
        String before=sourceFingerprint(); var page=copier().copyPage(vehicle,trip,500);
        assertThat(page.complete()).isTrue(); assertThat(page.retained()).isEqualTo(27);
        assertThat(retained(trip)).containsExactlyElementsOf(expected).doesNotContain(outside);
        assertThat(sourceFingerprint()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from trip_verified_archive where trip_id=?",Integer.class,trip)).isZero();
    }
    @Test void oneLinkPagesResumeAcrossServicesAndRetainExactlyClosedFinalEvidence() {
        UUID trip=drive(true,true); var expected=original(trip); String before=sourceFingerprint();
        assertThat(expected).hasSize(27);
        int links=jdbc.queryForObject("select count(*) from trip_reading_link where trip_id=?",Integer.class,trip);
        int visited=0,retained=0,copied=0; boolean complete=false;
        for (int i=0;i<100;i++) {
            var page=copier().copyPage(vehicle,trip,1); // Fresh instance each time, durable cursor.
            assertThat(page.visited()).isBetween(0,1); assertThat(page.busy()).isFalse();
            visited+=page.visited(); retained+=page.retained(); copied+=page.copied();
            if (page.complete()) { complete=true; break; }
        }
        assertThat(complete).isTrue(); assertThat(visited).isEqualTo(links);
        assertThat(retained).isEqualTo(expected.size()); assertThat(copied).isEqualTo(expected.size());
        assertThat(retained(trip)).isEqualTo(expected); assertThat(links).isGreaterThan(expected.size());
        var progress=jdbc.queryForMap("select * from trip_retained_evidence_progress where trip_id=?",trip);
        assertThat(((Number)progress.get("visited_rows")).intValue()).isEqualTo(links);
        assertThat(((Number)progress.get("retained_rows")).intValue()).isEqualTo(27);
        assertThat(copier().copyPage(vehicle,trip,500)).isEqualTo(new FinalTripEvidence.Page(0,0,0,true,false));
        assertThat(sourceFingerprint()).isEqualTo(before);
        assertThat(jdbc.queryForMap("select * from trip_retained_evidence_progress where trip_id=?",trip)).isEqualTo(progress);
        assertThat(jdbc.queryForObject("""
                select count(*) from trip_retained_evidence a join telemetry_replay_identity e on e.reading_id=a.reading_id
                where a.trip_id=? and e.vehicle_id=? and e.device_id=? and e.source='SIMULATED'
                """,Integer.class,trip,vehicle,device)).isEqualTo(27);
    }
    @Test void provisionalActiveUnknownAndInvalidRequestsCreateNoProgress() {
        UUID trip=drive(false); String before=sourceFingerprint();
        assertThatThrownBy(() -> copier().copyPage(vehicle,trip,100)).hasMessageContaining("FINAL COMPLETED");
        assertThatThrownBy(() -> jdbc.update("insert into trip_retained_evidence(reading_id,trip_id) values (?,?)",original(trip).getFirst(),trip)).hasMessageContaining("FINAL COMPLETED");
        assertThatThrownBy(() -> copier().copyPage(UUID.randomUUID(),trip,100)).hasMessageContaining("Unknown");
        assertThatThrownBy(() -> copier().copyPage(vehicle,UUID.randomUUID(),100)).hasMessageContaining("Unknown");
        for (int n : new int[]{0,501}) assertThatThrownBy(() -> copier().copyPage(vehicle,trip,n)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copier().copyPage(null,trip,1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copier().copyPage(vehicle,null,1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(sourceFingerprint()).isEqualTo(before);
        jdbc.update("update trip_record set status='ACTIVE',ended_at=null,confirmed_at=null,completion_reason=null where id=?",trip);
        assertThatThrownBy(() -> copier().copyPage(vehicle,trip,100)).hasMessageContaining("FINAL COMPLETED");
        assertThat(retained(trip)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from trip_retained_evidence_progress where trip_id=?",Integer.class,trip)).isZero();
    }
    @Test void retainedMembershipAndCompletedProgressAreImmutableWithoutWeakeningOriginalGuards() {
        UUID trip=drive(true); copier().copyPage(vehicle,trip,500); var id=retained(trip).getFirst();
        assertThatThrownBy(() -> jdbc.update("update trip_retained_evidence set trip_id=trip_id where reading_id=?",id)).hasMessageContaining("retained final evidence is immutable");
        assertThatThrownBy(() -> jdbc.update("delete from trip_retained_evidence where reading_id=?",id)).hasMessageContaining("retained final evidence is immutable");
        assertThatThrownBy(() -> jdbc.update("update trip_retained_evidence_progress set complete=false where trip_id=?",trip)).hasMessageContaining("cannot be deleted or reset");
        assertThatThrownBy(() -> jdbc.update("delete from trip_retained_evidence_progress where trip_id=?",trip)).hasMessageContaining("cannot be deleted or reset");
        assertThatThrownBy(() -> jdbc.update("delete from trip_reading_link where reading_id=?",id)).hasMessageContaining("finalized trip evidence is immutable");
        assertThatThrownBy(() -> jdbc.update("update trip_record set revision=revision+1 where id=?",trip)).hasMessageContaining("finalized trip is immutable");
    }
    @Test void preexistingValidEvidenceIsVerifiedWithoutDoubleCountingOrOverwriting() {
        UUID trip=drive(true); var expected=original(trip);
        jdbc.update("insert into trip_retained_evidence(reading_id,trip_id) values (?,?)",expected.getFirst(),trip);
        var page=copier().copyPage(vehicle,trip,500);
        assertThat(page.retained()).isEqualTo(27); assertThat(page.copied()).isEqualTo(26);
        assertThat(page.complete()).isTrue(); assertThat(retained(trip)).isEqualTo(expected);
    }
    @Test void failedPageRollsBackCopiesAndCursorThenResumesAfterOutage() {
        UUID trip=drive(true); copier().copyPage(vehicle,trip,1);
        var previous=retained(trip); var progress=jdbc.queryForMap("select * from trip_retained_evidence_progress where trip_id=?",trip);
        UUID bad=original(trip).get(2); String before=sourceFingerprint();
        jdbc.execute("create function test_retained_failure() returns trigger language plpgsql as $$ begin if NEW.reading_id::text=TG_ARGV[0] then raise exception 'evidence fixture outage'; end if; return NEW; end $$");
        jdbc.execute("create trigger test_retained_failure after insert on trip_retained_evidence for each row execute function test_retained_failure('"+bad+"')");
        try {
            assertThatThrownBy(() -> copier().copyPage(vehicle,trip,500)).hasMessageContaining("evidence fixture outage");
            assertThat(retained(trip)).isEqualTo(previous);
            assertThat(jdbc.queryForMap("select * from trip_retained_evidence_progress where trip_id=?",trip)).isEqualTo(progress);
        } finally {
            jdbc.execute("drop trigger test_retained_failure on trip_retained_evidence"); jdbc.execute("drop function test_retained_failure()");
        }
        assertThat(copier().copyPage(vehicle,trip,500).complete()).isTrue();
        assertThat(retained(trip)).isEqualTo(original(trip)); assertThat(sourceFingerprint()).isEqualTo(before);
    }
    @Test void competingCopierReturnsBusyAndRecoversWithoutPartialRegistration() throws Exception {
        UUID trip=drive(true); var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        try (var executor=Executors.newSingleThreadExecutor()) {
            var holder=executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                jdbc.execute("select pg_advisory_xact_lock(hashtextextended('"+trip+"',916))"); locked.countDown();
                try { if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            try {
                assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
                assertThat(copier().copyPage(vehicle,trip,1)).isEqualTo(new FinalTripEvidence.Page(0,0,0,false,true));
                assertThat(retained(trip)).isEmpty();
                assertThat(jdbc.queryForObject("select count(*) from trip_retained_evidence_progress where trip_id=?",Integer.class,trip)).isZero();
            } finally { release.countDown(); }
            holder.get(10,TimeUnit.SECONDS);
        }
        assertThat(copier().copyPage(vehicle,trip,500).complete()).isTrue();
    }
    @Test void trailingOrUnlinkedLateReadingsCannotBeInventedAsFinalEvidence() {
        UUID trip=drive(true,true); copier().copyPage(vehicle,trip,500);
        var tripBefore=jdbc.queryForMap("select * from trip_record where id=?",trip); var archive=retained(trip);
        UUID trailing=jdbc.queryForObject("""
                select l.reading_id from trip_reading_link l join telemetry_reading r on r.id=l.reading_id
                join trip_record t on t.id=l.trip_id where l.trip_id=? and r.recorded_at>t.ended_at order by r.recorded_at limit 1
                """,UUID.class,trip);
        assertThatThrownBy(() -> jdbc.update("insert into trip_retained_evidence(reading_id,trip_id) values (?,?)",trailing,trip)).hasMessageContaining("exact original closed membership");
        var late=((ObjectNode)mapper.readTree(Path.of("../contracts/examples/telemetry-sample.json").toFile()))
                .put("vehicleId",publicVehicle).put("deviceId",publicDevice).put("sequenceNumber",BigInteger.valueOf(99999))
                .put("recordedAt",BASE.plusSeconds(10).toString());
        assertThat(repository.store(vehicle,device,validator.validate(late))).isEqualTo("accepted");
        UUID unlinked=jdbc.queryForObject("select id from telemetry_reading where vehicle_id=? and sequence_number=99999",UUID.class,vehicle);
        assertThatThrownBy(() -> jdbc.update("insert into trip_retained_evidence(reading_id,trip_id) values (?,?)",unlinked,trip)).hasMessageContaining("exact original closed membership");
        assertThat(copier().copyPage(vehicle,trip,500).visited()).isZero();
        assertThat(retained(trip)).isEqualTo(archive); assertThat(jdbc.queryForMap("select * from trip_record where id=?",trip)).isEqualTo(tripBefore);
    }
}
