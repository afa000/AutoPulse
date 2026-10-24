# ADR 0017: Verified public trip history reads canonical retained identities

Status: accepted phased read handoff; checkpoint 5 remains incomplete.

## Decision and scope

HistoryQueries selects trip_ledger_reading ONLY for trip-filtered requests whose
trip qualifies in the shared trip_verified_archive view. Qualification requires
a FINAL COMPLETED trip, complete retained membership and aggregate build, and
independent VERIFIED state with matching counts. Vehicle identity-backfill
completion alone is not sufficient. Active, provisional, legacy and not-yet-
verified trips retain raw reads.

The default unfiltered whole-vehicle raw/aggregate reader stays raw-backed. The
separate opt-in owned whole-vehicle aggregate adapter and its disabled saved flag
are unchanged. A trip filter still enforces vehicle ownership, primary device,
closed trip boundaries, request half-open bounds and trip_evidence membership.
Ledger rows not belonging to the trip cannot become public evidence just because
they exist. Publication gating and superseded-trip errors still run first.

## Snapshot, cursors and values

Scope records one closed reading-source identifier inside the existing read-only
REPEATABLE READ transaction. Data reads, raw/native and aggregate anchors, sparse
bucket seek, bounded aggregate input and lookahead all use that source. Vehicle
lookup, publication gate, generation, cursor checks, source readiness, points,
processing state and availability remain in the same request snapshot.

RawHistoryCursor's HMAC format/version/kinds, original UUID anchor, TTL and filter
binding do not change. Raw pagination still orders the full recorded/device/boot/
NUMERIC-sequence tuple without truncating microseconds or arbitrary-size sequence
values. Aggregate continuation still resolves the stored UUID's canonical bucket
and advances to its UTC boundary. Exact equivalent-source handoff preserves member
UUIDs and generation, so a valid cursor issued before verification can resume
afterward. Accepted changes still invalidate old generations with PAGE_CHANGED;
filter/resolution/vehicle misuse remains INVALID_CURSOR.

The raw response remains the exact original measurement JSON and receipt, not a
bucket mislabeled as raw. Aggregate responses still use PostgreSQL NUMERIC,
null-aware count/sum/min/max, UTC alignment, clipped bounds, provenance and the
existing 100001-candidate input cap / 100000-row density rejection. This step does
not route these public trip aggregates through the internal cached-contribution
reader or change its separate clipping limits. No average-of-averages shortcut or
new response schema is introduced.

## Migration and availability boundary

Forward-only V924 adds an ascending ledger native-order index for fixed vehicle/
device history. V923's descending correction index remains. Ordinary index build
reads existing keys and takes normal DDL/index locks; it is not a no-lock/no-scan
deployment guarantee. No payload copying, automatic archive/backfill, pruning,
raw FK removal or guard change is performed by migration.

No raw rows have expired through an implemented job. Availability therefore stays
AVAILABLE with no expiry cutoff and the existing processing-incomplete flag.
Moving original reading values to another source is not evidence of expiration,
reduced precision, storage savings or authorization to delete. Actual expiration
still requires coordinated generation/availability publication and write-time
ownership/replay checks. All raw FKs and immutable evidence guards remain.

Unfiltered history, work preparation/hydration, archive copying, original-raw
write checks and legacy view arms still depend on raw. The combined membership
view also retains its raw schema dependency. Java source-query observation is not
a claim that a whole request or the complete database schema is raw-free.

## Disposable verification scope

New endpoint/service cases cover raw-token handoff, both aggregate resolutions'
tokens across verification, raw fallback until independent verification, canonical
values/receipts after deliberate privileged disposable raw drift, signed-token
filter and generation protections, vehicle isolation, publication gating, exact
wire precision, microsecond bounds and clipped all-null metrics. Deliberate drift
never disables a guard or deletes a row; raw deletion still fails its FK. It is
not a repair workflow or a proof of post-pruning availability.

A separate representative 5,000-reading EXPLAIN ANALYZE/buffers fixture checks the
native ascending ledger-page query shape and its new index, with normal planner
settings. This is not the complete trip-membership query plan, a universal bound
on membership-filter work, throughput or every equal-time tie distribution.

Next: remaining raw-dependent preparation/copy restrictions and default public
whole-vehicle behavior, coordinated availability/generation publication, write-time
coverage/ownership checks and a bounded crash-safe opt-in pruner. Checkpoint 5 and
checkpoint 6's real sender-to-browser/retention demonstration remain incomplete.

The complete backend report set contains 322 passing tests across 23 suites,
zero failures/errors/skips, including seven public-history additions and one
ascending-index case. The reports include 24 history API cases and 135 migration
cases. No production/test/DDL code changed after that run. No fresh frontend,
Python, browser, hosted-CI, throughput or successful-pruning result is claimed.
