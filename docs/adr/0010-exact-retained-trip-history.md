# 0010: Exact clipped retained-trip history, without public publication

Status: implemented internal checkpoint-5 reader foundation. This does not switch
public history, expire raw data, establish whole-vehicle interval ownership or
implement pruning. Follows [aggregate verification](0009-bounded-trip-aggregate-verification.md).
The internal whole-vehicle ownership reader follows in
[ADR 0011](0011-disjoint-whole-vehicle-history-ownership.md), without switching public routes.

## One explicit owner for this query

`RetainedTripHistory.read(vehicleUuid, historyRequest, trustedCursor)` accepts a
specific FINAL COMPLETED trip and resolution 1m/5m. It requires complete retained
evidence, a complete build with matching membership counts and independently
VERIFIED aggregates. ACCUMULATE, COMPARE, FAILED, absent or incomplete work cannot
fall back to supposedly available archive data. Wrong-vehicle trips are rejected.
The existing correction-publication gate remains in force.

This reader's sole input owner is that trip's retained membership. It NEVER unions
raw rows with stored buckets, never selects membership by time overlap alone and
does not implement unfiltered vehicle-wide history. Existing raw rows may coexist
with archived identities without entering the result a second time. Newly accepted
unlinked late readings do not become members of an immutable final trip merely
because their timestamps match. They remain outside this trip query, not silently
discarded from the future whole-vehicle availability/ownership problem.

The method is internal only: no controller, CLI, scheduler, startup runner,
configuration switch or public API contract was added. It returns aggregate points,
a trusted continuation and work diagnostics, not raw availability/expiration claims
or a CURRENT processing status. Public history/trip/Live readers remain unchanged.

## Full buckets versus clipped edges

Bounds remain UTC half-open `[from,to)`, with microsecond precision, aggregate range
at most 31 days, 1m/5m resolution and limit 1..1000. Four-digit UTC years are checked.
The retained membership itself has the final trip's closed start/end semantics:
include a final observation only when the requested exclusive `to` is later than
that observation (e.g. end + 1 microsecond, not a rounded millisecond).

The reader seeks the trip/resolution/bucket primary key and fetches at most limit+1
fixed-size-statistic rows, omitting empty UTC spans rather than generating zeroes.
If the request covers a whole UTC bucket, its verified per-trip contribution can
be reused directly. That does not imply the trip occupied every moment in the
bucket: source, device, sample count and observed bounds remain explicit.

A query edge cutting through a bucket must be recalculated from retained members
inside the clipped `[from,to)` portion. A full-bucket sum or mean cannot represent
that subset. Edge calculation joins candidate ledger identities to THIS trip's
retained membership and uses exact NUMERIC non-null count/sum/min/max. First/last
observations come from the actual subset. No mean-of-means, interpolation, null
zero-filling, invented elapsed coverage or distance estimate is introduced. Empty
clipped subsets produce no point. Supported zero remains distinct from absence.
Points preserve the existing AggregatePoint shape, source and primary device.

V919 adds `(vehicle_id, device_id, recorded_at, reading_id)` on the replay ledger
for bounded edge seeks. It indexes existing identity metadata; it does not copy or
aggregate historical payloads, publish availability or remove FKs. A normal index
build still reads existing table metadata, uses resources and can block writes;
an eventual owner-database migration needs an appropriate maintenance/deployment
plan. No owner database is migrated by these development tests. Earlier migration
checksums are unchanged.

## Bounded work and read consistency

Each call is a new read-only REPEATABLE READ transaction with a 10-second timeout.
Bucket selection, verification readiness and all edge calculations share a snapshot.
Work diagnostics count reused stored buckets, recomputed clipped buckets and ledger
candidates (including candidates excluded by membership).

Across the page, clipped calculations permit at most 10,000 ledger candidates.
The index seek selects at most remaining-budget+1 metadata IDs BEFORE membership
filtering or payload lookup. Exceeding the budget fails the entire call with
HISTORY_DENSITY_LIMIT, never a truncated apparently complete summary. This is
conservative: many unrelated/unlinked identities in the same primary-device edge
can cause rejection even if few belong to this trip. After the bounded count
succeeds, the same capped seek in the same snapshot feeds exact owned-subset SQL
aggregation. The page never streams payloads into Java. Full buckets consume no
edge candidate budget and do not re-read ledger payloads.

A contiguous query has at most two clipped UTC edges; a single bucket cut at both
ends is recomputed once. Returned points are at most limit, but limit counts visited
nonempty STORED bucket keys, not nonempty clipped outputs. Therefore an empty point
page can have a continuation when its clipped first bucket has no included samples;
callers must follow nextCursor until null. This preserves both response/work bounds
without scanning arbitrarily far to fill a page. Diagnostics are per-call, not a
whole-trip completeness count. Numeric sizes, all planner/lock costs and throughput
are not bounded merely by row counts. Timeout/density failures return no partial
summary and do not mutate any cursor, verification result or source data.

The trusted in-process cursor binds vehicle, trip, original bounds, resolution,
limit and last aligned stored bucket. Filters cannot silently change on continuation.
It is NOT signed, serialized or suitable for accepting from an HTTP client. It has
no public generation/expiry protocol: final source membership and verified buckets
are immutable in the normal application path, and unlinked late identities cannot
change their statistics. Pages have separate snapshots; new late metadata can
increase an edge's candidate budget or cause a density failure on a later call.
Future mixed/public queries still need their existing signed generation-coherent
cursor and publication/availability behavior, not this internal cursor as a shortcut.

## Tests and remaining boundary

Disposable tests compare full and clipped archived points to the existing real
raw-derived reader while both stores coexist. They cover 1m/5m, all-null/zero/exact
decimals, source/device metadata, exclusive end versus end+1us, an empty clipped
page with continuation, no-skip bounded pagination and filter mismatch rejection.
They reject nonfinal, absent/incomplete/failed verification, wrong-vehicle and
unsupported requests. A newly accepted unlinked late reading consumes candidate
work but cannot enter the trip statistics or invalidate the trusted archive cursor.
Negative-epoch microsecond queries preserve exact bounds and OBD test provenance
(a test label, not hardware acquisition).

A 10,001-identity unowned ledger fixture proves density rejection occurs before
membership filtering, while full-bucket reuse remains independent of edge density.
An actual EXPLAIN ANALYZE on that larger fixture checks use of the new metadata
range index. A V918 upgrade fixture preserves real finalized trips, verified
aggregates and ledger payloads; exact clipped reads and repeated migration/reads
remain stable after the index is added. No final guard is disabled or raw evidence
deleted to manufacture these results.

The full offline backend suite passed 251 tests with zero failures/errors/skips
across 21 fresh suites: nine reader cases and one V918 upgrade case extend the
previous 241 tests. No fresh frontend, Python, browser, hosted-CI, throughput or
actual-pruning result is claimed for this backend-only step.

Whole-vehicle raw/archive ownership, public query integration/generations, truthful
expired availability, raw-independent legacy link/work/correction FK paths and
synchronized write-time safeguards are still required before crash-safe opt-in
pruning. Final-summary invariance after actual raw removal is not yet verified.
Full canonical ledger payloads/reference statistics remain stored; no storage
savings are claimed. Checkpoint 5 and checkpoint 6's real sender-to-trip-browser
retention demo remain incomplete. Owner-data deletion requires separate explicit
approval of concrete ranges, not merely a successful internal archive read.
