# 0007: Retained final-trip membership foundation

Status: implemented additive checkpoint-5 foundation, not pruning or persisted
downsampling. See [ADR 0006](0006-retention-preview-and-pruning-prerequisites.md)
for the retention policy and remaining hard prerequisites.

## Why membership comes before archived aggregates

Trip-filtered history must continue to distinguish the readings belonging to a
particular final trip. A summary, a time overlap or a vehicle-level bucket alone
cannot prove membership. Current `trip_reading_link` and `trip_evidence` still
depend on raw rows, and final links cannot be deleted or reassigned. Preserve
those guarantees rather than disabling guards to make a retention demo succeed.

V916 adds `trip_retained_evidence`, an immutable mapping from original reading UUID
to final trip UUID. Its reading foreign key targets the immutable replay ledger's
unique reading UUID, not raw telemetry; its trip foreign key targets the existing
immutable final trip. Device/boot/arbitrary-precision sequence, original receipt,
measurement time, source and canonical payload remain in that ledger rather than
being copied into another payload store. The trip record retains summary, revision,
boundaries and lineage as before. No existing migration/checksum is edited.

This table can support future aggregate provenance. It does not yet switch any
reader or original foreign key away from raw telemetry. The full-payload ledger's
storage tradeoff remains unresolved, and no storage reduction is claimed.

## Exact, immutable, closed membership

Every INSERT, including one attempted outside the Java copier, must pass the DB
guard. The target must be FINAL and COMPLETED. The reading must already be linked
to that exact trip, use its vehicle/primary device/source, and lie in the closed
interval `[started_at, ended_at]`. Full raw/ledger reading UUID, vehicle, device,
boot, NUMERIC sequence, exact measurement/receipt time, source and JSONB must match.
Missing or divergent ledger evidence aborts the transaction; it is never silently
repaired or treated as equivalent using approximate metric columns.

UPDATE/DELETE of retained membership are rejected, including no-op updates. Original
final-trip and link guards remain unchanged. Trailing confirmation links outside
the closed interval are not retained as trip history. Newly received late readings
cannot acquire final membership merely by falling within the trip's time range.

The retained set follows closed linked history membership, not a promise that its
count always equals summary.sampleCount: rule summaries and equal-time winner
selection have their own semantics. Do not regenerate finalized summaries from a
different membership/count policy or assume enough evidence for arbitrary rebuilds.

## Explicit bounded copying and recovery

`FinalTripEvidence.copyPage(vehicleUuid, tripUuid, limit)` is an internal explicit
call, not a startup task, scheduled retention runner, CLI, HTTP route or public
feature flag. Constructing the service does not copy anything. It uses a new
transaction with a 10-second timeout and a page size of 1..500 links.

A transaction-scoped try-advisory lock serializes calls for a trip; a competing
caller returns busy without creating progress. The hash is only a lock key: a
collision can make an unrelated trip temporarily busy, never merge identities.
The trip is checked under a shared row lock. Progress is lazily registered only
after eligibility checks, then locked for the bounded copy transaction.

The existing `(trip_id, reading_id)` index selects ALL next link UUIDs before raw
lookups. Out-of-range links consume the page budget and advance the durable cursor;
they cannot cause a search through arbitrarily many hidden links within one call.
Only closed-range members are inserted. The database guard verifies their exact
ledger evidence. Existing valid retained rows are checked, not overwritten, and
do not increase the newly-copied count.

Copies, cursor, visited-link count and retained-member count commit together. A
failed page rolls all of its changes back while preserving earlier committed pages.
A recreated service resumes at the persisted UUID cursor. Complete means that this
copier reached the end of the immutable final trip's original link set; an exact-size
last page needs an extra empty call. Completed progress cannot be reset or deleted;
repeated complete calls return zero work. Partial-copy records are not advertised
as a complete archive by any reader.

`visited` counts all scanned links; `retained` counts closed members in that page;
`copied` counts newly inserted rows. Persisted retained_rows counts visited closed
membership, including rows already validly copied before this pass. There is no
whole-trip count/payload scan per call or migration-time backfill. Row bounds do not
bound individual payload size or eliminate all database-plan/lock costs. External
metadata locks still follow the normal transaction timeout/failure behavior.

## No public read or deletion switch yet

Current raw/history/trip APIs and `trip_evidence` keep their original data sources
and publication/generation protections. Archive copying does not change original
raw rows, receipts, work, projection, final summaries/revisions, links or raw/trip
read generation. It does not fake EXPIRED availability. New ingestion remains
independent; a newly accepted late identity has no new final link to copy.

The preview deliberately retains RETAINED_FINAL_EVIDENCE_REQUIRED: additive storage
alone is not a complete raw-independent read/foreign-key path. Work and correction
references, the original final links, latest state, persisted aggregate ownership,
honest availability and synchronized write-time checks remain unresolved. Do not
cascade-delete old links or disable their final guards. A future integration must
preserve ledger/archive/progress invariants and public snapshot behavior before
any pruning is executable.

## Verification and authority

Tests use actual projection/finalization of the hand-checkable short drive in
disposable PostgreSQL. They compare retained IDs with the original closed evidence,
exercise one-link pages across reconstructed services, protect trailing/nonmember
readings, reject nonfinal/unknown/wrong-vehicle requests, preserve immutability and
original source fingerprints, reuse valid copies, recover a rolled-back page after
an injected storage outage, and test a real competing advisory lock.

The current projector already cleans trailing confirmation links. Tests add a legal
extra link while the fixture is still PROVISIONAL, then finalize normally, to prove
that a legacy out-of-range link consumes bounded work without being archived. They
do not disable final guards or pretend the current projector normally leaves it.

The V913 upgrade fixture reaches FINAL before the ledger exists, then migrates
without copying payloads or membership. Copying fails atomically until explicit
ledger backfill makes original evidence available; retry then succeeds without
rewriting raw data or the final trip. Fresh/local/V900/restart migration checks
remain enabled. No final raw evidence is deleted or final guard bypassed in tests.

The complete backend suite passed 220 tests (zero failures/errors/skips), including
seven new evidence-copy cases and one legacy upgrade case. The initial trailing-link
fixture assumption was corrected as described above; no production guard was weakened.

Durable UTC 1m/5m aggregates, null-aware/weighted statistics, clipped-range ownership,
raw-independent public queries, actual prune/recovery and finalized-summary
invariance after raw deletion are not implemented or verified by this foundation.
The subsequent [ADR 0008](0008-durable-trip-aggregate-contributions.md) adds persisted
contributions, but does not complete the public-query or pruning requirements.
Checkpoint 5 remains incomplete; the full simulator-to-trip-browser/retention
demonstration remains checkpoint 6. No owner database has been migrated or pruned.
Named owner-data deletion requires separate explicit approval; Continue is not it.

Run the disposable evidence and migration checks from `server`:

Subsequent [ADR 0020](0020-canonical-retained-copy-bounds.md) uses original ledger
timestamps for unfinished copy-page bound classification on identity-ready
vehicles. Incomplete vehicles keep raw classification; exact-original raw INSERT
guards and raw FKs still apply. Existing completed copies are not reopened.

```powershell
.\mvnw.cmd test '-Dtest=FinalTripEvidenceTests,TripWorkMigrationTests,LocalProfileMigrationTests'
```
