ALTER TABLE trip_vehicle_activity ADD COLUMN ingest_generation BIGINT NOT NULL DEFAULT 0 CHECK (ingest_generation >= 0);
ALTER TABLE trip_correction_plan ADD COLUMN input_generation BIGINT NOT NULL DEFAULT 0 CHECK (input_generation >= 0);

CREATE TABLE trip_correction_replay (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES trip_correction_plan(id),
    input_generation BIGINT NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,
    planned_as_of TIMESTAMPTZ NOT NULL,
    read_from TIMESTAMPTZ NOT NULL,
    read_to TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('RUNNING','STAGED','INVALIDATED')),
    cursor_at TIMESTAMPTZ,
    checkpoint JSONB NOT NULL CHECK (jsonb_typeof(checkpoint)='object'),
    current_segment_id UUID,
    visited_rows BIGINT NOT NULL DEFAULT 0 CHECK (visited_rows >= 0)
);
CREATE UNIQUE INDEX trip_one_replay ON trip_correction_replay(plan_id) WHERE status IN ('RUNNING','STAGED');
ALTER TABLE trip_correction_plan ADD COLUMN current_replay_id UUID REFERENCES trip_correction_replay(id);

-- Pre-generation V904 plans may have missed arrivals behind their old cursors.
-- Rediscover those regions in bounded pages; do not trust a legacy READY label.
UPDATE trip_correction_plan SET phase='TRIPS',trip_cursor_at=NULL,trip_cursor_id=NULL,
    input_cursor_at=NULL,input_cursor_id=NULL
    WHERE phase IN ('TRIPS','INPUTS','READY');

CREATE TABLE trip_correction_segment (
    id UUID PRIMARY KEY,
    replay_id UUID NOT NULL REFERENCES trip_correction_replay(id),
    anchor_boot UUID NOT NULL,
    anchor_sequence NUMERIC NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    observed_through TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    confirmed_at TIMESTAMPTZ,
    source TEXT NOT NULL CHECK (source IN ('SIMULATED','OBD')),
    status TEXT NOT NULL CHECK (status IN ('CANDIDATE','ACTIVE','COMPLETED','DISCARDED')),
    completion_reason TEXT,
    summary JSONB NOT NULL,
    quality JSONB NOT NULL,
    UNIQUE (replay_id,id),
    UNIQUE (replay_id,anchor_boot,anchor_sequence)
);
ALTER TABLE trip_correction_replay ADD FOREIGN KEY (current_segment_id) REFERENCES trip_correction_segment(id);
CREATE INDEX trip_replay_segments ON trip_correction_segment(replay_id,started_at,id);
CREATE TABLE trip_correction_reading (
    replay_id UUID NOT NULL,
    reading_id UUID NOT NULL REFERENCES telemetry_reading(id),
    segment_id UUID NOT NULL,
    PRIMARY KEY (replay_id,reading_id),
    FOREIGN KEY (replay_id,segment_id) REFERENCES trip_correction_segment(replay_id,id)
);
CREATE VIEW trip_correction_evidence AS
    SELECT link.replay_id,link.segment_id,link.reading_id FROM trip_correction_reading link
    JOIN trip_correction_segment segment ON segment.id=link.segment_id
    JOIN trip_correction_replay replay ON replay.id=link.replay_id
    JOIN telemetry_reading reading ON reading.id=link.reading_id
    WHERE replay.status IN ('RUNNING','STAGED') AND segment.status IN ('ACTIVE','COMPLETED')
      AND reading.recorded_at BETWEEN segment.started_at AND coalesce(segment.ended_at,segment.observed_through);

-- Acquire the receipt barrier before inspecting plan bounds. The next statement
-- then sees committed plan changes, not bounds read before waiting on that lock.
CREATE OR REPLACE FUNCTION enqueue_trip_reading() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO trip_work_item (reading_id,vehicle_id,accepted_at,next_attempt_at)
    VALUES (NEW.id,NEW.vehicle_id,NEW.received_at,greatest(NEW.received_at,NEW.recorded_at-interval '2 seconds'));
    PERFORM vehicle_id FROM trip_vehicle_activity WHERE vehicle_id=NEW.vehicle_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'vehicle activity registration missing'; END IF;
    UPDATE trip_vehicle_activity SET
        last_eligible_receipt_at=CASE WHEN NEW.recorded_at <= clock_timestamp()+interval '2 seconds'
            AND EXISTS(SELECT 1 FROM trip_policy WHERE vehicle_id=NEW.vehicle_id AND primary_device_id=NEW.device_id)
            THEN greatest(last_eligible_receipt_at,NEW.received_at) ELSE last_eligible_receipt_at END,
        ingest_generation=ingest_generation+CASE WHEN EXISTS(
            SELECT 1 FROM trip_correction_plan p WHERE p.vehicle_id=NEW.vehicle_id
              AND p.primary_device_id=NEW.device_id AND p.phase IN ('TRIPS','INPUTS','READY')
              AND NEW.recorded_at BETWEEN p.window_start-interval '30 seconds' AND p.window_end+interval '30 seconds'
              AND NEW.recorded_at<=p.planned_as_of+interval '2 seconds') THEN 1 ELSE 0 END
    WHERE vehicle_id=NEW.vehicle_id;
    RETURN NEW;
END;
$$;
