-- Read-path handoff only. Do not remove raw FKs/guards, scan/copy payloads or prune.
-- Eligibility is NOT publication visibility or authorization to delete raw rows.
CREATE VIEW trip_verified_archive AS
    SELECT t.id AS trip_id,t.vehicle_id,t.primary_device_id,t.source,a.retained_rows
    FROM trip_record t
    JOIN trip_retained_evidence_progress a ON a.trip_id=t.id AND a.complete
    JOIN trip_aggregate_progress b ON b.trip_id=t.id AND b.complete AND b.visited_rows=a.retained_rows
    JOIN trip_aggregate_verification v ON v.trip_id=t.id AND v.state='VERIFIED' AND v.visited_rows=a.retained_rows
    WHERE t.status='COMPLETED' AND t.finalization='FINAL';

-- The two arms are disjoint by trip eligibility, in the SAME statement snapshot.
-- Legacy, active, provisional and unverified trips keep the original raw-link path.
CREATE OR REPLACE VIEW trip_evidence AS
    SELECT link.trip_id,link.reading_id FROM trip_reading_link link
    JOIN trip_visible_record trip ON trip.id=link.trip_id
    JOIN telemetry_reading reading ON reading.id=link.reading_id
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
