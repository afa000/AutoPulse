# Trip-processing review and recovery

This is an internal developer handoff, not a public operations interface or
permission to change an owner's database. Processing is disabled by default.
The implementation does not provide automatic repair for every review condition.
Never clear a database/volume, delete queued work, change finalized content or
edit an applied migration to make an error disappear.

## Current retention entry point (V931)

Checkpoint 5 is complete and locally verified: 408 backend tests, 59 frontend
tests plus typecheck/lint/build, and 94 Python tests on each of 3.12 and 3.14.
All actual retention checks used disposable databases. Saved defaults remain
disabled/dry-run; checkpoint 6, owner-data operations and hosted CI are separate.

Earlier retention sections below describe implementation history. ADR 0027
supersedes their preview-only prerequisite lists with explicit guarded retention.
The saved enabled=false/dry-run=true defaults remain. No automatic invocation or
public admin route exists. Do not start/migrate/prune an owner database to test it.

RetentionMaintenance.runPage(vehicle, limit, cursor) performs one bounded stage
(1..100): retained evidence, durable buckets, independent verification, then prune.
Disabled does nothing. Dry-run never creates archives/progress or deletes rows.
Non-dry execution requires explicit enabling and repeats all synchronized checks
inside the deletion transaction. BUSY/FAILED are not permission to bypass a guard.

Persisted archive progress resumes after restart. Reuse the trusted returned prune
cursor only with unchanged vehicle/settings/limit and within its lifetime. After
a deleted anchor, the result is rebased; complete=false with nextCursor=null means
continue at the new first raw row. On PAGE_CHANGED/expiry restart the traversal.
Always bound total calls/runtime. complete means old-raw traversal exhaustion,
not that protected rows were deleted. Inactive correction references remain blockers.

V931 replaces work/link raw FKs with a guarded exact-archive raw-delete trigger,
retaining their ledger FKs, rows and final immutability. The correction raw FK
remains. Legacy references are not scanned/repaired/validated by migration;
ineligible legacy rows fail deletion. Expiry proof is immutable and generation
changes in the same transaction. Never disable triggers or edit finalized links
as a shortcut. Full ledger payload retention is intentional; no compact-storage
benefit is certified. Owner-data deletion still requires separate named approval.

## Inspect before changing anything

Use the bounded internal `TripProcessingWorker.status(vehicleId)` lookup. Record
the vehicle, stage, state/revision, last result, safe error/SQLSTATE, failure
counts and operational timestamps. Inspect only that vehicle's active correction
plan/publication if applicable. Raw telemetry and original receipt times remain
the source of truth; the live SSE stream is not the processing log.

| State or result | Meaning and safe response |
| --- | --- |
| RETRY_WAIT | Wait until the saved deadline; the same stage is retried. Do not bypass it by running another service directly. |
| RUNNING | A page has a claim lease. Let it finish; after expiry the worker can resume the same stage. Expiry does not cancel an already-running transaction. |
| PAUSED / TRANSIENT_DATABASE_FAILURE | The failure budget was exhausted. Diagnose the infrastructure cause before explicitly resuming. |
| PROCESSING_INVARIANT / DATABASE_OPERATION_REQUIRES_REVIEW | Investigate the code, schema and exact affected checkpoint in a disposable reproduction. Do not repeatedly retry an integrity failure. |
| REVIEW_POLICY_CHANGED / POLICY_CHANGED | The saved projection or publication used another policy revision. Decide whether the change was intentional. Resume alone cannot migrate policy-dependent history. |
| REVIEW_FINALIZED | Accepted input overlaps immutable finalized evidence. Preserve it for review. A separately approved, versioned rebuild would need retained raw evidence; automatic unsealing is not supported. |
| REVIEW_TOO_WIDE | The connected correction exceeds the 48-hour automatic core limit. A separately designed/reviewed backfill is required; do not remove the limit or delete evidence to shrink the window. |
| REVIEW_PUBLICATION / REVIEW_REQUIRED | State cannot be published/sealed safely, or unresolved review remains. Inspect the specific plan/work; do not silently acknowledge it as applied. |
| IDLE / NO_DUE_WORK | No selected stage is currently due. Future-quarantined input can still be queued. IDLE after another result means only that one page finished. |

