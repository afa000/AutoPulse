# 0009: Independent bounded verification of trip contributions

Status: implemented internal checkpoint-5 verification foundation. Public archive
queries, expiration and pruning remain unfinished. Builds on
[ADR 0008](0008-durable-trip-aggregate-contributions.md).
The internal exact clipped-trip reader follows in
[ADR 0010](0010-exact-retained-trip-history.md); it does not publish public history.

## Why building is not verification

A completed build cursor says which retained identities the builder visited. It
does not independently prove the bucket contents. The existing database guards
validate provenance and statistical shape, not that a well-shaped sum actually
matches the inputs. Verification must also detect absent and unexpected buckets,
not just check the rows that happen to exist.

V918 adds empty `trip_aggregate_verification` and `trip_aggregate_reference` tables.
Migration performs no historical scan, automatic verification or owner-data change
beyond the schema when explicitly applied. Earlier migrations remain unchanged.
`TripAggregateVerifier.verifyPage(vehicleUuid, tripUuid, limit)` is an explicit
internal method, with no scheduler/startup runner, CLI or HTTP/admin endpoint.

## Two durable phases

Every call uses a new transaction with a 10-second timeout and accepts a page size
of 1..500. A trip-scoped try-advisory lock (namespace 918) returns BUSY without
registering progress when another cooperative verifier owns the trip. It does not
serialize unrelated ingestion or confer authorization to delete data. The trip
must be FINAL COMPLETED, retained evidence must be complete, and the completed
aggregate build's visited count must equal retained_rows. Vehicle isolation is
checked before progress registration. Builder namespace 917 remains unchanged;
verification cannot start on an incomplete build.

1. ACCUMULATE pages retained membership IDs in native UUID order, bounded BEFORE
   ledger lookups. It projects only identity/provenance/time and the nine exact
   NUMERIC sensor values, not full JSON payloads. Java BigDecimal addition and
   comparisons independently calculate count/sum/min/max and observed bounds.
   Integer floor division of epoch seconds gives UTC 1m/5m alignment, including
   negative epochs and microsecond observations. It does not reuse the builder's
   date_bin/group/merge SQL or perform floating-point arithmetic. Null sensors
   do not contribute; supported zero remains supported. At most 2*limit reference
   keys are touched per call, each with nine metric objects. Existing reference
   state is read by primary key, then new reference results and the input cursor
   commit together. Completion requires exactly retained_rows inputs. Exact-size
   final pages need an extra empty call.
2. COMPARE walks the ordered UNION of reference and actual keys. EACH index walk
   is limited before the union and the combined page is also limited. Comparison
   performs primary-key lookups for at most limit keys, checking sample count,
   exact first/last timestamps, all nine metric objects with PostgreSQL JSONB
   numeric equality, and vehicle/device/source. It persists a comparison cursor
   and count. An absent actual row yields MISSING_BUCKET; an actual-only key
   yields UNEXPECTED_BUCKET; a differing row yields CONTENT_MISMATCH. The first
   discrepancy durably terminates as FAILED with its key and code. Only exhausting
   BOTH key sets without a discrepancy reaches VERIFIED.

A call performs one phase, never both, and returns this call's visited/compared
counts plus state and busy flag. Reconstructing the service resumes durable work.
An outage rolls back that page, not earlier committed progress. Completed VERIFIED
and FAILED results return zero work; neither is silently reset or repaired.
Reference rows are sealed when accumulation ends. Reference deletion/reassignment,
verification deletion/reassignment, phase reversal and terminal reset are guarded.
No rebuild-version or cleanup policy is introduced; reference statistics currently
remain stored, with an associated storage cost.

The page bounds constrain row/key counts, not individual NUMERIC sizes, all query
plan/lock costs, JVM scheduling or throughput. Java holds at most one bounded input
page and its touched reference keys, never the entire trip or a dense bucket's
entire history. The timeout permits a busy/expensive page to fail and be retried;
it is not a promise of fixed runtime independent of input/environment.

## Meaning and limits of VERIFIED

VERIFIED means this internal trip contribution set was independently compared
against its retained membership at both supported resolutions. The source ledger,
final trip and normal complete membership/build path are immutable. This is not a
cryptographic attestation against privileged SQL writers, who can tamper with
incomplete checkpoints/reference results or forge application state. Shape guards
alone cannot certify arbitrary privileged writes. Failure is reported, not fixed
by weakening final-trip/archive/aggregate guards or overwriting completed data.

No current public history, trip or Live reader consumes these results. Verification
does not advance public generations, publish expiration, prove complete vehicle-wide
interval ownership, account for unlinked late data or authorize raw deletion.
Retention preview continues to report its existing hard prerequisites and zero
wouldDelete. Raw/work/correction/final-link FKs are unchanged. The full canonical
ledger and additional reference statistics remain stored; no storage savings are
claimed. No owner database is used for tests, migrated automatically or pruned.

Clipped queries still require exact retained-subset handling: a whole-bucket row
cannot stand in for only part of that bucket. Raw/archive ownership, generation
coherence, truthful availability, raw-independent evidence/FK paths and synchronized
write-time rechecks must be integrated before opt-in crash-safe pruning. Checkpoint
5 and checkpoint 6's real sender-to-trip-browser/retention demo remain incomplete.

## Disposable verification

Integration tests use the existing hand-checkable finalized 20-member trip. They
exercise exact/null/zero/weighted statistics, independent one-member and multi-member
pages, reconstructed services, SIMULATED/OBD provenance, negative epoch alignment
and microsecond bounds. Corruption fixtures use permitted partial-build SQL or an
explicit incorrectly sealed disposable build; production immutability triggers are
never disabled. They detect wrong-but-well-shaped metrics, entirely missing bucket
sets and extra keys. Both accumulation-write and comparison-completion outages
preserve earlier cursors and recover; real overlapping locks return BUSY. Invalid,
wrong-vehicle, nonfinal, missing/incomplete evidence/build requests do not register
verification work. Reference sealing and both terminal states resist reset.

A V917 upgrade fixture starts with real finalized membership and completed bucket
storage. V918 creates no reference/progress rows until explicit verification.
Migration/service reruns preserve source buckets and finalized trip data.

The full backend regression passed 241 tests with zero failures/errors/skips across
21 fresh suites, adding 11 verifier cases and one V917 upgrade case to the previous
229 tests. No frontend, Python, browser, hosted-CI, throughput or actual-pruning
result is claimed by this backend-only step.

Run only disposable verification/migration checks from the repository root:

```powershell
Push-Location server
.\mvnw.cmd test '-Dtest=TripAggregateBuilderTests,TripWorkMigrationTests,LocalProfileMigrationTests'
Pop-Location
```
