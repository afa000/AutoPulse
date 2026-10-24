# Trips and historical analytics: milestone verification

Checkpoint 6 is complete locally as of October 22, 2026. This document describes
the real demo and its evidence boundaries; the combined verification entry point
exited successfully. This completion does not authorize owner-data
operations, commits, pushes, merges or deployment.

## Reproduce against disposable infrastructure

Prerequisites remain Java 21, running Docker, Node 24.15+ (24.x), npm 11, installed
web dependencies/Chromium and the Python 3.12+ agent/dev environment. Do not change
global npm or bypass the version check. From the repository root in PowerShell:

```powershell
.\scripts\verify.ps1 -Python .\device-agent\.venv312\Scripts\python.exe -Browser
```

Use the configured Python environment appropriate to your checkout; the ignored
.venv312 path is this owner's local 3.12 environment, not a committed dependency.
The script runs Python/contracts, the ordinary Java/PostgreSQL suite, both real
browser demos, and frontend typecheck/lint/tests/build. Browser execution now
includes DashboardBrowserDemo and TripMilestoneDemo, not only contract fixtures.

To run only the new real trip demo after prerequisite setup, from server/:

```powershell
.\mvnw.cmd -o "-Dtest=TripMilestoneDemo" "-Dautopulse.python=../device-agent/.venv312/Scripts/python.exe" test
```

The same Maven invocation accepts a normal `python` executable on CI. The workflow
now includes both demos in its web job; Python 3.12/3.14 and the ordinary real-
PostgreSQL/slow-emitter gates remain. Updated YAML is not an observed hosted-CI
run. A new commit/push and actual resulting hosted run need separate authorization.

## Real pipeline and crash recovery

TripMilestoneDemo owns a new PostgreSQL Testcontainer, random loopback backend
ports and temporary captures/control files. It explicitly overrides datasource
settings and config imports, so owner .env/development settings are never used.
A separate backend JVM migrates that disposable DB and accepts data from the
production Python simulator, capture, BatchSender and HttpBatchClient code.

After 557 readings and 557 PENDING work rows commit, with zero projection progress
or trips, the launcher forcibly terminates that child JVM. A fresh backend then
recovers using only durable work/history. No browser or new telemetry is required
to trigger recovery. Boundaries and statistics are checked against pre-existing
hand-derived fixtures, not values invented after observing the output.

| Input | Raw observations | Expected retained trip evidence | Key arithmetic/behavior |
| --- | ---: | ---: | --- |
| Normal commute | 251 | 241 | 480 s; RPM sum 506050; speed area 23400; estimated 6.5 km |
| Same commute, batch size 7 instead of 100 | 251 | 241 | Identical summary and boundaries |
| Short commute | 31 | 27 | 52 s; RPM sum 46275; speed area 1760 |
| Tiny hand-checkable fixture | 6 | 4 | 6 s; speed area 144; estimated 0.04 km; null MAF remains null |
| Short null interval and boot restart | 7 | 5 | One trip; speed area 72; UNKNOWN_RPM and AGENT_RESTART |
| Long interruption | 6 | 2 + 3 in two trips | First closes SIGNAL_TIMEOUT; areas 72 + 108, no integration across outage |
| Equal timestamps across boots | 5 | 3 canonical members | Deterministic tie selection; area 108; raw paging preserves both tied observations |

Small synthetic cases arrive reversed and are duplicated before projection.
Captures are replayed after projection and again after retention; all replayed
identities remain duplicates, with no new raw/trips/distance or receipt changes.
An empty registered vehicle stays isolated. These capture files remain demo
artifacts, not the deferred durable device outbox.

## Unmocked browser and actual retention

The new trip-milestone browser test has no response interception or fixture
fulfillment. React's list/detail/chart requests hit the real Java API/PostgreSQL.
It checks the normal trip, SIMULATED/UTC/estimated-distance labeling, null-aware
tables, 200+41 raw pages, aggregates, same-time boundary paging and wrong-vehicle
lookups. Empty and unknown-trip states remain explicit.

A second real Python sender supplies live snapshots on an independent vehicle
while Java repeatedly processes its committed queue and the main page reads
history. The test observes card updates, stale measurement labeling, a genuine
TCP stream interruption/reconnect through the loopback fault proxy and retained
last-known state. All three live work rows must be PROCESSED. The ordinary suite's
actual Spring emitter-lock/slow-client tests and original multi-tab browser test
remain enabled; no shared SSE coordinator was introduced for history work.

A test-only file signal then asks JUnit to call the real bounded retention
maintenance service. It creates/verifies archives and removes exactly 241 normal
trip raw rows, leaving ten protected raw observations. No application admin HTTP
endpoint is added. Final summaries and aggregates remain identical, raw trip
history explicitly becomes EXPIRED, supported aggregates remain usable, and
replay cannot resurrect deleted raw. Generation changes are handled by summary
refresh before charting.

Retention defaults remain disabled/dry-run outside this explicitly constructed
test service. Full canonical payloads are intentionally retained; this verifies
correctness of expiration, not compact-storage savings or deletion of all old
protected data.

## Accessibility, visual review and cleanup

Desktop and 390px narrow views are exercised with actual API data. Keyboard links
open trips and native table regions scroll horizontally without widening the
page. Axe checks cover list/detail, charts, retained aggregates, empty and unknown
states. Screenshots are generated in ignored web/test-results/; failures retain
Playwright traces. Desktop list/detail, narrow detail, expired raw and retained
aggregate screenshots have been visually inspected for readable layout, metric
labels, explicit uncertainty and no page-wide horizontal overflow. The narrow
list was also captured and visually inspected after the final browser run.

Generated evidence:

- server/target/trip-seed-server.log: child backend startup/migration evidence.
- server/target/trip-milestone-browser.log: unmocked browser results.
- server/target/dashboard-browser.log: original live/contract browser results.
- server/target/surefire-reports/: Java results, including optional demos.
- web/test-results/: screenshots and failure traces; later browser runs replace
  this ignored output directory.

Test children, Vite, proxies, application contexts and connection pools are closed;
the trip demo asserts zero remaining stream reservations after shutdown. JUnit/
Testcontainers own their temporary files and containers. There is no development
volume reset or owner-data cleanup.

## Final local verification

The combined PowerShell entry point with `-Browser` exited 0 on October 22, 2026,
at approximately 19:30 local (America/New_York), using the ignored local npm 11
selection and offline Maven dependencies:

- Python 3.12: 96 tests passed, including contracts and isolated-demo safety tests.
- Ordinary Java/PostgreSQL: 408 tests across 24 fresh suites; zero failures,
  errors or skips. Report timestamps span 19:24:40 through 19:28:53.
