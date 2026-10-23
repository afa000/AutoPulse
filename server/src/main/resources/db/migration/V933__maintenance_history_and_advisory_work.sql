-- Additive, no historical backfill, owner-data processing or deletion.
CREATE TABLE maintenance_vehicle (
    vehicle_id UUID PRIMARY KEY REFERENCES vehicle(id),
    generation BIGINT NOT NULL DEFAULT 0 CHECK(generation>=0),
    oil_state JSONB NOT NULL DEFAULT '{"active":false,"cycle":0,"basis":null}',
    next_evaluation_at TIMESTAMPTZ NOT NULL DEFAULT 'infinity'
);
CREATE TABLE maintenance_profile (
    vehicle_id UUID NOT NULL REFERENCES maintenance_vehicle(vehicle_id),
    revision BIGINT NOT NULL CHECK(revision>0),
    recorded_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    PRIMARY KEY(vehicle_id,revision)
);
CREATE TABLE maintenance_entry (
    id UUID PRIMARY KEY,
    vehicle_id UUID NOT NULL REFERENCES maintenance_vehicle(vehicle_id),
    revision BIGINT NOT NULL CHECK(revision>0),
    kind TEXT NOT NULL CHECK(kind IN ('ODOMETER','SERVICE')),
    service_type TEXT CHECK(service_type='OIL_FILTER'),
    observed_at TIMESTAMPTZ NOT NULL,
    odometer_km NUMERIC NOT NULL CHECK(odometer_km>=0),
    payload JSONB NOT NULL,
    CHECK((kind='SERVICE')=(service_type IS NOT NULL)),
    UNIQUE(vehicle_id,id)
);
CREATE INDEX maintenance_entry_chronology ON maintenance_entry(vehicle_id,observed_at DESC,id DESC);
CREATE INDEX maintenance_odometer_neighbors ON maintenance_entry(vehicle_id,observed_at,odometer_km);
CREATE INDEX maintenance_service_chronology ON maintenance_entry(vehicle_id,service_type,observed_at DESC,id DESC) WHERE kind='SERVICE';
CREATE TABLE maintenance_entry_revision (
    entry_id UUID NOT NULL,
    vehicle_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK(revision>0),
    recorded_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    PRIMARY KEY(entry_id,revision),
    FOREIGN KEY(vehicle_id,entry_id) REFERENCES maintenance_entry(vehicle_id,id)
);
CREATE TABLE maintenance_command (
    vehicle_id UUID NOT NULL REFERENCES maintenance_vehicle(vehicle_id),
    request_id UUID NOT NULL,
    operation TEXT NOT NULL,
    payload JSONB NOT NULL,
    receipt JSONB NOT NULL,
    PRIMARY KEY(vehicle_id,request_id)
);
CREATE TABLE maintenance_work (
    id BIGSERIAL PRIMARY KEY,
    vehicle_id UUID NOT NULL REFERENCES maintenance_vehicle(vehicle_id),
    kind TEXT NOT NULL CHECK(kind IN ('MANUAL','DIAGNOSTIC','TEMPERATURE')),
    evidence JSONB NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','PROCESSED','REVIEW')),
    failures INTEGER NOT NULL DEFAULT 0 CHECK(failures BETWEEN 0 AND 3),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error TEXT,
    processed_at TIMESTAMPTZ
);
CREATE INDEX maintenance_work_due ON maintenance_work(next_attempt_at,id) WHERE status='PENDING';
CREATE INDEX maintenance_vehicle_work ON maintenance_work(vehicle_id,id) WHERE status='PENDING';
CREATE INDEX maintenance_evaluation_due ON maintenance_vehicle(next_evaluation_at,vehicle_id);
CREATE TABLE maintenance_recommendation (
    id UUID PRIMARY KEY,
    vehicle_id UUID NOT NULL REFERENCES maintenance_vehicle(vehicle_id),
    kind TEXT NOT NULL CHECK(kind IN ('OIL_FILTER','DIAGNOSTIC','TEMPERATURE')),
    semantic_key TEXT NOT NULL,
    rule_version TEXT NOT NULL,
    revision BIGINT NOT NULL CHECK(revision>0),
    state TEXT NOT NULL CHECK(state IN ('OPEN','ACKNOWLEDGED','COMPLETED','DISMISSED','EXPIRED')),
    generated_at TIMESTAMPTZ NOT NULL,
    evidence JSONB NOT NULL,
    UNIQUE(vehicle_id,semantic_key), UNIQUE(vehicle_id,id)
);
CREATE UNIQUE INDEX maintenance_one_oil_advisory ON maintenance_recommendation(vehicle_id,kind) WHERE kind='OIL_FILTER' AND state IN ('OPEN','ACKNOWLEDGED');
CREATE INDEX maintenance_recommendation_page ON maintenance_recommendation(vehicle_id,generated_at DESC,id DESC);
CREATE TABLE maintenance_recommendation_revision (
    recommendation_id UUID NOT NULL,
    vehicle_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK(revision>0),
    state TEXT NOT NULL CHECK(state IN ('OPEN','ACKNOWLEDGED','COMPLETED','DISMISSED','EXPIRED')),
    recorded_at TIMESTAMPTZ NOT NULL,
    reason TEXT NOT NULL,
    evidence JSONB NOT NULL,
    PRIMARY KEY(recommendation_id,revision),
    FOREIGN KEY(vehicle_id,recommendation_id) REFERENCES maintenance_recommendation(vehicle_id,id)
);
CREATE TABLE maintenance_thermal_scope (
    vehicle_id UUID NOT NULL REFERENCES maintenance_vehicle(vehicle_id),
    device_id UUID NOT NULL,
    source TEXT NOT NULL CHECK(source='SIMULATED'),
    captured_at TIMESTAMPTZ NOT NULL,
    boot_id UUID NOT NULL,
    sequence_number NUMERIC NOT NULL,
    state JSONB NOT NULL,
    PRIMARY KEY(vehicle_id,device_id,source),
    FOREIGN KEY(vehicle_id,device_id) REFERENCES device(vehicle_id,id)
);
CREATE TABLE maintenance_rule_decision (
    work_id BIGINT PRIMARY KEY REFERENCES maintenance_work(id),
    vehicle_id UUID NOT NULL REFERENCES maintenance_vehicle(vehicle_id),
    rule_version TEXT NOT NULL,
    decision TEXT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    evidence JSONB NOT NULL
);
CREATE FUNCTION maintenance_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'maintenance evidence is immutable' USING ERRCODE='23514'; END; $$;
CREATE TRIGGER maintenance_profile_immutable BEFORE UPDATE OR DELETE ON maintenance_profile FOR EACH ROW EXECUTE FUNCTION maintenance_immutable();
CREATE TRIGGER maintenance_entry_revision_immutable BEFORE UPDATE OR DELETE ON maintenance_entry_revision FOR EACH ROW EXECUTE FUNCTION maintenance_immutable();
CREATE TRIGGER maintenance_command_immutable BEFORE UPDATE OR DELETE ON maintenance_command FOR EACH ROW EXECUTE FUNCTION maintenance_immutable();
CREATE TRIGGER maintenance_recommendation_revision_immutable BEFORE UPDATE OR DELETE ON maintenance_recommendation_revision FOR EACH ROW EXECUTE FUNCTION maintenance_immutable();
CREATE TRIGGER maintenance_decision_immutable BEFORE UPDATE OR DELETE ON maintenance_rule_decision FOR EACH ROW EXECUTE FUNCTION maintenance_immutable();
CREATE TRIGGER maintenance_entry_no_delete BEFORE DELETE ON maintenance_entry FOR EACH ROW EXECUTE FUNCTION maintenance_immutable();
CREATE TRIGGER maintenance_recommendation_no_delete BEFORE DELETE ON maintenance_recommendation FOR EACH ROW EXECUTE FUNCTION maintenance_immutable();
CREATE TRIGGER maintenance_work_no_delete BEFORE DELETE ON maintenance_work FOR EACH ROW EXECUTE FUNCTION maintenance_immutable();
CREATE FUNCTION maintenance_entry_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id,NEW.vehicle_id,NEW.kind) IS DISTINCT FROM (OLD.id,OLD.vehicle_id,OLD.kind) OR NEW.revision<>OLD.revision+1 THEN
        RAISE EXCEPTION 'entry identity/revision is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER maintenance_entry_guard BEFORE UPDATE ON maintenance_entry FOR EACH ROW EXECUTE FUNCTION maintenance_entry_guard();
