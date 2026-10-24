# 0005: Proposed trip rules and bounded history

Status: checkpoint-1 rules accepted by owner (2026-10-21). Algorithm `trip-v1-draft`.

Pure rules, fixtures and proposed contracts exist. Checkpoint 2's processing
implementation is verified in disposable databases, with
V901 durable processing work, bounded backfill and migration compatibility, then
V902 internal forward projection/checkpoints, V903 finalization safeguards and
V904 resumable correction-window planning, V905 isolated replay/input fencing and
V906 internal paged publication/identity lineage, and V907 durable worker control
with bounded retry/pause reporting. V908/V909 add opt-in bounded stage/vehicle
orchestration and timer-continuation recovery. V910 adds a canonical indexed work
page and bounded legacy sort-metadata preparation. Scheduling is disabled by default;
checkpoint 3 now serves trip list/detail and raw/1-minute/5-minute history.
Checkpoint 4 now includes trip list/detail summaries and bounded charts. Checkpoint 5
has a read-only preview foundation in ADR 0006; pruning/downsampling remain unimplemented.
Do not treat a simulator run/boot ID as a saved trip. ADRs 0001-0004 still apply.

## State rules

Defaults are project demonstration rules, not verified automotive thresholds:

| Setting | Default |
| --- | --- |
| Start confirmation | 2 consecutive distinct-time samples with RPM > 0 |
| Maximum start/off confirmation gap | 5 seconds |
| End confirmation | >=2 consecutive zero-RPM samples spanning >=2 seconds |
| Running-signal grace | 30 seconds |
| Maximum distance interval | 5 seconds |
| Future-clock tolerance | 2 seconds beyond injected processing time |
| Maximum trip duration | 24 hours; split with DURATION_LIMIT, not engine-off |
| Automatic correction quiet period | 5 minutes since last eligible acceptance |

`IDLE -> START_CANDIDATE -> ACTIVE -> END_CANDIDATE -> completed/IDLE`.
Start time is the first confirming candidate, not the second sample. Null/zero
interrupt a start candidate. Equal-time samples cannot confirm a start/end.

Zero RPM is off evidence; null RPM is unknown and zero speed is not off evidence.
An active trip survives short unknown intervals. A positive RPM cancels an off
candidate. Null cancels off confirmation without asserting the engine is off.
Confirmed off ends at the first sample of the successful off candidate; waiting
for confirmation does not inflate duration. Include that first off boundary
sample in evidence/statistics, but exclude subsequent confirmation/wait samples.

At >=30 seconds after the last running sample, expire an unconfirmed trip with
SIGNAL_TIMEOUT at its last running measurement, not at the timeout clock. This
applies before processing a new measurement and on an injected-clock timer tick.
Timeout is incomplete evidence, not confirmed shutdown. Completed historical
drives can appear immediately as provisional; they do not wait for real sender
sleeps. Open historical drives close honestly on a timer. A backend restart alone
does not split a trip: recover the persisted checkpoint/work marker; if its last
running signal is expired, close with SIGNAL_TIMEOUT and recovery metadata.

Use one explicitly configured primary internal device UUID per vehicle. Never
auto-select by arrival order or interleave contradictory devices. Secondary data
remains raw history and is counted as excluded evidence, not silently discarded.
A primary-device configuration change is an explicit projection revision, not an
automatic handover. Source changes close the old trip at its last running sample
with SOURCE_CHANGED; SIMULATED/OBD cannot mix. Boot changes within grace continue
the same trip with AGENT_RESTART quality, but distance never bridges a boot change.
Long boot gaps timeout first. A boot ID is neither a trip ID nor a global clock.

Read committed raw events, sorted ascending by `(recorded_at, internal device
UUID, boot UUID, sequence)` with PostgreSQL UUID byte ordering, not Java signed
UUID comparison. For a primary-device instant, only the greatest boot/sequence
tie participates in classification and trip summary evidence; retain all ties
in unfiltered raw history and report the collapse. A tripId history filter uses
that selected evidence membership, not every raw reading inside its time range.
Do not integrate zero-duration intervals. Retries deduplicate by
event identity; conflicting same-key content is invalid upstream, not two points.
Valid future outliers beyond `asOf+2s` are quarantined from classification and
watermarks; they stay visible in raw history. Re-evaluate by bounded timer work
when due, never let an outlier advance retention/finalization horizons.