- Opt-in Java demos: two tests passed; DashboardBrowserDemo ran five browser
  scenarios, and TripMilestoneDemo ran one unmocked end-to-end scenario. Both
  Maven demo tests had zero failures, errors or skips.
- Frontend: 59 tests passed; typecheck, lint and production build passed.
- Python 3.14: a separate run passed the same 96 tests. Its first restricted
  attempt could not create a pytest temporary directory; the rerun with normal
  temporary-directory permissions passed without changing tests.

Real-data evidence includes 557 committed seed readings recovered after child-JVM
termination, unchanged replay/receipts, exactly 241 pruned rows with ten protected
raw rows, identical retained statistics and zero stream reservations on shutdown.
Desktop/narrow accessibility checks passed; all six real-data screenshots were
visually inspected. Whitespace checks passed, apart from the existing informational
OpenAPI line-ending conversion notice. Private learning log, blueprint and .env
remain ignored/untracked; staging is empty. Saved disabled/dry-run defaults remain
unchanged. No owner development database was accessed and nothing was committed,
pushed, merged or deployed.

## Remaining boundary

No new hosted CI, merge or deployment is claimed. Milestone completion does not
complete the broader MVP: hardware/OBD,
the separate durable edge outbox, authentication, diagnostics/maintenance features
and production deployment remain separate work. Review the completed milestone
before choosing the next project scope.

## Subsequent diagnostics/maintenance checkpoint 0: exact numeric browser boundary

This is a separate post-milestone repair, not diagnostic feature implementation.
See [ADR 0028](adr/0028-lossless-analytical-browser-boundary.md) for compatibility,
malformed-response handling and the distinction between exact tokens and display
approximations. The old milestone/hosted counts above describe their dated commit,
not new hosted evidence for this repair.

Reproduce the new regression from server/ after the normal dependency setup:

```powershell
.\mvnw.cmd -o "-Dtest=NumericAnalyticsDemo" test
```

It uses a new PostgreSQL Testcontainer/random loopback backend, actual HTTP batch
ingestion and the production Chromium UI/parsers. Three 31-reading short fixtures
produce 27-member trips: +2.7e309, -2.7e309 and +1e308 coolant totals after signed
cancellation. Their finite means are +1e308, -1e308 and approximately 3.703704e306.
Each exact speed integral is 5.2e309 / 3600, about 1.444444e306 estimated km.
These are synthetic numeric boundary tests, not realistic vehicle values.

The real browser checks lists/details, raw history, 1m/5m trip aggregates and the
opt-in unfiltered owned adapter. Explicit bounded test-only maintenance prunes
27 eligible raw rows per vehicle (81 total), while four protected raw rows per
vehicle, complete trip records, exact totals, finite means and estimated distance
remain. Expired raw is labeled; retained/owned aggregates remain equal. Saved
processing/retention/owned-reader defaults stay off and dry-run true. No owner DB
is accessed. No API responses are mocked or fulfilled in this scenario.

Evidence is in ignored server/target/numeric-analytics-browser.log and the
NumericAnalyticsDemo Surefire report. Desktop/narrow screenshots are generated
under web/test-results/numeric-boundary-*/; subsequent browser runs replace them.
Both were visually inspected after the standalone passing run. Axe checks returned
zero violations. Large evidence strings wrap, table columns scroll within their
region, and chart power-of-ten scaling/UTC/source/expiry labels remain readable.

The updated optional local -Browser entry point and CI web gate include this demo
alongside DashboardBrowserDemo and TripMilestoneDemo. Standalone numeric Java and
browser tests have passed; frontend typecheck/lint/78 tests/build and Python
97 tests on each of 3.12 and 3.14 have passed. The final combined entry point
exited 0 on October 22, 2026 at approximately 23:40:53 America/New_York. Fresh
backend reports contain 408 tests across 24 suites (23:34:41–23:39:02), with zero
failures/errors/skips. All three opt-in Java demo tests passed, running seven
browser scenarios (five original plus trip/restart/retention plus numeric). The
final frontend check passed 78 tests, typecheck, lint and build. Python 3.12 passed
97 in the combined run; Python 3.14 separately passed 97. Tracked/untracked source
whitespace checks pass with only the existing informational OpenAPI line-ending
notice. No new schema migration or owner database operation was introduced.
No fresh hosted result or publication is claimed. Stop for owner
review of checkpoint 0 before adding diagnostics contracts/features.

## Diagnostics/maintenance checkpoint 1: contracts and simulator fixtures

After the owner's checkpoint-0 approval, checkpoint 1 added ADR 0029, planned
OpenAPI routes/schemas, shared snapshot/API/invalid/thermal fixtures, immutable
Python models/strict codec, typed read-only source protocols and opt-in simulator
stories. No diagnostic endpoint, database migration, persistence/event processor,
catalog, recommendation rule, service workflow or physical adapter is implemented.

Fresh verification on October 22, 2026:

- Python 3.12 and 3.14 each passed all 150 tests. The 53 new tests cover shared
  schema/model round trips, semantic invalid cases, 64KiB/int64/UTC-microsecond
  boundaries, separate telemetry identity, source capabilities, explicit unsupported
  reads, partial positives, multiple ECUs, future/stale/tie/watermark eligibility,
  read-only control/unknown-operation rejection and deterministic fixture equality.
- Frontend typecheck, lint, 78 tests and production build passed unchanged.
- Existing opt-in CommuteDemo passed one Java/PostgreSQL integration test using
  the actual updated Python sender/source code against a disposable container.
  Evidence: 151 accepted + 100 duplicate on interrupted acknowledgment; replay
  accepted 0 / duplicate 251; identical retry bytes and unchanged latest/receipt.
- No full ordinary Java suite, browser demos or hosted CI was rerun for this
  checkpoint. The earlier checkpoint-0 408/backend and seven-browser results above
  remain historical evidence, not fresh checkpoint-1 executions. No backend/UI
  implementation source changed for checkpoint 1.

The lifecycle fixture specifies 15 deliveries, 13 unique snapshots, two exact
duplicates, five future event cycles and three future confirmed-code advisory
cycles. Snapshot/source/identity counts are tested; event/advisory derivation is
explicitly EXPECTED ONLY until checkpoints 2/3 implement it. The synthetic thermal
fixture has 36 two-second measurements, planned condition start at32s, a null at34s
that cannot imply recovery, and supported recovery at70s. Its thresholds are not
manufacturer limits and no DTC is inferred. No recommendation engine is claimed.

