-- Replace only the old status-related checks, retaining numeric/source checks.
DO $$ DECLARE item RECORD; BEGIN
    FOR item IN SELECT conname FROM pg_constraint WHERE conrelid='trip_record'::regclass
        AND contype='c' AND pg_get_constraintdef(oid) LIKE '%status%'
    LOOP EXECUTE format('ALTER TABLE trip_record DROP CONSTRAINT %I',item.conname); END LOOP;
    FOR item IN SELECT conname FROM pg_constraint WHERE conrelid='trip_record'::regclass
        AND contype='u' AND pg_get_constraintdef(oid)='UNIQUE (vehicle_id, primary_device_id, anchor_boot, anchor_sequence)'
    LOOP EXECUTE format('ALTER TABLE trip_record DROP CONSTRAINT %I',item.conname); END LOOP;
END $$;
ALTER TABLE trip_record ADD CONSTRAINT trip_status_shape CHECK (status IN ('CANDIDATE','ACTIVE','COMPLETED','DISCARDED','SUPERSEDED','REBUILDING'));
ALTER TABLE trip_record ADD CONSTRAINT trip_completion_shape CHECK (
    (status<>'COMPLETED' OR (ended_at IS NOT NULL AND confirmed_at IS NOT NULL AND completion_reason IS NOT NULL))
    AND (status NOT IN ('CANDIDATE','ACTIVE','DISCARDED') OR (ended_at IS NULL AND confirmed_at IS NULL AND completion_reason IS NULL)));
ALTER TABLE trip_record ADD CONSTRAINT final_trip_is_complete CHECK (finalization<>'FINAL' OR status='COMPLETED');
CREATE UNIQUE INDEX trip_current_anchor ON trip_record(vehicle_id,primary_device_id,anchor_boot,anchor_sequence) WHERE status<>'SUPERSEDED';
ALTER TABLE trip_correction_plan DROP CONSTRAINT trip_correction_plan_phase_check;
ALTER TABLE trip_correction_plan ADD CONSTRAINT trip_correction_plan_phase_check CHECK
    (phase IN ('TRIPS','INPUTS','READY','REVIEW_FINALIZED','REVIEW_TOO_WIDE','REVIEW_POLICY_CHANGED','REVIEW_PUBLICATION','DONE'));

ALTER TABLE trip_vehicle_activity ADD COLUMN acceptance_counter BIGINT NOT NULL DEFAULT 0;
ALTER TABLE trip_work_item ADD COLUMN acceptance_order BIGINT;

CREATE TABLE trip_correction_publication (
    id UUID PRIMARY KEY,
    vehicle_id UUID NOT NULL REFERENCES vehicle(id),
    plan_id UUID NOT NULL UNIQUE REFERENCES trip_correction_plan(id),
    replay_id UUID NOT NULL REFERENCES trip_correction_replay(id),
    acceptance_fence BIGINT NOT NULL,
    phase TEXT NOT NULL CHECK (phase IN ('CAPTURE','MAP','RETIRE','TRIPS','OLD','LINEAGE','DELETE_LINKS','INSERT_LINKS','ACK','FINISH','DONE','ABANDONED')),
    cursor_id UUID,
    cursor_other UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX trip_one_publication ON trip_correction_publication(vehicle_id) WHERE phase NOT IN ('DONE','ABANDONED');
CREATE TABLE trip_publication_original (
    publication_id UUID NOT NULL REFERENCES trip_correction_publication(id),
    trip_id UUID NOT NULL REFERENCES trip_record(id),
    anchor_at TIMESTAMPTZ NOT NULL,
    snapshot JSONB NOT NULL,
    PRIMARY KEY (publication_id,trip_id)
);
CREATE TABLE trip_publication_identity (
    publication_id UUID NOT NULL REFERENCES trip_correction_publication(id),
    segment_id UUID NOT NULL REFERENCES trip_correction_segment(id),
    target_id UUID NOT NULL,
    retained BOOLEAN NOT NULL,
    PRIMARY KEY (publication_id,segment_id),
    UNIQUE (publication_id,target_id)
);
CREATE TABLE trip_correction_lineage (
    publication_id UUID NOT NULL REFERENCES trip_correction_publication(id),
    parent_trip_id UUID NOT NULL REFERENCES trip_record(id),
    child_trip_id UUID NOT NULL REFERENCES trip_record(id),
    relation TEXT NOT NULL CHECK (relation IN ('SPLIT','SUPERSEDED')),
    PRIMARY KEY (publication_id,parent_trip_id,child_trip_id),
    CHECK (parent_trip_id<>child_trip_id)
);
CREATE VIEW trip_superseded_by AS SELECT DISTINCT parent_trip_id,child_trip_id FROM trip_correction_lineage WHERE relation='SUPERSEDED';

-- Future API readers must first report PUBLICATION_PENDING (not an empty trip
-- list), then use these views. Raw tables are internal maintenance state.
CREATE VIEW trip_visible_record AS SELECT t.* FROM trip_record t
    WHERE t.status<>'REBUILDING' AND NOT EXISTS(SELECT 1 FROM trip_correction_publication p
        WHERE p.vehicle_id=t.vehicle_id AND p.phase NOT IN ('DONE','ABANDONED'));
CREATE OR REPLACE VIEW trip_evidence AS
    SELECT link.trip_id,link.reading_id FROM trip_reading_link link
    JOIN trip_visible_record trip ON trip.id=link.trip_id
    JOIN telemetry_reading reading ON reading.id=link.reading_id
    WHERE trip.status IN ('ACTIVE','COMPLETED')
      AND reading.recorded_at BETWEEN trip.started_at AND coalesce(trip.ended_at,trip.observed_through);

CREATE OR REPLACE FUNCTION enqueue_trip_reading() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE next_order BIGINT;
BEGIN
    INSERT INTO trip_work_item(reading_id,vehicle_id,accepted_at,next_attempt_at)
    VALUES (NEW.id,NEW.vehicle_id,NEW.received_at,greatest(NEW.received_at,NEW.recorded_at-interval '2 seconds'));
    PERFORM vehicle_id FROM trip_vehicle_activity WHERE vehicle_id=NEW.vehicle_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'vehicle activity registration missing'; END IF;
    UPDATE trip_vehicle_activity SET acceptance_counter=acceptance_counter+1,
        last_eligible_receipt_at=CASE WHEN NEW.recorded_at<=clock_timestamp()+interval '2 seconds'
            AND EXISTS(SELECT 1 FROM trip_policy WHERE vehicle_id=NEW.vehicle_id AND primary_device_id=NEW.device_id)
            THEN greatest(last_eligible_receipt_at,NEW.received_at) ELSE last_eligible_receipt_at END,
        ingest_generation=ingest_generation+CASE WHEN EXISTS(SELECT 1 FROM trip_correction_plan p
            WHERE p.vehicle_id=NEW.vehicle_id AND p.primary_device_id=NEW.device_id AND p.phase IN ('TRIPS','INPUTS','READY')
            AND NEW.recorded_at BETWEEN p.window_start-interval '30 seconds' AND p.window_end+interval '30 seconds'
            AND NEW.recorded_at<=p.planned_as_of+interval '2 seconds') THEN 1 ELSE 0 END
    WHERE vehicle_id=NEW.vehicle_id RETURNING acceptance_counter INTO next_order;
    UPDATE trip_work_item SET acceptance_order=next_order WHERE reading_id=NEW.id;
    RETURN NEW;
END;
$$;
