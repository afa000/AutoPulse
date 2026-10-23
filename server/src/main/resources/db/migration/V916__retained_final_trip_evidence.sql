-- Additive foundation only: do not remove raw links/FKs, rewrite trip_evidence,
-- scan existing payloads, or enable pruning during migration.
CREATE TABLE trip_retained_evidence (
    reading_id UUID PRIMARY KEY REFERENCES telemetry_replay_identity(reading_id),
    trip_id UUID NOT NULL REFERENCES trip_record(id)
);
CREATE INDEX trip_retained_members ON trip_retained_evidence(trip_id,reading_id);

CREATE FUNCTION guard_retained_final_evidence() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE target trip_record%ROWTYPE;
BEGIN
    IF TG_OP<>'INSERT' THEN
        RAISE EXCEPTION 'retained final evidence is immutable' USING ERRCODE='23514';
    END IF;
    SELECT * INTO target FROM trip_record WHERE id=NEW.trip_id FOR SHARE;
    IF NOT FOUND OR target.status<>'COMPLETED' OR target.finalization<>'FINAL' THEN
        RAISE EXCEPTION 'retained evidence requires a FINAL COMPLETED trip' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM trip_reading_link link
        JOIN telemetry_reading r ON r.id=link.reading_id
        JOIN telemetry_replay_identity e ON e.reading_id=r.id
        WHERE link.trip_id=target.id AND link.reading_id=NEW.reading_id
          AND r.vehicle_id=target.vehicle_id AND r.device_id=target.primary_device_id AND r.source=target.source
          AND r.recorded_at BETWEEN target.started_at AND target.ended_at
          AND e.vehicle_id=r.vehicle_id AND e.device_id=r.device_id AND e.boot_id=r.boot_id
          AND e.sequence_number=r.sequence_number AND e.recorded_at=r.recorded_at
          AND e.received_at=r.received_at AND e.source=r.source AND e.payload=r.payload
    ) THEN
        RAISE EXCEPTION 'retained evidence requires exact original closed membership' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER retained_final_evidence_guard BEFORE INSERT OR UPDATE OR DELETE ON trip_retained_evidence
    FOR EACH ROW EXECUTE FUNCTION guard_retained_final_evidence();

CREATE TABLE trip_retained_evidence_progress (
    trip_id UUID PRIMARY KEY REFERENCES trip_record(id),
    last_reading_id UUID,
    visited_rows BIGINT NOT NULL DEFAULT 0 CHECK (visited_rows>=0),
    retained_rows BIGINT NOT NULL DEFAULT 0 CHECK (retained_rows>=0 AND retained_rows<=visited_rows),
    complete BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE FUNCTION protect_retained_evidence_progress() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR (OLD.complete AND NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'retained evidence progress cannot be deleted or reset after completion' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER retained_evidence_progress_guard BEFORE UPDATE OR DELETE ON trip_retained_evidence_progress
    FOR EACH ROW EXECUTE FUNCTION protect_retained_evidence_progress();
