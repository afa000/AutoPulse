package dev.autopulse.trip;

import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit bounded retention: disabled by default, dry-run by default, no automatic caller. */
@Service
public class RetentionPreview {
    public record Options(boolean enabled,boolean dryRun,int rawDays,int receiptHoldHours) {
        public Options {
            if (rawDays<7 || rawDays>3650 || receiptHoldHours<96 || receiptHoldHours>8760)
                throw new IllegalArgumentException("Retention requires raw-days 7..3650 and receipt-hold-hours 96..8760");
        }
    }
    /** Trusted in-process continuation, NOT a public cursor or a deletion authorization. */
    public record Cursor(UUID vehicle,Instant asOf,String generation,int limit,int rawDays,int receiptHoldHours,
                         long policyRevision,UUID primaryDevice,UUID anchor) {
        public Cursor {
            if (vehicle==null || asOf==null || asOf.getNano()%1000!=0 || generation==null || !generation.matches("[0-9]{1,20}")
                    || anchor==null || limit<1 || limit>100 || rawDays<7 || rawDays>3650 || receiptHoldHours<96 || receiptHoldHours>8760
                    || policyRevision<0 || (primaryDevice==null)!=(policyRevision==0))
                throw new IllegalArgumentException("Invalid trusted retention preview continuation");
        }
    }
    public record Reading(UUID readingId,Instant recordedAt,Instant receivedAt,String replayIdentityState,boolean policyCandidate,List<String> protections) {}
    /** Counts cover returned rows only, not the lookahead or unseen vehicle history. */
    public record ReplayCoverage(String backfillState,int checked,int matched,int missing,int mismatched) {}
    public record Page(String state,UUID vehicle,Instant asOf,Instant measurementBefore,Instant receiptBefore,String generation,
                       List<String> vehicleProtections,List<Reading> readings,int policyCandidates,int wouldDelete,
                       ReplayCoverage replayCoverage,List<String> missingPrerequisites,Cursor nextCursor) {}
    /** Point-in-time coverage for one returned reading, never a deletion permit. */
    public record ArchiveCheck(UUID readingId,UUID verifiedTrip,String state) {}
    /** Selected-row reference observations; MATCHED is not deletion authorization. */
    public record ReferenceCheck(UUID readingId,String workState,String linkState,boolean correctionReference) {}
    public record PrunePage(String state,int eligible,int deleted,boolean complete,Cursor nextCursor) {}
    /** Locks end before return. This result is NEVER a reusable deletion permit. */
    public record BarrierPage(String state,Page preview,List<ArchiveCheck> archiveChecks,List<ReferenceCheck> referenceChecks) {
        public BarrierPage { archiveChecks=List.copyOf(archiveChecks); referenceChecks=List.copyOf(referenceChecks); }
    }
    private record Context(Instant asOf,Instant cutoff,Instant receiptBefore) {}
    private record ReferencePins(long work,long links) {}
    private static final List<String> PREREQUISITES=List.of("DURABLE_AGGREGATES_REQUIRED","RETAINED_FINAL_EVIDENCE_REQUIRED",
            "WRITE_TIME_REPLAY_COVERAGE_RECHECK_REQUIRED","WRITE_TIME_REFERENCE_RECHECK_REQUIRED","EXPLICIT_PRUNE_TRANSACTION_REQUIRED");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate snapshot;
    private final TransactionTemplate barrier;
    private final Options options;
    private final Clock clock;
    boolean enabled() { return options.enabled(); }
    boolean dryRun() { return options.dryRun(); }
    Instant cutoff() { return clock.instant().minus(Duration.ofDays(options.rawDays())).truncatedTo(ChronoUnit.DAYS); }

