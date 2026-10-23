-- Dual references are a transition, not permission to remove raw data. NOT VALID
-- avoids scanning/copying historical payloads during deployment. PostgreSQL still
-- enforces these constraints for new references; explicit legacy backfill remains.
CREATE FUNCTION ensure_trip_replay_reference() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE original telemetry_reading%ROWTYPE;
BEGIN
    -- One existing raw row, not a history scan. Pin its canonical metadata until
    -- this reference transaction commits. Never rewrite an immutable ledger row.
    SELECT * INTO original FROM telemetry_reading WHERE id=NEW.reading_id FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'trip reference requires its original raw reading' USING ERRCODE='23503';
    END IF;
    INSERT INTO telemetry_replay_identity
        (device_id,boot_id,sequence_number,reading_id,vehicle_id,recorded_at,received_at,source,payload)
    VALUES (original.device_id,original.boot_id,original.sequence_number,original.id,original.vehicle_id,
            original.recorded_at,original.received_at,original.source,original.payload)
    ON CONFLICT DO NOTHING;
    IF NOT EXISTS (
        SELECT 1 FROM telemetry_replay_identity l
        WHERE l.reading_id=original.id AND l.device_id=original.device_id
          AND l.boot_id=original.boot_id AND l.sequence_number=original.sequence_number
          AND l.vehicle_id=original.vehicle_id AND l.recorded_at=original.recorded_at
          AND l.received_at=original.received_at AND l.source=original.source AND l.payload=original.payload
    ) THEN
        RAISE EXCEPTION 'trip replay reference does not match original reading' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trip_work_replay_reference BEFORE INSERT OR UPDATE OF reading_id ON trip_work_item
    FOR EACH ROW EXECUTE FUNCTION ensure_trip_replay_reference();
CREATE TRIGGER trip_link_replay_reference BEFORE INSERT OR UPDATE OF reading_id ON trip_reading_link
    FOR EACH ROW EXECUTE FUNCTION ensure_trip_replay_reference();
CREATE TRIGGER trip_correction_replay_reference BEFORE INSERT OR UPDATE OF reading_id ON trip_correction_reading
    FOR EACH ROW EXECUTE FUNCTION ensure_trip_replay_reference();

ALTER TABLE trip_work_item ADD CONSTRAINT trip_work_durable_reading
    FOREIGN KEY (reading_id) REFERENCES telemetry_replay_identity(reading_id) NOT VALID;
ALTER TABLE trip_reading_link ADD CONSTRAINT trip_link_durable_reading
    FOREIGN KEY (reading_id) REFERENCES telemetry_replay_identity(reading_id) NOT VALID;
ALTER TABLE trip_correction_reading ADD CONSTRAINT trip_correction_durable_reading
    FOREIGN KEY (reading_id) REFERENCES telemetry_replay_identity(reading_id) NOT VALID;

-- Original raw FKs and all final/evidence guards deliberately remain unchanged.
