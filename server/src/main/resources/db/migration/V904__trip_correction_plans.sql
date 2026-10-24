CREATE TABLE trip_correction_plan (
    id UUID PRIMARY KEY,
    vehicle_id UUID NOT NULL REFERENCES trip_policy(vehicle_id),
    requested_work_id UUID NOT NULL UNIQUE REFERENCES trip_work_item(reading_id),
    primary_device_id UUID NOT NULL,
    policy_revision BIGINT NOT NULL,
    projection_cursor_at TIMESTAMPTZ NOT NULL,
    planned_as_of TIMESTAMPTZ NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL CHECK (window_end >= window_start),
    phase TEXT NOT NULL CHECK (phase IN ('TRIPS','INPUTS','READY','REVIEW_FINALIZED','REVIEW_TOO_WIDE','REVIEW_POLICY_CHANGED')),
    trip_cursor_at TIMESTAMPTZ,
    trip_cursor_id UUID,
    input_cursor_at TIMESTAMPTZ,
    input_cursor_id UUID,
    visited_rows BIGINT NOT NULL DEFAULT 0 CHECK (visited_rows >= 0),
    FOREIGN KEY (vehicle_id,primary_device_id) REFERENCES device(vehicle_id,id)
);
CREATE UNIQUE INDEX trip_one_correction ON trip_correction_plan(vehicle_id)
    WHERE phase IN ('TRIPS','INPUTS','READY');
CREATE INDEX trip_correction_input_page ON trip_work_item(vehicle_id,status,reading_id);
-- Planning is durable work only: no trip/evidence is rewritten by this schema.
