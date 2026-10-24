CREATE TABLE vehicle_read_generation (
    vehicle_id UUID PRIMARY KEY REFERENCES vehicle(id),
    generation BIGINT NOT NULL DEFAULT 0 CHECK (generation>=0)
);
INSERT INTO vehicle_read_generation(vehicle_id) SELECT id FROM vehicle;
CREATE FUNCTION register_read_generation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN INSERT INTO vehicle_read_generation(vehicle_id) VALUES (NEW.id); RETURN NEW; END;
$$;
CREATE TRIGGER vehicle_read_generation_registration AFTER INSERT ON vehicle FOR EACH ROW EXECUTE FUNCTION register_read_generation();

CREATE FUNCTION advance_vehicle_read_generation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE target UUID;
BEGIN
    IF TG_OP='DELETE' THEN target=OLD.vehicle_id; ELSE target=NEW.vehicle_id; END IF;
    -- Match ingestion/publication lock order; readers use MVCC, never this lock.
    PERFORM vehicle_id FROM trip_vehicle_activity WHERE vehicle_id=target FOR UPDATE;
    UPDATE vehicle_read_generation SET generation=generation+1 WHERE vehicle_id=target;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER zz_telemetry_read_generation AFTER INSERT OR UPDATE OR DELETE ON telemetry_reading
    FOR EACH ROW EXECUTE FUNCTION advance_vehicle_read_generation();
CREATE TRIGGER trip_read_generation AFTER INSERT OR UPDATE OR DELETE ON trip_record
    FOR EACH ROW EXECUTE FUNCTION advance_vehicle_read_generation();

CREATE FUNCTION advance_lineage_read_generation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE parent UUID; target UUID;
BEGIN
    IF TG_OP='DELETE' THEN parent=OLD.parent_trip_id; ELSE parent=NEW.parent_trip_id; END IF;
    SELECT vehicle_id INTO target FROM trip_record WHERE id=parent;
    PERFORM vehicle_id FROM trip_vehicle_activity WHERE vehicle_id=target FOR UPDATE;
    UPDATE vehicle_read_generation SET generation=generation+1 WHERE vehicle_id=target;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER lineage_read_generation AFTER INSERT OR UPDATE OR DELETE ON trip_correction_lineage
    FOR EACH ROW EXECUTE FUNCTION advance_lineage_read_generation();