These are safe diagnostic codes, not exception messages. Do not copy credentials,
SQL with private payloads, environment contents or full telemetry into public logs.
The status row is bounded latest-state reporting, not a complete error audit trail.

## An unfinished publication needs special care

An active publication deliberately hides that vehicle's derived trips/evidence.
Future trip APIs must report temporary unavailability/PUBLICATION_PENDING rather
than interpreting empty gated views as no trips. Raw trip tables can contain
partially rebuilt internal state and must not be served directly.

Do not delete the publication, change its phase to DONE/ABANDONED, clear the
READY plan, release the visibility gate or regenerate identities manually. Fix
the actual failure, preserve the original policy/snapshot/identity mapping, and
resume the existing publication through the worker. If an intentional policy
change makes that impossible, keep the safe pause and request an explicit
recovery design; a general rollback/abandon tool is not implemented.

Raw ingestion and latest/live updates remain independent. While projection is
paused, valid new readings can still accumulate as queued work, so derived trip
data must not be described as current or complete.

## Explicit resume is not a repair operation

After reproducing and fixing the cause, re-read status and use internal
`resume(vehicleId, expectedRevision)`. The revision check rejects stale requests.
Resume resets the consecutive retry budget and permits the same stage to run;
it retains total failures and does not change policy, clear review work, unseal
trips, rewrite receipts, delete data or release a publication gate. If the cause
remains, processing pauses again. Do not call lower-level services to bypass this.

Owner-data recovery, configuration changes or an exceptional rebuild need a
separate explicit decision. The tests in this repository use disposable databases;
they do not authorize demonstrating recovery on development data.

## Performance evidence and limits

The PostgreSQL integration audit uses EXPLAIN ANALYZE with buffers and normal
planner settings, after analyzing a 5,000-reading fixture. Originally a
10-reading page visited all 5,000 raw rows. V910 stores immutable native canonical
sort keys on queued work and materializes the ordered, limited work page before
joining raw payloads. The audited backlog and processed-history-tail cases each
visit 10 raw rows; work-row visits were 10 and 20 respectively. These are
representative observed row visits, not a wall-time SLA, a forced-index benchmark
or a proof for every distribution, future/deferred backlog, tie density or MVCC
churn. Large correction-window queries and deployments still need their own plans.

V910 leaves existing pending work unprepared at migration time. Bounded backfill
pages hydrate only missing sort metadata, including when old raw backfill was
already marked complete. New work is initialized transactionally by a trigger.
Receipts, retry deadlines, identities, attempts and acknowledgments are preserved.
Hydration can restart or roll back without losing the original work. Do not use a
one-shot unbounded UPDATE against owner history as a shortcut.

## Explicit replay identity coverage audit

The internal ReplayIdentityAudit service provides read-only pages for inspecting
raw-to-ledger coverage without copying data or touching backfill checkpoints.
Call readPage(vehicleId, afterReadingId, pageSize), starting with a null boundary
and a size of 1..500. Continue using lastReadingId until complete. An exactly full
page requires an additional call. No public route, scheduler, startup scan or
automatic constraint validation is installed.

Each page reports visited, matched, missing and mismatched counts from its own
read-only repeatable snapshot. Payloads are compared exactly in PostgreSQL, not
loaded into the application. A known empty vehicle and an unknown vehicle remain
distinct. This can recheck metadata even after the copying checkpoint is complete.
It does not repair a conflict or mark a vehicle ready for deletion.

Pages are not a globally frozen history scan or a durable safety certificate.
Neither one vehicle's audit nor a successful ID-only foreign-key validation
certifies all vehicles or exact payload equality. Reference validation rehearsal
exists only in disposable tests: table-wide PostgreSQL validation needs a separate
rollout/locking plan, not a bounded worker loop against owner data. Original raw
foreign keys remain, retention stays disabled, and write-time pruning checks are
still required. See ADR 0015 for the precise transition limits.

