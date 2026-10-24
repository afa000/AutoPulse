-- Add future-write ownership checks without rewriting/scanning historical links.
-- A trip's vehicle is its stable owner. Other provisional fields remain mutable
-- under the existing stage/finalization rules; finalized guards run first.
CREATE FUNCTION protect_trip_vehicle_owner() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.vehicle_id IS DISTINCT FROM OLD.vehicle_id THEN
        RAISE EXCEPTION 'trip vehicle ownership is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trip_vehicle_owner_immutable BEFORE UPDATE OF vehicle_id ON trip_record
    FOR EACH ROW EXECUTE FUNCTION protect_trip_vehicle_owner();

-- Retargeting only trip_id must re-run exact original-raw capture/verification,
-- not skip it just because reading_id was unchanged. Final-link guards remain.
DROP TRIGGER trip_link_replay_reference ON trip_reading_link;
CREATE TRIGGER trip_link_replay_reference BEFORE INSERT OR UPDATE OF reading_id,trip_id ON trip_reading_link
    FOR EACH ROW EXECUTE FUNCTION ensure_trip_replay_reference();

CREATE FUNCTION guard_trip_link_vehicle_owner() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE reading_vehicle UUID; trip_vehicle UUID;
BEGIN
    -- Existing trip_evidence_immutable runs first and holds FOR SHARE on the
    -- target trip. Original-raw verification runs next, before this guard.
    SELECT vehicle_id INTO reading_vehicle FROM telemetry_replay_identity WHERE reading_id=NEW.reading_id;
    SELECT vehicle_id INTO trip_vehicle FROM trip_record WHERE id=NEW.trip_id;
    IF reading_vehicle IS NULL OR trip_vehicle IS NULL OR reading_vehicle<>trip_vehicle THEN
        RAISE EXCEPTION 'trip link requires matching vehicle ownership' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trip_link_vehicle_owner BEFORE INSERT OR UPDATE OF reading_id,trip_id ON trip_reading_link
    FOR EACH ROW EXECUTE FUNCTION guard_trip_link_vehicle_owner();

-- All raw/durable FKs and final/link/evidence/ledger guards remain. No legacy
-- certification/repair, new source routing, archive publication or pruning.
