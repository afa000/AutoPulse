# ADR 0015: Dual durable reading references before raw-reference removal

Status: accepted transition; checkpoint 5 remains incomplete.

## Decision

V922 adds ledger foreign keys to trip_work_item, trip_reading_link and
trip_correction_reading. Each reading_id references the unique reading_id in
telemetry_replay_identity. All three original raw foreign keys remain. There is
no cascade, raw deletion, guard relaxation or change to finalized associations.

The new constraints are NOT VALID: PostgreSQL enforces new references without
scanning all historical rows during migration. This is deliberately NOT a claim
that every legacy reference has been covered or that constraints are validated.
No existing row, payload or backfill progress is changed by migration. Normal
DDL locks are still required; this is not a zero-lock deployment guarantee.

## Future writes and compatibility with legacy history

A BEFORE INSERT / UPDATE OF reading_id trigger on each table reads exactly one
raw row by primary key, taking FOR SHARE until the reference transaction ends.
It copies a missing ledger identity using the original UUID, native identity,
vehicle, recorded/received times, source and JSONB payload. An existing identity
is never rewritten. The trigger then verifies every field against the raw row;
missing raw input or a conflicting ledger/native identity aborts the statement.

This preserves processing of pre-ledger raw readings: a new bounded work/link/
correction write can establish its exact ledger target in the same transaction.
Capture and reference insertion roll back together. Accepted modern ingestion
already captures its ledger row before enqueuing work; the new trigger verifies
that existing row. JSONB equality retains PostgreSQL numeric semantics.

This is write-through coverage, not an automatic historical backfill job. An
INSERT ON CONFLICT DO NOTHING can execute BEFORE INSERT triggers even when its
reference already exists, so a bounded backfill retry may establish a missing
ledger target without changing the existing reference. It never marks the
vehicle replay backfill complete. Explicit ReplayIdentityBackfill remains the
bounded whole-vehicle coverage mechanism and verifies original metadata.

Ordinary status updates that do not target reading_id do not run the new trigger.
PostgreSQL can skip unchanged foreign-key checks; such updates are not evidence
that untouched legacy references have been validated. Existing final-link guards
continue to reject updates/deletes, including no-op updates. No historical final
association needs modification to gain a ledger target through explicit backfill.

## Limits and remaining work

All reference paths still require raw to exist. Raw-backed queue hydration,
projection, correction and public metric queries are not converted by this DDL.
The additional row lock and exact payload checks add write cost; no throughput
benchmark or pruning safety certificate is claimed. The function is not a
defense against arbitrary privileged SQL edits or a permanent raw-delete barrier.

Before original raw FKs can be removed, legacy reference coverage must be proven,
new constraints deliberately validated, raw-backed readers/workers transitioned
or protected by a finalized-data restriction, and equivalent write-time guards
and generation/availability publication demonstrated. Never remove raw FKs just
because these second constraints exist. Retention remains disabled/dry-run with
wouldDelete=0. No owner database is used for development checks.

Tests cover no-scan/restart upgrades, unchanged legacy status writes, exact
one-target capture and rollback, legacy link/correction write-through, conflicting
native UUIDs, changed raw metadata, normal projection/correction references and
continued raw FK rejection. Existing final/evidence/replay guard and concurrency
regressions remain enabled. Disposable fixtures only; no actual pruning claimed.

Final full offline backend regression passed 294 tests across 23 fresh suites,
with zero failures/errors/skips, including six new cases. No production/test/DDL
changes followed that run. Frontend, Python, browser, hosted CI and actual pruning
were not reverified in this backend-only step.

## Explicit coverage audit and disposable validation rehearsal

ReplayIdentityAudit.readPage(vehicle, after, limit) is an internal, explicitly
called diagnostic. Each page uses a new read-only REPEATABLE READ transaction
with a ten-second timeout. Page size is 1..500 raw UUIDs, materialized before
payload lookup; exact UUID/native identity/vehicle/time/receipt/source/JSONB
comparisons remain in PostgreSQL. Only counts and the last UUID leave SQL.
Unknown vehicles are rejected; a known empty vehicle has an empty complete page.

MATCHED means every stored field matches at that page's snapshot. MISSING means
the required reading UUID has no ledger target, even if its native identity has
been occupied under a different UUID. MISMATCHED means the reading UUID exists
but differs in at least one original field. This service never copies/repairs a
row, advances backfill state, changes a constraint, generates work or enables
retention. Completed ReplayIdentityBackfill calls intentionally return no work;
the separate audit can still detect later privileged metadata corruption.

Pass the last UUID to a fresh instance for the next page. An exactly full page
requires another call to establish exhaustion. The boundary is trusted/internal,
not a signed public token. Pages have separate snapshots: complete means that
page exhausted its raw UUID range, NOT a durable multi-page certificate. New
ingestion behind an earlier UUID boundary, arbitrary SQL changes and other
vehicles cannot be certified by a single vehicle's completed traversal. Auditing
raw candidates is not a scan of raw-free ledger identities or future expired
references; currently the original raw FKs still guarantee their raw targets.

Disposable PostgreSQL tests explicitly rehearse validating all three new FKs
after exact legacy backfill, without editing final links, work, raw data or trip
summaries. Legacy correction targets reject validation while coverage is missing
or partial. Validation of all three runs in one test-only transaction, so failure
does not leave a partly validated set. Repeat validation and Flyway restart retain
the validated catalog state in those fixtures; fresh/default migration still
leaves the constraints NOT VALID. No new migration changes that default.

PostgreSQL ALTER TABLE VALIDATE CONSTRAINT checks table-wide existence, not exact
payload/metadata equality. It is not bounded/resumable page work. These statements
are confined to small disposable fixtures, with no production validation runner,
startup DDL or owner-DB operation added. A real rollout still requires a separately
planned validation/locking window and complete coverage for every vehicle. A
passed vehicle audit or FK validation alone never permits dropping raw FKs or
deleting data. Write-time rechecks and remaining raw-reader protections remain.

This audit/validation continuation passed the full offline backend suite:
300 tests across 23 fresh suites, zero failures/errors/skips, including six new
cases and all 120 migration cases. No production/test/DDL code changed after the
successful run. No new frontend/Python/browser, hosted-CI, performance or actual
pruning evidence is claimed.

Subsequent ADR 0022 and V927 additionally bind trip_work_item's vehicle/reading
pair to a unique canonical ledger pair. The new composite FK remains NOT VALID
by default and replaces none of the references described here. Building its
supporting unique index scans ledger UUID keys, not payloads. Historical ownership
validation and raw-reference removal remain separate guarded rollout steps.

ADR 0023 and V928 subsequently extend trip-link exact-original verification to
trip_id-only retargets and compare canonical reading/trip vehicle owners after
the existing parent/final guards. A trip's vehicle owner cannot be reassigned.
No historical link scan/repair or correction-owner guard is implied; original
raw references and all immutable-final restrictions remain.

ADR 0024 and V929 subsequently extend correction-reference exact checks to parent-
only retargets, lock plan/replay/segment before raw verification, compare canonical
vehicle ownership and freeze the parent ownership path. Historical references
are not scanned/certified; original raw FKs and immutable/publication guards remain.

ADR 0025 adds a separately invoked bounded global TripReferenceAudit for existing
work/link/correction references, checking exact raw/ledger targets and canonical
vehicle ownership. Page-local diagnostic counts do not repair history, validate
constraints or certify multi-page coverage, and do not permit raw-FK removal.
