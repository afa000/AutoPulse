-- Read-source handoff only. Do not scan/modify existing work, remove raw FKs,
-- weaken original-raw reference checks or enable retention during deployment.
CREATE OR REPLACE FUNCTION fill_trip_work_order() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' OR (NEW.status='PENDING' AND NEW.sort_at IS NULL) THEN
        IF EXISTS (SELECT 1 FROM telemetry_replay_backfill WHERE vehicle_id=NEW.vehicle_id AND complete) THEN
            SELECT recorded_at,device_id,boot_id,sequence_number
                INTO NEW.sort_at,NEW.sort_device,NEW.sort_boot,NEW.sort_sequence
                FROM telemetry_replay_identity WHERE reading_id=NEW.reading_id AND vehicle_id=NEW.vehicle_id;
            IF NOT FOUND THEN
                RAISE EXCEPTION 'work ordering requires its canonical ledger reading' USING ERRCODE='23514';
            END IF;
        ELSE
            SELECT recorded_at,device_id,boot_id,sequence_number
                INTO NEW.sort_at,NEW.sort_device,NEW.sort_boot,NEW.sort_sequence
                FROM telemetry_reading WHERE id=NEW.reading_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
