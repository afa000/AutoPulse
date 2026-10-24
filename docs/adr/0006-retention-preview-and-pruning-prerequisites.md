# 0006: Read-only retention preview and hard pruning prerequisites

Status: checkpoint 5 partially implemented. Read-only preview and exact-payload
identity capture/backfill, retained-identity replay and bounded preview coverage
checks are implemented;
deletion/downsampling policy is proposed, not authority to delete owner data.

## Why retention needs more than an age filter

Raw `telemetry_reading` rows currently provide all of the following:

- exact JSONB duplicate/conflict comparison and original receipt timestamps;
- event identities and canonical time/device/boot/sequence ordering;
- finalized trip evidence through immutable links and foreign keys;
- forward/reconciliation work and staged correction references;
- live/latest state, selected from the same raw table;
- raw and read-time aggregated history.

Deleting old rows now would destroy guarantees or fail constraints. A finalized
summary alone is not a replacement for evidence, replay identity, or history
availability. Do not disable final-evidence guards, cascade-delete links/trips,
drop partitions, or edit applied migrations to make deletion succeed.

## Implemented: policy preview only

`RetentionPreview.report(vehicleUuid, limit, continuation)` is an internal Java
service method, not an unauthenticated HTTP/admin route. It has no scheduler,
startup scan, command-line runner or prune method. Defaults:

| Setting | Default | Current meaning |
| --- | --- | --- |
| `autopulse.retention.enabled` | `false` | Disabled report returns without DB/transaction/clock calls |
| `autopulse.retention.dry-run` | `true` | `false` fails configuration; non-preview mode does not exist |
| `autopulse.retention.raw-days` | `30` | Proposed minimum measurement age; allowed 7..3650 days |
| `autopulse.retention.receipt-hold-hours` | `96` | Additional original-receipt quarantine; allowed 96..8760 hours |

These are conservative preview defaults, not an approved promise to retain only
30 days. Raw data is not being removed. Turning on this setting only permits an
explicit service call; it does not automatically scan or enable processing.

The initial report freezes trusted server UTC time, truncated to microseconds.
Measurement cutoff is `floor-to-UTC-day(asOf - rawDays)`, so a partial day is
conservatively retained. Only `recordedAt < cutoff` is enumerated. Receipts must
also satisfy `receivedAt < asOf - receiptHoldHours`; equality remains protected.
Telemetry maximum time never advances either horizon. The 96-hour receipt hold
is a new, independent retention quarantine, NOT a redefinition of the existing
five-minute finalization quiet period or the 48-hour maximum connected correction
span. That span is not a guarantee about maximum delivery lateness.

Each call uses a new read-only REPEATABLE READ snapshot and a 10-second timeout.
The raw page is materialized with limit+1 before joining work/link/trip metadata;
limit is 1..100, and no payload/whole-drive data is loaded into Java. Native
time/device/boot/NUMERIC order is recovered from a primary-key anchor, preserving
arbitrary sequence precision. Existing vehicle/time/tuple indexes support this
selection. Guard queries may inspect additional metadata/index visibility entries;
the row cap is not a universal query-cost guarantee.

Continuation is a validated **trusted in-process record**, not a signed public
cursor, saved deletion plan, authorization or executable list. It binds vehicle,
settings, page size, policy/primary device, frozen time and raw/trip generation,
expires at 15 minutes and rejects changed data/policy. Do not expose it unchanged
as an HTTP cursor. Each page reevaluates operational protections in its own snapshot;
those statuses are not frozen across a multi-page report. Counts apply to that
page, not an unseen complete database scan.

## Conservative protections

A vehicle is protected when primary policy, backfill, activity recovery, projection
or idle-worker status is missing/unfinished, policy changed, review is required,
work is pending/review (including deferred future work), correction/publication is
active, or any open/rebuilding/provisional-completed trip exists. These whole-vehicle
guards deliberately retain more data than an eventual fine-grained interval planner.

An individual old reading is additionally protected unless it:

1. is from the configured primary device and passes receipt quarantine;
2. has PROCESSED work, with projection strictly past its measurement timestamp;
3. belongs to the closed evidence interval of a FINAL, COMPLETED trip with matching
   primary device/source (confirmation-waiting links do not count);
4. is not the authoritative latest raw reading for the vehicle, across all devices;
5. has exactly matching retained replay evidence, with completed legacy replay backfill.

Unassigned idle data, secondary readings, losing ties, provisional/active data and
unknown metadata are not implicitly safe. Strictly-past timestamp comparison is
conservative about all equal-time tuple ties. A future outlier can block work but
cannot authorize deleting other measurements by moving the cutoff forward.

