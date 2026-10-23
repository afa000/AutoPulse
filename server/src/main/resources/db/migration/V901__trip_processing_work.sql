-- Forward-only: existing local databases may already have applied V900.
-- This is the durable handoff foundation, not the trip projection itself.
ALTER TABLE device ADD CONSTRAINT device_vehicle_identity UNIQUE (vehicle_id, id);
CREATE INDEX telemetry_backfill_page ON telemetry_reading (vehicle_id, id);

CREATE TABLE trip_policy (
    vehicle_id UUID PRIMARY KEY REFERENCES vehicle(id),
    primary_device_id UUID NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    FOREIGN KEY (vehicle_id, primary_device_id) REFERENCES device(vehicle_id, id)
);

CREATE TABLE trip_work_item (
    reading_id UUID PRIMARY KEY REFERENCES telemetry_reading(id),
    vehicle_id UUID NOT NULL REFERENCES vehicle(id),
    accepted_at TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSED', 'REVIEW')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error TEXT
);

CREATE INDEX trip_pending_work ON trip_work_item (next_attempt_at, vehicle_id, reading_id)
    WHERE status = 'PENDING';
CREATE INDEX trip_vehicle_work ON trip_work_item (vehicle_id, status, next_attempt_at, reading_id);

-- Existing rows are discovered in bounded UUID pages, not copied in one big migration.
CREATE TABLE trip_backfill_progress (
    vehicle_id UUID PRIMARY KEY REFERENCES vehicle(id),
    last_reading_id UUID,
    complete BOOLEAN NOT NULL DEFAULT FALSE,
    visited_rows BIGINT NOT NULL DEFAULT 0 CHECK (visited_rows >= 0)
);
INSERT INTO trip_backfill_progress (vehicle_id) SELECT id FROM vehicle;

CREATE FUNCTION enqueue_trip_reading() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO trip_work_item (reading_id, vehicle_id, accepted_at, next_attempt_at)
    VALUES (NEW.id, NEW.vehicle_id, NEW.received_at,
            greatest(NEW.received_at, NEW.recorded_at - interval '2 seconds'));
    RETURN NEW;
END;
$$;
CREATE TRIGGER telemetry_trip_work AFTER INSERT ON telemetry_reading
    FOR EACH ROW EXECUTE FUNCTION enqueue_trip_reading();

CREATE FUNCTION register_trip_backfill() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO trip_backfill_progress (vehicle_id) VALUES (NEW.id);
    RETURN NEW;
END;
$$;
CREATE TRIGGER vehicle_trip_backfill AFTER INSERT ON vehicle
    FOR EACH ROW EXECUTE FUNCTION register_trip_backfill();

-- Both triggers participate in the caller's transaction. A retry that inserts
-- nothing creates no work, and rollback removes raw input and work together.