Private files remain excluded, owner DB untouched, no staging/publication or new
hosted run, and saved safe defaults unchanged. Stop for review of checkpoint-1
contracts/expected outcomes before diagnostic persistence work in checkpoint 2.

## Diagnostics/maintenance checkpoint 2: durable history and catalog

Implemented locally after owner approval to move past checkpoint 1. ADR 0030
records the event state machine, durable queue, bounded recovery/query behavior,
catalog provenance/rights and limits. V932 adds seven diagnostic tables; no older
migration changed. Snapshot acceptance, queue insertion and generation commit
together. Event applications, transitions and one ECU/category of progress commit
together, independently of coalescing SSE/browser delivery. The saved diagnostic
poll is disabled, as are existing trip processing/retention/owned aggregates;
retention dry-run remains true. Only disposable PostgreSQL databases were used.

Executed on October 23, 2026 (America/New_York):

- Full `scripts/verify.ps1 -Python .\device-agent\.venv312\Scripts\python.exe -Browser`
  exited 0. Process-local npm 11 selection and cached/offline Maven were used,
  without global runtime changes. Ordinary backend reports span 00:28:35–00:32:40:
  **427 tests across 27 suites, zero failures/errors/skips**. A stale historical
  ReviewNumericProbeTests report is excluded from this count.
- The 19 new backend tests comprise 16 actual HTTP/PostgreSQL diagnostic cases,
  one enabled managed-poll test and two fresh/931-upgrade migration cases. The
  upgrade preserves baseline telemetry/receipt values and applied checksums.
- Hand-authored lifecycle: 15 deliveries yield 13 snapshots, two exact duplicates,
  39 category applications, five event cycles and positive counts [3,2,3,2,2];
  three category applications are historical-only. Advisory counts remain expected
  only: checkpoint 3 is not implemented.
- Tests cover atomic enqueue rollback, conflicting/replayed identities, original
  receipt on replay, strict UTF-8/64KiB/native-int64/UTC-microseconds, registry
  association, scoped absence/recurrence, failed/partial/stale/future evidence,
  native UUID ties, concurrent acceptance/workers, injected transition rollback,
  fresh-instance continuation, bounded retries/review, and immutable guards.
- The 1000-event indexed query-plan case uses explicit query-only synthetic rows,
  not a claim that the processor generated that history. Detail bounds return 100
  of 101 synthetic transitions with hasEarlierTransitions=true. Active-code growth
  beyond the conservative 100-code union limit goes to REVIEW without a partial
  category publication. Catalog known/unknown provenance is visible over HTTP.
- The actual successful HTTP list/detail JSON exported by DiagnosticApiTests is
  validated against shared OpenAPI by scripts/verify_diagnostic_http.py, both in
  the combined entry point and after the final schema-description update. CI's
  backend job now repeats that bridge; no new hosted execution is claimed.
- Python 3.12.10 and 3.14.3 each pass **152 tests**, including all shared contracts.
  Final reruns after documentation/schema-description edits: 152 in 6.08s on 3.12,
  152 in 6.29s on 3.14. An initial final-rerun invocation named a nonexistent
  .venv314 path; corrected to the existing .venv and reran successfully.
- All three opt-in Java demos pass (completed 00:34:28), exercising **seven real
  browser scenarios** across live dashboard, trip/restart/retention and numeric
  boundaries. Fresh numeric evidence includes positive/negative/cancellation,
  raw/1m/5m/owned/retained values, finite means and axe=0. Existing trip proof
  retains 557-reading child-JVM crash recovery, 241-row raw expiry with protected
  evidence, replay without resurrection and accessible desktop/narrow browsing.
- Frontend typecheck, lint, **78 tests**, production build pass (tests 00:34:32).
  Main JS 93.68KB gzip and chart chunk 95.93KB gzip remain unchanged observations.

The diagnostic recovery tests reconstruct workers over committed queue state and
inject a transaction failure; the managed-poll test accepts queued work without a
browser. They do not kill/restart a diagnostic JVM or run a diagnostic sender/UI.
That full end-to-end diagnostic proof remains checkpoint 5; the executed child-JVM
test above is the existing trip demonstration, not diagnostic crash proof.

The two original catalog summaries are pinned and verified against primary Audi
and FCA documents hosted by NHTSA (links and exact scope in ADR 0030). MIT covers
only AutoPulse's own paraphrases, not manufacturer documents/tables. Generic
definitions are not Hyundai repair guidance or thresholds; unknown codes receive
no guessed definition. No diagnostic/service deletion, recommendation engine,
maintenance workflow, physical driver or diagnostic dashboard was added.

Private learning notes remain ignored/untracked; no owner database operation,
staging, commit/push, PR, merge, deployment or new hosted result. Stop for owner
review before checkpoint 3 maintenance data and recommendation rules.

## Diagnostics/maintenance checkpoint 3: in progress, database proof blocked

Owner authorized checkpoint 3 and confirmed U.S. market. Added ADR 0031, shared
hand-written oil/calendar/lifecycle cases, strict manual command schemas and scoped
routes, pinned oil-source metadata, additive V933, manual history/command ledger,
three bounded rules, durable work/decision evidence and opt-in managed poll.
All earlier dirty work is preserved. This is source implementation, **not yet
verified database/API functionality or completed checkpoint-3 acceptance**.

Source verification: visually reviewed manufacturer-authored 2021 Venue manual
normal/severe tables p8-8/p8-11, with oil-grade footnote, engine G1.6 applicability
and Hyundai Motor America copyright. Exact provenance/mirror-hosting and original
factual interval metadata are in ADR 0031 and maintenance/oil-filter-v1.json.
Downloaded reference/rendered table pages reside only in ignored server/target;
no manufacturer PDF/table is added to source control. Actual usage selection stays
UNSET until explicitly recorded by the owner; chat confirmation did not write data.
The PDF skill guided visual table/footnote review; no PDF was authored or modified.

Fresh executed checks on October 23:

- Six deterministic Java maintenance rule tests pass (latest observed 12:36:15
  America/New_York), after compiling all new production/test sources. Cover seven
  independently written oil cases, inclusive distance/time, leap/month-end/timezone,
  missing profile/history/oil-grade/schedule/odometer, stale uncertainty, exact-unit
  boundary and synthetic thermal gap/null/future/stale/hysteresis behavior.
- Python 3.12.10 and 3.14.3 each pass154 tests (6.68s/7.55s at this point), including
  two new pinned-source/strict-decimal-command contract tests. A final rerun after
  the additional currentObservation response schema is recorded below if executed.