CREATE FUNCTION maintenance_recommendation_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id,NEW.vehicle_id,NEW.kind,NEW.semantic_key,NEW.rule_version,NEW.generated_at)
        IS DISTINCT FROM (OLD.id,OLD.vehicle_id,OLD.kind,OLD.semantic_key,OLD.rule_version,OLD.generated_at)
        OR NEW.revision<>OLD.revision+1 OR OLD.state IN ('COMPLETED','DISMISSED','EXPIRED')
        OR NEW.state NOT IN ('ACKNOWLEDGED','COMPLETED','DISMISSED','EXPIRED') OR NEW.state=OLD.state THEN
        RAISE EXCEPTION 'recommendation identity/lifecycle is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER maintenance_recommendation_guard BEFORE UPDATE ON maintenance_recommendation FOR EACH ROW EXECUTE FUNCTION maintenance_recommendation_guard();
CREATE FUNCTION maintenance_work_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id,NEW.vehicle_id,NEW.kind,NEW.evidence) IS DISTINCT FROM (OLD.id,OLD.vehicle_id,OLD.kind,OLD.evidence)
        OR (OLD.status='PROCESSED' AND NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'maintenance work identity/processed evidence is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER maintenance_work_guard BEFORE UPDATE ON maintenance_work FOR EACH ROW EXECUTE FUNCTION maintenance_work_guard();
CREATE FUNCTION enqueue_maintenance_diagnostic() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE evidence JSONB;
BEGIN
    SELECT jsonb_build_object('eventId',e.id,'kind',NEW.kind,'capturedAt',NEW.captured_at,'snapshotId',NEW.snapshot_id,
        'code',e.code,'category',e.category,'source',s.source,'deviceId',s.device_id,'ecuId',s.ecu_id,
        'cycleNumber',e.cycle_number,'positiveObservationCount',e.positive_count)
    INTO evidence FROM diagnostic_event e JOIN diagnostic_scope s ON s.id=e.scope_id WHERE e.id=NEW.event_id;
    IF evidence->>'category'='CONFIRMED' THEN
        INSERT INTO maintenance_vehicle(vehicle_id) VALUES(NEW.vehicle_id) ON CONFLICT DO NOTHING;
        INSERT INTO maintenance_work(vehicle_id,kind,evidence) VALUES(NEW.vehicle_id,'DIAGNOSTIC',evidence);
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER diagnostic_advisory_work AFTER INSERT ON diagnostic_transition FOR EACH ROW EXECUTE FUNCTION enqueue_maintenance_diagnostic();
CREATE FUNCTION enqueue_maintenance_temperature() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.source='SIMULATED' THEN
        INSERT INTO maintenance_vehicle(vehicle_id) VALUES(NEW.vehicle_id) ON CONFLICT DO NOTHING;
        INSERT INTO maintenance_work(vehicle_id,kind,evidence,next_attempt_at)
        VALUES(NEW.vehicle_id,'TEMPERATURE',jsonb_build_object('readingId',NEW.reading_id,'deviceId',NEW.device_id,
            'bootId',NEW.boot_id,'sequenceNumber',NEW.sequence_number,'capturedAt',NEW.recorded_at,
            'receivedAt',NEW.received_at,'source',NEW.source,'coolantTempC',NEW.payload->'coolantTempC'),
            GREATEST(NEW.recorded_at,NEW.received_at));
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER thermal_advisory_work AFTER INSERT ON telemetry_replay_identity FOR EACH ROW EXECUTE FUNCTION enqueue_maintenance_temperature();
