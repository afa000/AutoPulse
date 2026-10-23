-- Find only nearby timeout boundaries, not all vehicle history after each reading.
CREATE INDEX trip_timeout_continuation ON trip_record(vehicle_id,primary_device_id,ended_at DESC)
    WHERE status='COMPLETED' AND completion_reason='SIGNAL_TIMEOUT';
CREATE INDEX trip_discarded_continuation ON trip_record(vehicle_id,primary_device_id,observed_through DESC)
    WHERE status='DISCARDED';