`policyCandidates` counts readings passing these preliminary checks. **It does not
mean deletable.** Every result reports `wouldDelete=0` and these hard prerequisites:

- `DURABLE_AGGREGATES_REQUIRED`
- `RETAINED_FINAL_EVIDENCE_REQUIRED`
- `WRITE_TIME_REPLAY_COVERAGE_RECHECK_REQUIRED`
- `PRUNER_NOT_IMPLEMENTED`

The preview neither creates aggregates nor pretends raw history has expired.
Existing history continues reporting AVAILABLE until an actual, atomic retention
implementation is separately completed. Current UI expired-history tests remain
contract-fixture tests, not evidence of pruning.

### Implemented bounded replay coverage assessment

The report reads `telemetry_replay_backfill` in the same read-only REPEATABLE READ
snapshot as raw/trip generation, policy and the selected page. Missing registration
or incomplete backfill protects the whole vehicle (`REPLAY_BACKFILL_MISSING` or
`REPLAY_BACKFILL_INCOMPLETE`), even if all currently returned rows already have
matching trigger-captured evidence. Reporting never creates registration, copies
ledger entries or marks progress complete.

After materializing the limit+1 raw metadata page, unique-key joins retrieve raw
payload and ledger evidence only for those bounded rows. Native device/boot/NUMERIC
identity must exist in the ledger, and original reading UUID, vehicle, exact
measurement/receipt timestamps, source and JSONB payload must all match. A completed
backfill flag cannot waive this comparison. Per-reading `replayIdentityState` is
MATCHED, MISSING or MISMATCH; the latter states add REPLAY_IDENTITY_MISSING/MISMATCH
and prevent policy candidacy. Payload comparison happens in PostgreSQL; payloads
are not selected into Java or the report. Differently scaled equal numeric values
still match, while exact differences hidden by display doubles do not.

`replayCoverage` contains `backfillState` (COMPLETE/INCOMPLETE/MISSING, or
NOT_EVALUATED when disabled) and checked/matched/missing/mismatched counts. Counts
cover only returned rows, not the lookahead, a different vehicle or unseen pages.
An empty page counts zero checks, not proof of vehicle-wide coverage. The row cap
does not bound individual payload bytes or the cost of other protection queries.

Coverage/progress is reevaluated on each continuation page. Backfill changes do not
advance raw/trip generation, so a concurrent backfill can leave the old snapshot
coherently incomplete while a continuation sees it complete at the same generation.
That is intentional per-page operational assessment, not a frozen multi-page ledger
certificate. Accepted raw/trip/policy changes retain the existing cursor safeguards.

The old blanket EXACT_REPLAY_IDENTITY_LEDGER_REQUIRED prerequisite is replaced by
WRITE_TIME_REPLAY_COVERAGE_RECHECK_REQUIRED: the ledger and per-page verification now
exist, but a read-only report is not a lock, durable pruning plan or deletion permit.
Any future pruner must verify complete backfill and exact evidence for its actual
bounded deletion set inside its synchronized write transaction, alongside all other
guards. No row becomes deletable here; wouldDelete remains zero.

## Required before any pruning implementation can execute

### Durable downsampling and honest queries

Persist exact per-sensor count/sum/min/max for UTC 1m/5m buckets, including all-null
metrics, source/device provenance and trip filtering/evidence semantics. Define
whole-bucket ownership, clipped query edges and sufficient retained evidence before
removing raw input. Do not serve full-bucket statistics for a clipped subrange as
if exact, average averages, combine overlapping raw/archived contributions twice,
or interpolate missing intervals. Repeated runs must be idempotent.

Availability needs committed coverage metadata, not an inference from an empty
raw query or MAX(recorded_at). Readers must distinguish never-observed data from
partially/fully expired raw ranges and advertise the resolutions actually served.
The existing generation/publication snapshot safeguards must remain effective.

[ADR 0008](0008-durable-trip-aggregate-contributions.md) implements bounded persisted
1m/5m per-final-trip contributions from immutable retained membership. It does not
yet implement clipped public archive reads, raw/archive ownership, availability or
the synchronized verify/prune path; DURABLE_AGGREGATES_REQUIRED remains deliberate.

### Evidence, identity and late data

Retain final trip summaries, stable trip IDs/revisions/lineage, necessary membership
and time/source/identity evidence independently of pruned raw rows. Migrate links
forward without weakening final immutability. Existing correction/replay references,
work checkpoints and future diagnostic/maintenance records must remain protected.
Preserve the authoritative latest state rather than making Live look empty.