## Corrections, identity and recoverability (checkpoint 2 implemented)

Accepted ingestion must atomically persist dirty work independent of process-local
SSE notifications. A worker uses bounded pages/checkpoints; it is not the SSE
coordinator. The pure bounded reference evaluator here is a fixture oracle, not
the production scanner or a claim of restart-safe processing.

Completed trips remain PROVISIONAL until the vehicle has had five minutes without
new eligible accepted data, no pending/failed projection work, and their closing
evidence is processed. This receipt-time quiet period allows historical batches
to settle without an old event timestamp immediately sealing the first page.
Duplicate delivery does not restart it; future-quarantined input cannot postpone
it. Rule classification is event-time based; only sealing uses receipt/wall time.

While unsealed, reconcile connected affected sessions with a 30-second boundary
halo, in bounded resumable pages. Support at most 48 hours of automatically
revisable measurement span; larger corrections require explicit backfill/review,
not an unbounded rescan. Finalized data is immutable under automatic processing.
Input affecting sealed or out-of-policy regions still persists in history; expose
LATE_DATA_UNAPPLIED and a pending-review coverage indicator. Do not claim complete
history or silently change an already finalized estimate.

Before sealing, a correction can split/merge boundaries. Persist UUID trip IDs,
increment revisions only for semantic changes, retain old IDs as SUPERSEDED
records with `supersededBy` links, and never cascade-delete links. The segment
containing the original anchor retains its ID; a merge retains the oldest
persisted anchor (created time then UUID). Other segments get new IDs. Membership
is unique under the primary-device policy. Equivalent in-policy event sets have
equivalent boundaries/sums/coverage regardless of batching; persisted IDs are
stable references within one database, not cross-database hashes. An approved
post-finalization rebuild must version/supersede, require retained raw evidence,
and cannot be promised after retention deletes necessary data.

## Statistics and distance

Evidence is closed `[start,end]` for completed summaries. Every numeric metric
stores its own non-null count, exact decimal sum, min and max. All-null metrics
have count 0 and null sum/min/max; do not zero-fill or average averages.
Averages are sample-weighted and represented by sum/count, evaluated/rounded only
for display. They are not time-weighted averages or complete-time coverage.

Estimated distance is trapezoidal speed integration only between consecutive
evidence samples with both speeds, matching boot/source and `0 < dt <=5s`.
Missing speed, boot changes and longer gaps break integration. Speed area is
stored exactly in km/h-seconds; km = area/3600. A rational numerator/denominator
preserves repeating values without rounding at ingestion. Covered seconds / trip
duration is reported separately; no coverage means null estimated distance, not
an invented zero. Observed stationary eligible intervals can give a real zero.
Unknown RPM, restart, gaps and timeout are quality signals, not diagnoses. This
is never the vehicle odometer. Raw exact JSON values remain authoritative.

### Expectations written before the reference implementation

Tiny input at seconds 0/2/4/6/8/10: RPM 0/1000/2000/1000/0/0,
speed 0/0/36/36/0/0. Trip starts at 2, ends at 8, confirms at 10:
four evidence samples, duration 6s, reason ENGINE_OFF. Speed area is
`18*2 + 36*2 + 18*2 = 144`; estimate `144/3600 = 0.04 km`, coverage 6/6.
RPM sum/count 4000/4; speed 72/4; coolant [20,null,40,50] gives 110/3;
load [10,20,null,null] gives 30/2. MAF stays entirely unavailable.

At two-second cadence, existing full commute starts at 10s, ends at 490s,
confirms at 492s: duration 480s, 241 evidence samples. City triangle area
`50*120/2=3000`; highway trapezoid `85*(300-60)=20400`; estimate 6.5km.
The short 4/8/16/20/8/4 commute starts at 4s, ends at 56s, confirms at
58s: duration 52s, 27 evidence samples; city area 400 plus highway 1360,
estimate `1760/3600 = 22/45 km`. Both have full speed-time coverage.
Exact sample sums/counts and their independently derived phase subtotals are in
`contracts/examples/trip-rule-fixtures.json`; averages remain rational sum/count.
These expected values must not be rewritten just to match an implementation.

