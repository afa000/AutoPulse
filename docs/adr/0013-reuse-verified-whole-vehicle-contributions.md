# 0013: Reuse verified full-bucket trip contributions

Status: implemented in the internal and disabled-by-default public ownership-aware
aggregate reader. No retention, raw-expiration publication or raw-independent legacy
FK change. Builds on [ADR 0012](0012-opt-in-public-owned-aggregate-history.md).
The next membership read-path step follows in
[ADR 0014](0014-verified-final-evidence-read-handoff.md), without removing raw FKs.

## Three disjoint inputs

The ownership reader previously recalculated sensor statistics from every included
raw/retained observation. It now composes:

1. Raw-owned readings, including late/unlinked, secondary-device and confirmation
   inputs, grouped by UTC bucket/device/source.
2. Archive-owned readings in clipped query edges, calculated from the exact retained
   subset and grouped by bucket/device/source.
3. Stored per-trip contributions for archive-owned FULL UTC buckets covered by this
   page. These readings are excluded from the second branch before calculation.

The branches remain disjoint by original identity, retained membership and full-key
selection. No raw mirror or cached trip observation can enter twice. Ownership still
requires FINAL COMPLETED status, complete matching evidence/build counts and an
independently VERIFIED result. Incomplete/failed/unverified trip data stays raw-owned.

## Bounded full-key selection and fail-closed checks

The existing 10,000 combined physical raw/ledger candidate cap remains BEFORE owner
filtering or payload calculation. Existing raw/archive identity and canonical JSONB
equality checks also remain. Neither is skipped merely because a cached total exists.

The bounded archive-owned metadata set now carries trip UUID and aligned bucket.
Full keys are (trip UUID, bucket) whose complete UTC interval lies inside this page's
actual half-open input window. A clipped first or last bucket cannot qualify, even
if the subset happens to contain all currently observed samples. Cursor pages use
their actual aligned continuation boundary; interior pages do not become artificially
clipped just because the original request began inside an earlier bucket.

For each full key, the reader checks the stored row by its primary key and requested
resolution. Stored sample count and first/last observed timestamps must match the
selected owned metadata; vehicle/device/source must match final-trip provenance.
An absent or inconsistent contribution fails with HISTORY_OWNERSHIP_GAP, not a prefix
total, a silent raw fallback or an invented empty bucket. Sensor statistics rely on
independent verification plus completed-bucket immutability; arbitrary privileged
SQL state forgery is not certified by a VERIFIED label.

Full keys are bounded by the owned metadata page (at most 10,000 keys, not necessarily
limit keys because several trips may contribute to one UTC bucket). No whole-trip
payload scan, historical rebuild, migration, publication or write is added.

## Exact composition, not averages of averages

Residual raw/clipped inputs produce count/sum/min/max metric objects. Cached rows
provide the same exact NUMERIC objects. The final rollup sums sample counts and each
sensor's non-null count/sum, and takes non-null min/max. No mean is averaged; a mean
must be final sum/count. An all-null metric remains count zero with null statistics,
distinct from a supported zero. Device/source coverage is DISTINCT across both
residual and cached contributions, not a sum of per-trip device counts. First/last
timestamps are observed bounds, not evidence of continuous elapsed coverage.

Internal diagnostics add reusedContributions (stored trip/bucket rows used) and
archiveRecomputed (archive observations whose sensors needed edge recalculation).
They do not count public output buckets and are not exposed by the HTTP adapter.
An empty internal page reports zero for both. Public response/token/availability
shapes, single-snapshot behavior and feature defaults are unchanged.

## What this improves, and what it does not

Fully covered verified archive readings no longer undergo per-sensor numeric
recalculation in the result path. Raw-owned and clipped archived readings still do.
However, bounded identity metadata is STILL scanned, and the canonical raw/archive
equality check can STILL read full payloads in PostgreSQL. Thus this is arithmetic
reuse, not zero-payload-access, a larger density limit, a measured throughput gain or
a storage-saving claim. Query-plan/lock costs and individual numeric sizes remain
outside the simple row bound; the 10-second read-only snapshot timeout remains.

The saved public setting remains false, default/raw/trip-filtered paths remain
unchanged, retention is disabled and no raw data expires. No migration is added;
latest schema remains V920. Legacy raw/link/work/correction FKs and all immutability
guards stay intact. Actual raw removal and finalized-summary invariance afterward
are still unverified.

## Disposable evidence and next work

The initial 46 existing aggregate/API tests passed with reuse. Six new cases then
extend focused coverage: complete 1m/5m reuse with zero archive sensor recalculation;
exact mixed/clipped-only edges; cursor-boundary reuse; shared-bucket weighted
composition and distinct coverage; missing required contributions; and unchanged
full-bucket HTTP output without internal diagnostic leakage. Source/bucket fingerprints
remain unchanged by queries.

The hand fixture reuses six 1m or two 5m contributions for 20 archive members. A
request from second 1 through end+1us reuses four interior 1m contributions and
recalculates six archive edge readings; clipping both 5m edges recalculates all 19
included archive readings instead. Two final trips sharing a 5m bucket reuse four
trip contributions, retain one distinct device/two sources and speed sum 14/count 4.
The missing-cache fixture deliberately forges incomplete privileged checkpoint
metadata in a disposable DB; no real verifier result is reset, no guard disabled
and no final evidence/raw row deleted. It proves fail-closed required-row handling,
not that arbitrary privileged forgery is otherwise safe.

Next: raw-independent legacy evidence/read/FK paths and synchronized generation,
availability and write-time ownership/replay checks before crash-safe opt-in pruning.
Public trip-filtered archive integration and checkpoint 6's real simulator-to-trip-
browser/retention demonstration remain unfinished. Checkpoint 5 is incomplete.

The full offline backend suite passed 282 tests with zero failures/errors/skips
across 23 fresh suites, adding six cases to the previous 276. No fresh frontend,
Python, browser, hosted-CI, throughput benchmark or actual-pruning result is claimed.
