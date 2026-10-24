# ADR 0016: Canonical correction reads after identity backfill

Status: accepted phased read transition; checkpoint 5 remains incomplete.

## Decision and source selection

The correction planner, replayer and publisher choose one reading source inside
each page transaction. If V923's ledger view exists and the vehicle's exact
ReplayIdentityBackfill checkpoint is complete, they use trip_ledger_reading.
Otherwise they retain telemetry_reading, including older-schema upgrade fixtures
that execute legacy processing before the new view/checkpoint exists.

TripReadingSource contains closed SQL identifiers, never request-provided table
names. Selection is explicit rather than a raw/ledger UNION on every payload
query, preserving direct PK/native/range index access. There is no automatic
backfill or global feature switch. New accepted ingestion already captures ledger
identity transactionally; finishing historical backfill supplies legacy rows.

The source-only handoff must preserve the exact accepted UUIDs, native ordering,
times, source and payloads. Normal backfill verifies those originals, so a page
may resume on the other source without changing its timestamp cursor or fold.
The existing input-generation and publication acceptance-fence rules still apply.
Completing a copy is not an attestation against arbitrary privileged metadata
edits; use the separate read-only audit for diagnosis and retain future write-time
safety checks. No vehicle generation bump is required solely for equivalent data
being read from a different storage source.

## Query and schema changes

Forward-only V923 creates a simple ledger-only view with the existing reading
column names, including reading_id AS id. It adds replay_correction_order on
(vehicle_id,device_id,recorded_at,boot_id DESC,sequence_number DESC). Canonical
DISTINCT ON winners and timestamp continuation retain their existing semantics;
no UUID ordering is substituted for native ties, no sequence is cast to a machine
integer and no microseconds are truncated.

The planner uses the selected source for review seeds and connected-window input
discovery. The replayer uses it for measurement/metric input pages. The publisher
uses it for original anchor capture, work acknowledgement and the terminal native
cursor tuple. Publication gates, receipt locks, acceptance fencing, final-overlap
checks, revisions and transaction rollback behavior are unchanged.

trip_correction_evidence keeps the same replay/segment/reading columns and status/
closed-time bounds. Its two disjoint arms select raw before vehicle backfill is
complete and ledger afterward, in one statement snapshot. The combined view still
has a raw schema dependency through its legacy arm; it is not a raw-free schema.

Migration performs no payload copy, checkpoint advance, automatic processing,
FK removal or guard edit. The ordinary index build reads existing ledger keys and
takes normal DDL/index locks: this is not a no-scan/no-lock deployment guarantee.
Existing migration checksums are untouched; only disposable databases are migrated.

## Boundaries, tests and remaining work

All three raw FKs and V922's original-raw write checks remain. Ledger-only SELECTs
in Java do not mean correction writes or database triggers are raw-independent.
Ready forward/finalization reads are covered by the extension below; work
hydration/backfill, archive copying, legacy branches and public history paths
still use raw. Retention
stays disabled/dry-run, with wouldDelete=0; no expired-raw availability claim,
successful raw deletion or post-pruning summary proof is made.

Disposable tests cover a complete correction cycle using only ledger-backed
Java reading queries, legacy raw fallback, source handoff between replay pages
without member/checkpoint/generation changes, V922 upgrade/restart with staged
inputs and unchanged originals, and continued rejection of finalized overlap.
The corrected hand fixture still has 27 observations, RPM sum 47237.5 and speed
area 1784, with stable trip identity and original raw receipts.

An EXPLAIN ANALYZE/buffers fixture checks the ledger-native ordering index for a
ten-instant page in a 5,000-reading history, with normal planner settings. It is
representative index/row-visit evidence, not a throughput SLA or universal bound
for large equal-time ties. The existing cap remains distinct timestamps, not all
physical tie rows; ten-second transaction timeouts remain. No new broad physical
I/O bound or production scale benchmark is claimed.

Next: remaining public metric paths and raw-dependent preparation safeguards, followed
by synchronized availability/generation publication and write-time replay/ownership
checks before bounded crash-safe opt-in pruning. Never remove raw FKs just because
correction reads can select the ledger. Checkpoints 5 and 6 remain incomplete.

The final full offline backend suite passed 306 tests across 23 fresh reports,
with zero failures/errors/skips, including all 126 migration cases. An initial
run exposed two existing pre-ledger fixture errors; the view-existence fallback
fixed them without weakening tests or guards. No production/test/DDL code changed
after the successful rerun. No fresh frontend, Python, browser, hosted CI or
actual-pruning result is claimed.

## Forward processing and finalization extension

The forward projector now uses the same source selector for its materialized work
page, complete native timestamp winner and timer-continuation identity lookup.
Selection is fixed inside its page transaction after policy/checkpoint checks.
The work page still limits ordered, prepared queue IDs BEFORE fetching payloads;
the raw WORK_PAGE_SQL remains available for legacy plan checks, with the equivalent
LEDGER_WORK_PAGE_SQL using the simple ledger view. No unbounded history join or
combined-source payload scan replaces the queue page.

The finalizer selects the same source inside its page transaction for bounded
receipt/activity recovery and eligible pending-work checks. It still uses the
original work accepted_at for receipt quietness, not processing time. Exact
five-minute quietness, pending/review/correction/policy fences, finalized revision
updates and immutable associations are unchanged. Reading from ledger must not
turn incomplete or pending work into permission to seal a trip.

The coordinator's late-review discovery also uses the selected source. Coordinator
discovery remains advisory, outside the worker's stage transaction, as before;
there is no new cross-query snapshot/atomicity claim. Worker/stage locks and
transactional checks recheck current state and source before doing work. Switching
equivalent sources does not itself create a job or reset its native cursor.

There is no new migration or backfill runner. Existing V923 supplies the view/index,
and incomplete or older-schema vehicles retain raw behavior. Work backfill and
ordering hydration, retained-evidence copying, original-raw write verification,
all raw FKs, public history paths and combined-view legacy arms remain raw-dependent.
Java reading-query observation is not evidence that PostgreSQL write triggers or
the complete processing pipeline can operate without raw. Retention stays disabled.

New disposable cases cover the hand-calculated forward summary, legacy fallback,
source handoff between work pages, a forty-digit sequence winner across one-row
pages, exact quietness and pending/review protection, late-correction discovery and
full coordinated publication, plus representative ledger work-page plans with a
5,000-row backlog/processed tail. No guards, retry behavior or receipt rules are
relaxed to get the new source path through tests.

Final full backend regression for this extension passed 314 tests across 23 fresh
suites, with zero failures/errors/skips, including all 134 migration cases. The
ledger work-page plan fixture returns ten rows with at most 100 ledger/work row
visits per relation and no raw row visits for both tested backlog shapes. These
are representative checks, not universal physical I/O or throughput guarantees.
An initial new-test completion-condition mistake was corrected to wait for
NO_DUE_WORK rather than one successful page's IDLE state; all stage/summary
assertions remain. No production/test/DDL changes followed the successful rerun.

## Subsequent work-preparation handoff

ADR 0019 and V925 extend the same readiness rule to work discovery and ordering
hydration. The statements above that these reads remain raw-backed describe the
earlier extension; ready vehicles now select ledger for those reads. Original-raw
reference verification, raw FKs and the other raw-dependent paths remain. This
does not make a whole processing transaction raw-free or authorize pruning.