## REST contract (trip list/detail/raw/aggregate history served)

- Trips list: default limit 20, max 100, descending start then trip UUID;
  active/provisional included, SUPERSEDED excluded unless individually looked up.
- Detail: vehicle-scoped UUID; unknown/wrong-vehicle lookup both return 404
  UNKNOWN_TRIP. Include version/revision, source, status/reason, finalization,
  summaries, coverage/quality and supersession links. Active end/reason are null;
  duration is observed time, not time since a browser opened. Refresh on navigation
  and explicit refresh; reconcile a changed revision, do not invent a new SSE log.
- History requires UTC-normalizable `from` and `to`, max six fractional digits,
  half-open `[from,to)`, strictly increasing bounds. Raw default, max 24h range;
  `1m` and `5m` UTC-aligned aggregation max 31 days. Page size default 200/max 1000.
  Ascending raw tuples preserve timestamp microseconds and all device/boot ties.
  Optional vehicle-scoped tripId selects evidence membership. To include the
  closed end boundary in a chart, request `to=end+1 microsecond` (bounds permitting).
- Aggregate bucket edges are UTC aligned; edge buckets are clipped to the range
  and marked partial. Per-sensor count/min/max/sum; chart means use sum/count.
  Absent buckets break lines, not zero-fill. Raw and aggregate points are distinct
  tagged schemas. Explicit availability metadata distinguishes expired raw history
  from never-observed data. No silent chart truncation: nextCursor must be followed.
- Opaque HMAC-bound cursors expire after 15 minutes and bind vehicle, trip filter,
  normalized range/resolution/limit, full last tuple and vehicle data generation.
  Cursor length <=2048 bytes; strict bounded decoding/validation. An ephemeral
  process key invalidates cursors on restart (400 INVALID_CURSOR; restart paging).
  A generation change from accepted inserts, projection revision or pruning returns
  409 PAGE_CHANGED. Each page reads revision/data in one short repeatable snapshot;
  cursors do not provide an immutable history across mutations. Retry from page 1.
- Invalid/equal/reversed/too-wide bounds, resolution, limit or cursor: structured
  400. Unknown vehicle 404; known empty list/history 200 with empty items/points.
  Wrong-vehicle cursor is 400, never access to the other vehicle. System failure
  503. Reuse ApiError and correlation IDs. No new auth or public-network exposure.

## Review gate and deferred work

Owner approved checkpoint 1 on 2026-10-21. V901 now installs durable per-reading
work and backfill progress. V902 adds an internal bounded forward projection
with persisted snapshots/evidence. It is not scheduled automatically. V1/V900
remain unchanged. Fresh/already-V900/profile-switch tests and explicit idempotent
local seeding cover the migration foundation. Forward fixtures now persist trips
and resume bounded pages after restart. Late input is retained and explicitly
marked for review as a temporary limitation, not a replacement for the agreed
automatic in-window reconciliation. V903 adds bounded receipt-horizon recovery
and guarded internal sealing after five quiet minutes, with no eligible pending
work or unresolved review. Final records and evidence links are protected against
mutation. V904 discovers connected measurement windows in bounded pages and
routes finalized/overwide/policy conflicts to review, without changing saved
evidence. V905 replays canonical readings into separate segments/checkpoints in
bounded pages and invalidates/replans when relevant inputs change. Existing V904
plans are rediscovered on upgrade; frozen future horizons and secondary/unrelated
input cannot invalidate a replay. Policy/finalized guards are rechecked. A READY
plan or STAGED attempt is not a corrected projection, stable production identity
or immutable snapshot: publication must still fence incoming changes in its own
write transaction. V906 adds that internal publication step: original snapshots,
stable anchor-based identity mapping, provisional content/evidence replacement,
typed SPLIT/SUPERSEDED lineage and acknowledgment/progress are durably paged.
Each page and its cursor commit together; finalized content remains protected.
Publication captures the vehicle's acceptance counter under the receipt barrier.
Later accepted readings are left pending even if their measurement times fall
inside the rebuilt region; they require a follow-up correction. Relevant changes
before publication starts invalidate staging and force discovery again.

