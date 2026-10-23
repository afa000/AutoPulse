# ADR 0027: Guarded final-only retention and atomic history availability

Status: accepted and verified; checkpoint 5 complete. Supersedes
the preview-only/raw-FK-removal prerequisites in ADRs 0006 and 0015-0026; their
earlier results remain historical evidence. No owner-data deletion is authorized.

## Explicit opt-in execution, not an automatic cleanup job

Saved defaults remain retention.enabled=false and retention.dry-run=true. The
internal RetentionMaintenance.runPage(vehicle, limit, cursor) entry point handles
one bounded stage per explicit call; limit is 1..100. There is no scheduler,
startup invocation or unauthenticated admin route. Disabled calls do not touch
the database or clock. Dry runs inspect only and create no archive/progress rows.
RetentionPreview.prunePage provides a single explicitly called pruning stage
when the archive is already ready; it never consumes an earlier returned report
as a permit. Both enabled=true and dry-run=false are required for deletion.

Maintenance selects one old FINAL COMPLETED trip without a verified archive,
copies retained membership in bounded pages, builds durable UTC 1m/5m exact
contributions, then independently verifies them. Each existing service commits
its own durable progress. BUSY/FAILED states are visible, not bypassed. When old
archives are ready, one prune page executes. Concurrent stage calls use existing
trip progress/advisory locks; pruning uses the vehicle processing/receipt barrier.
Failed archives conservatively block progress until investigated.

## Authoritative write transaction

Pruning acquires the existing vehicle processing advisory lock, policy share lock,
receipt update lock, selected work/link/trip-parent share pins and selected raw
update pins, without waiting on contended rows. The post-raw missing-reference
gap check remains. BUSY releases all acquired locks and returns no deletion.
After pins, policy/progress/protections are freshly read, and exact replay,
verified retained archive/provenance and reference ownership are checked again
for the actual selected rows. Only readings passing every check may be deleted.

Measurement age is a strict UTC day cutoff, independent of telemetry maxima;
original receipt quarantine is independently strict. Missing/incomplete replay,
activity or work backfill; policy mismatch; projection not strictly past the
measurement; pending/review work; non-idle control; active correction/publication;
open/rebuilding/provisional trips; secondary devices; missing/incorrect membership
or canonical originals; incomplete/failed aggregate verification; latest raw; and
any correction-reading reference remain protected. Inactive correction references
are not silently removed. Retention is finalized-only, not arbitrary idle-data
cleanup or complete re-derivation after expiration.

The whole transaction has the existing ten-second timeout and at most 100 selected
raw rows. The page cap is not a universal physical-I/O/runtime guarantee. A failed
statement or crash before commit rolls back raw removal, expiry proof and read
generation together. Committed archive preparation remains reusable. Retrying
cannot duplicate membership/buckets or delete an identity twice.

## V931 reference transition and database enforcement

The immutable canonical ledger preserves original payloads, UUID/native identity,
vehicle, source, measurement and receipt times. V931 retains all work/link rows,
their ledger FKs, final-link immutability and retained evidence. It replaces only
the work and trip-link raw foreign keys with a BEFORE DELETE retention guard;
the correction raw FK remains. NOT VALID ledger constraints are not automatically
validated and old references are not scanned/repaired/certified during migration.
Legacy missing/mismatched ownership/originals fail the per-row deletion guard.

Referenced raw deletion requires transaction-local retention settings and repeats
the exact canonical, verified final membership/provenance, age/quarantine,
processing/late-work/correction/latest protections in PostgreSQL. A flag alone
cannot waive those checks. Expiry proof is inserted by the guarded deletion
trigger, cannot be directly fabricated through ordinary INSERT, and is immutable.
As before, deleting entirely unreferenced synthetic data is not a supported
retention operation; the bounded service never selects it as eligible. This
transition is not a defense against administrators disabling triggers or issuing
arbitrary privileged DDL. Normal DDL locks and expiry-index maintenance apply.

Processed work with committed expiry proof becomes immutable. Bounded queue
discovery may retry an expired work identity against its canonical ledger proof;
other new link/correction references still require original raw, and final links
remain immutable. Raw cannot be recreated by duplicate/conflicting ingestion:
the existing canonical replay gate remains authoritative.