## Canonical forward and finalization reads

When the V923 ledger view exists and a vehicle's identity backfill is complete,
forward projection and finalization use it for reading payloads/metadata. Older
schemas and incomplete vehicles keep raw reads. ADR 0019 extends this readiness
rule to work discovery and V925 ordering hydration. Completing identity copy does
not replace work backfill or certify safe pruning; original-raw write checks and
FKs remain. No runner finishes either checkpoint automatically.

Each projector/finalizer page chooses one source inside its transaction. Work IDs
are ordered and limited before payload lookup; full native timestamp winner and
timer-continuation checks use that same source. Finalization still recovers original
work receipts in bounded pages, waits the exact quiet period and refuses pending,
review or correction work. The coordinator uses the source for advisory review
discovery; workers recheck state transactionally, not under a new global snapshot.

These are internal read-path changes. Raw FKs, original-raw write checks, finalized
immutability and public availability behavior remain unchanged. Ledger-backed Java
SELECTs do not prove a raw-free pipeline. See ADR 0016 for the phased transition.

## Explicit non-deleting retention barrier inspection

RetentionPreview.report remains the ordinary read-only diagnostic. The separate
internal reportUnderBarrier method requires explicit invocation and can briefly
block ingestion: it takes the processing advisory lock, shares the existing policy
row, then locks the activity/receipt row. Contention returns BUSY without a page;
success returns CHECKED_NOT_AUTHORIZED with wouldDelete=0. Disabled mode makes no
database calls. No route, scheduler, migration or automatic invocation is added.

The locking transaction uses READ COMMITTED so commits before receipt acquisition
are visible to subsequent checks. It issues no data changes. All locks release
before return; never treat the result as permission for later deletion. Archive
writers and privileged SQL are not globally frozen. Missing receipt registration
fails explicitly rather than masquerading as perpetual contention.

Retention remains disabled/dry-run, raw FKs and guards remain, and availability
is unchanged. A future pruner still needs complete write-time checks and atomic
deletion/availability/generation publication in one transaction. See ADR 0018.

The barrier result includes per-reading archiveChecks for returned rows only.
VERIFIED_COVERAGE means exact original raw/replay metadata and JSONB match a
retained owner eligible in trip_verified_archive, with correct provenance and
closed bounds. Finalization, evidence copying and aggregate building alone do not
qualify. verifiedTrip is absent for any failed coverage check. BUSY/disabled/empty
pages have no checks; this is not full-vehicle coverage.

Policy candidates and archive coverage are separate diagnostics. Neither removes
pending-work/replay protections or authorizes a write. Archive writers are not
globally locked: the coverage statement can see archive completion after the
earlier preview read. Results are not a shared immutable snapshot or a durable
certificate. No deletion guard or availability publication is installed here.

After choosing a page, inspection pins only its returned raw UUIDs in UUID order
with FOR UPDATE SKIP LOCKED before running archive checks. Any missing/contended
row produces BUSY with no partial preview/coverage and releases all locks. Never
replace this with blocking row acquisition: a raw writer may hold the row while
its generation trigger waits for the receipt lock held by inspection. Lookahead
is not pinned; an empty page takes no raw locks. These locks end on success or
rollback too, so a returned result still cannot authorize subsequent deletion.

## Canonical work preparation (V925)

Explicit TripBackfill pages now discover UUIDs and source insert metadata from
trip_ledger_reading only after the view exists and exact identity-copy progress is
complete. Incomplete/older-schema vehicles keep raw reads. Page size remains
1..500, source selection is fixed per discovery page, and the same persisted UUID
boundary resumes across handoff. An exactly full page still needs another call.

V925 changes the ordering trigger only: existing rows are not hydrated during
migration. Explicit recovery pages populate missing pending sort metadata from
ledger for ready vehicles, fail on a missing ledger target, and otherwise retain
raw behavior. Receipt, retry/attempt/error state and acknowledgments are unchanged.
Normal DDL locks still apply to migration.