During publication all trip readers for that vehicle must report
PUBLICATION_PENDING/temporary unavailability before reading the gated views
trip_visible_record/trip_evidence. Their empty intermediate result must not be
presented as no history. Raw tables are internal, partially rebuilt state, not a
public read model. The final publication transaction releases this gate with the
updated checkpoint and DONE plan. A policy change during publication leaves it
paused/hidden for explicit recovery, not abandoned with partial data visible.
V907 adds one durable processing-control row per vehicle and an explicit
single-page worker, independent of stage transactions. It records safe codes,
SQLSTATE, stage/result, counters and operational timestamps, not exception text
or telemetry. Transient failures back off 1/2/4/8 seconds; the fifth consecutive
failure pauses. Non-transient/invariant/policy/review failures pause immediately.
BUSY defers without consuming the budget; normal correction/publication handoffs
permit selecting the next stage. Retry/pause must not be bypassed by a scheduler.

Claims have two-minute leases; recovery resumes the same stage and counts expired
claims toward the pause threshold. Claim tokens fence late status updates, not
the underlying transaction: serialized/idempotent durable pages remain necessary.
If reporting is unavailable after page commit, the claim may expire and the page
may safely repeat. Control updates and processing must not share a caller's
ambient transaction. Retry clocks are injectable independently of event horizons.
Explicit revision-checked resume resets the consecutive retry budget, preserving
total failures, and never repairs policy, clears review/evidence, unseals data or
releases an unfinished publication gate. Fix the cause before resuming.

V908 persists one round-robin scan cursor. The opt-in scheduler starts only after
application readiness, selects one configured primary vehicle and invokes one
bounded worker call per fixed-delay tick. Selection commits before processing;
paused/deferred vehicles cannot stop later vehicles being visited. Existing worker
claims/trip locks protect competing app instances, not merely an in-process flag.
The coordinator resumes retry/expired-lease stages, unfinished publications and
correction plans before normal forward/timer/finalization work. It never bypasses
the worker's pause, unseals final data or silently acknowledges unresolved review.
Candidate/active timers progress even without new HTTP input or a browser.

V909 indexes nearby timer-closed/discarded trip boundaries. New event-time-adjacent
evidence after a timer closure/candidate expiry is routed to canonical correction,
including captures delivered in separate commits. The exact saved raw cursor can
also supply a running candidate when an earlier replay had only discarded staged
segments. Replaying retains the original rules for start confirmation, source
separation and timeout boundaries; wall-clock batching must not permanently lose
the first confirming observation or manufacture a false trip split. Finalized
overlap still requires explicit review rather than automatic unsealing.

The scheduler remains disabled by default and in the local profile. An enabled
disposable Spring test verifies real application-ready/timer invocation; ordinary
contexts verify no scheduler bean exists. Representative concurrency/query-plan
checks and the operational-review handoff are complete for checkpoint 2. Owner-data
enablement remains a separate explicit decision, not performed by these tests.
Policy/finalized/overwide review is a safe pause, not a
claim that automatic repair or a public operator interface has been implemented.

