-- A simple ledger-only view keeps PK/native/range predicates indexable. Source
-- selection is explicit per correction page; legacy vehicles still use raw.
CREATE VIEW trip_ledger_reading AS
    SELECT reading_id AS id,vehicle_id,device_id,boot_id,sequence_number,
           recorded_at,received_at,source,payload
    FROM telemetry_replay_identity;
CREATE INDEX replay_correction_order ON telemetry_replay_identity
    (vehicle_id,device_id,recorded_at,boot_id DESC,sequence_number DESC);

-- Disjoint arms in one statement snapshot. Completing exact legacy identity
-- backfill is a source handoff, NOT permission to prune or reopen final trips.
CREATE OR REPLACE VIEW trip_correction_evidence AS
    SELECT link.replay_id,link.segment_id,link.reading_id
    FROM trip_correction_reading link
    JOIN trip_correction_segment segment ON segment.id=link.segment_id
    JOIN trip_correction_replay replay ON replay.id=link.replay_id
    JOIN trip_correction_plan plan ON plan.id=replay.plan_id
    JOIN telemetry_reading reading ON reading.id=link.reading_id
    WHERE replay.status IN ('RUNNING','STAGED') AND segment.status IN ('ACTIVE','COMPLETED')
      AND reading.recorded_at BETWEEN segment.started_at AND coalesce(segment.ended_at,segment.observed_through)
      AND NOT EXISTS(SELECT 1 FROM telemetry_replay_backfill b WHERE b.vehicle_id=plan.vehicle_id AND b.complete)
    UNION ALL
    SELECT link.replay_id,link.segment_id,link.reading_id
    FROM trip_correction_reading link
    JOIN trip_correction_segment segment ON segment.id=link.segment_id
    JOIN trip_correction_replay replay ON replay.id=link.replay_id
    JOIN trip_correction_plan plan ON plan.id=replay.plan_id
    JOIN telemetry_replay_backfill b ON b.vehicle_id=plan.vehicle_id AND b.complete
    JOIN telemetry_replay_identity reading ON reading.reading_id=link.reading_id
    WHERE replay.status IN ('RUNNING','STAGED') AND segment.status IN ('ACTIVE','COMPLETED')
      AND reading.recorded_at BETWEEN segment.started_at AND coalesce(segment.ended_at,segment.observed_through);

-- No payload scan/copy, raw FK removal, guard changes or automatic backfill.