[ADR 0007](0007-retained-final-trip-membership.md) implements an additive immutable
final-trip membership copy backed by the replay ledger, with bounded resumable
pages. It does not switch existing raw links, public readers or deletion guards;
RETAINED_FINAL_EVIDENCE_REQUIRED therefore remains a hard pruning prerequisite.

An independent durable replay ledger must preserve duplicate versus conflict
behavior and original receipt semantics for expired identities. A hash of JSONB
text alone is not enough: JSONB numeric equality can consider differently scaled
numeric text equal. Before using a digest, prove deterministic normalization against
the ingestion contract, document collision assumptions and add exact replay/conflict
tests. Alternatively retaining full canonical payloads is exact but may negate the
intended storage savings. The foundation below uses this conservative representation;
a compact representation/storage target remains unresolved. V915 implements replay
routing for retained identities. The preview assesses bounded coverage as described
above; synchronized write-time coverage remains a prerequisite for any future pruning.

### Implemented identity capture and bounded backfill

Forward-only V914 adds `telemetry_replay_identity`, keyed by native device/boot/
arbitrary-precision sequence. It retains the original reading/vehicle identity,
measurement time, receipt, source and full canonical JSONB payload. There is no
foreign key back to raw readings. UPDATE/DELETE are rejected, including no-op
updates; this protects accepted history, not against a database administrator.
No hash, JSONB text equality or approximate sensor columns decide equivalence.

An AFTER INSERT trigger captures new accepted rows in the same transaction as raw,
work and generation. Duplicate/conflict attempts insert no raw row and therefore
do not recapture identity or refresh receipts. Rollback removes capture too. Capture
is a mandatory ingestion invariant after migration, not a retention runner, and
does not depend on the retention flag. V914 alone failed insertion on an unexpected
preexisting ledger identity; V915 adds the retained-identity gate below without
editing V914 or weakening ledger immutability.

Migration registers existing vehicles but does not scan/copy raw payloads. New
vehicles register automatically. `ReplayIdentityBackfill.copyPage(vehicle, limit)`
is an explicit internal call (1..500 rows, new transaction, 10-second timeout),
without scheduler, startup runner, CLI or HTTP route. It locks that vehicle's
durable progress using SKIP LOCKED; contention returns busy. Pages use the existing
vehicle/UUID raw index. Each page copies at most the requested rows, verifies the
complete stored metadata and JSONB equivalence, and commits copies/cursor together.
Any mismatch rolls the page back. Service/process recreation resumes the cursor;
an exact-size last page needs a final empty call to mark completion. Post-migration
inserts behind the UUID cursor are covered by the atomic trigger. The bound limits
rows, not payload bytes; a row retains the ingestion contract's payload size cost.

This deliberately duplicates payload storage for now. It is a correctness foundation,
not demonstrated storage reduction or a permanent compact-ledger decision.
Coverage/backfill must still become a verified pruning barrier, not just a completed
flag assumed by a future caller. The replay gate below is not that coverage check.

### Implemented retained-identity replay gate

Forward-only V915 adds a BEFORE INSERT gate and leaves V914's AFTER INSERT capture
unchanged. The gate takes a transaction-scoped advisory lock for the device,
then returns NULL (no raw insertion) if the ledger already contains that identity.
The repository classifies that outcome using exact JSONB comparison against the
authoritative ledger, including vehicle identity. This returns the existing public
duplicate or IDENTITY_CONFLICT result without recreating raw history, queuing work,
advancing the vehicle read generation, updating receipts/activity or publishing a
TelemetryCommitted event. Retained evidence survives repository reconstruction.

The advisory hash is only a serialization key, never a payload digest/equality
decision. All identities on one device share a gate, bounding lock-table consumption
for large batches instead of retaining a lock per sample. Accepted writes already
serialize at the vehicle activity barrier; the device gate does not itself update
activity. Collisions serialize unrelated devices rather than merge them; full native
device/boot/NUMERIC keys and JSONB still decide identity/content, including 1 versus
1.00. Locks live until the caller's
transaction ends (the complete batch for normal ingestion). Like existing unique-key
waits, contention can wait on another writer; reverse-order batches can deadlock and
must follow the existing retriable database-failure/503 behavior. No global database
timeout setting or new public retry protocol was introduced.

