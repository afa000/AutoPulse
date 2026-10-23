# 0011: Disjoint whole-vehicle history ownership proof

Status: internal checkpoint-5 foundation, not a public archive/expiration switch,
persisted-bucket reuse optimization or pruner. Follows
[the exact retained-trip reader](0010-exact-retained-trip-history.md).
Disabled-by-default public integration follows in
[ADR 0012](0012-opt-in-public-owned-aggregate-history.md); expiration/pruning remain unfinished.

## One owner per original identity

`OwnedVehicleHistory.read(publicVehicle, historyRequest, trustedCursor)` accepts
unfiltered whole-vehicle 1m/5m queries. A retained member is archive-owned only when
its trip is FINAL COMPLETED, retained evidence and aggregate builds are complete
with matching membership counts, and independent verification is VERIFIED with a
matching visited count. Other raw readings stay raw-owned: late/unlinked readings,
secondary devices, confirmation/trailing readings and unverified trips are not
silently omitted. A copied ledger identity alone does not qualify as an archive.

The raw branch excludes ready-owned original UUIDs; the archive branch reads those
identities from the immutable ledger. UNION ALL combines disjoint observations,
not overlapping buckets or means. Overlapping raw copies must match the ledger's
full original identity, vehicle/device/source, measurement/receipt times and JSONB
payload before exclusion. Otherwise the entire read fails with
HISTORY_OWNERSHIP_MISMATCH, not an arbitrary choice of source.

Owner lookup uses the bounded UNION of raw AND ledger candidate IDs. Thus a changed
archive-claimed raw timestamp moved into the page is still checked even if its
original ledger timestamp lies elsewhere. Ledger candidates without either a ready
archive owner or a raw row for this vehicle fail with HISTORY_OWNERSHIP_GAP; they
cannot look like an empty minute. These are internal fail-closed conditions, not
public expiration metadata or a general audit against privileged SQL forgery.

## Bounded exact calculation

Each call is a new read-only REPEATABLE READ transaction with a 10-second timeout.
Vehicle registration, publication gating, generation, readiness and calculation
share a snapshot. HistoryRequest's aggregate precision/range/limit rules apply,
plus four-digit UTC years. Trip filters, raw resolution and public cursor strings
are rejected. Known-empty and unknown vehicles remain distinct.

Two limited metadata seeks find the earliest raw/ledger candidate at/after the
position. A page spans at most limit consecutive UTC buckets, clipped to the original
half-open bounds. Empty UTC spans are skipped. Both source scans share a budget of
10,000 PHYSICAL metadata candidates, capped BEFORE membership/readiness filtering
or payload access. Mirrored copies consume two candidates, but enter statistics
once. Exceeding the cap yields HISTORY_DENSITY_LIMIT with no partial totals.

Materialized bounded ID sets feed owner lookups, evidence checks and exact NUMERIC
aggregation. Counts/sums combine observations, never averages of averages. Nulls
stay null; supported zero stays supported. Device/source coverage uses distinct
identities/sources across both branches, not summed contribution device counts.
Observed bounds are actual measurements, not proof of continuous elapsed coverage
or distance. Java receives statistics/diagnostics, not payload pages.

V920 adds `(vehicle_id, recorded_at, reading_id)` on the ledger. Raw canonical-order
and device-scoped ledger indexes remain. This indexes existing metadata, without
payload copying, ownership publication, FK changes or deletion. Index creation can
consume resources and block writes; an eventual owner migration needs operational
planning. Only disposable test DBs are migrated here; earlier checksums are unchanged.

rawOwned/archiveOwned count logical included observations in THIS page. Candidate
diagnostics count bounded physical input, including unowned ledger candidates; they
are not extra measurements or whole-history completeness claims. Row bounds do not
bound individual numeric sizes, all planner/lock costs or throughput. This proof
reader recalculates archive statistics from ledger evidence, not persisted bucket
totals. Contribution reuse remains a separate performance/integration step.

## Cursor and public boundary

Trusted in-process continuations bind vehicle, bounds, resolution, limit, generation
and next aligned bucket boundary. Changed filters are rejected. New accepted raw
input changes generation and yields PAGE_CHANGED. Static pages do not split buckets
or double-count mirrors. Each page rechecks publication/readiness in a new snapshot.

While both exact copies coexist, completing verification changes an identity's owner
but not its canonical value, inclusion, measurement time or raw generation. Tests
exercise that handoff BETWEEN pages: ownership labels can differ, values cannot.
This is not a general archive-publication protocol. Real removal/expiration still
needs synchronized generation changes and signed, expiring public cursors. These
internal records are not HTTP tokens or pruning certificates.

No public controller/HistoryQueries route, availability metadata, Live/trip summary,
processing/retention default, scheduler, startup reader or admin command is changed.
No expired-state response or owner-data deletion is demonstrated.

## Disposable evidence and remaining work

Tests compare internal points to the existing real raw-derived service while both
stores coexist. A 21-reading fixture moves 20 final retained members to the archive;
the off-confirmation reading stays raw-owned and total remains 21, not 41. COMPARE
does not transfer ownership. Late primary and secondary-device OBD readings remain
included once. Two final trips share a 5m bucket: speed sum 14/count 4 gives mean
3.5, and a shared device counts once across two trip/source contributions.

Other cases cover exact clipped/microsecond/pre-epoch/null statistics, static paging,
filter binding, generation invalidation, owner handoff mid-page sequence, vehicle
isolation, explicit ledger-only gaps and privileged altered-raw fixtures (including
out-of-window original timestamps). No immutability trigger is disabled. The dense
fixture also tests the combined budget. EXPLAIN ANALYZE checks bounded range-index
plans and specifically the new unfiltered index with 11 actual returned rows.

With V920 PostgreSQL legitimately chose the new vehicle-range index in an older
single-device test. Its assertion now permits either applicable range index while
rejecting sequential scans/payload projection; the new unfiltered plan specifically
requires replay_vehicle_history_range. Production queries were not altered to force
an optimizer choice. A V919 upgrade fixture preserves real final trips, ledger data
and disjoint ownership totals through migration/read reruns.

Next: public signed-cursor/generation-coherent integration, truthful availability
and persisted contribution reuse; then raw-independent legacy evidence/read/FK
paths and synchronized write-time retention guards. Actual raw removal and final
summary invariance afterward remain unverified: original FKs/guards stay intact.
Checkpoint 5 and checkpoint 6's real simulator-to-trip-browser/retention demo remain
incomplete. Full ledger payloads and reference statistics remain stored; no storage
savings are claimed. Owner deletion requires separate explicit approval.

The full offline backend suite passed 261 tests with zero failures/errors/skips
across 21 fresh suites. Nine ownership cases and one V919 upgrade case extend the
previous 251; the expanded focused run passed 145 cases before the full regression.
No fresh frontend, Python, browser, hosted-CI, throughput or actual-pruning result
is claimed for this backend-only step.
