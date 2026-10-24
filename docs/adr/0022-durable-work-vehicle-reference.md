# ADR 0022: Bind work ownership to the canonical reading vehicle

Status: accepted additional reference guard; checkpoint 5 remains incomplete.

## Decision

V927 adds a unique ledger key (vehicle_id, reading_id) and a NOT VALID composite
foreign key trip_work_durable_vehicle from trip_work_item's same pair. Existing
separate FKs proved that a vehicle and reading existed, but not that they belonged
together. New stored work and changes to either referencing key must now match
the canonical reading's vehicle, including vehicle-only updates that do not invoke
the reading_id-specific original-reference trigger.

This is one additional work-ownership invariant, not immutability of all work
fields or validation of receipt/status metadata. It does not introduce equivalent
new composite ownership constraints for trip or correction links. Their existing
guards/references remain, as do all original raw FKs and single-reading durable
FKs. Exact original-raw verification still runs for attempted reading inserts or
reading_id updates. No raw-free reference path, cascade or pruning is introduced.

## Rollout cost and legacy state

The unique ledger key builds an index over existing vehicle/reading UUIDs, not
payloads. This DOES scan ledger keys, takes normal DDL/index locks and adds index
maintenance to future accepted capture. It is not a no-scan/zero-lock migration.

NOT VALID avoids a historical WORK-table validation scan or automatic repair.
Existing mismatched work can survive upgrade unchanged; ordinary non-key status
updates are not evidence of its ownership being validated. The catalog remains
unvalidated until explicitly validated, even in a fresh schema whose new rows
have always been checked. No validation runner or startup scan is installed.

Whole-table VALIDATE CONSTRAINT needs a separate rollout/locking plan. Tests
rehearse failure on one deliberately invalid disposable legacy row, correct only
that fixture, then validate/restart. This is not an application repair procedure,
bounded validation job or owner-data operation. Validation proves pair existence,
not exact payload equality or the safety of raw deletion.

## Transaction and conflict behavior

If original-reference write-through captures a legacy ledger target and the later
composite FK rejects a wrong owner, the failed statement rolls capture and work
back together. The unchanged valid backfill can retry with the correct owner.
Before-insert raw guards still run on conflict retries. PostgreSQL does not check
an AFTER INSERT FK for a proposed row discarded by ON CONFLICT DO NOTHING: that
no-op may succeed while leaving the already-correct stored owner unchanged. Do
not describe this FK as validating every discarded proposal or all historical rows.

## Disposable checks and limits

Six new cases cover preservation of invalid legacy work without validation;
wrong-owner inserts; vehicle-only and reading-only cross-vehicle reassignment;
rollback of legacy ledger capture on FK failure; explicit validation failure,
fixture correction and repeat validation/restart; and conflict no-op preservation.
Existing original raw deletion rejection, exact-data checks and full processing/
correction/publication tests remain enabled. Applied migrations are unchanged.

The full offline backend regression passed 361 tests across 23 fresh suites,
zero failures/errors/skips, including all 149 migration cases. The six new focused
ownership cases also passed separately. No production/test/migration changes
followed the full passing run. No new frontend/Python/browser, hosted CI, capture
throughput/index-cost benchmark, owner-database validation or actual expiration/
successful pruning evidence is claimed.

No route, runner, saved flag, expiry cutoff, availability publication or pruning
is added. Retention remains disabled/dry-run and wouldDelete=0. Reference coverage,
remaining ownership restrictions and complete same-transaction deletion/generation/
availability checks still precede raw-FK removal or bounded opt-in pruning.
Checkpoints 5 and 6 remain incomplete; owner deletion needs separate explicit
approval of concrete ranges and safeguards.