- Unchanged frontend typecheck, lint, all78 tests and build pass (tests12:33:04).
  Main JS93.68KB/chart95.93KB gzip unchanged. An initial unsupported npm 'check'
  shorthand failed before running checks; corrected to the actual individual scripts.
- Java's initial compound-var declaration compilation error was fixed; subsequent
  compilations/unit tests pass. No database failure was masked as a passing check.

Prepared but NOT EXECUTED:14 PostgreSQL/HTTP maintenance tests,2 fresh/932-upgrade
migration tests and1 opt-in poll test. They cover actual manual retries/conflicts,
history/corrections, matching-only service completion, advisory deduplication,
thermal fixture/recurrence/late data, scope/cursor boundaries, failure rollback,
retry/review blocking, immutable evidence and independent raw-expiry evidence.
scripts/verify_maintenance_http.py is wired into local/CI verification but has NOT
validated actual response output yet, because the database tests have not run.
Full ordinary backend and all browser demos likewise have not rerun for this slice;
the previous427/backend and seven-browser counts remain checkpoint-2 history.

Final contract rerun after all response-schema changes: Python3.12 passed154 in
6.84s; Python3.14 passed154 in7.36s. Tracked whitespace and all48 untracked source
files pass whitespace checks (only informational OpenAPI line-ending notice).
Private log/blueprint/.env and downloaded reference PDF are ignored; no private
files tracked and no staged files. This does not lift the PostgreSQL blocker.

Docker was unavailable. At the owner's explicit request, started their installed
per-user Docker Desktop executable. It launched but failed initializing its
Secrets Engine: engine.sock could not be renamed to engine.sock.stale (system
file-access error). Linux engine pipe remained absent. Read-only local logs
confirmed startup failure, not a slow successful engine start. No factory reset,
socket/config/data deletion, Docker reinstall, WSL shutdown or database workaround
was attempted. A hung read-only desktop-status command was canceled.

Next: resolve Docker startup with owner direction, verify development DB remains
stopped, execute focused disposable tests/actual HTTP bridge, fix discovered
failures, then run full regressions and finish docs/learning log/checklist before
claiming checkpoint3 complete. No checkpoint4 UI or publication work is authorized.
Saved trip/diagnostic/maintenance processing, retention and owned aggregates are
off; dry-run is true. Private files remain excluded, owner DB untouched, no staging,
commit/push, PR, merge, deployment or new hosted result.

## Checkpoint 3 pre-repair finding: backend verified, shutdown blocked sign-off

This continuation supersedes the earlier Docker-blocked progress note. Docker
29.9.2 was healthy and no containers were running before the disposable tests.
No Docker data/config reset or development database connection/start was performed.

Executed October23,2026 (America/New_York):

- Focused maintenance run initially23 cases: five lifecycle failures, no migration
  or managed-poll failure. Fixed a missing recommendation-ID SQL bind parameter
  in transition persistence. Temporary exception-debug branch was removed.
- Added a failing downtime/backlog regression before its fix: later current service
  state erased accepted earlier queued due evidence (expected1 advisory, actual0).
  Freeze each command's accepted profile/service/current-odometer revision inputs
  into work; processing now preserves the due-then-service history. All24 focused
  tests pass:15 actualHTTP/PostgreSQL,2 fresh/932-upgrade,1 opt-in managed poll,
  6 deterministic rule tests. Fresh worker reconstruction and injected rollback
  cover database recovery, not a separate diagnostic/maintenance JVM kill.
- Final full ordinary backend run: **451 tests across31 fresh suites**, zero
  failures/errors/skips; report timestamps15:52:38–15:57:24. Excluded stale older
  probe/demo reports. Includes all169 trip/migration regressions and all existing
  diagnostics/history/replay/retention tests. New poll selection also passes with
  review/backoff blocking only its vehicle, not unrelated eligible work.
- Actual diagnostic and maintenance HTTP OpenAPI bridges pass in the combined
  entry point. The maintenance artifact is produced from successful actual HTTP
  responses, including profile, entry list/detail, recommendation list/detail and
  receipt; no invented output substituted for database proof.
- Python3.12.10 passes154 in6.93s in the combined run; Python3.14.3 separately
  passes154 in9.27s. Private inputs are not part of those public fixtures.
- Combined optional demos: DashboardBrowserDemo and NumericAnalyticsDemo pass
  their Java tests. All **seven browser scenarios pass** (five dashboard/trip,
  one numeric, one real trip), including axes=0/keyboard/desktop+narrow proof.
  Trip scenario also proves557-reading JVM recovery, replay and241-row retention
  with protected evidence. These are existing regression scenarios, not a new
  maintenance/diagnostic browser implementation.
- **Combined entry point exits1** at15:59:25: TripMilestoneDemo's final shutdown
  assertion at line166 expects zero stream reservations after10s, but one remains.
  Awaitility reports an actual deadlock: Tomcat's error-listener thread holds a
  socket-processing lock while awaiting Spring's async lifecycle lock; live-close-1
  holds the other lock while waiting for that socket lock. Browser functionality
  passed before cleanup failed. Do not describe this as a passing full demo or
  merely a slow test. No timeout/assertion weakening, process leak hiding or rerun
  to overwrite the failure was performed.
- Because the combined entry point stops before frontend checks, ran them
  independently afterward: typecheck, lint, **78 tests** and build pass (tests
  16:01:50). Main JS93.68KB/chart95.93KB gzip unchanged.

