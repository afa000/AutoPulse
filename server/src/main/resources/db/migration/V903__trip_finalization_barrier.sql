CREATE TABLE trip_vehicle_activity (
    vehicle_id UUID PRIMARY KEY REFERENCES vehicle(id),
    last_eligible_receipt_at TIMESTAMPTZ,
    scan_cursor UUID,
    scan_complete BOOLEAN NOT NULL DEFAULT FALSE
);
INSERT INTO trip_vehicle_activity (vehicle_id) SELECT id FROM vehicle;
CREATE INDEX trip_activity_history_page ON trip_work_item (vehicle_id,reading_id);
ALTER TABLE trip_record ADD CONSTRAINT final_trip_is_complete CHECK (finalization<>'FINAL' OR status='COMPLETED');

-- Keep the original accepted-reading handoff; add a vehicle serialization point
-- shared with finalization. Duplicates do not fire this INSERT trigger.
CREATE OR REPLACE FUNCTION enqueue_trip_reading() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO trip_work_item (reading_id,vehicle_id,accepted_at,next_attempt_at)
    VALUES (NEW.id,NEW.vehicle_id,NEW.received_at,
            greatest(NEW.received_at,NEW.recorded_at-interval '2 seconds'));
    IF NEW.recorded_at <= clock_timestamp()+interval '2 seconds'
       AND EXISTS (SELECT 1 FROM trip_policy WHERE vehicle_id=NEW.vehicle_id AND primary_device_id=NEW.device_id)
    THEN
        UPDATE trip_vehicle_activity SET last_eligible_receipt_at=
            greatest(last_eligible_receipt_at,NEW.received_at) WHERE vehicle_id=NEW.vehicle_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION register_trip_backfill() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO trip_backfill_progress (vehicle_id) VALUES (NEW.id);
    INSERT INTO trip_vehicle_activity (vehicle_id) VALUES (NEW.id);
    RETURN NEW;
END;
$$;

CREATE FUNCTION protect_final_trip() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.finalization='FINAL' THEN
        IF TG_OP='DELETE' THEN RAISE EXCEPTION 'finalized trip is immutable'; END IF;
        IF NEW IS DISTINCT FROM OLD THEN RAISE EXCEPTION 'finalized trip is immutable'; END IF;
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    IF NEW.finalization='FINAL' AND NEW.status<>'COMPLETED'
       THEN RAISE EXCEPTION 'only completed trips can be finalized'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trip_immutable BEFORE UPDATE OR DELETE ON trip_record
    FOR EACH ROW EXECUTE FUNCTION protect_final_trip();

CREATE FUNCTION protect_final_evidence() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE protected BOOLEAN;
BEGIN
    IF TG_OP<>'INSERT' THEN
        SELECT finalization='FINAL' INTO protected FROM trip_record WHERE id=OLD.trip_id FOR SHARE;
        IF protected THEN RAISE EXCEPTION 'finalized trip evidence is immutable'; END IF;
    END IF;
    IF TG_OP<>'DELETE' THEN
        SELECT finalization='FINAL' INTO protected FROM trip_record WHERE id=NEW.trip_id FOR SHARE;
        IF protected THEN RAISE EXCEPTION 'finalized trip evidence is immutable'; END IF;
        RETURN NEW;
    END IF;
    RETURN OLD;
END;
$$;
CREATE TRIGGER trip_evidence_immutable BEFORE INSERT OR UPDATE OR DELETE ON trip_reading_link
    FOR EACH ROW EXECUTE FUNCTION protect_final_evidence();
CREATE INDEX trip_provisional_completion ON trip_record (vehicle_id,started_at,id)
    WHERE status='COMPLETED' AND finalization='PROVISIONAL';
