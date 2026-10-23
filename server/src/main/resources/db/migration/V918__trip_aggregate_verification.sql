-- Internal, explicit verification only. No automatic scan, publication or pruning.
CREATE TABLE trip_aggregate_verification (
    trip_id UUID PRIMARY KEY REFERENCES trip_aggregate_progress(trip_id),
    state TEXT NOT NULL DEFAULT 'ACCUMULATE' CHECK (state IN ('ACCUMULATE','COMPARE','VERIFIED','FAILED')),
    last_reading_id UUID,
    visited_rows BIGINT NOT NULL DEFAULT 0 CHECK (visited_rows>=0),
    last_resolution INTEGER CHECK (last_resolution IN (60,300)),
    last_bucket_start TIMESTAMPTZ,
    compared_buckets BIGINT NOT NULL DEFAULT 0 CHECK (compared_buckets>=0),
    failure_code TEXT CHECK (failure_code IN ('MISSING_BUCKET','UNEXPECTED_BUCKET','CONTENT_MISMATCH')),
    CHECK ((last_resolution IS NULL)=(last_bucket_start IS NULL)),
    CHECK ((state='FAILED')=(failure_code IS NOT NULL))
);
CREATE FUNCTION guard_trip_aggregate_verification() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE ready BOOLEAN;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'aggregate verification cannot be deleted' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' THEN
        IF NEW.trip_id<>OLD.trip_id OR (OLD.state IN ('VERIFIED','FAILED') AND NEW IS DISTINCT FROM OLD)
            OR (OLD.state='COMPARE' AND NEW.state='ACCUMULATE') THEN
            RAISE EXCEPTION 'aggregate verification cannot be reassigned or reset' USING ERRCODE='23514';
        END IF;
    END IF;
    SELECT complete INTO ready FROM trip_aggregate_progress WHERE trip_id=NEW.trip_id FOR SHARE;
    IF ready IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'verification requires a complete aggregate build' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trip_aggregate_verification_guard BEFORE INSERT OR UPDATE OR DELETE ON trip_aggregate_verification
    FOR EACH ROW EXECUTE FUNCTION guard_trip_aggregate_verification();

-- Separate reference results, accumulated by Java decimal arithmetic, not builder SQL.
CREATE TABLE trip_aggregate_reference (
    trip_id UUID NOT NULL REFERENCES trip_aggregate_verification(trip_id),
    resolution_seconds INTEGER NOT NULL CHECK (resolution_seconds IN (60,300)),
    bucket_start TIMESTAMPTZ NOT NULL,
    sample_count BIGINT NOT NULL CHECK (sample_count>0),
    first_observed_at TIMESTAMPTZ NOT NULL,
    last_observed_at TIMESTAMPTZ NOT NULL,
    metrics JSONB NOT NULL CHECK (jsonb_typeof(metrics)='object'),
    PRIMARY KEY (trip_id,resolution_seconds,bucket_start),
    CHECK (bucket_start=date_bin(resolution_seconds*interval '1 second',bucket_start,timestamptz '1970-01-01 00:00:00+00')),
    CHECK (first_observed_at>=bucket_start AND first_observed_at<=last_observed_at
        AND last_observed_at<bucket_start+resolution_seconds*interval '1 second')
);
CREATE FUNCTION guard_trip_aggregate_reference() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE phase TEXT;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'aggregate reference cannot be deleted' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' AND (NEW.trip_id,NEW.resolution_seconds,NEW.bucket_start)
        IS DISTINCT FROM (OLD.trip_id,OLD.resolution_seconds,OLD.bucket_start) THEN
        RAISE EXCEPTION 'aggregate reference ownership is immutable' USING ERRCODE='23514';
    END IF;
    SELECT state INTO phase FROM trip_aggregate_verification WHERE trip_id=NEW.trip_id FOR SHARE;
    IF phase IS DISTINCT FROM 'ACCUMULATE' THEN
        RAISE EXCEPTION 'aggregate reference is sealed or absent' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trip_aggregate_reference_guard BEFORE INSERT OR UPDATE OR DELETE ON trip_aggregate_reference
    FOR EACH ROW EXECUTE FUNCTION guard_trip_aggregate_reference();
