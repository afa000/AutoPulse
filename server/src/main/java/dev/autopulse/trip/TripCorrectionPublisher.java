package dev.autopulse.trip;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;

/** Internal paged publication. No scheduler or public reader may bypass its visibility gate. */
@Service
public class TripCorrectionPublisher {
    public record Page(UUID publicationId, String state, int visited) {}
    private static final UUID ZERO = new UUID(0,0);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public TripCorrectionPublisher(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager transactions) {
        this.jdbc = jdbc; this.mapper = mapper;
        transaction = new TransactionTemplate(transactions); transaction.setTimeout(10);
    }

    public Page publishPage(UUID vehicle, int limit) {
        if (vehicle == null || limit < 1 || limit > 100) throw new IllegalArgumentException("vehicle and limit 1..100 required");
        return transaction.execute(status -> publish(vehicle,limit));
    }

    private Page publish(UUID vehicle, int limit) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtextextended(cast(? as text),902))",
                Boolean.class,vehicle.toString()))) return new Page(null,"BUSY",0);
        var policies = jdbc.queryForList("select * from trip_policy where vehicle_id=? for share",vehicle);
        if (policies.isEmpty()) return new Page(null,"NO_PRIMARY_DEVICE",0);
        var policy = policies.getFirst();
        var activity = jdbc.queryForMap("select * from trip_vehicle_activity where vehicle_id=? for update",vehicle);
        var active = jdbc.queryForList("select * from trip_correction_publication where vehicle_id=? and phase not in ('DONE','ABANDONED') for update",vehicle);
        Map<String,Object> publication;
        if (active.isEmpty()) {
            var plans = jdbc.queryForList("select * from trip_correction_plan where vehicle_id=? and phase='READY' for update",vehicle);
            if (plans.isEmpty()) {
                var last = jdbc.queryForList("select id from trip_correction_publication where vehicle_id=? and phase='DONE' order by created_at desc,id desc limit 1",vehicle);
                return new Page(last.isEmpty() ? null : (UUID)last.getFirst().get("id"),last.isEmpty() ? "NO_READY_PLAN" : "DONE",0);
            }
            var plan = plans.getFirst(); UUID planId = (UUID)plan.get("id");
            if (plan.get("current_replay_id") == null) return new Page(null,"REPLAY_NOT_STAGED",0);
            var replay = jdbc.queryForMap("select * from trip_correction_replay where id=? for update",plan.get("current_replay_id"));
            if (!replay.get("status").equals("STAGED")) return new Page(null,"REPLAY_NOT_STAGED",0);
            if (!Objects.equals(plan.get("input_generation"),activity.get("ingest_generation"))) {
                jdbc.update("update trip_correction_replay set status='INVALIDATED' where id=?",replay.get("id"));
                jdbc.update("""
                        update trip_correction_plan set phase='TRIPS',current_replay_id=null,input_generation=?,
                        trip_cursor_at=null,trip_cursor_id=null,input_cursor_at=null,input_cursor_id=null where id=?
                        """,activity.get("ingest_generation"),planId);
                return new Page(null,"REPLAN_REQUIRED",0);
            }
            var progress = jdbc.queryForMap("select * from trip_projection_progress where vehicle_id=? for update",vehicle);
            var fold = mapper.readValue(replay.get("checkpoint").toString(),TripFold.Checkpoint.class);
            boolean invalid = !samePolicy(policy,plan) || !Objects.equals(progress.get("policy_revision"),plan.get("policy_revision"))
                    || java.time.Duration.between(((Timestamp)plan.get("window_start")).toInstant(),((Timestamp)plan.get("window_end")).toInstant())
                            .compareTo(TripReconciliation.MAX_AUTOMATIC_SPAN)>0
                    || !Objects.equals(progress.get("cursor_at"),plan.get("projection_cursor_at"))
                    || !Objects.equals(replay.get("input_generation"),plan.get("input_generation"))
                    || !Objects.equals(replay.get("window_start"),plan.get("window_start"))
                    || !Objects.equals(replay.get("window_end"),plan.get("window_end"))
                    || !Objects.equals(replay.get("planned_as_of"),plan.get("planned_as_of"))
                    || (((Timestamp)replay.get("read_to")).before((Timestamp)progress.get("cursor_at")) && fold.state().phase()!=TripRules.Phase.IDLE)
                    || exists("select exists(select 1 from trip_record where vehicle_id=? and finalization='FINAL' and started_at<=? and observed_through>=?)",
                            vehicle,replay.get("read_to"),replay.get("read_from"));
            if (invalid) {
                jdbc.update("update trip_correction_plan set phase='REVIEW_PUBLICATION' where id=?",planId);
                jdbc.update("update trip_correction_replay set status='INVALIDATED' where id=?",replay.get("id"));
                jdbc.update("update trip_projection_progress set needs_review=true where vehicle_id=?",vehicle);
                return new Page(null,"REVIEW_PUBLICATION",0);
            }
            UUID id = UUID.randomUUID();
            jdbc.update("insert into trip_correction_publication(id,vehicle_id,plan_id,replay_id,acceptance_fence,phase) values (?,?,?,?,?,'CAPTURE')",
                    id,vehicle,planId,replay.get("id"),activity.get("acceptance_counter"));
            return new Page(id,"CAPTURE",0);
        }
        publication = active.getFirst(); UUID id = (UUID)publication.get("id");
        var plan = jdbc.queryForMap("select * from trip_correction_plan where id=? for update",publication.get("plan_id"));
        // Once publication starts, newer input is follow-up work, not grounds for
        // abandoning a half-published projection. Policy changes fail closed.
        if (!samePolicy(policy,plan)) return new Page(id,"POLICY_CHANGED",0);
        var replay = jdbc.queryForMap("select * from trip_correction_replay where id=?",publication.get("replay_id"));
        var source=TripReadingSource.forVehicle(jdbc,vehicle);
        UUID cursor = publication.get("cursor_id") == null ? ZERO : (UUID)publication.get("cursor_id");
        List<Map<String,Object>> rows;
        String phase = publication.get("phase").toString(), next;
        switch (phase) {
            case "CAPTURE" -> {
                rows = jdbc.queryForList("""
                        select t.id,r.recorded_at as anchor_at,to_jsonb(t)::text as snapshot from trip_record t
                        join %s r on r.device_id=t.primary_device_id and r.boot_id=t.anchor_boot and r.sequence_number=t.anchor_sequence
                        where t.vehicle_id=? and t.status not in ('SUPERSEDED','REBUILDING')
                        and t.started_at<=? and t.observed_through>=? and t.id>? order by t.id limit ?
                        """.formatted(source.table()),vehicle,replay.get("read_to"),replay.get("read_from"),cursor,limit);
                for (var row : rows) jdbc.update("insert into trip_publication_original(publication_id,trip_id,anchor_at,snapshot) values (?,?,?,cast(? as jsonb))",
                        id,row.get("id"),row.get("anchor_at"),row.get("snapshot"));
                next="MAP";
            }
            case "MAP" -> {
                rows = segments(publication,cursor,limit);
                for (var row : rows) {
                    var candidates = jdbc.queryForList("""
                            select o.trip_id from trip_publication_original o where o.publication_id=?
                            and o.anchor_at between ? and ? and o.snapshot->>'source'=?
                            and not exists(select 1 from trip_publication_identity i where i.publication_id=o.publication_id and i.target_id=o.trip_id)
                            order by (o.snapshot->>'created_at')::timestamptz,o.trip_id limit 1
                            """,id,row.get("started_at"),row.get("ended_at")==null ? row.get("observed_through") : row.get("ended_at"),row.get("source"));
                    jdbc.update("insert into trip_publication_identity(publication_id,segment_id,target_id,retained) values (?,?,?,?)",
                            id,row.get("id"),candidates.isEmpty() ? UUID.randomUUID() : candidates.getFirst().get("trip_id"),!candidates.isEmpty());
                }
                next="RETIRE";
                if (rows.size()<limit && exists("""
                        select exists(select 1 from trip_correction_reading s join trip_reading_link l on l.reading_id=s.reading_id
                        where s.replay_id=? and not exists(select 1 from trip_publication_original o where o.publication_id=? and o.trip_id=l.trip_id))
                        """,replay.get("id"),id)) throw new IllegalStateException("correction crosses an uncaptured evidence boundary");
            }
            case "RETIRE" -> {
                rows = originals(id,cursor,limit);
                for (var row : rows) jdbc.update("update trip_record set status='REBUILDING' where id=?",row.get("id"));
                next="TRIPS";
            }
            case "TRIPS" -> {
                rows = jdbc.queryForList("""
                        select s.*,i.target_id,i.retained,o.snapshot::text as original from trip_publication_identity i
                        join trip_correction_segment s on s.id=i.segment_id
                        left join trip_publication_original o on o.publication_id=i.publication_id and o.trip_id=i.target_id
                        where i.publication_id=? and s.id>? order by s.id limit ?
                        """,id,cursor,limit);
                // Retiring every original first frees the one-open-trip invariant.
                for (var row : rows) save(vehicle,plan,row);
                next="OLD";
            }
            case "OLD" -> {
                rows = originals(id,cursor,limit);
                for (var row : rows) jdbc.update("""
                        update trip_record set status='SUPERSEDED',revision=(?::jsonb->>'revision')::bigint+1 where id=?
                        and not exists(select 1 from trip_publication_identity where publication_id=? and target_id=?)
                        """,row.get("snapshot").toString(),row.get("id"),id,row.get("id"));
                next="LINEAGE";
            }
            case "LINEAGE" -> {
                UUID other = publication.get("cursor_other")==null ? ZERO : (UUID)publication.get("cursor_other");
                rows = jdbc.queryForList("""
                        select o.trip_id as id,s.id as other,i.target_id,
                        exists(select 1 from trip_publication_identity keep where keep.publication_id=o.publication_id and keep.target_id=o.trip_id) as retained
                        from trip_publication_original o join trip_publication_identity i on i.publication_id=o.publication_id
                        join trip_correction_segment s on s.id=i.segment_id where o.publication_id=?
                        and o.trip_id<>i.target_id and (o.snapshot->>'started_at')::timestamptz<=coalesce(s.ended_at,s.observed_through)
                        and (o.snapshot->>'observed_through')::timestamptz>=s.started_at
                        and (o.trip_id,s.id)>(?,?) order by o.trip_id,s.id limit ?
                        """,id,cursor,other,limit);
                for (var row : rows) jdbc.update("insert into trip_correction_lineage(publication_id,parent_trip_id,child_trip_id,relation) values (?,?,?,?)",
                        id,row.get("id"),row.get("target_id"),Boolean.TRUE.equals(row.get("retained")) ? "SPLIT" : "SUPERSEDED");
                next="DELETE_LINKS";
            }
            case "DELETE_LINKS" -> {
                rows = jdbc.queryForList("""
                        select l.reading_id as id from trip_reading_link l join trip_publication_original o on o.trip_id=l.trip_id
                        where o.publication_id=? and l.reading_id>? order by l.reading_id limit ?
                        """,id,cursor,limit);
                for (var row : rows) jdbc.update("delete from trip_reading_link where reading_id=?",row.get("id"));
                next="INSERT_LINKS";
            }
            case "INSERT_LINKS" -> {
                rows = jdbc.queryForList("""
                        select l.reading_id as id,i.target_id from trip_correction_reading l
                        join trip_publication_identity i on i.segment_id=l.segment_id and i.publication_id=?
                        where l.replay_id=? and l.reading_id>? order by l.reading_id limit ?
                        """,id,replay.get("id"),cursor,limit);
                for (var row : rows) jdbc.update("insert into trip_reading_link(reading_id,trip_id) values (?,?)",row.get("id"),row.get("target_id"));
                next="ACK";
            }
            case "ACK" -> {
                rows = jdbc.queryForList("""
                        select w.reading_id as id,w.accepted_at from trip_work_item w join %s r on r.id=w.reading_id
                        where w.vehicle_id=? and r.device_id=? and r.recorded_at between ? and ? and w.status in ('PENDING','REVIEW')
                        and (w.acceptance_order is null or w.acceptance_order<=?) and w.reading_id>? order by w.reading_id limit ?
                        """.formatted(source.table()),vehicle,plan.get("primary_device_id"),replay.get("read_from"),replay.get("read_to"),publication.get("acceptance_fence"),cursor,limit);
                for (var row : rows) {
                    jdbc.update("update trip_work_item set status='PROCESSED',last_error=null,attempts=attempts+1 where reading_id=?",row.get("id"));
                    jdbc.update("update trip_vehicle_activity set last_eligible_receipt_at=greatest(last_eligible_receipt_at,?) where vehicle_id=?",row.get("accepted_at"),vehicle);
                }
                next="FINISH";
            }
            case "FINISH" -> { finish(vehicle,publication,plan,replay,source); return new Page(id,"DONE",0); }
            default -> throw new IllegalStateException("unknown publication phase " + phase);
        }
        boolean done = rows.size()<limit;
        var last = rows.isEmpty() ? null : rows.getLast();
        jdbc.update("update trip_correction_publication set phase=?,cursor_id=?,cursor_other=? where id=?",
                done ? next : phase,done ? null : last.get("id"),!done && phase.equals("LINEAGE") ? last.get("other") : null,id);
        return new Page(id,done ? next : phase,rows.size());
    }

    private void save(UUID vehicle, Map<String,Object> plan, Map<String,Object> row) {
        boolean retained = Boolean.TRUE.equals(row.get("retained"));
        long revision=1;
        if (retained) {
            var exact=mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            var old=exact.readTree(row.get("original").toString());
            revision=old.get("revision").longValue();
            // Compare the semantic snapshot, not the temporary REBUILDING status.
            boolean changed=false;
            for (String field : List.of("started_at","observed_through","ended_at","confirmed_at")) {
                var value=row.get(field); var previous=old.get(field);
                var before=previous==null || previous.isNull() ? null : java.time.OffsetDateTime.parse(previous.asText()).toInstant();
                if (!Objects.equals(before,value==null ? null : ((Timestamp)value).toInstant())) changed=true;
            }
            for (String field : List.of("status","source","completion_reason")) {
                var previous=old.get(field);
                if (!Objects.equals(previous==null || previous.isNull() ? null : previous.asText(),row.get(field))) changed=true;
            }
            var oldQuality=new java.util.TreeSet<String>(); old.get("quality").forEach(value -> oldQuality.add(value.asText()));
            var newQuality=new java.util.TreeSet<String>(); exact.readTree(row.get("quality").toString()).forEach(value -> newQuality.add(value.asText()));
            if (!old.get("summary").equals(exact.readTree(row.get("summary").toString()))
                    || !oldQuality.equals(newQuality)
                    || !old.get("algorithm_version").asText().equals(TripRules.VERSION)) changed=true;
            if (changed) revision++;
            jdbc.update("""
                    update trip_record set started_at=?,observed_through=?,ended_at=?,confirmed_at=?,source=?,status=?,completion_reason=?,
                    algorithm_version=?,revision=?,summary=cast(? as jsonb),quality=cast(? as jsonb) where id=?
                    """,row.get("started_at"),row.get("observed_through"),row.get("ended_at"),row.get("confirmed_at"),row.get("source"),row.get("status"),
                    row.get("completion_reason"),TripRules.VERSION,revision,row.get("summary").toString(),row.get("quality").toString(),row.get("target_id"));
        } else jdbc.update("""
                insert into trip_record(id,vehicle_id,primary_device_id,anchor_boot,anchor_sequence,started_at,observed_through,ended_at,confirmed_at,
                source,status,completion_reason,algorithm_version,summary,quality)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb))
                """,row.get("target_id"),vehicle,plan.get("primary_device_id"),row.get("anchor_boot"),row.get("anchor_sequence"),row.get("started_at"),
                row.get("observed_through"),row.get("ended_at"),row.get("confirmed_at"),row.get("source"),row.get("status"),row.get("completion_reason"),
                TripRules.VERSION,row.get("summary").toString(),row.get("quality").toString());
    }

    private void finish(UUID vehicle, Map<String,Object> publication, Map<String,Object> plan, Map<String,Object> replay, TripReadingSource source) {
        var progress=jdbc.queryForMap("select * from trip_projection_progress where vehicle_id=? for update",vehicle);
        if (!((Timestamp)replay.get("read_to")).before((Timestamp)progress.get("cursor_at"))) {
            var last=jdbc.queryForMap("""
                    select r.boot_id,r.sequence_number from %s r join trip_work_item w on w.reading_id=r.id
                    where r.vehicle_id=? and r.device_id=? and r.recorded_at=? and (w.acceptance_order is null or w.acceptance_order<=?)
                    order by r.boot_id desc,r.sequence_number desc limit 1
                    """.formatted(source.table()),vehicle,plan.get("primary_device_id"),replay.get("cursor_at"),publication.get("acceptance_fence"));
            var current=jdbc.queryForList("select target_id from trip_publication_identity where publication_id=? and segment_id=?",
                    publication.get("id"),replay.get("current_segment_id"));
            jdbc.update("""
                    update trip_projection_progress set cursor_at=?,cursor_boot=?,cursor_sequence=?,checkpoint=cast(? as jsonb),current_trip_id=? where vehicle_id=?
                    """,replay.get("cursor_at"),last.get("boot_id"),last.get("sequence_number"),replay.get("checkpoint").toString(),
                    current.isEmpty() ? null : current.getFirst().get("target_id"),vehicle);
        }
        if (exists("select exists(select 1 from trip_record where vehicle_id=? and status='REBUILDING')",vehicle))
            throw new IllegalStateException("publication left an unfinished trip");
        jdbc.update("update trip_projection_progress set needs_review=exists(select 1 from trip_work_item where vehicle_id=? and status='REVIEW') where vehicle_id=?",vehicle,vehicle);
        jdbc.update("update trip_correction_plan set phase='DONE' where id=?",plan.get("id"));
        jdbc.update("update trip_correction_publication set phase='DONE',cursor_id=null,cursor_other=null where id=?",publication.get("id"));
    }

    private List<Map<String,Object>> originals(UUID id, UUID cursor, int limit) {
        return jdbc.queryForList("select trip_id as id,snapshot from trip_publication_original where publication_id=? and trip_id>? order by trip_id limit ?",id,cursor,limit);
    }
    private List<Map<String,Object>> segments(Map<String,Object> publication, UUID cursor, int limit) {
        return jdbc.queryForList("select * from trip_correction_segment where replay_id=? and status in ('CANDIDATE','ACTIVE','COMPLETED') and id>? order by id limit ?",
                publication.get("replay_id"),cursor,limit);
    }
    private boolean exists(String sql,Object... args) { return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args)); }
    private static boolean samePolicy(Map<String,Object> policy,Map<String,Object> plan) {
        return Objects.equals(policy.get("revision"),plan.get("policy_revision")) && Objects.equals(policy.get("primary_device_id"),plan.get("primary_device_id"));
    }
}
