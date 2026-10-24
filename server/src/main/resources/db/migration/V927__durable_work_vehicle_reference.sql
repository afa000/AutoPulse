-- Bind a work marker's vehicle to the canonical reading's vehicle, not merely
-- to an existing vehicle and an independently existing reading UUID.
-- This unique key builds an index over existing ledger keys (not payloads).
-- It takes normal DDL/index locks and adds index maintenance to future capture.
ALTER TABLE telemetry_replay_identity ADD CONSTRAINT replay_vehicle_reading_identity
    UNIQUE (vehicle_id,reading_id);

-- Enforce future stored writes without scanning/repairing all legacy work.
-- Explicit historical validation needs a separate rollout/locking plan.
ALTER TABLE trip_work_item ADD CONSTRAINT trip_work_durable_vehicle
    FOREIGN KEY (vehicle_id,reading_id)
    REFERENCES telemetry_replay_identity(vehicle_id,reading_id) NOT VALID;

-- Original raw FKs, single-reading durable FKs and exact-original write guards
-- remain. No pruning, archive publication, backfill or automatic validation.
