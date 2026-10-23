# 0014: Verified final-trip evidence read handoff

Status: implemented checkpoint-5 read-path step, not raw FK removal, public trip
metric expiration, raw deletion or completed retention. Follows
[ADR 0013](0013-reuse-verified-whole-vehicle-contributions.md).

## Why reads come before deleting references

The dependency inventory still finds raw foreign keys in trip_reading_link,
trip_work_item and trip_correction_reading. Existing final links cannot be deleted
or reassigned, and processing/correction state cannot simply lose its input.
Removing constraints first would discard protection without proving reader safety.
This step changes the final membership read source while preserving EVERY raw FK
and final/link/ledger/retained/aggregate guard. It does not authorize pruning.

V921 adds `trip_verified_archive`, a metadata eligibility view requiring FINAL
COMPLETED status, complete retained evidence and aggregate builds, independently
VERIFIED results and matching retained/build/verification visited counts. It reads
no raw payloads. A VERIFIED label with inconsistent counters does not qualify.
The two existing archive readers now use this shared eligibility definition instead
of maintaining separate equivalent readiness joins.

Eligibility is NOT publication visibility, a public availability claim or a deletion
certificate. Callers must still preserve vehicle filtering and publication gating.
It also is not an attestation against arbitrary privileged checkpoint forgery.

## Two disjoint evidence arms

trip_evidence retains exactly its original `(trip_id, reading_id)` columns:

- Active, provisional, legacy and otherwise unqualified trips keep the existing
  original-link/raw-timestamp path and closed evidence bounds.
- Eligible final trips use immutable retained membership and canonical ledger
  vehicle/device/source/time, within the final trip's closed bounds. This arm does
  not read telemetry_reading or the original raw link table.

Both arms join trip_visible_record, so vehicle-wide correction publication still
hides evidence. API readers still report PUBLICATION_PENDING before returning
points; hidden evidence must not be presented as a genuinely empty trip. The raw
arm explicitly excludes eligible trips, so UNION ALL cannot duplicate mirrored
membership. Both arms evaluate eligibility in the same SQL statement snapshot.

Under the normal immutable-copy/build/verify path, handoff preserves the EXACT
same original identity set, not just its count. No summary/revision/receipt is
rewritten, and no new generation is published for a source-only handoff. Tests
prove equal IDs and unchanged generation across partial copy/build, COMPARE and
eventual VERIFIED eligibility. Changed measurements or actual expiration would
still need their existing/new synchronized generation protocol.

The membership view change applies to all its consumers; it is not controlled by
the optional public aggregate flag. The original public raw/trip-filtered metric
reader still SELECTs raw telemetry. Thus this does NOT make public trip metrics
raw-independent or change successful availability to expired. Normal response
contracts/values are unchanged while both exact copies coexist.

## Migration and remaining structural dependencies

V921 only creates/replaces views; no historical payload scan/copy, automatic archive
job, reference backfill, FK removal or guard edit occurs. Earlier migration checksums
are unchanged. View DDL still takes normal database locks; no owner DB is migrated
during development tests. Saved ownership/retention flags remain disabled.

The combined trip_evidence view STILL has a schema dependency on raw telemetry
through its fallback arm. Raw foreign keys and raw-backed processing/correction/
public metric paths remain. A raw-independent final ARM is not a raw-independent
schema, a safe DELETE or storage savings. Physical raw removal and final-summary
invariance afterward remain unverified; failed deletion tests do not claim otherwise.

## Disposable checks and next work

Five new integration cases cover exact eligibility/source transitions, canonical
ledger-time membership after deliberate privileged raw metadata corruption,
publication hiding/API gating, unchanged raw FKs/immutable associations, and a
malformed VERIFIED-counter fixture that must retain the raw fallback. None disables
a guard or deletes final/raw evidence. Deliberate corruption is scoped to disposable
DBs and is not an application edit/repair workflow.

A V920 upgrade case starts with an unarchived final trip: V921 creates no retained
rows or automatic eligibility, then explicit copy/build/verify preserves its member
IDs and raw/link/summary data. An existing already-verified V919 upgrade case is
extended to prove V921 immediately selects the same retained IDs after migrations.
Repeated migration/reads preserve results; default/local/legacy startup cases remain.

Next: phased raw-independent processing/link/correction references and public metric
read paths, synchronized generation/availability publication and write-time ownership/
replay checks before bounded crash-safe opt-in pruning. Do not drop raw FKs or weaken
final evidence guards as a shortcut. Checkpoints 5 and 6's real sender-to-trip-browser/
retention demonstration remain incomplete; owner deletion needs separate approval.

The full offline backend suite passed 288 tests with zero failures/errors/skips
across 23 fresh suites, adding five evidence-read cases and one V920 migration case
to the previous 282. No fresh frontend, Python, browser, hosted-CI, throughput or
actual-pruning result is claimed for this backend-only step.

Subsequent ADR 0021 and V926 split the non-verified linked arm by vehicle identity
readiness: incomplete/missing registration keeps raw, complete identity coverage
uses ledger bound metadata. The independently verified retained arm and every
publication-visibility requirement remain. This does not remove raw write guards
or make active/provisional trips safe to prune.