    @Autowired
    public RetentionPreview(JdbcTemplate jdbc,PlatformTransactionManager transactions,
            @Value("${autopulse.retention.enabled:false}") boolean enabled,
            @Value("${autopulse.retention.dry-run:true}") boolean dryRun,
            @Value("${autopulse.retention.raw-days:30}") int rawDays,
            @Value("${autopulse.retention.receipt-hold-hours:96}") int receiptHoldHours) {
        this(jdbc,transactions,new Options(enabled,dryRun,rawDays,receiptHoldHours),Clock.systemUTC());
    }
    public RetentionPreview(JdbcTemplate jdbc,PlatformTransactionManager transactions,Options options,Clock clock) {
        this.jdbc=Objects.requireNonNull(jdbc); this.options=Objects.requireNonNull(options); this.clock=Objects.requireNonNull(clock);
        snapshot=new TransactionTemplate(transactions); snapshot.setReadOnly(true); snapshot.setTimeout(10);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        barrier=new TransactionTemplate(transactions); barrier.setTimeout(10);
        barrier.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        barrier.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Page report(UUID vehicle,int limit,Cursor cursor) {
        Objects.requireNonNull(vehicle,"vehicle is required");
        if (limit<1 || limit>100) throw new IllegalArgumentException("Retention preview limit must be 1..100");
        // Disabled means zero DB/transaction calls, not an automatic empty-history scan.
        if (!options.enabled()) return new Page("DISABLED",vehicle,null,null,null,null,List.of(),List.of(),0,0,
                new ReplayCoverage("NOT_EVALUATED",0,0,0,0),PREREQUISITES,null);
        var context=context(vehicle,limit,cursor);
        return snapshot.execute(tx -> read(vehicle,limit,cursor,context.asOf(),context.cutoff(),context.receiptBefore()));
    }
    /** Explicit bounded inspection under processing/policy/receipt barriers; no DML or pruning. */
    public BarrierPage reportUnderBarrier(UUID vehicle,int limit,Cursor cursor) {
        Objects.requireNonNull(vehicle,"vehicle is required");
        if (limit<1 || limit>100) throw new IllegalArgumentException("Retention preview limit must be 1..100");
        if (!options.enabled()) return new BarrierPage("DISABLED",report(vehicle,limit,cursor),List.of(),List.of());
        var context=context(vehicle,limit,cursor);
        return barrier.execute(tx -> inspectLocked(vehicle,limit,cursor,context));
    }
    private BarrierPage inspectLocked(UUID vehicle,int limit,Cursor cursor,Context context) {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtextextended(cast(? as text),902))",Boolean.class,vehicle.toString())))
                return new BarrierPage("BUSY",null,List.of(),List.of());
            // Match stage lock order: processing lock, existing policy, receipt.
            var policy=jdbc.queryForList("select vehicle_id from trip_policy where vehicle_id=? for share skip locked",vehicle);
            if (policy.isEmpty() && exists("select exists(select 1 from trip_policy where vehicle_id=?)",vehicle)) return new BarrierPage("BUSY",null,List.of(),List.of());
            var activity=jdbc.queryForList("select vehicle_id from trip_vehicle_activity where vehicle_id=? for update skip locked",vehicle);
            if (activity.isEmpty()) {
                if (!exists("select exists(select 1 from vehicle where id=?)",vehicle)) throw new IllegalArgumentException("Unknown retention preview vehicle");
                if (!exists("select exists(select 1 from trip_vehicle_activity where vehicle_id=?)",vehicle)) throw new IllegalStateException("Retention receipt barrier registration missing");
                return new BarrierPage("BUSY",null,List.of(),List.of());
            }
            // READ COMMITTED gives fresh statement snapshots AFTER lock acquisition,
            // rather than reusing a snapshot established before another writer commits.
            var page=read(vehicle,limit,cursor,context.asOf(),context.cutoff(),context.receiptBefore());
            var references=pinReferences(page.readings());
            if (references==null) return new BarrierPage("BUSY",null,List.of(),List.of());
            if (!pinReadings(vehicle,page.readings())) return new BarrierPage("BUSY",null,List.of(),List.of());
            if (!referencesUnchanged(page.readings(),references)) return new BarrierPage("BUSY",null,List.of(),List.of());
            // Authoritative policy refresh AFTER all selected locks and gap checks.
            // Never execute a deletion using the preliminary pre-pin policy page.
            page=read(vehicle,limit,cursor,context.asOf(),context.cutoff(),context.receiptBefore());
            return new BarrierPage("CHECKED_NOT_AUTHORIZED",page,archiveChecks(vehicle,page.readings()),referenceChecks(vehicle,page.readings()));
    }
    /** Explicit one-page write; only disabled=false AND dryRun=false can delete. */
    public PrunePage prunePage(UUID vehicle,int limit,Cursor cursor) {
        Objects.requireNonNull(vehicle,"vehicle is required");
        if (limit<1 || limit>100) throw new IllegalArgumentException("Retention prune limit must be 1..100");
        if (!options.enabled()) return new PrunePage("DISABLED",0,0,true,null);
        var context=context(vehicle,limit,cursor);
        return barrier.execute(tx -> {
            var checked=inspectLocked(vehicle,limit,cursor,context);
            if (checked.preview()==null) return new PrunePage("BUSY",0,0,false,cursor);
            var page=checked.preview(); var ids=new ArrayList<UUID>();
            for (int i=0;i<page.readings().size();i++) {
                var r=page.readings().get(i); var a=checked.archiveChecks().get(i); var ref=checked.referenceChecks().get(i);
                if (r.policyCandidate() && a.state().equals("VERIFIED_COVERAGE") && ref.workState().equals("MATCHED")
                        && ref.linkState().equals("MATCHED") && !ref.correctionReference()) ids.add(r.readingId());
            }
            if (options.dryRun()) return new PrunePage("DRY_RUN",ids.size(),0,page.nextCursor()==null,page.nextCursor());
            if (ids.isEmpty()) return new PrunePage("CHECKED",0,0,page.nextCursor()==null,page.nextCursor());
            var last=page.readings().getLast();
            var boundary=jdbc.queryForMap("select recorded_at,device_id,boot_id,sequence_number from telemetry_reading where id=?",last.readingId());
            jdbc.queryForObject("select set_config('autopulse.retention_write','on',true)",String.class);
            for (var setting:Map.of("as_of",context.asOf(),"cutoff",context.cutoff(),"receipt_before",context.receiptBefore()).entrySet())
                jdbc.queryForObject("select set_config(?, ?, true)",String.class,"autopulse.retention_"+setting.getKey(),setting.getValue().toString());
            var args=new ArrayList<Object>(); args.add(vehicle); args.addAll(ids);
            int deleted=jdbc.update("delete from telemetry_reading where vehicle_id=? and id in ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",args.toArray());
            if (deleted!=ids.size()) throw new IllegalStateException("Retention deletion set changed; transaction rolled back");
            Cursor next=null;
            if (page.nextCursor()!=null) {
                var anchors=jdbc.queryForList("select id from telemetry_reading where vehicle_id=? and (recorded_at,device_id,boot_id,sequence_number)<=(?,?,?,?) order by recorded_at desc,device_id desc,boot_id desc,sequence_number desc limit 1",
                        UUID.class,vehicle,boundary.get("recorded_at"),boundary.get("device_id"),boundary.get("boot_id"),boundary.get("sequence_number"));
                if (!anchors.isEmpty()) next=new Cursor(vehicle,page.asOf(),jdbc.queryForObject("select generation::text from vehicle_read_generation where vehicle_id=?",String.class,vehicle),
                        limit,options.rawDays(),options.receiptHoldHours(),page.nextCursor().policyRevision(),page.nextCursor().primaryDevice(),anchors.getFirst());
            }
            // No surviving earlier anchor means restart at the new first raw row.
            return new PrunePage("PRUNED",ids.size(),deleted,page.nextCursor()==null,next);
        });
    }
    private ReferencePins pinReferences(List<Reading> readings) {
        if (readings.isEmpty()) return new ReferencePins(0,0);
        String placeholders=String.join(",",Collections.nCopies(readings.size(),"?"));
        var ids=readings.stream().map(Reading::readingId).toList();
        var args=new ArrayList<Object>(ids); args.addAll(ids);
        // Closed table names only. Every step skips contention: arbitrary writers
        // may already hold a reference/parent while waiting on our receipt lock.
        // Count in the same statement snapshot to distinguish missing from busy.
        var counts=new ArrayList<Long>();
        for (String table:List.of("trip_work_item","trip_reading_link")) {
            String selected=" from "+table+" where reading_id in ("+placeholders+")";
            Long locked=jdbc.queryForObject("with reference_locks as materialized (select reading_id"
                    +selected+" order by reading_id for share skip locked) select case when (select count(*) from reference_locks)=(select count(*)"
                    +selected+") then (select count(*) from reference_locks) end",Long.class,args.toArray());
            if (locked==null) return null;
            counts.add(locked);
        }
        // Stable link rows identify the exact parents. Match reference writers'
        // parent-before-raw order, never wait, and do not lock unrelated trips.
        String parents=" from trip_record where id in (select trip_id from trip_reading_link where reading_id in ("+placeholders+"))";
        boolean pinned=Boolean.TRUE.equals(jdbc.queryForObject("with reference_parent_locks as materialized (select id"
                +parents+" order by id for share skip locked) select (select count(*) from reference_parent_locks)=(select count(*)"
                +parents+")",Boolean.class,args.toArray()));
        return pinned?new ReferencePins(counts.get(0),counts.get(1)):null;
    }
    private boolean referencesUnchanged(List<Reading> readings,ReferencePins pins) {
        if (readings.isEmpty()) return true;
        String placeholders=String.join(",",Collections.nCopies(readings.size(),"?"));
        var ids=readings.stream().map(Reading::readingId).toList();
        var args=new ArrayList<Object>(ids); args.add(pins.work()); args.addAll(ids); args.add(pins.links());
        // A missing reference can be inserted after reference pinning but BEFORE
        // raw acquisition. Existing rows cannot disappear/change while pinned;
        // changed counts mean a new, unpinned reference: discard and retry. Once
        // raw is pinned, its exact-original guard blocks further new references.
        return Boolean.TRUE.equals(jdbc.queryForObject("select (select count(*) from trip_work_item where reading_id in ("
                +placeholders+"))=? and (select count(*) from trip_reading_link where reading_id in ("+placeholders+"))=?",
                Boolean.class,args.toArray()));
    }
    private List<ReferenceCheck> referenceChecks(UUID vehicle,List<Reading> readings) {
        if (readings.isEmpty()) return List.of();
        String values=String.join(",",Collections.nCopies(readings.size(),"(?::uuid,?::integer)"));
        var args=new ArrayList<Object>();
        for (int i=0;i<readings.size();i++) { args.add(readings.get(i).readingId()); args.add(i); }
        args.add(vehicle);
        // One result per pinned row. EXISTS uses the reading-first correction
        // index; never expand a selected reading into every historical replay.
        return jdbc.query("with reference_requested(id,ordinal) as (values "+values+") "+"""
                select q.id,
                  case when r.id is null then 'RAW_MISSING'
                    when e.reading_id is null then 'REPLAY_MISSING'
                    when w.reading_id is null then 'MISSING'
                    when w.vehicle_id<>e.vehicle_id then 'OWNER_MISMATCH'
                    when w.status<>'PROCESSED' then 'NOT_PROCESSED'
                    else 'MATCHED' end work_state,
                  case when r.id is null then 'RAW_MISSING'
                    when e.reading_id is null then 'REPLAY_MISSING'
                    when l.reading_id is null then 'MISSING'
                    when t.vehicle_id is distinct from e.vehicle_id then 'OWNER_MISMATCH'
                    when m.reading_id is null then 'RETAINED_MEMBERSHIP_MISSING'
                    when m.trip_id<>l.trip_id then 'RETAINED_OWNER_MISMATCH'
                    else 'MATCHED' end link_state,
                  exists(select 1 from trip_correction_reading c where c.reading_id=q.id)
                from reference_requested q
                left join telemetry_reading r on r.id=q.id and r.vehicle_id=?
                left join telemetry_replay_identity e on e.reading_id=r.id
                left join trip_work_item w on w.reading_id=q.id
                left join trip_reading_link l on l.reading_id=q.id
                left join trip_record t on t.id=l.trip_id
                left join trip_retained_evidence m on m.reading_id=q.id
                order by q.ordinal
                """,(r,n) -> new ReferenceCheck(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getBoolean(4)),args.toArray());
    }
    private boolean pinReadings(UUID vehicle,List<Reading> readings) {
        if (readings.isEmpty()) return true;
        var args=new ArrayList<Object>(); args.add(vehicle);
        readings.forEach(r -> args.add(r.readingId()));
        // A raw writer can already hold a row and be waiting on our receipt lock.
        // NEVER wait here: skip contention, discard the report and release all locks.
        // Lock returned IDs only, not the lookahead, and use deterministic UUID order.
        var locked=jdbc.queryForList("select id from telemetry_reading where vehicle_id=? and id in ("
                +String.join(",",Collections.nCopies(readings.size(),"?"))+") order by id for update skip locked",UUID.class,args.toArray());
        return locked.size()==readings.size();
    }
    /** Fresh bounded statement, not a frozen archive or permission to delete later. */
    private List<ArchiveCheck> archiveChecks(UUID vehicle,List<Reading> readings) {
        if (readings.isEmpty()) return List.of();
        // IDs come only from this inspected page; never fetch payloads into Java or
        // expand the scan to all members of the owning trip. Preserve page order.
        String values=String.join(",",Collections.nCopies(readings.size(),"(?::uuid,?::integer)"));
        var args=new ArrayList<Object>();
        for (int i=0;i<readings.size();i++) { args.add(readings.get(i).readingId()); args.add(i); }
        args.add(vehicle);
        return jdbc.query("with requested(id,ordinal) as (values "+values+") "+"""
                select q.id,
                  case when r.id is not null and e.reading_id is not null
                    and (r.id,r.vehicle_id,r.device_id,r.boot_id,r.sequence_number,r.recorded_at,r.received_at,r.source,r.payload)
                      is not distinct from
                        (e.reading_id,e.vehicle_id,e.device_id,e.boot_id,e.sequence_number,e.recorded_at,e.received_at,e.source,e.payload)
                    and ready.trip_id is not null and r.device_id=ready.primary_device_id and r.source=ready.source
                    and r.recorded_at between t.started_at and t.ended_at then ready.trip_id end verified_trip,
                  case when r.id is null then 'RAW_MISSING'
                    when e.reading_id is null then 'REPLAY_MISSING'
                    when (r.id,r.vehicle_id,r.device_id,r.boot_id,r.sequence_number,r.recorded_at,r.received_at,r.source,r.payload)
                      is distinct from
                        (e.reading_id,e.vehicle_id,e.device_id,e.boot_id,e.sequence_number,e.recorded_at,e.received_at,e.source,e.payload)
                      then 'REPLAY_MISMATCH'
                    when m.reading_id is null then 'RETAINED_MEMBERSHIP_MISSING'
                    when ready.trip_id is null then 'ARCHIVE_NOT_VERIFIED'
                    when r.device_id<>ready.primary_device_id or r.source<>ready.source
                      or r.recorded_at not between t.started_at and t.ended_at then 'ARCHIVE_PROVENANCE_MISMATCH'
                    else 'VERIFIED_COVERAGE' end coverage_state
                from requested q
                left join telemetry_reading r on r.id=q.id and r.vehicle_id=?
                left join telemetry_replay_identity e on e.reading_id=q.id
                left join trip_retained_evidence m on m.reading_id=q.id
                left join trip_verified_archive ready on ready.trip_id=m.trip_id and ready.vehicle_id=r.vehicle_id
                left join trip_record t on t.id=ready.trip_id
                order by q.ordinal
                """,(r,n) -> new ArchiveCheck(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3)),args.toArray());
    }
    private Context context(UUID vehicle,int limit,Cursor cursor) {
        Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (cursor!=null && (!cursor.vehicle().equals(vehicle) || cursor.limit()!=limit || cursor.rawDays()!=options.rawDays()
                || cursor.receiptHoldHours()!=options.receiptHoldHours() || cursor.asOf().isAfter(now)
                || Duration.between(cursor.asOf(),now).compareTo(Duration.ofMinutes(15))>=0))
            throw new IllegalArgumentException("Retention preview context changed or expired; restart the report");
        Instant asOf=cursor==null ? now : cursor.asOf();
        Instant cutoff=asOf.minus(Duration.ofDays(options.rawDays())).truncatedTo(ChronoUnit.DAYS);
        Instant receiptBefore=asOf.minus(Duration.ofHours(options.receiptHoldHours()));
        return new Context(asOf,cutoff,receiptBefore);
    }
    private Page read(UUID vehicle,int limit,Cursor cursor,Instant asOf,Instant cutoff,Instant receiptBefore) {
        var generations=jdbc.queryForList("select generation::text from vehicle_read_generation where vehicle_id=?",String.class,vehicle);
        if (generations.isEmpty()) throw new IllegalArgumentException("Unknown retention preview vehicle");
        String generation=generations.getFirst();
        if (cursor!=null && !cursor.generation().equals(generation)) throw changed();
        var policies=jdbc.queryForList("select primary_device_id,revision from trip_policy where vehicle_id=?",vehicle);
        UUID primary=policies.isEmpty() ? null : (UUID)policies.getFirst().get("primary_device_id");
        long revision=policies.isEmpty() ? 0 : ((Number)policies.getFirst().get("revision")).longValue();
        if (cursor!=null && (revision!=cursor.policyRevision() || !Objects.equals(primary,cursor.primaryDevice()))) throw changed();
        var protections=new ArrayList<String>();
        if (primary==null) protections.add("PRIMARY_POLICY_MISSING");
        var replayBackfill=jdbc.queryForList("select complete from telemetry_replay_backfill where vehicle_id=?",Boolean.class,vehicle);
        String replayBackfillState=replayBackfill.isEmpty()?"MISSING":replayBackfill.getFirst()?"COMPLETE":"INCOMPLETE";
        if (!replayBackfillState.equals("COMPLETE")) protections.add("REPLAY_BACKFILL_"+replayBackfillState);
        if (!exists("select exists(select 1 from trip_backfill_progress where vehicle_id=? and complete)",vehicle)) protections.add("BACKFILL_INCOMPLETE");
        if (!exists("select exists(select 1 from trip_vehicle_activity where vehicle_id=? and scan_complete)",vehicle)) protections.add("ACTIVITY_SCAN_INCOMPLETE");
        var progress=jdbc.queryForList("select cursor_at,policy_revision,needs_review from trip_projection_progress where vehicle_id=?",vehicle);
        Instant projectedThrough=null;
        if (progress.isEmpty()) protections.add("PROJECTION_MISSING");
        else {
            var p=progress.getFirst();
            if (p.get("cursor_at")!=null) projectedThrough=((Timestamp)p.get("cursor_at")).toInstant();
            if (p.get("needs_review").equals(true)) protections.add("PROJECTION_REVIEW_REQUIRED");
            if (((Number)p.get("policy_revision")).longValue()!=revision) protections.add("POLICY_CHANGED");
        }
        if (!exists("select exists(select 1 from trip_processing_control where vehicle_id=? and state='IDLE')",vehicle)) protections.add("WORKER_NOT_IDLE_OR_UNKNOWN");
        if (exists("select exists(select 1 from trip_work_item where vehicle_id=? and status in ('PENDING','REVIEW'))",vehicle)) protections.add("PENDING_OR_REVIEW_WORK");
        if (exists("select exists(select 1 from trip_correction_plan where vehicle_id=? and phase in ('TRIPS','INPUTS','READY'))",vehicle)) protections.add("CORRECTION_PENDING");
        if (exists("select exists(select 1 from trip_correction_publication where vehicle_id=? and phase not in ('DONE','ABANDONED'))",vehicle)) protections.add("PUBLICATION_PENDING");
        if (exists("select exists(select 1 from trip_record where vehicle_id=? and status in ('CANDIDATE','ACTIVE','REBUILDING'))",vehicle)) protections.add("OPEN_OR_REBUILDING_TRIP");
        if (exists("select exists(select 1 from trip_record where vehicle_id=? and status='COMPLETED' and finalization='PROVISIONAL')",vehicle)) protections.add("PROVISIONAL_TRIP");
        var latest=jdbc.queryForList("select id from telemetry_reading where vehicle_id=? order by recorded_at desc,device_id desc,boot_id desc,sequence_number desc limit 1",UUID.class,vehicle);
        UUID latestId=latest.isEmpty() ? null : latest.getFirst();
        var args=new ArrayList<Object>(List.of(vehicle,Timestamp.from(cutoff)));
        String after="";
        if (cursor!=null) {
            var anchors=jdbc.queryForList("select recorded_at,device_id,boot_id,sequence_number from telemetry_reading where vehicle_id=? and id=?",vehicle,cursor.anchor());
            if (anchors.isEmpty()) throw changed();
            var a=anchors.getFirst(); after=" and (recorded_at,device_id,boot_id,sequence_number)>(?,?,?,?)";
            Collections.addAll(args,a.get("recorded_at"),a.get("device_id"),a.get("boot_id"),a.get("sequence_number"));
        }
        args.add(limit+1);
        var rows=jdbc.queryForList("""
                with page as materialized (
                  select id,vehicle_id,recorded_at,received_at,device_id,boot_id,sequence_number,source from telemetry_reading
                  where vehicle_id=? and recorded_at<?
                """+after+" order by recorded_at,device_id,boot_id,sequence_number limit ?) "+"""
                select p.*,w.status as work_status,
                  case when e.device_id is null then 'MISSING'
                    when e.reading_id=p.id and e.vehicle_id=p.vehicle_id and e.recorded_at=p.recorded_at
                      and e.received_at=p.received_at and e.source=p.source and e.payload=r.payload then 'MATCHED'
                    else 'MISMATCH' end as replay_identity_state,
                  coalesce(t.status='COMPLETED' and t.finalization='FINAL' and p.recorded_at between t.started_at and t.ended_at
                    and t.primary_device_id=p.device_id and t.source=p.source,false) as final_evidence
                from page p join telemetry_reading r on r.id=p.id
                left join telemetry_replay_identity e on e.device_id=p.device_id and e.boot_id=p.boot_id and e.sequence_number=p.sequence_number
                left join trip_work_item w on w.reading_id=p.id
                left join trip_reading_link l on l.reading_id=p.id left join trip_record t on t.id=l.trip_id
                order by p.recorded_at,p.device_id,p.boot_id,p.sequence_number
                """,args.toArray());
        boolean more=rows.size()>limit;
        var readings=new ArrayList<Reading>();
        for (var row : rows.stream().limit(limit).toList()) {
            var reasons=new ArrayList<>(protections);
            UUID id=(UUID)row.get("id"); Instant at=((Timestamp)row.get("recorded_at")).toInstant(),receipt=((Timestamp)row.get("received_at")).toInstant();
            String replayState=(String)row.get("replay_identity_state");
            if (!replayState.equals("MATCHED")) reasons.add("REPLAY_IDENTITY_"+replayState);
            if (!Objects.equals(primary,row.get("device_id"))) reasons.add("NON_PRIMARY_DEVICE");
            if (!receipt.isBefore(receiptBefore)) reasons.add("RECEIPT_QUARANTINE");
            if (!Objects.equals(row.get("work_status"),"PROCESSED")) reasons.add("READING_NOT_PROCESSED");
            if (!Boolean.TRUE.equals(row.get("final_evidence"))) reasons.add("FINAL_TRIP_EVIDENCE_REQUIRED");
            if (projectedThrough==null || !at.isBefore(projectedThrough)) reasons.add("PROJECTION_NOT_PAST_MEASUREMENT");
            if (id.equals(latestId)) reasons.add("LATEST_READING_PROTECTED");
            readings.add(new Reading(id,at,receipt,replayState,reasons.isEmpty(),List.copyOf(reasons)));
        }
        Cursor next=more ? new Cursor(vehicle,asOf,generation,limit,options.rawDays(),options.receiptHoldHours(),revision,primary,readings.getLast().readingId()) : null;
        var coverage=new ReplayCoverage(replayBackfillState,readings.size(),
                (int)readings.stream().filter(r -> r.replayIdentityState().equals("MATCHED")).count(),
                (int)readings.stream().filter(r -> r.replayIdentityState().equals("MISSING")).count(),
                (int)readings.stream().filter(r -> r.replayIdentityState().equals("MISMATCH")).count());
        return new Page("READ_ONLY_PREVIEW",vehicle,asOf,cutoff,receiptBefore,generation,List.copyOf(protections),List.copyOf(readings),
                (int)readings.stream().filter(Reading::policyCandidate).count(),0,coverage,PREREQUISITES,next);
    }
    private boolean exists(String sql,UUID vehicle) { return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,vehicle)); }
    private static IllegalStateException changed() { return new IllegalStateException("Retention preview data/policy changed; restart the report"); }
}