Original raw reference triggers run on attempted inserts, even conflict retries,
and the raw FKs still reject deletion. Ledger-sourced discovery is not a raw-free
write path. Metadata-only hydration does not audit/repair privileged raw changes.
Retained-evidence copying and remaining raw paths still need their own transition
or explicit restrictions before pruning. See ADR 0019; retention remains disabled.

## Canonical retained-copy bounds

For unfinished FinalTripEvidence pages, original ledger measurement time determines
closed-bound membership when the ledger view exists and exact identity backfill
is complete. Older/incomplete vehicles keep raw classification. All linked UUIDs
are still paged first, including out-of-range confirmation links, with a 1..500
limit and durable restartable boundary. No migration or automatic invocation is
introduced; schema remains V925.

A later raw timestamp edit cannot hide an original member outside the trip before
its INSERT guard checks exact original data. Such a mismatch rejects and rolls
back the page. A canonically outside confirmation remains outside even if its raw
time is moved inside. Only bound classification changes: original-raw write
checks, immutable final membership and raw FKs remain. Source handoff is not repair
or a deletion permit. Completed copies stay no-op and are not re-audited. See
ADR 0020. Retention remains disabled/dry-run and public availability is unchanged.

## Canonical linked membership (V926)

trip_evidence uses raw-linked membership for incomplete/missing vehicle identity
checkpoints, ledger-linked metadata for identity-ready but unverified trips, and
retained membership for independently verified final trips. The arms are disjoint
in one statement snapshot, including when a verified trip's vehicle checkpoint is
absent. Every arm still joins trip_visible_record; no readiness state bypasses
REBUILDING or correction-publication visibility.

V926 replaces the view definition without copying/changing stored membership or
archive progress. Normal DDL locks apply. Existing valid member IDs, summaries and
generation do not change just because the source changes. Active/provisional trips
remain mutable and protected; identity readiness alone is not archive ownership.

Original raw FKs/copy/reference guards remain. Public payload-source rules and
default whole-vehicle history are unchanged, so this is not a raw-free pipeline,
expiration publication or pruning. See ADR 0021; retention stays disabled/dry-run.

## Work-vehicle reference integrity (V927)

The composite trip_work_durable_vehicle FK checks stored (vehicle_id,reading_id)
against the ledger's new unique pair. It also protects vehicle-only reassignment,
which does not target reading_id and therefore does not run the old reference
trigger. Original raw/single-reading FKs and exact-original checks remain.

Migration builds an index over ledger UUID keys with ordinary DDL/index locks;
it does not validate or repair historical work. The new FK is NOT VALID. Existing
legacy mismatches and unchanged-key status updates are not certified by upgrade.
An eventual table-wide validation requires separate planning; no application job
or startup command performs it. Validation/repair is rehearsed only in disposable
fixtures. ON CONFLICT DO NOTHING can discard a proposed tuple without checking an
AFTER INSERT FK, while existing stored ownership remains unchanged. See ADR 0022.
This is not exact payload verification, raw-FK removal or pruning authorization.

## Trip-link vehicle ownership (V928)

New trip links and reading/trip retargets require matching canonical reading and
trip vehicles. Trip-ID-only retargets now run the existing original-raw exact
check/capture too. Parent/final guards retain first precedence and FOR SHARE locks;
raw verification follows, then the owner comparison. A trip's vehicle is immutable
on UPDATE, preventing a later parent move; other provisional rules remain.

These are future-write guards, not historical link validation or repair. Existing
legacy mismatches can survive migration unchanged. Final associations cannot be
edited to work around them. BEFORE INSERT ownership checks apply even to conflict
proposals; a failed attempt rolls back ledger capture and link creation. Normal
DDL locks and extra point-lookup write cost remain. No automatic audit/repair job,
device/source/bound rule or new correction-owner guard is added. See ADR 0023.
Original raw FKs remain; retention stays disabled/dry-run with no availability change.

## Correction-reference vehicle ownership (V929)

