-- Exact JSONB storage is deliberately conservative: this is not a compact hash
-- and not permission to prune. No raw payloads are copied during migration.
CREATE TABLE telemetry_replay_identity (
    device_id UUID NOT NULL REFERENCES device(id),
    boot_id UUID NOT NULL,
    sequence_number NUMERIC NOT NULL CHECK (sequence_number>=0 AND sequence_number=trunc(sequence_number)),
    reading_id UUID NOT NULL UNIQUE,
    vehicle_id UUID NOT NULL REFERENCES vehicle(id),
    recorded_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    source TEXT NOT NULL CHECK (source IN ('SIMULATED','OBD')),
    payload JSONB NOT NULL,
    PRIMARY KEY (device_id,boot_id,sequence_number),
    FOREIGN KEY (vehicle_id,device_id) REFERENCES device(vehicle_id,id)
);
-- Deliberately no FK back to raw: identity must eventually outlive raw storage.
CREATE FUNCTION protect_replay_identity() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'accepted replay identity is immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER replay_identity_immutable BEFORE UPDATE OR DELETE ON telemetry_replay_identity
    FOR EACH ROW EXECUTE FUNCTION protect_replay_identity();

CREATE FUNCTION capture_replay_identity() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO telemetry_replay_identity
        (device_id,boot_id,sequence_number,reading_id,vehicle_id,recorded_at,received_at,source,payload)
    VALUES (NEW.device_id,NEW.boot_id,NEW.sequence_number,NEW.id,NEW.vehicle_id,
            NEW.recorded_at,NEW.received_at,NEW.source,NEW.payload);
    RETURN NEW;
END;
$$;
-- AFTER INSERT does not run for ON CONFLICT DO NOTHING. Capture rolls back with
-- raw/work/generation; an unexpected preexisting ledger identity fails closed.
CREATE TRIGGER telemetry_replay_capture AFTER INSERT ON telemetry_reading
    FOR EACH ROW EXECUTE FUNCTION capture_replay_identity();

CREATE TABLE telemetry_replay_backfill (
    vehicle_id UUID PRIMARY KEY REFERENCES vehicle(id),
    last_reading_id UUID,
    complete BOOLEAN NOT NULL DEFAULT FALSE,
    visited_rows BIGINT NOT NULL DEFAULT 0 CHECK (visited_rows>=0)
);
INSERT INTO telemetry_replay_backfill(vehicle_id) SELECT id FROM vehicle;
CREATE FUNCTION register_replay_backfill() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO telemetry_replay_backfill(vehicle_id) VALUES (NEW.id);
    RETURN NEW;
END;
$$;
CREATE TRIGGER vehicle_replay_backfill AFTER INSERT ON vehicle
    FOR EACH ROW EXECUTE FUNCTION register_replay_backfill();
