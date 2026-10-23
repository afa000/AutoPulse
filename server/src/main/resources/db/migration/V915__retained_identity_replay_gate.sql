-- Keep V914's AFTER INSERT capture: only actual accepted raw inserts may create
-- ledger entries. This gate does not reserve identities for ignored/failed rows.
CREATE FUNCTION gate_retained_replay() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    -- This hash is a serialization key, NOT a content digest or equality test.
    -- A collision merely serializes unrelated devices. One lock per device
    -- bounds lock-table use for large same-device batches. Accepted writes already
    -- serialize through vehicle activity; retained retries need no activity update.
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.device_id::text,915));
    IF EXISTS (SELECT 1 FROM telemetry_replay_identity
               WHERE device_id=NEW.device_id AND boot_id=NEW.boot_id AND sequence_number=NEW.sequence_number)
    THEN
        RETURN NULL;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER telemetry_retained_replay_gate BEFORE INSERT ON telemetry_reading
    FOR EACH ROW EXECUTE FUNCTION gate_retained_replay();
