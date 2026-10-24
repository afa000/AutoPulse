CREATE TABLE trip_record (
    id UUID PRIMARY KEY,
    vehicle_id UUID NOT NULL REFERENCES vehicle(id),
    primary_device_id UUID NOT NULL,
    anchor_boot UUID NOT NULL,
    anchor_sequence NUMERIC NOT NULL CHECK (anchor_sequence >= 0 AND anchor_sequence=trunc(anchor_sequence)),
    started_at TIMESTAMPTZ NOT NULL,
    observed_through TIMESTAMPTZ NOT NULL CHECK (observed_through >= started_at),
    ended_at TIMESTAMPTZ,
    confirmed_at TIMESTAMPTZ,
    source TEXT NOT NULL CHECK (source IN ('SIMULATED','OBD')),
    status TEXT NOT NULL CHECK (status IN ('CANDIDATE','ACTIVE','COMPLETED','DISCARDED')),
    completion_reason TEXT CHECK (completion_reason IN ('ENGINE_OFF','SIGNAL_TIMEOUT','SOURCE_CHANGED','DURATION_LIMIT')),
    algorithm_version TEXT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    finalization TEXT NOT NULL DEFAULT 'PROVISIONAL' CHECK (finalization IN ('PROVISIONAL','FINAL')),
    summary JSONB NOT NULL CHECK (jsonb_typeof(summary)='object'),
    quality JSONB NOT NULL CHECK (jsonb_typeof(quality)='array'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (vehicle_id,primary_device_id,anchor_boot,anchor_sequence),
    FOREIGN KEY (vehicle_id,primary_device_id) REFERENCES device(vehicle_id,id),
    CHECK ((status='COMPLETED') = (ended_at IS NOT NULL AND completion_reason IS NOT NULL AND confirmed_at IS NOT NULL)),
    CHECK (ended_at IS NULL OR ended_at >= started_at)
);
CREATE UNIQUE INDEX trip_one_open ON trip_record (vehicle_id) WHERE status IN ('CANDIDATE','ACTIVE');
CREATE INDEX trip_vehicle_start ON trip_record (vehicle_id,started_at DESC,id DESC);

CREATE TABLE trip_projection_progress (
    vehicle_id UUID PRIMARY KEY REFERENCES trip_policy(vehicle_id),
    policy_revision BIGINT NOT NULL,
    cursor_at TIMESTAMPTZ,
    cursor_boot UUID,
    cursor_sequence NUMERIC,
    current_trip_id UUID REFERENCES trip_record(id),
    checkpoint JSONB NOT NULL CHECK (jsonb_typeof(checkpoint)='object'),
    needs_review BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK ((cursor_at IS NULL AND cursor_boot IS NULL AND cursor_sequence IS NULL)
        OR (cursor_at IS NOT NULL AND cursor_boot IS NOT NULL AND cursor_sequence IS NOT NULL))
);

-- Internal links may include pending/trailing evidence. The view exposes only
-- confirmed/active evidence, excluding the waiting samples after a closed end.
CREATE TABLE trip_reading_link (
    reading_id UUID PRIMARY KEY REFERENCES telemetry_reading(id),
    trip_id UUID NOT NULL REFERENCES trip_record(id)
);
CREATE INDEX trip_reading_members ON trip_reading_link (trip_id,reading_id);
CREATE VIEW trip_evidence AS
    SELECT link.trip_id, link.reading_id FROM trip_reading_link link
    JOIN trip_record trip ON trip.id=link.trip_id
    JOIN telemetry_reading reading ON reading.id=link.reading_id
    WHERE trip.status IN ('ACTIVE','COMPLETED')
      AND reading.recorded_at >= trip.started_at
      AND reading.recorded_at <= coalesce(trip.ended_at,trip.observed_through);

CREATE INDEX telemetry_trip_tie ON telemetry_reading
    (vehicle_id,device_id,recorded_at,boot_id DESC,sequence_number DESC);
