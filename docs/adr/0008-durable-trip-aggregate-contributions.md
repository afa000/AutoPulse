# 0008: Durable per-final-trip aggregate contributions

Status: implemented checkpoint-5 storage/build foundation. Independent bounded
verification follows in [ADR 0009](0009-bounded-trip-aggregate-verification.md).
Not a public archive query, raw-expiration policy, pruning runner or storage-savings claim.
Builds on [retained final membership](0007-retained-final-trip-membership.md) and
preserves [retention prerequisites](0006-retention-preview-and-pruning-prerequisites.md).

## Ownership and exact statistics

V917 adds `trip_aggregate_bucket`, keyed by trip UUID, resolution (60/300 seconds)
and UTC-aligned bucket start. Each row is a contribution from exactly that final
trip's retained membership, with explicit vehicle UUID, primary device UUID, source,
sample count and first/last observed measurement time. It is NOT a complete
vehicle-wide minute: a trip can begin/end inside the bucket, and another disjoint
trip can contribute to the same UTC interval.

All nine numeric sensors store exact PostgreSQL NUMERIC count/sum/min/max in JSONB.
Metric count excludes nulls; sample_count includes every retained reading. A zero
metric count has null sum/min/max, not invented zeroes. A supported zero reading
has nonzero count and zero-valued statistics. Empty time buckets are not generated.
No mean, interpolated coverage, distance, route or diagnostic conclusion is stored.

Composing contributions adds counts/sums and takes non-null min/max. Compute means
as combined sum / combined non-null count, never an average of means. Device/source
coverage must use distinct identities/sources, not summed per-contribution device
counts. First/last observations and sample counts do not prove continuous elapsed
coverage. Both resolutions are built directly from the same retained input page,
not by averaging 1m results into 5m results.

## Bounded build and recovery

`TripAggregateBuilder.buildPage(vehicleUuid, tripUuid, limit)` is an explicit internal
method with no startup runner, scheduler, CLI, HTTP/admin route or automatic owner-DB
work. Migration creates empty tables/functions/indexes; it never scans payloads or
builds old aggregates. Existing migrations/checksums are unchanged.

The trip must be FINAL and COMPLETED, and retained membership copying must be
complete. A transaction-scoped try-advisory lock for the trip returns busy for a
competing cooperative builder before progress registration. Hashing is only a lock
selection mechanism; a collision can make another trip busy, never merge identity.
Trip/evidence metadata is checked under shared locks. Each new transaction has a
10-second timeout and visits 1..500 retained IDs in native UUID order using the
existing retained-membership index, then reads their immutable ledger payloads in
PostgreSQL. Java receives only IDs/progress, never the payload page.

Each page groups exact input separately into UTC 1m and 5m contributions and merges
those into existing rows. Bucket updates for BOTH resolutions and the durable
cursor/visited count commit together. A failure in the second resolution rolls back
the first resolution and cursor as well. Earlier committed pages survive. Recreated
builders resume without adding their previous inputs again. An exact-size final
page needs one empty call to finish. Visited membership must not exceed the sealed
retained_rows count and must equal it on completion. `bucketWrites` counts rows
inserted/updated in that call, not distinct buckets over the whole build.

Partial buckets may exist internally while progress is incomplete; they are not
published as complete history. A complete build is immutable and repeat calls do
zero work. Progress cannot be deleted or reset after completion. No per-call
whole-trip payload scan or Java accumulation is used. Row bounds do not bound an
individual accepted payload's size, all query-plan/lock costs or guarantee throughput.

## Database guard limits

Bucket writes require matching FINAL-trip provenance, complete retained evidence
and an active incomplete aggregate build. Ownership cannot be reassigned and buckets
cannot be deleted. Constraints require UTC alignment, valid 60/300 resolutions and
observed bounds inside the half-open bucket. The metric guard checks all nine exact
count/sum/min/max objects, integral counts within sample_count, null statistics for
zero count, numeric statistics for positive count and min/max/sum consistency.

These guards reject malformed/provenance-changing writes; they do not independently
re-derive every bucket for arbitrary privileged SQL writers. The builder, immutable
source membership and atomic cursor supply normal application exact-once behavior.
ADR 0009 adds independent bounded verification. A future prune workflow still needs
synchronized write-time checks and query ownership/availability integration.
No reset/repair command or new rebuild versioning policy is introduced here.

## Public-query and clipping boundary

Current history/trip/Live readers, raw/trip generations, summaries/revisions and
availability are unchanged. They do not read these contributions. Copying/building
does not make raw data expired or reduce storage; the replay ledger still retains
full canonical payloads.

A full-bucket contribution CANNOT be returned for a smaller clipped query interval
as if it were exact. A future reader must select complete owned contributions or
recompute the clipped subset from sufficient retained evidence, with bounded work
and honest availability. It must prove raw/archive ownership to avoid counting a
reading twice; trip IDs preserve contribution provenance, but do not implement that
whole-vehicle query plan by themselves. Preserve generation/publication coherence
when archive data becomes publicly observable.

The preview keeps DURABLE_AGGREGATES_REQUIRED until aggregate verification, query
ownership/availability and the write-time retention path are integrated. Existing
raw links/work/correction references still prevent safe deletion. No raw data or
final evidence is removed, and non-dry-run retention remains unsupported.

## Verification and next steps

Disposable PostgreSQL tests use a hand-checkable finalized 20-member trip spanning
six UTC 1m buckets (5/3/3/3/4/2 readings) and two 5m buckets (18/2). Unequal sensor
counts yield weighted speed mean 3.5, not mean-of-means 4; all-null sensors stay null,
supported zero remains zero and coolant values differing below double precision
retain their exact min/max/sum. A 40-digit sequence remains in source identity.

Tests compare one-page and one-member/reconstructed-service builds using PostgreSQL
JSONB equality, preserve source fingerprints, reject nonfinal/missing/partial archive
and invalid requests, recover both resolutions after an injected second-resolution
outage, reject malformed statistics/ownership and completed mutations, exercise a
real competing lock, and preserve vehicle/device/SIMULATED-versus-OBD provenance.
OBD in this fixture is a provenance test label, not evidence of hardware acquisition.
The V916 upgrade starts with completed retained membership and creates no aggregate
rows until explicit build; restart/migration reruns preserve buckets and final data.

At the storage/build checkpoint, the backend suite passed 229 tests with zero failures/errors/skips, adding
eight aggregate cases and one legacy migration case to the previous 220. No current
public history reader or pruning path was switched during this verification.

Clipped public archive reads, raw/archive interval ownership, expiration metadata,
late input into expired ranges, verification/prune crash recovery and final-summary
invariance after actual raw removal remain unfinished. Checkpoint 5 and checkpoint
6's full real sender-to-trip-browser/retention demonstration are not complete.
Owner-data deletion still requires separate explicit approval of concrete ranges.

Run only disposable aggregate/migration checks from `server`:

```powershell
.\mvnw.cmd test '-Dtest=TripAggregateBuilderTests,TripWorkMigrationTests,LocalProfileMigrationTests'
```