Read-only investigation confirms no checkpoint-3 changes to LiveHub, LiveStreams
or TripMilestoneDemo. The observed cycle is in existing streaming cleanup, not
the new maintenance scheduler. Spring's own guidance says the container handles
emitter I/O-error completion; application cleanup should not issue a second
completion after such errors. This supports a redundant-completion race as a
repair hypothesis, not a verified fix. See
[Spring asynchronous streaming documentation](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html#webmvc-emitter).
Local Spring source was inspected as well. No live-stream production source,
dependency version or demo assertion has been changed for this investigation.

Checkpoint3's maintenance implementation is locally verified, but final checkpoint
sign-off remains pending owner direction to repair the separate live-stream cleanup
path, a focused permanent regression and complete verification afterward. Do not
start checkpoint4 or call the milestone complete. Existing historical fixture
EXPECTED-ONLY labels record their preimplementation origin; actual event/advisory
counts are now proven by the checkpoint2/3 tests, not inferred from those labels.

README, ADR0031, maintenance-operations.md and private log are updated. Safe
processing/retention/owned flags remain false; dry-run true. Disposable containers
were cleaned up and the running-container list was empty after testing. No owner
DB operation, staging, commit/push, PR, merge, deployment or new hosted execution.

## Checkpoint 3 final result: cleanup repaired and complete workflow passed

This result supersedes the earlier blocked progress records without erasing their
failed-run evidence. The owner explicitly authorized the separate cleanup repair.
No new maintenance features, database migrations or UI work were added in that repair.

The fix distinguishes container-owned I/O failure from application retirement.
LiveEmitter marks send-I/O ownership before releasing Spring's write lock, skips
redundant completion after I/O error/timeout, and uses a no-flush terminal dispatch
for server retirement. Container completion confirms failed-transport cleanup.
LiveHub retains capacity until writer return, transport completion and return of
any submitted cleanup task. Per-client synchronized bookkeeping closes a callback/
queued-cleanup release race. Writer shutdown drains queued closed-client tasks
rather than discarding their release bookkeeping. See ADR0004 for source rationale.

Permanent regressions: three real-Spring-emitter/controlled-handler cases for
no-flush retirement, I/O ownership and failed-send/queued-close ordering; three hub
cases for completion/writer/outstanding-cleanup reservation accounting; one actual
64-client backlog shutdown test. The no-flush and early-queued-release cases were
observed failing before their fixes. The final backlog test waits for at least four
real queued writer tasks, not a timing sleep. Focused17 tests passed. The unchanged
real trip/browser demo also passed standalone at16:14:57 before the tighter
accounting guard; final proof below covers the complete final repair.

Final executed verification, October23,2026 (America/New_York):

- `scripts/verify.ps1 -Python .\device-agent\.venv312\Scripts\python.exe -Browser`
  **exited0**. Used only disposable PostgreSQL containers, process-local npm11
  selection and existing/offline Maven dependencies.
- Ordinary backend: **458 tests across33 fresh suites**, zero failures/errors/skips;
  reports16:22:02–16:26:46. Includes169 trip/migration regressions, all24 maintenance
  cases and seven cleanup regressions. Historical stale probe/demo reports excluded.
- Both actual diagnostic and maintenance HTTP/OpenAPI schema gates pass.
- Python3.12.10:154 passed in7.04s in the final combined run. Python3.14.3:
  154 passed in8.40s separately; no Python/schema implementation changed afterward.
- Three optional Java demos pass (zero failures/errors/skips), completed16:28:26.
  All **seven real browser scenarios** pass. Final trip case16.7s, with
  `TRIP_CLEANUP_EVIDENCE applicationClosed=true streamReservations=0`; unchanged
  ten-second cleanup assertion passes. Numeric signed/cancellation/raw/1m/5m/owned/
  retained cases and existing live/reconnect/accessible desktop+narrow checks pass.
- Actual trip demo retains557-reading child-JVM recovery/replay and241-row raw
  retention with protected evidence. These remain existing trip regression proofs,
  not a diagnostic/maintenance browser/JVM demonstration, which belongs to checkpoint5.
- Frontend typecheck/lint/**78 tests**/build pass (tests16:28:30). Main JS93.68KB
  gzip and chart95.93KB gzip unchanged. Ordinary committed-response timeout warnings
  occur during container shutdown but no deadlock, failed cleanup or leaked
  reservation remains in the final demonstration.

The intermediate16:15 full run was canceled to add/verify the stronger outstanding-
cleanup guard; it is not counted as a complete run. The final run above restarted
from Python through all backend/browser/frontend gates. No timeout/assertion
weakening, forced reservation reset, framework upgrade or live-resource cap increase.
Caps remain64 global/8 per vehicle, four writers/two cleanup workers,64-task queues,
15s heartbeat,5s write-retirement threshold/response attachment and120s lease.

Checkpoint3 is now complete locally and ready for owner review before checkpoint4.
Includes revisioned manual profile/service/odometer history, retry/concurrency
protection, evidence-backed oil/confirmed-code/synthetic-temperature advisories,
explicit unknowns and matching-only service completion. New views, actual complete
diagnostic/maintenance simulator-to-browser proof, hardware, durable edge outbox,
authentication/deployment and any publication remain later work. Private learning
log/Desktop plan updated; saved processing/retention/owned flags stay off and
retention dry-run true. No owner DB operation, staging/commit/push, PR, merge,
deployment or new hosted result.

## Diagnostics/maintenance checkpoint 4 — October 23, 2026

Owner authorized the new views after checkpoint3 review. New files are CarePages,
care and useCare in web/src, their component tests, web/e2e/care.spec.ts and the
opt-in CareBrowserDemo. Existing hash navigation/styles are extended; no production
backend/default/migration change in this checkpoint. Native int64 revisions/counts
use lossless-json in both reads and exact numeric expectedRevision commands, without
changing existing trip analytics or the decimal-string odometer contract.

Focused actual UI proof passed at17:16:33America/New_York (test17.9s, Java total29.167s):

- Fresh postgres:17 Testcontainers, explicit disposable JDBC credentials, no owner
  .env import; only this demo opts diagnostic/maintenance workers in. LocalFixtures
  registers venue-2021/sim-device-001 and an empty vehicle, not owner records.
- Browser posts three real snapshots based on diagnostic-api-examples.snapshot,
  boot40000000-0000-4000-8000-000000000001, sequence0..2, capture one second before
  submission. Baseline is complete empty; sequence1 reports confirmed P0117/P1999
  and MIL on; sequence2 is partial with no confirmed codes. Two PRESENT events
  remain; known definition and unknown fallback both show provenance/uncertainty.
- Browser starts profile applicability/schedule unset, explicitly selects U.S. G1.6,
  NORMAL, America/New_York and oil confirmation. A60-day-old10000MI oil service
  correctly yields UNKNOWN (stale current odometer), not NOT_DUE. A fresh18000MI
  manual odometer reaches the inclusive native-unit distance boundary/DUE.
- A real POST is forwarded to the backend, then only its acknowledgment is aborted.
  Reloaded UI restores a pending command; identical requestId/body is replayed.
  Record count stays2 before/after retry, not3. No synthetic success response.
- Correcting that odometer to18000.125MI appends revision2 with a reason and preserves
  both versions. A third actual matching service at18000.125MI makes the oil
  assessment NOT_DUE and the oil advisory COMPLETED; an acknowledged diagnostic
  advisory remains ACKNOWLEDGED. Final SQL asserts3 records,4 entry revisions,
  3 snapshots and2 PRESENT diagnostic events. Recommendations total3 before service
  (two diagnostic/one oil). No claims of ECU clearing or confirmed repair.
- Keyboard-focus/Enter navigation, labeled forms, visible UTC validation and axe0
  at all9 captures. Desktop1280x900 and narrow390x844 have no horizontal document
  overflow. Visually reviewed all9 full-page PNGs for clipping, wrapping, labels,
  readable hierarchy, correction evidence and validation placement. Evidence is
  structured text rather than a new chart requiring an alternative data table.

Ignored screenshots under web/playwright-report/care-artifacts/
care-real-diagnostic-captu-19469-entity-and-advisory-actions/:
diagnostics-desktop.png, diagnostics-narrow.png, diagnostic-detail-narrow.png,
correction-narrow.png, maintenance-validation-narrow.png, recommendations-narrow.png,
advisory-narrow.png, advisory-desktop.png, maintenance-desktop.png.
Keeping these separate from test-results prevents the other demos deleting them.
Actual browser log: server/target/care-browser.log. No screenshot/build product is
added to Git. Repeat with `server/mvnw test -Dtest=CareBrowserDemo` (Windows: mvnw.cmd),
or the existing scripts/verify.ps1 -Browser entry point, now including all4 demos.
Future CI source includes the new demo, but no hosted run or publication occurred.

Observed failures during development are not counted as passes: missing Node JSON
import attribute, exact-label/text locator failures, a wrong NOT_DUE expectation
for stale history, and filling the outgoing page's field before hash navigation
completed. Fixed the harness and waited for destination headings, not the oil rule
or correction semantics. Screenshot review found and fixed a real freshness bug:
a reused age timer compared a new capture to the previous tick. Capture-keyed age
state resamples immediately; a new component regression proves it. No timeout or
assertion was weakened to force green. Action wait is now bounded10s.

Frontend103 tests passed17:16:43, covering missing/loading/partial/unsupported/
failed/empty/stale/error states, MIL unknown/off, recurrence/unknown definitions,
bounded history, native int64 exact wire tokens, wrong-vehicle/late/cursor changes,
timeout rejection, stale/new-capture aging, manual unset inputs, corrections,
allowed/terminal workflow actions, conflict and unconfirmed-receipt recovery.
Python3.12.10 passed154 in6.74s during the combined workflow. Python3.14.3 passed154
in8.03s separately; first restricted-temp attempt had153 passes and1 setup error
creating pytest's numbered temp directory, then all154 passed with normal test
permissions. Node24.19.0/npm11.21.1, Temurin21.0.12.1 and Docker29.9.2 observed.
At this entry, full backend/existing browser regression verification is still
running; do not infer its pass from the focused proof. Final continuation below.

Scope limits: UI proof is not checkpoint5's new actual Python diagnostic sender /
JVM crash/replay proof. Tab session storage protects ambiguous retries only while
that identity survives; closing/clearing the tab loses it. It is not an edge outbox,
cross-tab recovery, authentication/deployment or hardware validation. Details are
bounded to newest100 revisions/transitions, with truncation notices; profile has
current revision editing but no profile-history browsing endpoint/view. Owner DB
never started/accessed/migrated/cleared, safe defaults preserved, no automatic
diagnostic/service deletion and no ECU commands.

### Checkpoint 4 final combined result —17:24America/New_York

The complete `scripts/verify.ps1 -Python .\device-agent\.venv312\Scripts\python.exe
-Browser` workflow exited0 against final runtime source. Process-local npm11
tooling and offline cached Maven dependencies were used; no global runtime change.
Fresh ordinary Surefire reports17:17:18–17:22:01 contain458 tests/33 suites,
0failures/errors/skips. Both actual diagnostic and maintenance HTTP/OpenAPI bridges
passed (the entry point then proceeded into browser verification). Excluded stale
historical probe/demo XML rather than summing the entire target directory.

All4 optional Java demos pass, completed17:24:01, with8 real browser scenarios:
CareBrowserDemo15.9s (all checkpoint4 acceptance above), DashboardBrowserDemo5 cases
in14.2s, NumericAnalyticsDemo1 in4.4s and TripMilestoneDemo1 in15.8s. Existing exact
positive/negative/cancellation/raw/1m/5m/owned/retained proof passes. Trip proof
retains actual557-reading child-JVM recovery/replay and241-row raw deletion with
10protected rows, unchanged aggregates and no resurrection. Final shutdown reports
`applicationClosed=true streamReservations=0`. Ordinary committed-response async
timeout warnings remain; there is no failed cleanup assertion or hidden deadlock.

Python3.12 passed154 in6.74s and3.14 separately154 in8.03s. Frontend typecheck/lint/
103 tests (17:24:05,1.77s)/build pass. Main JS101.91KB gzip (new views included),
chart chunk95.93KB gzip unchanged. Nine care screenshots remain in the separate
ignored artifacts directory after the other demos; same source as the visually
reviewed focused run. No runtime source changed during the final workflow.

Guard checks: no running containers after cleanup; all six saved switches retain
their safe values; private log/blueprint/.env and generated screenshots ignored,
no private tracked files and no staged files. Tracked whitespace and58 untracked
source files pass checks; only the existing informational OpenAPI line-ending
notice remains. No owner DB operation, commit/push/PR/merge/deployment, workflow
rerun or new hosted verification. Private learning log and Desktop plan updated.
Checkpoint4 is complete locally and paused for owner review. Checkpoint5 remains
unstarted; its full new Python diagnostic/maintenance and JVM proof is not inferred
from this UI exercise or the pre-existing trip demonstration.

## Diagnostics/maintenance checkpoint 5 — actual simulator/JVM/UI proof

Owner approved checkpoint4 and authorized checkpoint5 on October23,2026. New
CareMilestoneDemo is opt-in, like the existing real trip demo. It owns a fresh
postgres:17 Testcontainer, explicit disposable JDBC credentials, empty config
import and six explicit safe switches. It calls production Python source/models/
diagnostic HTTP sender and telemetry batch transport, then existing production
backend validators/stores/projectors and the actual React UI. There is no production
test-control HTTP endpoint or fake successful browser/API response. Bounded test-only
file coordination chooses the next real delivery/processing phase. No owner database
connection, migration, reset, retention or worker enablement.

### Repeatable entry points and exact fixture construction

From server/, run `mvnw.cmd test -Dtest=CareMilestoneDemo
-Dautopulse.python=C:/absolute/path/to/device-agent/.venv312/Scripts/python.exe` on
Windows, or `bash ./mvnw test -Dtest=CareMilestoneDemo -Dautopulse.python=python` on
Linux after installing the supported environment/browser prerequisites. To include
all existing regression/browser gates, run scripts/verify.ps1 with -Python and
-Browser as documented in README. That entry point/future CI source now include
5 demos: DashboardBrowserDemo,TripMilestoneDemo,NumericAnalyticsDemo,CareBrowserDemo,
CareMilestoneDemo. No new hosted run has occurred.

scripts/demo_care.py collects the actual source once into a JUnit-temporary capture:
12 lifecycle snapshots plus a new-boot restart snapshot, all13 identical to the
independent diagnostic-contract-fixtures.json after rebasing measurement time.
Original boot123e4567-e89b-42d3-a456-426614174000, restart223e4567-e89b-42d3-a456-426614174000.
Device sim-device-001 / venue-2021; diagnostic counters0..11 and restart0. It also
collects all36 synthetic-temperature-fixture readings, plus a separately labeled
stale/older low control copied from the typed baseline model with sequence1000 and
measurement base-minus600s. Base is an explicit Instant roughly100s before demo
construction, with native microseconds, not wall-clock sleeping through the story.
The helper requires explicit isolated loopback HTTP/no credentials/query/fragment,
refuses nonempty initial diagnostic/telemetry targets and exclusive-creates the
capture. Captures are demo artifacts, not a durable device outbox or general CLI.
The fixture EXPECTED ONLY labels remain original authorship history; this section
and executed tests, not those labels, report the implementation's actual status.

### Actual crash boundary and hand-calculated checks

1. Python sends baseline diagnostic0 and thermal0. Bounded processors drain; a real
   browser shows complete-empty scoped reads/MIL off/no events or advisories, oil
   UNKNOWN and applicability/schedule unset. This does not assert vehicle health.
2. Close that application context. Start a separate accepting backend JVM with all
   processing off. Python sends diagnostic1,2,3 and thermal1..16. First diagnostic
   POST is really accepted, its acknowledgment deliberately discarded, then the
   production sender retries byte-identical identity/content and receives DUPLICATE.
   SQL proves4 total committed snapshots and17 readings including the baseline,
   3 PENDING diagnostic items and16 PENDING maintenance items,0 events/advisories.
3. Forcibly terminate that exact child and wait for process exit (10s bound). A fresh
   backend context compares all original committed snapshot/raw rows and receipt
   timestamps. Prefix replay returns4 diagnostic/17 telemetry duplicates, then
   bounded production pages recover3 events and2 advisories (one confirmed-code,
   one thermal) solely from durable evidence. No accepting-JVM callback survives.
4. Deliver diagnostic4..7 plus late11 (capture3.000001s), thermal null34s and stale
   low. Unavailable/failed/timeout/partial reads and historical evidence leave the
   same event IDs/counts/state; null leaves thermal active with freshness UNKNOWN.
5. Deliver diagnostic8/9: qualifying absence at16s for pending/confirmed and18s for
   permanent. Then10 plus restart12: genuine confirmed-code recurrence and unknown
   P1999. SQL checks5 event cycles, exact first/last/absence instants, positive
   counts3/2/3/2/2,13 snapshots,39 category applications,3 historical-only parts and
   3 diagnostic advisory cycles (one expired/two continuing). No repair claim.
6. The first new low68s arrives after a34s gap from null34s; it cannot recover the
   active cycle. Intermediate36..66s samples arrive late and are historical-only.
   Low70s qualifies2s recovery. Exact frozen decision evidence proves OPEN32s,
   UNKNOWN34s,RECOVER70s; one condition/advisory cycle and37 accepted thermal inputs
   (36 original+1 control),17 historical-only decisions. Stale control is also older
   than the watermark; this demonstrates non-recovery but does not independently
   isolate staleness from ordering. Independent qualification/gap tests already
   exist in MaintenanceRuleTests. No DTC is inferred from temperature.
7. UI explicitly selects severe schedule for disposable data, records oil service
   40days ago at10000MI and fresh odometer15000MI, then drains frozen work to create
   one oil reminder. Matching service at15000MI really commits but its HTTP reply
   is discarded. Reload/identical manual retry keeps exactly3 records; processing
   completes only oil and leaves all diagnostic rows/unrelated advisory IDs/states
   unchanged. Profile/timezone/schedule are never installed on the owner's vehicle.
8. Empty-vehicle diagnostic/recommendation lists remain empty and cross-vehicle
   detail404. Final captured replay sends15 diagnostic deliveries (13 unique+2
   repeated) and37 telemetry inputs: every result is DUPLICATE. Stored snapshots,
   original receipts, events, applications, transitions, raw rows, recommendations,
   revision evidence and rule decisions compare exactly before/after, no semantic
   duplication. There is no retention/canonical evidence rewrite in this demo.

Focused tightened proof passed17:41:03America/New_York,31.378s Maven total,
24.59s JUnit case. An earlier focused full pass17:39:21 preceded the added exact
absence/thermal timestamp and17-historical-decision assertions. First integrated
attempt stopped on a duplicated110 browser locator (hidden revision history also
contained it); scoped to visible frozen evidence, not a disabled assertion. First
Python focus had17passes/5wrong-import failures; corrected the telemetry codec
import and all22 new delivery/fixture/isolation tests pass. No production backend
repair, migration, default change or assertion/timeout weakening.

### Browser evidence, small label correction and current verification

Baseline and lifecycle are two real browser invocations in the one Java demo.
All7 new captures passed axe0 and no horizontal document overflow at1280x900 or
390x844. All7 were visually reviewed for wrapping/clipping, explicit simulation,
UNKNOWN baseline, separate frozen/current evidence, recurrence/history, recovery
not repair, matching-only manual completion and keyboard-focus/Enter navigation.
Artifacts are ignored web/playwright-report/care-milestone/{baseline,lifecycle}/
care-milestone-actual-Pyth-83c20-committed-work-JVM-boundary/:
simulator-baseline-desktop.png,simulator-baseline-maintenance-narrow.png,
recovered-work-desktop.png,null-and-stale-control-narrow.png,
genuine-recurrence-narrow.png,qualified-recovery-desktop.png,
matching-only-service-narrow.png. Separate phase directories survive subsequent
browser invocations. Logs are server/target/care-milestone-{baseline,lifecycle}-
browser.log and care-seed-server.log; temporary captures live only in JUnit's folder.

Source review found checkpoint4's latest-capture label incorrectly called a
COMPLETE category empty read incomplete whenever a different read made the parent
ECU PARTIAL. Backend/contract absence rules are category-scoped. Corrected only
that UI label, kept overall uncertainty and added a component/mixed-outcome browser
regression; no backend eligibility rule changed. Visual inspection also prompted
honest intro wording: summaries in UTC, raw forensic evidence retains explicit
offsets. No timestamp/content conversion or precision loss to prettify evidence.

Frontend typecheck/lint/104 tests pass17:40:26 before the final static intro-copy
clarification. Python3.12.10 passes176 in7.64s in the full run;3.14.3 separately
passes176 in9.01s. Full backend/HTTP/existing browser/frontend regression workflow
is running at this entry. Do not treat the focused proof as its pass. Final combined
result follows below once actually observed. No staging/commit/push/PR/merge/deploy,
workflow rerun or new hosted result. Owner review/publication is still separate.

### Checkpoint 5 final combined result — October23,17:49America/New_York

The complete local verification entry point exited0. Ordinary Java/PostgreSQL
fresh reports17:41:58–17:46:42:458 tests/33 suites,0failures/errors/skips. Both
actual diagnostic/maintenance HTTP/OpenAPI schema gates passed. Python3.12.10
passes176 in7.64s and3.14.3 separately176 in9.01s (includes22 new delivery tests).
Frontend typecheck/lint/104tests/build pass, tests17:49:07 in1.82s. Main JS101.96KB
gzip/chart95.93KB gzip. RuntimeNode24.19.0/npm11.21.1, Temurin21.0.12.1 and Docker29.9.2
confirmed; process-local npm/offline Maven selection, no global runtime change.

All5 optional Java demos pass by17:49:03,10 real browser scenarios total:
CareBrowserDemo1 in15.9s, CareMilestoneDemo baseline1 in1.3s and lifecycle1 in6.4s,
DashboardBrowserDemo5 in15.0s total, NumericAnalyticsDemo1 in4.3s and TripMilestoneDemo1
in16.4s. New milestones' final native capture base (visible in saved baseline
snapshot screenshot) is2026-10-23T21:45:36.809814Z; original baseline receipt is
21:47:18.135450Z. All exact diagnostic/thermal boundary assertions are relative
to that native-microsecond base, not rounded browser display time. New demo
finished17:47:35. Its final SQL/replay markers report exactly the documented13
snapshots/39 parts/5event cycles/3diagnostic advisories/36+1thermal readings/
1thermal/1oil/3manual records and15/37duplicates, unchanged original receipts.

