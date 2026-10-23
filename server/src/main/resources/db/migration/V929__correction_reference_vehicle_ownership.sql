-- Future-write guards only. No historical scan/repair or raw-FK removal.
CREATE FUNCTION protect_correction_parent_owner() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (to_jsonb(NEW)->TG_ARGV[0]) IS DISTINCT FROM (to_jsonb(OLD)->TG_ARGV[0]) THEN
        RAISE EXCEPTION 'correction parent ownership is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER correction_plan_owner_immutable BEFORE UPDATE OF vehicle_id ON trip_correction_plan
    FOR EACH ROW EXECUTE FUNCTION protect_correction_parent_owner('vehicle_id');
CREATE TRIGGER correction_replay_owner_immutable BEFORE UPDATE OF plan_id ON trip_correction_replay
    FOR EACH ROW EXECUTE FUNCTION protect_correction_parent_owner('plan_id');
CREATE TRIGGER correction_segment_owner_immutable BEFORE UPDATE OF replay_id ON trip_correction_segment
    FOR EACH ROW EXECUTE FUNCTION protect_correction_parent_owner('replay_id');

CREATE FUNCTION lock_correction_reference_parents() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE parent_plan UUID;
BEGIN
    SELECT plan_id INTO parent_plan FROM trip_correction_replay WHERE id=NEW.replay_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'correction reference requires replay parents' USING ERRCODE='23503';
    END IF;
    -- Match stage ordering: plan, replay, segment, then original raw verification.
    -- Recheck the replay/segment relationship after acquiring the parent locks.
    PERFORM id FROM trip_correction_plan WHERE id=parent_plan FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'correction reference requires plan parent' USING ERRCODE='23503'; END IF;
    PERFORM id FROM trip_correction_replay WHERE id=NEW.replay_id AND plan_id=parent_plan FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'correction reference parent relationship changed' USING ERRCODE='23503'; END IF;
    PERFORM id FROM trip_correction_segment WHERE id=NEW.segment_id AND replay_id=NEW.replay_id FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'correction reference requires matching replay segment' USING ERRCODE='23503'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trip_correction_parent_barrier BEFORE INSERT OR UPDATE OF reading_id,replay_id,segment_id ON trip_correction_reading
    FOR EACH ROW EXECUTE FUNCTION lock_correction_reference_parents();

-- Retargeting only a parent key must still verify/capture exact original data.
DROP TRIGGER trip_correction_replay_reference ON trip_correction_reading;
CREATE TRIGGER trip_correction_replay_reference BEFORE INSERT OR UPDATE OF reading_id,replay_id,segment_id ON trip_correction_reading
    FOR EACH ROW EXECUTE FUNCTION ensure_trip_replay_reference();

CREATE FUNCTION guard_correction_reference_vehicle() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE reading_vehicle UUID; plan_vehicle UUID;
BEGIN
    SELECT vehicle_id INTO reading_vehicle FROM telemetry_replay_identity WHERE reading_id=NEW.reading_id;
    SELECT p.vehicle_id INTO plan_vehicle FROM trip_correction_segment s
        JOIN trip_correction_replay r ON r.id=s.replay_id
        JOIN trip_correction_plan p ON p.id=r.plan_id
        WHERE s.id=NEW.segment_id AND r.id=NEW.replay_id;
    IF reading_vehicle IS NULL OR plan_vehicle IS NULL OR reading_vehicle<>plan_vehicle THEN
        RAISE EXCEPTION 'correction reference requires matching vehicle ownership' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
-- Alphabetical BEFORE-trigger order: parents, exact original, vehicle comparison.
CREATE TRIGGER trip_correction_vehicle_owner BEFORE INSERT OR UPDATE OF reading_id,replay_id,segment_id ON trip_correction_reading
    FOR EACH ROW EXECUTE FUNCTION guard_correction_reference_vehicle();

-- Existing raw/durable/composite-parent FKs, final guards and publication gates
-- remain. No new device/source/bounds, history validation, expiry or pruning.
