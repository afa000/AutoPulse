-- A constant-size round-robin checkpoint, not a scan of raw telemetry.
CREATE TABLE trip_processing_scan (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    last_vehicle_id UUID
);
INSERT INTO trip_processing_scan(singleton) VALUES (TRUE);
CREATE INDEX trip_due_pending_work ON trip_work_item(vehicle_id,next_attempt_at,reading_id) WHERE status='PENDING';
CREATE INDEX trip_unresolved_work ON trip_work_item(vehicle_id,reading_id) WHERE status='REVIEW';