Existing signed/cancellation/large-total raw/1m/5m/owned/retained numeric browser
proof passes. Existing real-trip557-reading child-JVM/replay/retention proof still
passes, including241-row deletion/protected evidence/no resurrection, accessible
raw/retained aggregate charts and live reconnect. Final
`TRIP_CLEANUP_EVIDENCE applicationClosed=true streamReservations=0` remains green.
Ordinary connection-abort/committed-response timeout warnings occur during deliberate
disconnect/shutdown; no failed cleanup assertion or concealed deadlock.

Final care captures include the category-vs-overall completeness correction and
UTC-summary/raw-offset intro wording. Final desktop/narrow output was rechecked
after all7 focused milestone captures were visually reviewed; axes0 and overflow
checks pass in every screenshot. Existing9 care-interface captures remain in their
separate ignored directory and also pass in the full run. No screenshot/build
artifact is tracked. No production backend/migration/default changes in checkpoint5.

Final guards: no running containers, six saved switches retain safe values,
private log/blueprint/.env/screenshots ignored and untracked, indexempty, tracked
whitespace and63 untracked source files clean (only pre-existing informational
OpenAPI line-ending warning). Owner development database never started/accessed/
migrated/pruned/reset. Private learning log and Desktop plan updated. Checkpoint5's
local implementation/verification/documentation is complete; owner review and any
commit/push/new hosted CI remain pending separate authorization. No PR/merge/deploy
or workflow rerun implied. This milestone does not supply durable edge buffering,
read-only physical hardware validation, production authentication or deployment.