New correction references and reading/replay/segment retargets require canonical
reading and plan vehicles to match. Parent-only retargets still run exact-original
raw verification/capture. Ordered BEFORE triggers acquire plan -> replay -> segment
share locks, recheck relationships, then verify raw and compare vehicles. They do
not acquire raw first while waiting for parent rows.

Plan.vehicle_id, replay.plan_id and segment.replay_id cannot be reassigned. Other
progress/status/invalidation/source changes retain their existing rules. These
guards do not validate all historical references, plan-request/device/source/bound
semantics or arbitrary SQL lock ordering. Existing raw/durable/composite-parent FKs,
finality and publication gates remain. No scan/repair runner or pruning switch is
added. See ADR 0024; retention remains disabled/dry-run and availability unchanged.

## Explicit legacy-reference audit

TripReferenceAudit.readPage(kind, after, limit) is an internal read-only diagnostic
for WORK, LINK or CORRECTION references, with 1..500 reference rows per page and
a separate REPEATABLE READ snapshot per call. It has no HTTP/startup/scheduled
entry point and writes nothing. Traverse globally so wrong vehicle owners cannot
hide behind a vehicle filter. Pass lastCursor to a fresh instance of the same
kind; correction boundaries include replay and reading UUIDs. A full final page
needs an extra empty call to establish exhaustion.

Counts report absent raw, absent ledger, differing exact originals and canonical
vehicle disagreement. Counts can overlap and count references, not unique raw
rows. Missing ledger also means owner agreement cannot be shown. complete and
zero counts are page-local observations, not multi-page coverage certificates or
deletion authorization. See ADR 0025 for concurrent-write and scope limitations.
No automatic backfill, repair, constraint validation, raw-FK removal, availability
publication or pruning occurs; saved retention defaults remain disabled/dry-run.

## Selected-reference inspection under retention barriers (V930)

reportUnderBarrier returns referenceChecks in selected raw-page order, separately
from archiveChecks. Work must have canonical vehicle agreement and PROCESSED
status; a link must have canonical trip-vehicle agreement and match the retained
trip UUID. Missing targets/coverage and ownership differences get explicit states.
Any correctionReference=true, even for invalidated/discarded history, remains a
conservative blocker. No referenced correction rows are removed or skipped.

The selected-ID statement runs after raw pinning inside the existing transaction.
V930 adds a reading-first correction index for EXISTS lookups, with normal key
index build/DDL locking costs. This is not a history-validation migration. BUSY,
disabled and empty results have no reference checks; errors release all locks.
No lookahead/whole-replay payload expansion or new parent row lock is introduced.

WRITE_TIME_REFERENCE_RECHECK_REQUIRED is an additional hard prerequisite. Matched
reference states do not waive exact archive/provenance/policy checks or remaining
raw FKs. Policy candidates are not deletable-row counts. A reference writer can
commit after return, so results cannot be reused by a later prune transaction.
See ADR 0026; availability, saved flags and wouldDelete=0 remain unchanged.

### Existing selected-reference and trip-parent pins

Before raw pinning, the barrier now share-locks selected existing work rows,
selected existing link rows, then their distinct trip parents. Each step uses
SKIP LOCKED and a same-statement locked-versus-present count: existing contention
returns BUSY/no partial results and releases earlier pins. Missing work/links are
still reported as missing, not confused with contention. No lookahead/unrelated
trip or whole correction replay is locked, and no new migration is needed.

After raw acquisition, fresh work/link counts must still match the pinned counts.
Missing references have no row to lock; a writer can fill a missing slot before
raw is pinned. Such a newly committed unpinned reference yields BUSY/retry rather
than being treated as frozen. Existing pinned rows cannot change/disappear and
the raw guard blocks new reference writes for the rest of this transaction.

This blocks selected existing reference/parent changes during fresh checks and
avoids waiting on a writer that might be waiting at the receipt barrier. Pins
end at return; a work writer can then commit and a fresh inspection can differ.
Initial preview policyCandidates is still preliminary; this is not complete
write-time policy enforcement or a reusable deletion permit. See ADR 0026's
continuation; original raw FKs, disabled/dry-run defaults and availability remain.
