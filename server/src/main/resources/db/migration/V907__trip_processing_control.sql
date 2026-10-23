-- One bounded status row per vehicle; no telemetry or migration-time history scan.
CREATE TABLE trip_processing_control (
    vehicle_id UUID PRIMARY KEY REFERENCES vehicle(id),
    stage TEXT NOT NULL CHECK (stage IN ('FORWARD','PLAN','REPLAY','PUBLISH','FINALIZE')),
    state TEXT NOT NULL CHECK (state IN ('IDLE','RUNNING','RETRY_WAIT','PAUSED')),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision>0),
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ,
    last_attempt_at TIMESTAMPTZ,
    last_finished_at TIMESTAMPTZ,
    consecutive_failures INTEGER NOT NULL DEFAULT 0 CHECK (consecutive_failures BETWEEN 0 AND 5),
    total_failures BIGINT NOT NULL DEFAULT 0 CHECK (total_failures>=consecutive_failures),
    last_failure_at TIMESTAMPTZ,
    last_error_code TEXT,
    last_sql_state TEXT CHECK (last_sql_state IS NULL OR last_sql_state ~ '^[0-9A-Z]{5}$'),
    last_result TEXT,
    CHECK ((state='RUNNING')=(claim_token IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK ((state='RETRY_WAIT')=(next_attempt_at IS NOT NULL))
);
CREATE INDEX trip_processing_retry_due ON trip_processing_control(next_attempt_at,vehicle_id) WHERE state='RETRY_WAIT';
CREATE INDEX trip_processing_expired_lease ON trip_processing_control(lease_until,vehicle_id) WHERE state='RUNNING';