## Atomic availability and truthful readers

telemetry_raw_expiry records one canonical reading UUID, vehicle, measurement time
and expiry time. It commits in the DELETE transaction; the existing raw-delete
generation trigger advances that same vehicle's history generation. Old cursors
therefore fail PAGE_CHANGED, not silently skip pruned anchors. Reader points,
availability and generation share one read-only REPEATABLE READ snapshot.

Raw history excludes identities with expiry proof, including trip-filtered ledger
reads: physically expired observations are not presented as raw availability.
Range- and trip-scoped markers distinguish AVAILABLE from PARTIALLY_EXPIRED and
EXPIRED, rather than inferring absence from an empty query. Known empty windows
without expiry markers remain AVAILABLE. Expired raw-only windows advertise 1m
and 5m, not raw. rawUnavailableBefore is an exclusive upper bound on expired
observations in the requested window, NOT a claim of contiguous expiration of
all earlier data; protected earlier rows can remain. The UI states that limit.

Trip aggregate queries continue using verified retained input. Default unfiltered
aggregates use a disjoint view of remaining raw plus only canonicals with committed
expiry proof when expiration exists. The view excludes raw mirrors and cannot
resurrect arbitrary raw-free ledger rows. Existing precise bucket calculations,
clipped edges, null counts, weighted sums, density caps and pagination remain.
The opt-in cached owned-aggregate reader retains its disjoint ownership/equality
checks and now reports the same range-specific availability. No averages of
averages, zero fill or fabricated continuous coverage is introduced.

## Continuation, late arrivals and storage limits

Archive progress survives process recreation. Prune cursors are trusted internal
records, expire after 15 minutes and bind policy/settings/time/generation. After a
successful deletion, continuation is rebased to the last surviving earlier raw
anchor and the new generation. If none survives, nextCursor=null with complete=false
means continue from the new first raw row. A stale cursor after crash/concurrent
ingestion must restart; restarting is safe. complete means this traversal exhausted
old raw candidates, not that all old protected data expired. Callers must bound
their own total calls and stop/retry/report BUSY or FAILED rather than spin.

Newly accepted late identities remain raw and pending/review; they do not rewrite
final summaries or disappear into old aggregate totals without visibility. Their
generation invalidates cursors. Correction references retain raw indefinitely until
a future separately proven lifecycle transition; no exceptional final rebuild is
promised. Latest telemetry and existing live behavior are preserved.

Full canonical JSONB is deliberately still retained for exact replay and clipped
aggregate correctness. Removing raw rows is demonstrated, but compact-ledger
storage savings are NOT claimed. A compact representation remains separate work.

## Verification boundary

Disposable tests exercise real deletion, unchanged summaries/links/ledger/latest,
repeat runs, small-page process recreation across archive/build/verify/prune,
rollback after DELETE, exact receipt equality, multi-trip/bucket aggregate equality,
explicit raw availability and stale cursors, late arrivals, correction/provisional/
unverified/review protection, concurrent ingestion at the receipt barrier, replay
after expiry, database rejection of a flag-only bypass, and V930 legacy upgrade/
restart without scans or constraint validation. HTTP and opt-in adapter checks
exercise real expiry results; frontend tests cover the explicit-expiry UI.

The final complete offline backend run passed 408 tests across 24 fresh suites,
zero failures/errors/skips: 169 migration cases, 40 retention-preview integration
cases, 15 actual-pruning integration cases and all previous regressions. An
initial run found a real indexed-raw-page regression caused by an unnecessary
expiry anti-join. The implementation was fixed without loosening the existing
11-row scan assertion; focused query-plan/expiry tests and the final full run
passed afterward. No production/test/migration changes followed the final run.

Frontend: 59 tests plus typecheck, lint and production build passed using Node
24.19.0 and local npm 11.21.1. Python: 94 tests passed on each of 3.12.10 and
3.14.3. These are fresh local results, not a new browser demo or hosted-CI run.
No owner database has been started, connected to, migrated, pruned or cleared.
No commit/push/hosted CI or checkpoint-6 simulator-to-browser demonstration is
implied by checkpoint-5 completion. Saved retention defaults remain disabled/
dry-run, and owner-data deletion still requires separate explicit approval.
