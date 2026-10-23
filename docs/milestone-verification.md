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
