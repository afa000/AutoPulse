-- A guarded reference transition, not an automatic pruning/validation job.
-- Legacy references are not scanned or declared valid. Each actual deletion
-- must establish exact canonical identity, final membership and verified buckets.
CREATE TABLE telemetry_raw_expiry (
    reading_id UUID PRIMARY KEY REFERENCES telemetry_replay_identity(reading_id),
    vehicle_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    expired_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (vehicle_id,reading_id) REFERENCES telemetry_replay_identity(vehicle_id,reading_id)
);
CREATE INDEX telemetry_raw_expiry_range ON telemetry_raw_expiry(vehicle_id,recorded_at,reading_id);

-- Disjoint whole-vehicle aggregate inputs. Never resurrect an arbitrary raw-free
-- identity without committed expiry proof, and never count raw and ledger twice.
CREATE VIEW telemetry_history_reading AS
    SELECT id,vehicle_id,device_id,boot_id,sequence_number,recorded_at,received_at,source,payload FROM telemetry_reading
    UNION ALL
    SELECT e.reading_id,e.vehicle_id,e.device_id,e.boot_id,e.sequence_number,e.recorded_at,e.received_at,e.source,e.payload
      FROM telemetry_raw_expiry x JOIN telemetry_replay_identity e ON e.reading_id=x.reading_id
      WHERE NOT EXISTS(SELECT 1 FROM telemetry_reading r WHERE r.id=x.reading_id);

CREATE FUNCTION protect_raw_expiry() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP<>'INSERT' OR pg_trigger_depth()<>2
       OR current_setting('autopulse.retention_write',true) IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'raw expiry is immutable and requires guarded raw deletion' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER raw_expiry_guard BEFORE INSERT OR UPDATE OR DELETE ON telemetry_raw_expiry
    FOR EACH ROW EXECUTE FUNCTION protect_raw_expiry();

