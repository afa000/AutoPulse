CREATE TABLE vehicle (
    id UUID PRIMARY KEY,
    public_id TEXT NOT NULL UNIQUE CHECK (public_id ~ '\S')
);

CREATE TABLE device (
    id UUID PRIMARY KEY,
    vehicle_id UUID NOT NULL REFERENCES vehicle(id),
    public_id TEXT NOT NULL UNIQUE CHECK (public_id ~ '\S')
);

CREATE TABLE telemetry_reading (
    id UUID PRIMARY KEY,
    vehicle_id UUID NOT NULL REFERENCES vehicle(id),
    device_id UUID NOT NULL REFERENCES device(id),
    boot_id UUID NOT NULL,
    sequence_number NUMERIC NOT NULL CHECK (sequence_number >= 0 AND sequence_number = trunc(sequence_number)),
    recorded_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    source TEXT NOT NULL CHECK (source IN ('SIMULATED', 'OBD')),
    rpm DOUBLE PRECISION,
    speed_kph DOUBLE PRECISION,
    coolant_temp_c DOUBLE PRECISION,
    engine_load_pct DOUBLE PRECISION,
    throttle_position_pct DOUBLE PRECISION,
    intake_temp_c DOUBLE PRECISION,
    maf_gps DOUBLE PRECISION,
    fuel_level_pct DOUBLE PRECISION,
    control_module_voltage_v DOUBLE PRECISION,
    mil_on BOOLEAN,
    payload JSONB NOT NULL,
    UNIQUE (device_id, boot_id, sequence_number)
);

CREATE INDEX telemetry_latest ON telemetry_reading
    (vehicle_id, recorded_at DESC, device_id DESC, boot_id DESC, sequence_number DESC);