The audit found that ordering a raw/work join before LIMIT could visit all 5,000
pending raw rows for a ten-reading page. V910 mirrors immutable event ordering
fields in native timestamp/UUID/NUMERIC types on queued work, fills new markers
transactionally, and hydrates old pending markers in bounded restartable pages.
The forward query materializes the limited ordered work page before joining raw
payloads; measured raw visits are ten for both audited backlog and tail cases.
Canonical order, original receipt/deadline and acknowledgment semantics remain
unchanged. This is not a claim that every deferred/future distribution, correction
window, tie density or MVCC churn pattern has identical query cost. The recovery
handoff in `docs/trip-processing-operations.md` defines safe pause inspection and
explicit revision-checked resume without automatic policy repair/unsealing.
Trip list/detail now use explicit public DTOs, bounded keyset paging, signed
process-local 15-minute cursors and a read-only repeatable snapshot. V911 advances
vehicle read generations atomically with accepted raw data, trip changes and
lineage; V912 indexes public trip order/replacement lookups. Generation change
returns PAGE_CHANGED; invalid/tampered/expired/context-mismatched cursors return
INVALID_CURSOR. Active publication returns PUBLICATION_PENDING. CURRENT/PENDING/
REVIEW processing-state headers prevent a stored result implying complete applied
history. A pre-publication snapshot may finish coherently without blocking writers.
Raw history now enforces precise half-open bounds (at most 24h), bounded ascending
tuple pages, optional vehicle-scoped trip evidence filtering and signed normalized
query context. A fixed-size reading-ID cursor anchor recovers the complete native
timestamp/device/boot/NUMERIC tuple within the same repeatable snapshot, without
rounding or embedding potentially huge sequence text in a bounded token. Unfiltered
raw history preserves all ties and per-reading source; trip filtering excludes
waiting/losing evidence. Superseded trip filters return PAGE_CHANGED, not an empty
history. V913 indexes primary-device history order. Existing generation/publication
guards apply to filtered and unfiltered raw/aggregate paths. Current availability is AVAILABLE with raw/1m/5m
resolution; incomplete conservatively flags pending/review projection rather than
certifying capture completeness. Expiry/pruning is not implemented or simulated.
Aggregate history now uses SQL NUMERIC over canonical JSONB, not approximate sensor
columns. Per-metric counts/sums define exact sample-weighted means and sample coverage
(count/sampleCount); they are not elapsed-time coverage. Bucket bounds are UTC-aligned
and clipped only by the request, with partial reflecting that clipping, not trip
occupancy/completeness. First/last observations and sources/deviceCount expose
observed limits and mixed provenance. Unfiltered summaries retain all raw ties/devices;
trip summaries use evidence. Do not label mixed buckets as homogeneous OBD/SIMULATED.
Absent buckets are omitted and must break lines. There is no cross-bucket interpolation.

Pages seek to the next observed bucket then span at most limit aligned buckets.
A MATERIALIZED input cap of 100001 detects pages exceeding 100000 matching readings;
these fail with HISTORY_DENSITY_LIMIT (503) and no partial summaries. Reduce limit
or narrow the range, including within a dense bucket. Database transactions time out
after 10 seconds. This is a numeric-input bound, not a universal bound on index/MVCC
visibility visits or sparse-membership candidates. Process-local HMAC cursor kinds
bind raw/1m/5m separately; aggregate anchors resume strictly after their UTC bucket.

Actual EXPLAIN ANALYZE on a disposable 5000-reading fixture verifies indexed raw input
visits of 11 for a 10-row raw page and 20 for a one-minute aggregate window near the
tail. It is representative evidence, not production-scale/tie-density certification.
Checkpoint 3's API implementation/verification is complete. Checkpoint 4's trip
list/detail summary UI now uses native hash links, 20-row pages and explicit refresh
of persisted revisions. Fetches are cancelled/obsolete responses ignored on identity
changes. Failed refreshes retain labeled last-known results; pending/review processing,
provisional/active/superseded states, synthetic source and sample/distance uncertainty
remain explicit. Summary and history tables provide accessible non-visual statistics.
Checkpoint 4 now also charts raw/1m/5m history in 50/200-point replaceable pages,
with explicit continuation, exact microsecond half-open trip bounds, bounded time
windows, cancellation, and honest unavailable/expired/incomplete notices. Raw lines
break at nulls, ties, boot changes and gaps over 5 seconds; aggregate means are
unconnected markers because buckets cannot prove continuous within-bucket coverage.
Source and metric/UTC labels remain visible, and all plotted values are available
in a keyboard-scrollable table. Display-only numbers do not regenerate wire data.

Trip list/detail expose X-Trip-Data-Generation from the same repeatable snapshot.
History generation must match exactly before the UI combines it with a summary;
mismatch requires refreshing the summary, missing headers withhold charts, and
summary generation/revision refresh resets chart state. Provisional data can still
change after a coherent page response; this is not an immutable cross-request view.
Pinned Recharts/react-is and an updated lockfile support lazy-loaded chart rendering.
Official peer compatibility and gap/accessibility docs were checked before adoption.
Checkpoint 4's implementation/isolated UI verification is complete. Trip/chart
browser tests use contract fixtures, while the existing live browser test remains
connected to the actual sender/backend/disposable database. Opt-in retention and
the full simulator-to-projected-trip-to-chart demonstration remain later checkpoints.
Retention must not run on owner data without separate authorization.

ADR 0006 now defines a default-disabled internal retention policy preview with
read-only bounded metadata pages and hard pruning prerequisites. It never deletes
data or creates persisted aggregates, and does not mark checkpoint 5 complete.