CREATE FUNCTION guard_final_raw_deletion() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE as_of TIMESTAMPTZ; cutoff TIMESTAMPTZ; receipt_before TIMESTAMPTZ;
BEGIN
    -- Preserve the pre-existing ability to remove entirely unreferenced synthetic
    -- data. The retention service never uses this exception for unprocessed rows.
    IF NOT EXISTS(SELECT 1 FROM trip_work_item WHERE reading_id=OLD.id)
       AND NOT EXISTS(SELECT 1 FROM trip_reading_link WHERE reading_id=OLD.id) THEN RETURN OLD; END IF;
    IF current_setting('autopulse.retention_write',true) IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'raw foreign key constraint replacement: guarded retention required' USING ERRCODE='23503';
    END IF;
    as_of:=current_setting('autopulse.retention_as_of')::timestamptz;
    cutoff:=current_setting('autopulse.retention_cutoff')::timestamptz;
    receipt_before:=current_setting('autopulse.retention_receipt_before')::timestamptz;
    IF cutoff>((date_trunc('day',as_of AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')-interval '7 days')
       OR receipt_before>as_of-interval '96 hours'
       OR OLD.recorded_at>=cutoff OR OLD.received_at>=receipt_before
       OR NOT EXISTS (
         SELECT 1 FROM telemetry_replay_identity e
         JOIN trip_work_item w ON w.reading_id=e.reading_id AND w.vehicle_id=e.vehicle_id AND w.status='PROCESSED'
         JOIN trip_reading_link l ON l.reading_id=e.reading_id
         JOIN trip_retained_evidence m ON m.reading_id=l.reading_id AND m.trip_id=l.trip_id
         JOIN trip_verified_archive a ON a.trip_id=m.trip_id AND a.vehicle_id=e.vehicle_id
         JOIN trip_record t ON t.id=a.trip_id
         JOIN trip_policy p ON p.vehicle_id=e.vehicle_id AND p.primary_device_id=e.device_id
         JOIN trip_projection_progress progress ON progress.vehicle_id=e.vehicle_id
           AND progress.policy_revision=p.revision AND NOT progress.needs_review AND progress.cursor_at>e.recorded_at
         WHERE e.reading_id=OLD.id
           AND (e.reading_id,e.vehicle_id,e.device_id,e.boot_id,e.sequence_number,e.recorded_at,e.received_at,e.source,e.payload)
             IS NOT DISTINCT FROM (OLD.id,OLD.vehicle_id,OLD.device_id,OLD.boot_id,OLD.sequence_number,OLD.recorded_at,OLD.received_at,OLD.source,OLD.payload)
           AND e.device_id=a.primary_device_id AND e.source=a.source AND e.recorded_at BETWEEN t.started_at AND t.ended_at
       )
       OR NOT EXISTS(SELECT 1 FROM telemetry_replay_backfill WHERE vehicle_id=OLD.vehicle_id AND complete)
       OR NOT EXISTS(SELECT 1 FROM trip_backfill_progress WHERE vehicle_id=OLD.vehicle_id AND complete)
       OR NOT EXISTS(SELECT 1 FROM trip_vehicle_activity WHERE vehicle_id=OLD.vehicle_id AND scan_complete)
       OR NOT EXISTS(SELECT 1 FROM trip_processing_control WHERE vehicle_id=OLD.vehicle_id AND state='IDLE')
       OR EXISTS(SELECT 1 FROM trip_work_item WHERE vehicle_id=OLD.vehicle_id AND status IN ('PENDING','REVIEW'))
       OR EXISTS(SELECT 1 FROM trip_record WHERE vehicle_id=OLD.vehicle_id AND
           (status IN ('CANDIDATE','ACTIVE','REBUILDING') OR (status='COMPLETED' AND finalization<>'FINAL')))
       OR EXISTS(SELECT 1 FROM trip_correction_plan WHERE vehicle_id=OLD.vehicle_id AND phase IN ('TRIPS','INPUTS','READY'))
       OR EXISTS(SELECT 1 FROM trip_correction_publication WHERE vehicle_id=OLD.vehicle_id AND phase NOT IN ('DONE','ABANDONED'))
       OR EXISTS(SELECT 1 FROM trip_correction_reading WHERE reading_id=OLD.id)
       OR OLD.id=(SELECT id FROM telemetry_reading WHERE vehicle_id=OLD.vehicle_id
          ORDER BY recorded_at DESC,device_id DESC,boot_id DESC,sequence_number DESC LIMIT 1) THEN
        RAISE EXCEPTION 'raw foreign key constraint replacement: retention protection failed' USING ERRCODE='23503';
    END IF;
    INSERT INTO telemetry_raw_expiry(reading_id,vehicle_id,recorded_at,expired_at)
        VALUES(OLD.id,OLD.vehicle_id,OLD.recorded_at,as_of);
    RETURN OLD;
END;
$$;
CREATE TRIGGER guarded_final_raw_deletion BEFORE DELETE ON telemetry_reading
    FOR EACH ROW EXECUTE FUNCTION guard_final_raw_deletion();

-- Retain references themselves, single/composite ledger FKs and final guards.
-- The correction raw FK remains an additional unconditional blocker.
ALTER TABLE trip_work_item DROP CONSTRAINT trip_work_item_reading_id_fkey;
ALTER TABLE trip_reading_link DROP CONSTRAINT trip_reading_link_reading_id_fkey;

CREATE FUNCTION protect_expired_work() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM telemetry_raw_expiry WHERE reading_id=OLD.reading_id) THEN
        IF TG_OP='DELETE' OR NEW IS DISTINCT FROM OLD THEN
            RAISE EXCEPTION 'expired processed work is immutable' USING ERRCODE='23514';
        END IF;
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER expired_work_immutable BEFORE UPDATE OR DELETE ON trip_work_item
    FOR EACH ROW EXECUTE FUNCTION protect_expired_work();

-- Bounded queue discovery can retry an already processed expired identity.
-- Only work references with committed expiry proof may use the immutable ledger.
-- Link/correction writes still require raw; final-link immutability is unchanged.
CREATE OR REPLACE FUNCTION ensure_trip_replay_reference() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE original telemetry_reading%ROWTYPE;
BEGIN
    SELECT * INTO original FROM telemetry_reading WHERE id=NEW.reading_id FOR SHARE;
    IF NOT FOUND THEN
        IF TG_TABLE_NAME='trip_work_item' AND EXISTS(
            SELECT 1 FROM telemetry_raw_expiry x JOIN telemetry_replay_identity e ON e.reading_id=x.reading_id
            WHERE x.reading_id=NEW.reading_id AND e.vehicle_id=NEW.vehicle_id
        ) THEN RETURN NEW; END IF;
        RAISE EXCEPTION 'trip reference requires its original raw reading' USING ERRCODE='23503';
    END IF;
    INSERT INTO telemetry_replay_identity(device_id,boot_id,sequence_number,reading_id,vehicle_id,recorded_at,received_at,source,payload)
      VALUES(original.device_id,original.boot_id,original.sequence_number,original.id,original.vehicle_id,
        original.recorded_at,original.received_at,original.source,original.payload) ON CONFLICT DO NOTHING;
    IF NOT EXISTS(SELECT 1 FROM telemetry_replay_identity e WHERE e.reading_id=original.id
      AND (e.vehicle_id,e.device_id,e.boot_id,e.sequence_number,e.recorded_at,e.received_at,e.source,e.payload)
        IS NOT DISTINCT FROM (original.vehicle_id,original.device_id,original.boot_id,original.sequence_number,original.recorded_at,original.received_at,original.source,original.payload)) THEN
        RAISE EXCEPTION 'trip replay reference does not match original reading' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