## 2026-10-24 - Diagnostic retry-ordering regression fix

The independent review reproduced accepted positive snapshots being overtaken
during retry backoff: the later complete absence advanced the watermark, and the
retry became HISTORICAL_ONLY, omitting three diagnostic event cycles. The fix
selects the earliest pending snapshot before checking eligibility, pauses a
vehicle with any REVIEW work, and excludes blocked vehicle heads from global
polling. Failure reporting now follows the same vehicle/work lock order as
processing. See ADR 0030. No schema/default/evidence changes or historical repair.

Five permanent `DiagnosticRecoveryTests` methods were run first on the original
code: five expected assertion failures, zero errors/skips. After the fix, all five
pass, including failures after each committed category boundary, restart/replay,
downstream advisory transitions, backoff/REVIEW isolation, native boot/sequence
ties and original-receipt eligibility. Fresh contexts isolate global poll tests.
The focused diagnostic recovery/API/startup and maintenance API run passed 37
tests, zero failures/errors/skips. Ignored red/green evidence logs are
`server/target/diagnostic-recovery-red.log` and `diagnostic-recovery-green.log`.

Full offline Java/PostgreSQL verification subsequently passed 463 tests across
34 suites, zero failures/errors/skips, finishing at 00:07:32 America/New_York.
Both actual diagnostic and maintenance HTTP/OpenAPI gates passed. Evidence:
`server/target/diagnostic-fix-regression.log` and fresh Surefire reports. Commands
from `server/` were `./mvnw.cmd --offline --batch-mode test`, followed by the
existing `verify_diagnostic_http.py` and `verify_maintenance_http.py` bridges using
the existing Python 3.12 environment.

Python 3.12.10 and 3.14.3 each passed all 176 tests again (10.40s and 11.14s).
Frontend typecheck, lint, 104 tests and production build passed using the existing
process-local npm 11 selection; no global tools or lockfiles changed. Verification
uses disposable infrastructure only. No owner database migration/processing,
commit, push, PR, merge, deployment or new hosted CI is implied by these results.

The explicit `CareBrowserDemo,CareMilestoneDemo` offline invocation also passed
both Java methods with zero failures/errors/skips at 00:08:23 America/New_York.
Its three real browser scenarios passed (care interface, actual Python baseline,
and actual Python lifecycle/JVM restart). Evidence remains in ignored
`server/target/diagnostic-fix-browser.log` and the three care browser logs. Markers
confirm 13 snapshots, 39 applications, five cycles with counts 3/2/3/2/2,
15 diagnostic and 37 telemetry replay duplicates, unchanged original receipts,
matching-only oil completion, vehicle isolation, simulation labels and keyboard/
desktop/narrow accessibility checks. These end-to-end fixtures complement the
new injected-failure tests; they do not replace them. All disposable containers
were cleaned up, saved switches remain unchanged and private files remain excluded.
