-- Read-source handoff only. No membership copy, existing-row scan/rewrite,
-- raw FK removal, guard relaxation, availability publication or pruning.
-- All three arms are disjoint in the same statement snapshot. Publication
-- visibility remains required even when identity or archive coverage is ready.
CREATE OR REPLACE VIEW trip_evidence AS
    SELECT link.trip_id,link.reading_id FROM trip_reading_link link
    JOIN trip_visible_record trip ON trip.id=link.trip_id
    JOIN telemetry_reading reading ON reading.id=link.reading_id
    WHERE trip.status IN ('ACTIVE','COMPLETED')
      AND reading.recorded_at BETWEEN trip.started_at AND coalesce(trip.ended_at,trip.observed_through)
      AND NOT EXISTS(SELECT 1 FROM telemetry_replay_backfill b WHERE b.vehicle_id=trip.vehicle_id AND b.complete)
      AND NOT EXISTS(SELECT 1 FROM trip_verified_archive ready WHERE ready.trip_id=trip.id)
    UNION ALL
    SELECT link.trip_id,link.reading_id FROM trip_reading_link link
    JOIN trip_visible_record trip ON trip.id=link.trip_id
    JOIN telemetry_replay_backfill b ON b.vehicle_id=trip.vehicle_id AND b.complete
    JOIN telemetry_replay_identity reading ON reading.reading_id=link.reading_id
    WHERE trip.status IN ('ACTIVE','COMPLETED')
      AND reading.recorded_at BETWEEN trip.started_at AND coalesce(trip.ended_at,trip.observed_through)
      AND NOT EXISTS(SELECT 1 FROM trip_verified_archive ready WHERE ready.trip_id=trip.id)
    UNION ALL
    SELECT retained.trip_id,retained.reading_id FROM trip_retained_evidence retained
    JOIN trip_verified_archive ready ON ready.trip_id=retained.trip_id
    JOIN trip_visible_record trip ON trip.id=ready.trip_id
    JOIN telemetry_replay_identity reading ON reading.reading_id=retained.reading_id
    WHERE reading.vehicle_id=ready.vehicle_id AND reading.device_id=ready.primary_device_id AND reading.source=ready.source
      AND reading.recorded_at BETWEEN trip.started_at AND trip.ended_at;
