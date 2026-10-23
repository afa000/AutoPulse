-- Additive only: no raw/history rewrite, backfill, default worker or diagnostic deletion.
CREATE TABLE diagnostic_vehicle (
    vehicle_id UUID PRIMARY KEY REFERENCES vehicle(id),
    generation BIGINT NOT NULL DEFAULT 0 CHECK(generation>=0)
);
CREATE TABLE diagnostic_snapshot (
    id UUID PRIMARY KEY,
    vehicle_id UUID NOT NULL REFERENCES vehicle(id),
    device_id UUID NOT NULL,
    boot_id UUID NOT NULL,
    sequence_number BIGINT NOT NULL CHECK(sequence_number>=0),
    source TEXT NOT NULL CHECK(source IN ('SIMULATED','OBD')),
    captured_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL CHECK(jsonb_typeof(payload)='object'),
    UNIQUE(device_id,boot_id,sequence_number), UNIQUE(vehicle_id,id),
    FOREIGN KEY(vehicle_id,device_id) REFERENCES device(vehicle_id,id)
);
CREATE INDEX diagnostic_latest ON diagnostic_snapshot(vehicle_id,captured_at DESC,device_id DESC,boot_id DESC,sequence_number DESC);
CREATE TABLE diagnostic_work (
    snapshot_id UUID PRIMARY KEY,
    vehicle_id UUID NOT NULL,
    next_part INTEGER NOT NULL DEFAULT 0 CHECK(next_part BETWEEN 0 AND 24),
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','PROCESSED','REVIEW')),
    failures INTEGER NOT NULL DEFAULT 0 CHECK(failures>=0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error TEXT,
    FOREIGN KEY(vehicle_id,snapshot_id) REFERENCES diagnostic_snapshot(vehicle_id,id)
);
CREATE INDEX diagnostic_due ON diagnostic_work(next_attempt_at,vehicle_id,snapshot_id) WHERE status='PENDING';
CREATE INDEX diagnostic_vehicle_work ON diagnostic_work(vehicle_id,status,next_attempt_at,snapshot_id);
CREATE FUNCTION register_diagnostic_work() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO diagnostic_vehicle(vehicle_id,generation) VALUES(NEW.vehicle_id,1)
        ON CONFLICT(vehicle_id) DO UPDATE SET generation=diagnostic_vehicle.generation+1;
    INSERT INTO diagnostic_work(snapshot_id,vehicle_id,next_attempt_at)
        VALUES(NEW.id,NEW.vehicle_id,GREATEST(NEW.captured_at,NEW.received_at));
    RETURN NEW;
END; $$;
CREATE TRIGGER diagnostic_acceptance AFTER INSERT ON diagnostic_snapshot FOR EACH ROW EXECUTE FUNCTION register_diagnostic_work();
CREATE TABLE diagnostic_scope (
    id UUID PRIMARY KEY,
    vehicle_id UUID NOT NULL REFERENCES vehicle(id),
    device_id UUID NOT NULL,
    source TEXT NOT NULL CHECK(source IN ('SIMULATED','OBD')),
    ecu_id TEXT NOT NULL CHECK(ecu_id ~ '^[A-Z][A-Z0-9_-]{0,31}$'),
    category TEXT NOT NULL CHECK(category IN ('PENDING','CONFIRMED','PERMANENT')),
    captured_at TIMESTAMPTZ NOT NULL,
    boot_id UUID NOT NULL,
    sequence_number BIGINT NOT NULL CHECK(sequence_number>=0),
    snapshot_id UUID NOT NULL,
    outcome TEXT NOT NULL CHECK(outcome IN ('COMPLETE','PARTIAL','UNSUPPORTED','UNAVAILABLE','TIMEOUT','FAILED')),
    mil_outcome TEXT NOT NULL CHECK(mil_outcome IN ('COMPLETE','UNSUPPORTED','UNAVAILABLE','TIMEOUT','FAILED')),
    mil_value BOOLEAN,
    UNIQUE(vehicle_id,device_id,source,ecu_id,category), UNIQUE(vehicle_id,id), UNIQUE(vehicle_id,id,category),
    FOREIGN KEY(vehicle_id,device_id) REFERENCES device(vehicle_id,id),
    FOREIGN KEY(vehicle_id,snapshot_id) REFERENCES diagnostic_snapshot(vehicle_id,id),
    CHECK((mil_outcome='COMPLETE' AND mil_value IS NOT NULL) OR (mil_outcome<>'COMPLETE' AND mil_value IS NULL))
);
CREATE TABLE diagnostic_event (
    id UUID PRIMARY KEY,
    vehicle_id UUID NOT NULL,
    scope_id UUID NOT NULL,
    category TEXT NOT NULL CHECK(category IN ('PENDING','CONFIRMED','PERMANENT')),
    code TEXT NOT NULL CHECK(code ~ '^[PBCU][0-3][0-9A-F]{3}$'),
    cycle_number BIGINT NOT NULL CHECK(cycle_number>0),
    state TEXT NOT NULL CHECK(state IN ('PRESENT','NOT_OBSERVED')),
    positive_count BIGINT NOT NULL CHECK(positive_count>0),
    first_observed_at TIMESTAMPTZ NOT NULL,
    last_observed_at TIMESTAMPTZ NOT NULL,
    first_snapshot_id UUID NOT NULL,
    last_positive_snapshot_id UUID NOT NULL,
    UNIQUE(scope_id,code,cycle_number), UNIQUE(vehicle_id,id),
    FOREIGN KEY(vehicle_id,scope_id,category) REFERENCES diagnostic_scope(vehicle_id,id,category),
    FOREIGN KEY(vehicle_id,first_snapshot_id) REFERENCES diagnostic_snapshot(vehicle_id,id),
    FOREIGN KEY(vehicle_id,last_positive_snapshot_id) REFERENCES diagnostic_snapshot(vehicle_id,id),
    CHECK(last_observed_at>=first_observed_at)
);
CREATE UNIQUE INDEX diagnostic_one_present ON diagnostic_event(scope_id,code) WHERE state='PRESENT';
CREATE INDEX diagnostic_scope_present ON diagnostic_event(scope_id,code) WHERE state='PRESENT';
CREATE INDEX diagnostic_event_page ON diagnostic_event(vehicle_id,last_observed_at DESC,id DESC);
CREATE INDEX diagnostic_event_category_page ON diagnostic_event(vehicle_id,category,last_observed_at DESC,id DESC);
CREATE INDEX diagnostic_event_state_page ON diagnostic_event(vehicle_id,state,last_observed_at DESC,id DESC);
CREATE INDEX diagnostic_event_filtered_page ON diagnostic_event(vehicle_id,category,state,last_observed_at DESC,id DESC);
CREATE TABLE diagnostic_application (
    snapshot_id UUID NOT NULL,
    vehicle_id UUID NOT NULL,
    scope_id UUID NOT NULL,
    decision TEXT NOT NULL CHECK(decision IN ('APPLIED','HISTORICAL_ONLY','STALE_HISTORICAL','CLOCK_WARNING')),
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(snapshot_id,scope_id),
    FOREIGN KEY(vehicle_id,snapshot_id) REFERENCES diagnostic_snapshot(vehicle_id,id),
    FOREIGN KEY(vehicle_id,scope_id) REFERENCES diagnostic_scope(vehicle_id,id)
);
CREATE TABLE diagnostic_transition (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    vehicle_id UUID NOT NULL,
    event_id UUID NOT NULL,
    snapshot_id UUID NOT NULL,
    kind TEXT NOT NULL CHECK(kind IN ('PRESENT','OBSERVED','NOT_OBSERVED')),
    captured_at TIMESTAMPTZ NOT NULL,
    UNIQUE(event_id,snapshot_id),
    FOREIGN KEY(vehicle_id,event_id) REFERENCES diagnostic_event(vehicle_id,id),
    FOREIGN KEY(vehicle_id,snapshot_id) REFERENCES diagnostic_snapshot(vehicle_id,id)
);
CREATE INDEX diagnostic_transition_history ON diagnostic_transition(event_id,id);
CREATE FUNCTION diagnostic_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'diagnostic evidence is immutable' USING ERRCODE='23514'; END; $$;
CREATE TRIGGER diagnostic_snapshot_immutable BEFORE UPDATE OR DELETE ON diagnostic_snapshot FOR EACH ROW EXECUTE FUNCTION diagnostic_immutable();
CREATE TRIGGER diagnostic_application_immutable BEFORE UPDATE OR DELETE ON diagnostic_application FOR EACH ROW EXECUTE FUNCTION diagnostic_immutable();
CREATE TRIGGER diagnostic_transition_immutable BEFORE UPDATE OR DELETE ON diagnostic_transition FOR EACH ROW EXECUTE FUNCTION diagnostic_immutable();
CREATE FUNCTION diagnostic_scope_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id,NEW.vehicle_id,NEW.device_id,NEW.source,NEW.ecu_id,NEW.category)
        IS DISTINCT FROM (OLD.id,OLD.vehicle_id,OLD.device_id,OLD.source,OLD.ecu_id,OLD.category)
        OR (NEW.captured_at,NEW.boot_id,NEW.sequence_number)<=(OLD.captured_at,OLD.boot_id,OLD.sequence_number) THEN
        RAISE EXCEPTION 'diagnostic scope identity/order is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER diagnostic_scope_guard BEFORE UPDATE ON diagnostic_scope FOR EACH ROW EXECUTE FUNCTION diagnostic_scope_guard();
