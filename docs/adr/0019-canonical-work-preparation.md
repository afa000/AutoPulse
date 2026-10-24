# ADR 0019: Canonical work discovery and queue-order hydration

Status: accepted read-path transition; checkpoint 5 remains incomplete.

## Decision

TripBackfill now chooses one closed TripReadingSource for its discovery page.
When the V923 ledger view exists and the vehicle's exact identity backfill is
complete, UUID discovery and insert-source metadata use trip_ledger_reading.
Incomplete vehicles and old schemas keep telemetry_reading. A source switch does
not reset the persisted raw/work UUID boundary, receipts or visited count.

Page size remains 1..500 and the existing progress row lock, timeout and
transactional cursor/insert rollback remain. Existing work is not overwritten by
ON CONFLICT DO NOTHING. The selected source is fixed for that discovery page;
this is not a new globally repeatable snapshot across workers or backfill calls.
Completing identity copy does not complete work discovery or process queued work.

## Ordering metadata and migration

Forward-only V925 replaces fill_trip_work_order without scanning or rewriting
existing work. On insertion or recovery of a PENDING row with null ordering, the
function selects original measurement time/device/boot/NUMERIC sequence from the
ledger only when that vehicle's identity checkpoint is complete. A missing ledger
target then fails explicitly rather than acknowledging unprepared work. Otherwise
it preserves the original raw-backed behavior. Existing populated metadata and
non-pending work are not rehydrated.

Explicit TripBackfill recovery still handles at most its page size per call,
including old pending rows after raw/work backfill was already complete. Recovery
changes ordering metadata only, not status, accepted_at, next_attempt_at, attempts,
error state or acceptance_order. Arbitrary-size sequence values and microsecond
measurement times remain native database values, without Java integer conversion.
No runner, startup history scan, payload copy or new configuration is installed.
Replacing a function still takes normal PostgreSQL DDL locks; no zero-lock
deployment claim is made. Applied migration files are not edited.

## Original-raw writes still required

V922's original-raw exact reference checks and all three original raw FKs remain.
Selecting ledger for an INSERT source does not bypass BEFORE INSERT triggers,
including on an ON CONFLICT retry. A missing or mismatched original raw reading
still aborts the insertion and rolls back its page/cursor. The trigger can still
copy an exact missing legacy ledger target when the checkpoint is incomplete.

Metadata-only hydration updates do not target reading_id, so V922's reference
trigger does not execute for those updates, as before. Ordering from the accepted
ledger is not a new audit/repair of privileged raw edits. Exact raw/ledger audit
and future deletion-time checks remain necessary; immutable ledger data is never
rewritten to match later raw corruption. A corrupt checkpoint is not a global
coverage certificate, and this change is not a defense against arbitrary SQL.

This removes a raw read dependency, not the raw write dependency or all raw-backed
view arms, public whole-vehicle history, archive copying and retention inspection.
Do not drop raw FKs, expire data or claim a raw-free pipeline because Java discovery
uses ledger. Retention stays disabled/dry-run, wouldDelete stays zero and public
availability stays unchanged.

## Disposable checks and remaining work

Six new cases cover bounded/restartable ready-ledger discovery with original
receipts, source handoff between UUID pages, a V924-to-V925 upgrade/restart with
no existing-row changes, original-ledger hydration despite privileged raw ordering
edits, exact original-reference rejection and cursor rollback, and a corrupt
complete checkpoint with missing ledger targets failing hydration. Existing
pre-ledger recovery, rollback, retry, finalization and correction cases remain.

The full offline backend suite passed 344 tests across 23 fresh suites, zero
failures/errors/skips, including all 141 migration cases. The six focused cases
also passed separately. No production/test/migration changes followed the full
passing run. No fresh frontend/Python/browser, hosted CI, physical-I/O benchmark
or actual expiration/pruning result is claimed for this backend-only step.

Next: retained raw-dependent copy/restriction rules, protected-reference transition
and complete same-transaction deletion checks with atomic generation/availability
publication and truthful expired whole-vehicle history. Checkpoints 5 and 6 remain
incomplete. All verification uses disposable databases; owner-data deletion still
requires separate explicit approval of concrete targets and safeguards.
