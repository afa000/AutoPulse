-- Internal per-final-trip contributions, not public history availability or pruning.
-- No payload scan/backfill occurs during migration.
CREATE TABLE trip_aggregate_progress (
    trip_id UUID PRIMARY KEY REFERENCES trip_retained_evidence_progress(trip_id),
    last_reading_id UUID,
    visited_rows BIGINT NOT NULL DEFAULT 0 CHECK (visited_rows>=0),
    complete BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE FUNCTION protect_trip_aggregate_progress() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR (OLD.complete AND NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'aggregate progress cannot be deleted or reset after completion' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trip_aggregate_progress_guard BEFORE UPDATE OR DELETE ON trip_aggregate_progress
    FOR EACH ROW EXECUTE FUNCTION protect_trip_aggregate_progress();

CREATE TABLE trip_aggregate_bucket (
    trip_id UUID NOT NULL REFERENCES trip_record(id),
    resolution_seconds INTEGER NOT NULL CHECK (resolution_seconds IN (60,300)),
    bucket_start TIMESTAMPTZ NOT NULL,
    vehicle_id UUID NOT NULL,
    device_id UUID NOT NULL,
    source TEXT NOT NULL CHECK (source IN ('SIMULATED','OBD')),
    sample_count BIGINT NOT NULL CHECK (sample_count>0),
    first_observed_at TIMESTAMPTZ NOT NULL,
    last_observed_at TIMESTAMPTZ NOT NULL,
    metrics JSONB NOT NULL CHECK (jsonb_typeof(metrics)='object'),
    PRIMARY KEY (trip_id,resolution_seconds,bucket_start),
    FOREIGN KEY (vehicle_id,device_id) REFERENCES device(vehicle_id,id),
    CHECK (bucket_start=date_bin(resolution_seconds*interval '1 second',bucket_start,timestamptz '1970-01-01 00:00:00+00')),
    CHECK (first_observed_at>=bucket_start AND first_observed_at<=last_observed_at
        AND last_observed_at<bucket_start+resolution_seconds*interval '1 second')
);
CREATE INDEX trip_aggregate_vehicle_range ON trip_aggregate_bucket(vehicle_id,resolution_seconds,bucket_start,trip_id);

CREATE FUNCTION guard_trip_aggregate_bucket() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE target trip_record%ROWTYPE; ready BOOLEAN; field TEXT; stat JSONB; n BIGINT;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'durable aggregate bucket cannot be deleted' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' AND (NEW.trip_id,NEW.resolution_seconds,NEW.bucket_start,NEW.vehicle_id,NEW.device_id,NEW.source)
        IS DISTINCT FROM (OLD.trip_id,OLD.resolution_seconds,OLD.bucket_start,OLD.vehicle_id,OLD.device_id,OLD.source) THEN
        RAISE EXCEPTION 'aggregate ownership is immutable' USING ERRCODE='23514';
    END IF;
    SELECT * INTO target FROM trip_record WHERE id=NEW.trip_id FOR SHARE;
    IF NOT FOUND OR target.status<>'COMPLETED' OR target.finalization<>'FINAL'
        OR NEW.vehicle_id<>target.vehicle_id OR NEW.device_id<>target.primary_device_id OR NEW.source<>target.source THEN
        RAISE EXCEPTION 'aggregate requires matching FINAL trip provenance' USING ERRCODE='23514';
    END IF;
    SELECT complete INTO ready FROM trip_retained_evidence_progress WHERE trip_id=NEW.trip_id FOR SHARE;
    IF ready IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'aggregate requires complete retained evidence' USING ERRCODE='23514';
    END IF;
    SELECT NOT complete INTO ready FROM trip_aggregate_progress WHERE trip_id=NEW.trip_id FOR SHARE;
    IF ready IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'aggregate build is absent or already complete' USING ERRCODE='23514';
    END IF;
    IF jsonb_typeof(NEW.metrics)<>'object' OR (SELECT count(*) FROM jsonb_object_keys(NEW.metrics))<>9 THEN
        RAISE EXCEPTION 'aggregate requires all nine metric objects' USING ERRCODE='23514';
    END IF;
    FOREACH field IN ARRAY ARRAY['rpm','speedKph','coolantTempC','engineLoadPct','throttlePositionPct','intakeTempC','mafGps','fuelLevelPct','controlModuleVoltageV'] LOOP
        stat=NEW.metrics->field;
        IF stat IS NULL OR jsonb_typeof(stat)<>'object' OR NOT (stat ?& ARRAY['count','sum','min','max'])
            OR (SELECT count(*) FROM jsonb_object_keys(stat))<>4 OR jsonb_typeof(stat->'count')<>'number' THEN
            RAISE EXCEPTION 'aggregate metric shape invalid' USING ERRCODE='23514';
        END IF;
        IF (stat->>'count')::numeric<>trunc((stat->>'count')::numeric) THEN
            RAISE EXCEPTION 'aggregate metric count must be integral' USING ERRCODE='23514';
        END IF;
        n=(stat->>'count')::bigint;
        IF n<0 OR n>NEW.sample_count THEN
            RAISE EXCEPTION 'aggregate metric count out of range' USING ERRCODE='23514';
        END IF;
        IF n=0 THEN
            IF stat->'sum'<>'null'::jsonb OR stat->'min'<>'null'::jsonb OR stat->'max'<>'null'::jsonb THEN
                RAISE EXCEPTION 'empty aggregate metric must retain null statistics' USING ERRCODE='23514';
            END IF;
        ELSE
            IF jsonb_typeof(stat->'sum')<>'number' OR jsonb_typeof(stat->'min')<>'number' OR jsonb_typeof(stat->'max')<>'number' THEN
                RAISE EXCEPTION 'nonempty aggregate metric requires numeric statistics' USING ERRCODE='23514';
            END IF;
            IF (stat->>'min')::numeric>(stat->>'max')::numeric
                OR (stat->>'sum')::numeric<(stat->>'min')::numeric*n
                OR (stat->>'sum')::numeric>(stat->>'max')::numeric*n THEN
                RAISE EXCEPTION 'aggregate metric statistics inconsistent' USING ERRCODE='23514';
            END IF;
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trip_aggregate_bucket_guard BEFORE INSERT OR UPDATE OR DELETE ON trip_aggregate_bucket
    FOR EACH ROW EXECUTE FUNCTION guard_trip_aggregate_bucket();