At the normal READ COMMITTED ingestion isolation, a waiter rechecks the ledger
after acquiring the lock. A committed first writer yields duplicate/conflict; a
rolled-back first writer leaves no reservation and the waiter can accept. Stronger
caller-selected isolation can still require retry on database concurrency errors;
this does not promise a successful receipt under an externally pinned old snapshot.
Only actual raw inserts fire capture, so failed or ignored raw inserts cannot leave
phantom reserved identities. Atomic raw/work/ledger rollback remains intact.

For pre-V914 raw rows not yet copied, the gate finds no ledger entry and raw's
existing unique constraint suppresses reinsertion; the repository falls back to
exact raw comparison. This does not implicitly backfill or refresh old receipts.
Once backfilled, ledger evidence takes precedence. The fallback does not authorize
removing unbackfilled raw data. No owner database has been migrated or pruned; only
disposable, unprojected test fixtures remove raw/work rows to prove replay behavior.
Those fixtures do not demonstrate final-evidence retention, latest preservation,
archived availability or a production pruning algorithm.

The initial deletion policy must remain finalized-only. After raw removal do not
promise complete re-derivation from unavailable data. Newly accepted late identities
need retained raw receipt/evidence and explicit review/unapplied quality semantics;
they cannot silently rewrite finalized trips, disappear into old bucket totals or
make expired history appear complete again. Exceptional rebuilds require retained
sufficient evidence, explicit versioning and separate operator authority.

### Bounded, crash-safe execution and authority

A future runner must default off and have durable bounded phases: plan, build,
verify, then prune. Require aggregates/evidence/identity and availability metadata
committed before raw removal. Recheck data/policy/receipts/latest state under the
appropriate ingestion barrier in each bounded write transaction. Define shared
lock order, overlap prevention, retry/lease recovery and exact cutoff handling.
A dry-run report is not that write-time recheck or an executable deletion permit.

Crashes after aggregation but before deletion must resume without double counts;
crashes around deletion/availability must not create invisible holes. Protect open,
provisional, failed, unprocessed and review intervals even if old. Test concurrency,
future clocks, late data and summary invariance on disposable PostgreSQL first.

Owner-data deletion requires separate explicit approval of named data/ranges after
review of the concrete dry-run. Continue, a feature flag, an old report or successful
tests are not such approval. No owner database has been migrated or pruned here.

## Verification and remaining checkpoint work

Eight identity-foundation tests cover atomic capture/rollback, exact JSONB decimal
comparison, immutable receipts/payloads, V900/V913 upgrades without migration-time
payload copying, restartable bounded pages, lower-UUID new input, mismatch rollback,
competing progress locks, bounds and vehicle isolation. Existing HTTP/Live fixtures
use a fresh boot per test case so raw fixture cleanup does not require erasing the
ledger; within-case duplicate/conflict/concurrency assertions still reuse identities.
These original tests alone do not establish pruning behavior. Additional replay
tests exercise retained exact/conflicting submissions after synthetic raw removal,
canonical equivalence/precise decimals, HTTP mixed batches, simultaneous new/retained
submissions, observed advisory-lock waits across commit/rollback, integral numeric
scale equivalence, bounded gate-lock use in a 2,000-reading transaction, unbackfilled
V913 fallback and V914-to-V915 restart/upgrade.

Seven further coverage cases verify incomplete/missing registration protection,
complete-flag-with-damaged-evidence rejection, exact JSONB numeric comparisons,
original reading/vehicle metadata, page-only counts and vehicle isolation, coherent
concurrent backfill snapshots, and an actual V913 legacy migration from no ledger
through partial and completed bounded backfill. Read-only fingerprints now include
ledger and replay-progress tables; payloads never enter returned maps. The full
backend run passed 212 tests with zero failures/errors/skips. No new migration was
needed for coverage assessment, and no pruning/availability behavior is inferred.

The preview tests use disposable databases, actual finalization/evidence and a fixed
server clock. They verify disabled no-op, unsafe-config rejection, no mutation,
bounded precise paging, strict age/receipt cutoffs, protection states, duplicate and
rollback behavior, generation/policy invalidation and coherent concurrent snapshots.

Persisted all-null/weighted buckets, aggregate/prune reruns, crash recovery across
deletion, late input into expired ranges, retention during ingestion and invariance
after actual raw pruning are **not yet implemented or verified**. Checkpoint 5 is
incomplete. The full sender-to-trip-browser/retention demonstration remains checkpoint 6.

Run only the disposable preview tests from `server`:

```powershell
.\mvnw.cmd test '-Dtest=RetentionPreviewTests,RetentionPreviewUnitTests'
```
