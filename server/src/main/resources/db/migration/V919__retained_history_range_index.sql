-- Metadata-only range access for bounded clipped-trip queries. No payload copy,
-- archive publication, expiration, FK removal or pruning is performed here.
CREATE INDEX replay_device_history_range
    ON telemetry_replay_identity(vehicle_id,device_id,recorded_at,reading_id);
