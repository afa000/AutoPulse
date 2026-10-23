-- Metadata seek only; no ownership publication, payload backfill or raw removal.
CREATE INDEX replay_vehicle_history_range
    ON telemetry_replay_identity(vehicle_id,recorded_at,reading_id);
