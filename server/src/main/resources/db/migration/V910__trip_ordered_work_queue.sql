-- Preserve immutable canonical ordering with the durable work marker. Existing
-- pending rows are hydrated in bounded backfill pages, not rewritten here.
ALTER TABLE trip_work_item ADD COLUMN sort_at TIMESTAMPTZ;
ALTER TABLE trip_work_item ADD COLUMN sort_device UUID;
ALTER TABLE trip_work_item ADD COLUMN sort_boot UUID;
ALTER TABLE trip_work_item ADD COLUMN sort_sequence NUMERIC;
ALTER TABLE trip_work_item ADD CONSTRAINT trip_work_sort_shape CHECK (
    (sort_at IS NULL AND sort_device IS NULL AND sort_boot IS NULL AND sort_sequence IS NULL)
    OR (sort_at IS NOT NULL AND sort_device IS NOT NULL AND sort_boot IS NOT NULL AND sort_sequence IS NOT NULL));

CREATE FUNCTION fill_trip_work_order() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' OR (NEW.status='PENDING' AND NEW.sort_at IS NULL) THEN
        SELECT recorded_at,device_id,boot_id,sequence_number INTO NEW.sort_at,NEW.sort_device,NEW.sort_boot,NEW.sort_sequence
        FROM telemetry_reading WHERE id=NEW.reading_id;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trip_work_order BEFORE INSERT OR UPDATE ON trip_work_item FOR EACH ROW EXECUTE FUNCTION fill_trip_work_order();
CREATE INDEX trip_ordered_pending ON trip_work_item(vehicle_id,sort_at,sort_device,sort_boot,sort_sequence)
    INCLUDE (reading_id,next_attempt_at) WHERE status='PENDING' AND sort_at IS NOT NULL;
CREATE INDEX trip_pending_order_recovery ON trip_work_item(vehicle_id,reading_id) WHERE status='PENDING' AND sort_at IS NULL;
