-- Verified trip history fixes vehicle/device and preserves the complete ASC
-- native tuple, including arbitrary NUMERIC sequences and microsecond times.
-- Regular metadata index build: no payload copy, automatic archive or pruning.
CREATE INDEX replay_trip_history_order ON telemetry_replay_identity
    (vehicle_id,device_id,recorded_at,boot_id,sequence_number);