CREATE FUNCTION diagnostic_event_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id,NEW.vehicle_id,NEW.scope_id,NEW.category,NEW.code,NEW.cycle_number,NEW.first_observed_at,NEW.first_snapshot_id)
        IS DISTINCT FROM (OLD.id,OLD.vehicle_id,OLD.scope_id,OLD.category,OLD.code,OLD.cycle_number,OLD.first_observed_at,OLD.first_snapshot_id)
        OR NEW.positive_count<OLD.positive_count OR NEW.last_observed_at<OLD.last_observed_at
        OR (OLD.state='NOT_OBSERVED' AND NEW.state='PRESENT') THEN
        RAISE EXCEPTION 'diagnostic event cycle identity/history is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER diagnostic_event_guard BEFORE UPDATE ON diagnostic_event FOR EACH ROW EXECUTE FUNCTION diagnostic_event_guard();
CREATE TRIGGER diagnostic_event_no_delete BEFORE DELETE ON diagnostic_event FOR EACH ROW EXECUTE FUNCTION diagnostic_immutable();
CREATE FUNCTION diagnostic_work_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.snapshot_id,NEW.vehicle_id) IS DISTINCT FROM (OLD.snapshot_id,OLD.vehicle_id)
        OR (NEW.next_part<>OLD.next_part AND NEW.next_part<>OLD.next_part+1)
        OR (OLD.status='PROCESSED' AND NEW IS DISTINCT FROM OLD)
        OR (NEW.status='PROCESSED' AND NEW.next_part<>(SELECT jsonb_array_length(payload->'ecus')*3 FROM diagnostic_snapshot WHERE id=NEW.snapshot_id)) THEN
        RAISE EXCEPTION 'diagnostic work identity/progress is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER diagnostic_work_guard BEFORE UPDATE ON diagnostic_work FOR EACH ROW EXECUTE FUNCTION diagnostic_work_guard();
CREATE TRIGGER diagnostic_work_no_delete BEFORE DELETE ON diagnostic_work FOR EACH ROW EXECUTE FUNCTION diagnostic_immutable();
