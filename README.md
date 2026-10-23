# AutoPulse

AutoPulse is a read-only automotive telemetry project for a 2021 Hyundai Venue SE. A Raspberry Pi will collect supported OBD-II data through a Vgate vLinker FS USB adapter and send it to a web application for live monitoring, trip history, diagnostic information, and cautious maintenance recommendations.

The project is being developed simulator-first so the complete software pipeline can be built and tested before the vehicle hardware arrives.

## Planned architecture

```text
Python device agent and vehicle simulator
                |
                | HTTPS/JSON
                v
Spring Boot backend
                |
                v
PostgreSQL database
                |
                v
React web application
```

The Python agent will expose the same telemetry-source contract for both simulated data and the future physical OBD-II connection. This keeps the backend and frontend independent of the source selected at runtime.

## Simulator-first development

The initial telemetry source will model coherent vehicle behavior such as starting from cold, idling, accelerating, cruising, stopping, and ending a trip. Deterministic scenarios will make development and automated testing possible without connecting to a car.

Planned scenarios include:

- Normal commute
- Overheating warning
- Diagnostic trouble code with the malfunction indicator lamp on
- Intermittent network connectivity
- Unsupported OBD-II parameters
- Duplicate and out-of-order telemetry

## Safety boundary

AutoPulse is strictly read-only. The device agent will use an explicit allowlist for supported telemetry discovery, live-data queries, VIN retrieval when available, and read-only diagnostic queries.

It will not clear diagnostic codes, reset an ECU, perform coding or programming, run actuator tests, transmit arbitrary CAN messages, or issue commands with unknown effects. This boundary will be enforced in code and covered by automated tests.

AutoPulse is informational. Its readings may be incomplete or delayed, and its recommendations are not a substitute for the owner's manual, official recall verification, or professional inspection. The dashboard is not intended for interaction while driving.

## Repository layout

```text
device-agent/  Python agent, simulator, transport, and local buffering
server/        Java and Spring Boot backend
web/           React and TypeScript application
contracts/     API schemas and shared payload examples
infra/         Local infrastructure and deployment definitions
docs/          Architecture decisions and technical documentation
scripts/       Repeatable development workflows
```

## Current status

The trips/history milestone is complete locally, including a real simulator-to-browser trip, crash recovery and retention demonstration. Python offers snapshots and a complete synthetic commute; Spring Boot validates and persists bounded batches in PostgreSQL; React receives committed latest state over SSE without refresh. Multi-batch capture/replay, bounded stream resources, freshness states, persisted trips, historical charts and tested opt-in retention are included. CI gates are updated, but a new hosted run has not been observed. The broader MVP remains future work.

No vehicle hardware is required during the simulator-based development phases.

## Current retention behavior (checkpoint 5)

Checkpoint 5 is complete: opt-in retention includes bounded archive preparation,
independent aggregate verification, guarded final-only raw pruning and atomic
expiry/generation publication, verified only in disposable databases.
Earlier checkpoint-5 notes below are implementation history; the current behavior
is specified in [ADR 0027](docs/adr/0027-guarded-final-retention-and-availability.md).

Saved defaults remain `autopulse.retention.enabled=false` and
`autopulse.retention.dry-run=true`. There is no automatic scheduler, startup scan
or public cleanup route. The internal `RetentionMaintenance.runPage(vehicleUuid,
limit, cursor)` entry point performs one bounded stage per explicit call (limit
1..100). A dry run writes nothing, including archive progress. Actual deletion
requires both enabled=true and dry-run=false, freshly synchronized checks and a
verified archive; it cannot use an old preview as permission.

Maintenance prepares retained evidence, builds UTC 1m/5m exact contributions,
verifies them independently, then prunes eligible FINAL readings. Pending/review,
unverified/provisional, latest, secondary-device and correction-referenced data
remain protected. Measurement cutoff and original-receipt quarantine are strict,
independent checks. Summaries, links, canonical identities and replay semantics
survive pruning. Any failure rolls back raw removal, expiry proof and generation.

History reports actual range/trip-specific AVAILABLE, PARTIALLY_EXPIRED or EXPIRED
raw availability, and stale cursors restart with PAGE_CHANGED. Aggregate values
remain available without double counting. `rawUnavailableBefore` is an exclusive
upper bound on expired observations in the requested window, not a contiguous
vehicle-wide cutoff; protected earlier readings can remain. Full canonical
payloads are still retained, so compact storage savings are not claimed.

For developers, repeat explicit bounded calls with the returned cursor. Stop on
BUSY/FAILED and retry or investigate. `complete=false` with a null cursor means
restart from the new first raw row; an expired/changed cursor must also restart.
Archive progress is durable, and restarting after any committed page is safe.
`complete=true` means the traversal ended, not that all protected old data expired.
See the operations notes and ADR for limits; never run deletion against owner data
without separate explicit approval of named targets and safeguards.

Disposable verification commands:

```powershell
cd server
.\mvnw.cmd -o test
.\mvnw.cmd -o "-Dtest=RetentionPruningTests" test
```

These commands use disposable Testcontainers databases; they do not start or
migrate the development database. Checkpoint 6's completed real simulator-to-browser
milestone demonstration is documented below.

Final verification: **408 backend tests** across 24 suites (zero failures/errors/
skips), including 169 migration, 40 retention-preview and 15 actual-pruning cases;
**59 frontend tests**, typecheck, lint and production build; **94 Python tests
each on 3.12 and 3.14**. The original indexed raw-page bound remains enforced.
No owner-data deletion, commit/push, new browser demo or hosted-CI result is claimed.

## Checkpoint 6: real trip/restart/retention demonstration

The new `TripMilestoneDemo` runs the real Python simulator/HTTP sender, a separate
backend JVM, disposable PostgreSQL, and unmocked React/Chromium browsing. It kills
the seed backend after telemetry commits but before projection, then verifies a
fresh backend recovers the expected normal/short/tiny/null/gap/restart/tie cases.
Replay and different batch sizes preserve boundaries, summaries and receipts.

The browser checks desktop and narrow list/detail/charts, accessible tables,
keyboard behavior, real live updates/reconnect during processing/history reads,
isolation and actual retention. The disposable normal trip loses 241 eligible raw
rows while keeping ten protected raw readings, its 6.5 km estimate and identical
aggregate statistics. Raw expiry is explicit, and replay does not resurrect data.
Saved processing/retention defaults remain unchanged.

Run the updated full local entry point with npm 11 selected:

```powershell
.\scripts\verify.ps1 -Python .\device-agent\.venv312\Scripts\python.exe -Browser
```

For a normal checkout, select your installed Python agent/dev environment rather
than assuming the ignored local .venv312 directory exists. See
[milestone verification](docs/milestone-verification.md) for exact isolated-demo
commands, evidence locations, coverage and limitations. The CI workflow now runs
both real browser demos; no new hosted result can be claimed before an authorized
commit/push and observed run. The combined local verification passed on October
22, 2026: 408 backend regression tests; two opt-in Java demos running six browser
scenarios; 59 frontend tests plus typecheck/lint/build; and 96 Python tests on
3.12. A separate Python 3.14 run also passed all 96 tests. Browser accessibility
checks and visual review cover desktop and narrow layouts. The owner development
database was not accessed, and nothing was committed or pushed.

## Technology direction

- Python 3.12+ for the device agent and simulator
- Java 21 and Spring Boot for the backend
- PostgreSQL with Flyway migrations for persistence
- React and TypeScript for the web application
- OpenAPI for HTTP contracts
- Server-Sent Events for initial live browser updates
- Docker Compose for local infrastructure

These choices represent the initial direction and may be refined through documented architecture decision records as the project develops.

## Development principles

- Discover supported vehicle parameters instead of assuming availability.
- Preserve missing telemetry as unavailable rather than displaying it as zero.
- Use UTC internally and metric units for storage.
- Make ingestion idempotent and tolerant of temporary network loss.
- Keep transport models separate from persistence and domain logic.
- Prefer explainable rules and evidence over unsupported diagnostic claims.
- Add complexity only when a demonstrated requirement justifies it.

## Getting started

The local PostgreSQL database runs through Docker Compose. Install and start Docker Desktop, then run the following commands from the project root in PowerShell.

1. If you do not already have a `.env` file, copy the example configuration. Replace its placeholder password with a password for your local database. Keep `.env` private; it is ignored by Git.

   ```powershell
   if (-not (Test-Path .env)) { Copy-Item .env.example .env }
   ```

2. Check the Compose configuration, then start PostgreSQL.

   ```powershell
   docker compose config -q
   docker compose up -d
   ```

3. Check that the `db` service is running and accepts a query.

   ```powershell
   docker compose ps
   docker compose exec db psql -U iamnetgo -d autopulse -c "SELECT 1;"
   ```

   The query should return one row containing `1`. Applications running on your computer can connect to the database at `localhost:5433` using the database name, user, and password from the Compose configuration.

4. Stop the local database when you are finished.

   ```powershell
   docker compose down
   ```

The named Docker volume preserves database data between normal stops and starts. `docker compose down -v` also removes that volume and deletes the local database data, so use it only when you intend to start with an empty database.

Backend and live-dashboard instructions follow below.

## Preview the Python simulator

From the project root in PowerShell, create an isolated Python environment and install the device agent's development dependencies:

```powershell
Set-Location device-agent
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"
```

Run the local preview and tests:

```powershell
.\.venv\Scripts\python.exe -m autopulse_agent.simulate
.\.venv\Scripts\python.exe -m pytest tests
```

The declared development dependencies include the contract validators. To run only the OpenAPI document, shared-example, and simulator-output checks (including UUID and timestamp formats), use:

```powershell
.\.venv\Scripts\python.exe -m pytest tests/test_contract.py
```

The preview prints three JSON lines through the same serializer intended for future transport: engine off, cold idle, then acceleration. It needs no vehicle, network, or database. Each normal run gets a fresh boot ID and start time; tests supply fixed values to reproduce exact readings. Unsupported or unavailable sensors appear as JSON `null`, distinct from real zero and false readings.

Select the complete deterministic commute with `--scenario normal-commute`. The six phases are engine off, cold idle, city acceleration/deceleration, highway cruise/deceleration, hot idle, and engine off/completion. Default phase durations are 10, 30, 120, 300, 30 and 10 simulated seconds. At the default two-second sample interval this produces 251 readings, including the completion endpoint. Every read advances an index-driven simulation clock; the offline preview never sleeps.

Use `--sample-interval` to change measurement spacing and `--phase-seconds OFF COLD CITY HIGHWAY HOT FINAL` to configure positive phase durations. The sample interval cannot exceed the shortest phase. `--start-at` and `--boot-id` let tests reproduce exact events; use a new boot ID for a genuinely new run. These same options are available when sending. Values are synthetic, not verified Hyundai Venue characteristics, thresholds or diagnoses. MAF and fuel level remain unavailable. Other named scenarios, diagnostic snapshots and the full `TelemetrySource` interface remain open.

## Run the local backend

The service uses Java 21 and Spring Boot 4.1.1, which supports Java 21 according to the [official system requirements](https://docs.spring.io/spring-boot/system-requirements.html). The checked-in Maven wrapper downloads Maven; no global Maven installation is needed. Docker Desktop must be running for PostgreSQL and the Testcontainers integration tests.

From the repository root:

```powershell
docker compose up -d
Set-Location server
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

The server reads the ignored root `.env` for `POSTGRES_PASSWORD`. Optional `DATABASE_URL`, `DATABASE_USER`, and `SERVER_PORT` overrides are supported. It binds to `127.0.0.1:8080` by default. The `local` profile registers `venue-2021`, `empty-vehicle`, and `sim-device-001` through an explicit idempotent startup seed after migrations; it also configures the simulator as the primary trip device. Seeding refuses conflicting existing identities/policies rather than overwriting them. Default startup does not register fixture identities. Tests use disposable PostgreSQL 17 Testcontainers, not your local database.

Startup now applies V901 (durable trip-work foundation), V902 (trip records
and calculation checkpoints), V903 (finalization safeguards) and V904 (durable
correction plans), V905 (isolated correction replay and input-change fencing),
V906 (paged correction publication and stable identity/lineage), and V907
(durable processing status, retry timing and explicit pause/resume), V908
(durable round-robin processing scan), V909 (indexed timer-continuation recovery),
V910 (canonical ordered work queue with bounded legacy preparation), V911
(transactional read generations), V912 (public trip-query indexes), and V913
(primary-device history ordering).
Already-V900 databases
advance without changing V1/V900 checksums. The application resolves the unchanged
legacy local migration only when its history says V900 was applied. A new default
database can therefore reach V901 and later enable `local` without trying to run
an older V900. No out-of-order migration or volume reset is needed. Use the normal
application startup path, not an independently configured Flyway command that
omits this compatibility handling. Never edit an applied migration.

Process health is `/actuator/health/liveness`; database readiness is `/actuator/health/readiness`. Authentication and user authorization are deferred, so this service is currently for private local development only.

## Send and replay the simulator

Leave the backend running in one terminal. In another terminal at the repository root:

```powershell
.\device-agent\.venv\Scripts\python.exe -m autopulse_agent.send --capture device-agent/.venv/demo-readings.json
Invoke-RestMethod http://127.0.0.1:8080/api/v1/vehicles/venue-2021/telemetry/latest
docker compose exec -T db psql -U iamnetgo -d autopulse -c "SELECT count(*) FROM telemetry_reading;"
.\device-agent\.venv\Scripts\python.exe -m autopulse_agent.send --replay device-agent/.venv/demo-readings.json
docker compose exec -T db psql -U iamnetgo -d autopulse -c "SELECT count(*) FROM telemetry_reading;"
```

A default snapshot run reports three accepted readings; replay reports three duplicates and leaves the row count unchanged. The optional capture file is under the ignored virtual environment and must not already exist. Each event retains its original boot ID, sequence number, timestamp, and readings on retry/replay. The offline `simulate` command still only prints JSON.

Sending options include `--server-url`, `--device-id`, `--vehicle-id`, `--interval` (wall-clock pacing, default two seconds), `--batch-size` (1-100), and `--max-attempts` (default three). `AUTOPULSE_SERVER_URL` can set the default URL. Wall-clock `--interval` is independent of simulated `--sample-interval`: accelerate delivery without altering measurement timestamps. Temporary network/5xx/protocol failures use bounded exponential backoff; permanent item rejections are acknowledged and are not resent indefinitely. Request and response bodies are bounded at 262144 bytes.

For the full 500-second synthetic commute, sent in roughly a few wall-clock seconds:

```powershell
.\device-agent\.venv\Scripts\python.exe -m autopulse_agent.simulate --scenario normal-commute
.\device-agent\.venv\Scripts\python.exe -m autopulse_agent.send --scenario normal-commute --interval 0.01 --batch-size 100 --capture device-agent/.venv/commute-v1.jsonl
.\device-agent\.venv\Scripts\python.exe -m autopulse_agent.send --replay device-agent/.venv/commute-v1.jsonl
```

New captures use UTF-8 JSON Lines: first `{"format":"autopulse-batches-v1"}`, then one bounded `{"readings":[...]}` envelope per line. Default commute batches contain 100, 100 and 51 readings. Byte limits can split batches earlier. Replay streams one envelope at a time; legacy single-envelope JSON captures still work within the original limits. Sending retains at most a batch and the next reading, not the entire history. A capture is flushed before delivery and may be a partial prefix after failure; already-delivered batches are safe to replay, but captures are not an atomic outbox, resume protocol or restart-safe delivery guarantee. Replay may deliver an earlier prefix before discovering a malformed later line.

To demonstrate partial results with the shared example:

```powershell
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/v1/device/telemetry/batches -ContentType application/json -InFile contracts/examples/batch-request.json
```

The example contains a valid event twice and an invalid negative-RPM event. On a fresh database it reports one accepted, one duplicate, and one rejected item; replay reports two duplicates and one rejection. Results retain input indexes. Unknown devices/vehicles, association mismatches, and changed content under an existing identity are item rejections. Malformed envelopes and systemic database failures use request-level errors with a correlation ID.

The event identity is `(deviceId, bootId, sequenceNumber)`. `recordedAt` is the source measurement time; `receivedAt` is the original server receipt time. Latest follows measurement time and deterministic identity tie-breakers, so delayed older readings do not replace newer measurements.

Wire timestamps allow at most six fractional second digits and valid RFC offsets through 23:59. Finer precision and UTC overflow are rejected, not silently rounded. Sensor bounds use exact submitted decimals before approximate database-column conversion. The Python wire codec preserves Decimal values; use it rather than ordinary float parsing when reading captured JSON. Storage overflow/underflow are item errors. See `docs/adr/0003-timestamp-and-numeric-precision.md`. Shared valid/invalid fixtures run in Python, Java and format-aware contract checks; explicitly marked semantic-only cases cover constraints outside portable OpenAPI primitives.

There is no durable outbox or restart-safe delivery guarantee yet. Fault scenarios, diagnostics, trip processing, authentication and remaining foundation tasks are still open. This demonstration alone does not complete Sprint 0, Sprint 1, or the MVP.

## Run the live dashboard

Install Node 24.15+ (24.x) and npm 11; `web/.nvmrc` records the locally verified
Node version. With the local backend running as above, use another terminal:

```powershell
Set-Location web
npm ci
npm run dev
```

Open `http://127.0.0.1:5173`. Default vehicle: `venue-2021`; use
`?vehicle=empty-vehicle` to view another registered public vehicle slug.
`web/.env.example` documents public settings (`VITE_VEHICLE_ID`,
`VITE_STALE_SECONDS`, default 6). Never copy root database credentials into web
environment variables. The Vite-only `AUTOPULSE_BACKEND_URL` proxy defaults to
`http://127.0.0.1:8080` and permits only loopback HTTP. `/api` stays same-origin;
no broad CORS or public deployment is configured. Stop Vite/backend with Ctrl+C.

`GET /api/v1/vehicles/{vehicleId}/live` sends named `empty` or `telemetry` events;
telemetry data matches the existing latest envelope. Heartbeat comments every
15 seconds keep transport active but never refresh measurements. Reconnect
resnapshots; there are no event IDs, durable replay or distributed notifications.
Accepted older readings, duplicate retries and rollbacks cannot refresh latest.
Streams are bounded to 64 globally/8 per vehicle, with four writer threads,
single-slot coalescing, slow-write retirement and a two-minute logical lease.
Transport completion runs on two separate cleanup workers with a bounded queue,
never on the shared coordinator. Active plus retiring clients retain the 64/8
capacity reservations until their writer and cleanup finish, preventing unbounded
resource growth under reconnect churn.
See ADR 0004 for synchronization, socket/container limitations and UI semantics.
Closed sockets can take later writes to detect; the two-minute lease retires
them but is not a guaranteed socket-close deadline. A blocked write/completion
can retain its capacity slot beyond that lease without blocking the coordinator.
Rapid tab/reconnect churn may temporarily reach capacity.
The local proxy explicitly propagates upstream termination and downstream closure.

The page labels the **server connection**, not vehicle connectivity. Measurement
age advances without events; original receipt age is separate. Data is stale at
six seconds by default. Measurements over two seconds ahead of the browser show
a clock/simulation warning, not an indefinitely fresh gauge. Zero RPM is a
last-known engine-off reading that can also be stale; null is unavailable and
false MIL is reported off, not unknown. No heartbeat/outbox/hardware status or
diagnostic interpretation is invented. SIMULATED remains visible.

For a human live demo, align sample spacing and send pacing. From the root:

```powershell
.\device-agent\.venv\Scripts\python.exe -m autopulse_agent.send --scenario normal-commute --phase-seconds 4 8 16 20 8 4 --sample-interval 2 --interval 2 --batch-size 1 --capture device-agent/.venv/live-commute.jsonl
```

This is 60 simulated seconds/31 readings, including engine-off completion.
Use a new capture path if it already exists. Stop readings while keeping the
backend running to see stale data with a healthy server connection. The earlier
accelerated historical examples are for replay/testing: a current start plus
accelerated delivery otherwise creates future-dated samples and a clock warning.

## Repeatable verification

Prerequisites: Python 3.12+ with `device-agent[dev]` installed as above, Java 21,
Docker Desktop/Engine running, Node 24.15+ (24.x), npm 11 with `npm ci` completed
in `web/`, and PowerShell (PowerShell 7 on non-Windows).
Maven is downloaded by the checked-in wrapper. From the repository root:

```powershell
.\scripts\verify.ps1
# Or select another already-installed Python environment:
.\scripts\verify.ps1 -Python C:/path/to/venv/Scripts/python.exe
# Include the isolated integrated browser check (install its browser first):
Push-Location web
npm exec -- playwright install chromium
Pop-Location
.\scripts\verify.ps1 -Browser
```

The entry point checks required tools/dependencies, runs all Python/format-aware
contract tests, Java tests using real PostgreSQL Testcontainers, frontend type
checking/lint/unit tests and production build. `-Browser` adds the explicit
integrated Chromium check. It exits
unsuccessfully on missing tools or a failed test; it never silently substitutes
another database. On Linux, create `device-agent/.venv` with `python3 -m venv`
and install `-e './device-agent[dev]'`, then run `pwsh -File scripts/verify.ps1`.
The separate Python and Maven commands above remain available. Frontend checks
are `npm run typecheck`, `npm run lint`, `npm test`, and `npm run build` in `web/`.

`.github/workflows/verify.yml` prepares the same checks on Docker-capable Ubuntu
runners with declared dependencies and no developer `.env` or database. Its
Python matrix includes the minimum 3.12 and 3.14 because clock parsing differed
in the latter during review. Java uses 21 and the Maven wrapper. The workflow
YAML/structure and PowerShell syntax are checked locally.
On 2026-10-21, the [first hosted verification run](https://github.com/afa000/AutoPulse/actions/runs/39549809554)
passed all four jobs: Python 3.12 and 3.14 (89 tests each), Java/PostgreSQL
(26 tests plus the replay demo), and frontend type/lint/unit/build checks plus
the integrated real-browser check. Hosted Python 3.12 used CPython 3.12.15 on Ubuntu.
Local Windows checks also passed all 89 tests on Python 3.12.10 and 3.14.3;
the separate compatibility environment `device-agent/.venv312` remains ignored.
The web CI job uses the Node version file, lockfile-based `npm ci`, explicit
Chromium/system dependency installation and a disposable Java/PostgreSQL browser
test. Action setup follows the
[official Python](https://github.com/actions/setup-python),
[Java](https://github.com/actions/setup-java) and
[checkout](https://github.com/actions/checkout) documentation.

## Isolated end-to-end demonstration

This opt-in check launches a random-port Java server and a disposable PostgreSQL
Testcontainer, then exercises the real Python HTTP sender through a temporary
loopback proxy that cuts off one acknowledgment after the backend commits. It
does not connect to or clear the normal development database. From the root:

```powershell
Push-Location server
.\mvnw.cmd test '-Dtest=CommuteDemo' '-Dautopulse.python=../device-agent/.venv/Scripts/python.exe'
Pop-Location
```

The demo selects `normal-commute`, fixes source time at `2026-10-20T12:00:00Z`,
uses 100-item batches and advances the simulated clock without real sleeps. It
creates a temporary v1 capture and prints `COMMUTE_EVIDENCE` on success. Java
assertions independently inspect the disposable database. Expected evidence:

- 251 unique commute readings in batches 100/100/51. Because the first committed
  acknowledgment is lost, delivery reports 151 accepted and 100 duplicates.
- The retried HTTP bodies are byte-identical. Full replay reports 251 duplicates
  and creates no rows; latest content and original receipt time remain unchanged.
- One delayed older event and two valid neighbors around an invalid exact-decimal
  item bring the total to 254 unique rows, not 255. Latest stays at sequence 250,
  measurement time `2026-10-20T12:08:20Z`. Stored null, zero and false are checked.

This demonstration passed locally on 2026-10-20 using Python 3.14 and Java 21.
Testcontainers, the Java server, the Python child and the fault proxy are cleaned
up; temporary captures are discarded. The normal development database retained
its four existing rows. The demonstration was rerun successfully with local
Python 3.12.10 on 2026-10-21 and with hosted Python 3.12 in the linked CI run.

## Isolated browser and live-commute demonstration

After the frontend install and Chromium setup, run from the repository root:

```powershell
Push-Location server
.\mvnw.cmd test '-Dtest=DashboardBrowserDemo' '-Dautopulse.python=../device-agent/.venv/Scripts/python.exe'
# Also observe the short six-phase commute at real two-second pacing:
.\mvnw.cmd test '-Dtest=CommuteDemo,DashboardBrowserDemo' '-Dautopulse.python=../device-agent/.venv/Scripts/python.exe' '-Dautopulse.liveDemo=true'
Pop-Location
```

JUnit creates fresh PostgreSQL containers and random-port Java servers. Playwright
starts its own loopback Vite server on port 5174 (it refuses to reuse another
server), launches Chromium, and sends persisted events using the real Python
transport. The test-only TCP proxy cuts/restores backend connections; it is not
an application endpoint. The regular integrated browser check covers empty data,
two tabs, vehicle isolation, replay, delayed input, stale-with-healthy-stream,
disconnect/reconnect, future-clock/unknown sensor states and keyboard/axe checks.
Five fixture readings persist; exact replay adds none. Tests wait for zero server
subscriptions within the ordinary-disconnect test budget, not an assumed instant
TCP-disconnect event or a universal socket-close guarantee. A separate regression
holds Spring's actual emitter write lock and verifies other vehicles still update
and retire while completion waits; resource slots remain reserved until release.

The optional `autopulse.liveDemo=true` check adds 31 new commute readings at two
seconds for both source and delivery clocks. It observes cold idle, city driving,
85 km/h synthetic highway cruise, hot idle and final engine off, then retains
that engine-off reading as stale while the server connection remains live. This
explicit human-paced smoke check takes roughly a minute plus setup/cleanup;
precise freshness regressions use controlled clocks and do not require that wait.
The isolated database has 36 rows across both browser scenarios, not duplicates
from replay. This is synthetic telemetry, not a verified vehicle operating test.

Evidence is printed as `DASHBOARD_BROWSER_EVIDENCE`, `LIVE_COMMUTE_EVIDENCE` and
`STREAM_CLEANUP`; detailed logs are in `server/target/dashboard-browser.log`.
Screenshots/traces/captures are under ignored `web/test-results/`. The smoke test
stops its own Vite/browser/Python/proxy processes and disposes only test databases;
the normal Compose database is not cleared or used. No deployment/authentication
or durable event replay is provided. The linked hosted CI run passed the quick
browser scenario. Both browser flows, including the optional human-paced commute,
also passed locally with Python 3.12.10; the human-paced flow is not a default CI gate.

## Trips and history rules / processing foundation (in progress)

Checkpoint 1 of the trips/history milestone was approved by the owner in
[ADR 0005](docs/adr/0005-trip-rules-and-history-contract.md). It defines start/off
confirmation, unknown readings, timeouts, restarts, late data and distance coverage.
The pure Java reference rules are **not connected to ingestion**. Trip list/detail
and raw/1-minute/5-minute telemetry history are implemented. Checkpoint 3's API
implementation is verified; checkpoint 4 now includes trip browsing, summaries and
bounded measurement charts. Retention remains unfinished.
V901 installs pending-work/backfill tables. V902 adds an internal forward trip
projection and evidence/checkpoints, tested in isolation but not automatically
scheduled by default. V903 adds guarded internal finalization. Trip browsing is
available as described below, including charts. The retention job is not yet implemented.

Shared expectations are in `contracts/examples/trip-rule-fixtures.json`, with
full and short simulator inputs and `trip-api-examples.json`. Hand-calculated
distance checks are 0.04 km (tiny), 6.5 km (full commute) and 22/45 km (short).
Distance is an estimate over covered intervals, never an odometer reading.

Run the focused checks from the repository root using the existing dev environment:

```powershell
.\device-agent\.venv\Scripts\python.exe -m pytest device-agent/tests/test_trip_contract.py -q
Push-Location server
.\mvnw.cmd test '-Dtest=TripRulesTests'
Pop-Location
```

The Java rule checks require no database. Python checks validate the draft OpenAPI,
examples and existing simulator against independent totals.

Checkpoint 2's processing implementation is complete and verified in disposable
databases; owner-data processing remains disabled by default. A database trigger writes one `trip_work_item` in the
same transaction as each newly accepted reading. Duplicates create no work, and
rollback removes both input and work. Future input's next-attempt time is deferred
until measurement time minus the draft two-second tolerance. These records are
not the deferred device-side durable outbox, and are not fed by SSE.

Pre-V901 readings have per-vehicle backfill progress. `TripBackfill.enqueuePage`
queues at most 500 rows per transaction, preserving original receipt times and
resuming after restart. An index supports its vehicle/UUID cursor. Inserts behind
that cursor cannot be lost because the trigger independently queues new input.
This is an internal service with no public route; the opt-in coordinator invokes
it through the projector, with automatic processing disabled by default. No
operator SQL or destructive database reset is required to test it.

`TripProjector.processPage(vehicle,asOf,limit)` is an internal service, not a public
endpoint or scheduler. It first finishes bounded legacy queuing, then handles at
most 100 selected work items per transaction with a ten-second timeout. PostgreSQL
transaction locks serialize each vehicle (including the first checkpoint); a
unique index also prevents two open candidate/active trips. State, exact decimal
totals, evidence links and work acknowledgments commit or roll back together.
The calculation keeps fixed-size snapshots rather than an entire drive in memory.
Waiting samples after the chosen end are excluded from the `trip_evidence` view.

Timers run only when eligible backlog is drained, so a historical drive is not
timed out between pages. Deferred future readings do not postpone a timeout.
Candidate trips remain internal and can be discarded; only active/completed
records are trip evidence. Exact replay does not change IDs, totals or revisions.

The forward path alone does not apply late corrections: it retains raw input
and marks `REVIEW` / `LATE_DATA_UNAPPLIED`, with vehicle `needs_review=true`.
The correction pipeline then applies eligible split/merge/supersession publication,
with internal retry/failure controls and opt-in startup/timer orchestration. A
primary-policy revision change also stops processing for review. Trips stay
provisional until the internal finalizer's checks pass. No automatic runner is
enabled; normal app traffic therefore
still accumulates pending work unless this service is explicitly invoked. No
operator SQL/reset is needed to run the isolated tests. Representative forward-page
query plans are now checked, but page limits/timeouts are not a claim that every
query or data distribution touches only the returned rows.

`TripFinalizer.finalizePage(vehicle,asOf,limit)` seals at most 100 completed trips
per transaction, after five minutes since the last eligible primary-device receipt.
It requires completed backfill/projection work, no eligible pending work (including
deferred retries), no unresolved review and the same primary-policy revision.
Duplicates, secondary data and quarantined future input do not restart that quiet
period. Activity history from a V902 database is recovered in resumable pages.

Finalization and insertion of eligible primary data share a locked activity row;
processing/finalization also share the per-vehicle transaction lock. PostgreSQL
guards finalized trip records and evidence links against edits/deletion. Finalizing
increments the revision once; repeating the call does not rewrite a final trip.
This internal service is tested and selected by the opt-in coordinator. It is
not itself the correction/supersession path, and unresolved late data
blocks sealing rather than being silently ignored.

`TripCorrectionPlanner.planPage(vehicle,asOf,limit)` discovers a connected affected
measurement window in resumable pages of at most 100 rows. It expands through
neighboring trip evidence and eligible late/pending primary input within a
30-second halo, with a maximum 48-hour core measurement span. Confirmation waits
remain context, not extra driving duration; a timeout's processing-clock timestamp
cannot make a short drive look like a multi-day measurement window. Finalized
overlap, an oversized window or policy changes stop planning for explicit review.

While a plan is active, the internal projector/finalizer return
`CORRECTION_PENDING` for that vehicle. `READY` means only that the interval was
planned: no old trip/evidence has been rewritten, no late work acknowledged and
no correction published. V905 stages replay with input-change checks, and V906
provides a separate internal publication step. Until that step finishes, the plan
remains pending. This is not a runnable owner-database correction tool;
exercise it through the isolated tests, not a manual production/demo invocation.

`TripCorrectionReplayer.replayPage(vehicle,limit)` rebuilds canonical primary
readings into separate staging tables, with at most 100 distinct instants per
transaction and a ten-second timeout. It reuses the exact-decimal, constant-space
fold and saves its checkpoint, staged segments and evidence together. The timer
runs only after the frozen replay range is drained. Old trip IDs, summaries,
evidence and late-work acknowledgments remain unchanged.

Relevant arrivals inside an active plan's context increment a generation under
the shared receipt barrier. A changed generation invalidates the staging attempt
and restarts window planning; policy changes or finalized overlap stop for review.
Future input beyond the frozen horizon, secondary devices and disconnected input
do not invalidate that replay. V904 plans created before generation tracking are
forced through discovery again on upgrade. Old invalidated attempts are retained
for inspection; bounded cleanup is deferred, not performed on owner data.

`STAGED` is not publication or a permanent validity guarantee. Even a completed
stage is revalidated on the next call. The internal
`TripCorrectionPublisher.publishPage(vehicle,limit)` checks policy, generation,
projection progress and finalized overlap again before beginning publication.
It then captures original snapshots, maps identities, replaces provisional trip
content/evidence, records lineage, acknowledges included work and advances progress
in separately committed pages of at most 100 rows. Each page resumes after restart;
it is not one long transaction. No raw measurement or original receipt is deleted
or rewritten.

The segment containing an old anchor keeps its trip ID; a merge retains the oldest
persisted anchor (creation time, then UUID). Other segments receive new IDs.
Retained revisions change only for semantic changes; retired IDs remain as
`SUPERSEDED` with replacement links. Separate `SPLIT` lineage records additional
children without retiring their still-valid parent.

Publication captures a per-vehicle acceptance counter under the ingestion barrier.
Arrivals committed after that fence stay queued for follow-up correction rather
than being acknowledged by this rebuild. Planner/replayer calls pause during
publication; the forward projector/finalizer remain paused by the READY plan.
A policy change during publication pauses it, retaining the visibility gate for
explicit recovery; it must not expose an unfinished rebuild.

**Future trip APIs must check for active publication and report temporary
unavailability (`PUBLICATION_PENDING`), then use `trip_visible_record` and
`trip_evidence`.** These views hide the vehicle during publication. Empty gated
results are not evidence that the vehicle has no trips. Raw trip tables contain
internal intermediate maintenance state and must not be served directly. The
completion transaction publishes progress, the completed plan and the visibility
gate together. The opt-in runner is disabled by default; no trip API is served. This is
not an owner-database demo instruction.

### Processing failures and recovery (internal, not scheduled)

`TripProcessingWorker.runPage(vehicle,stage,asOf,limit)` invokes exactly one bounded
page of FORWARD, PLAN, REPLAY, PUBLISH or FINALIZE. Its durable status is separate
from the processing transaction, so page rollback does not erase its failure
report. `status(vehicle)` returns stage/state, operational attempt/finish/failure
times, retry/lease deadline, failure counts, safe error code, SQLSTATE and last
stage result. This is an internal read helper, not a public status endpoint or an
unlimited error history. Exception text, SQL, payloads and credentials are not
stored in this table.

Transient database failures wait 1, 2, 4 and 8 seconds between attempts; the fifth
consecutive failure pauses that vehicle. There are no sleeps/retry loops inside
one call. Lock contention defers for one second without consuming the failure
budget. Integrity/programming failures and explicit policy/review results pause
immediately. A pending correction/publication is a normal stage handoff rather
than a reason to retry the wrong stage forever. Retry/pause blocks other stages
from bypassing the failed one. A successful page returns control to IDLE; this
means the page finished, **not** that a multi-page replay/publication is complete.

A two-minute claim lease permits recovery after process loss; a fresh worker must
resume the same stage. Expired claims consume the failure budget. Token-checked
status updates stop an old worker overwriting a newer worker's report. Leases do
not cancel a running transaction or guarantee exactly-once execution: existing
trip locks, atomic page checkpoints and idempotent replay still provide data
safety. A crash after a page commits but before its status update can cause a
safe repeat. If the database is unavailable even for reporting, the report cannot
be fabricated; the durable claim remains recoverable when its lease expires.

After fixing a pause's underlying cause, internal `resume(vehicle,expectedRevision)`
permits another attempt of that stage, using a revision check to reject stale
requests. It retains total failure count and does not change policy, unseal a
trip, clear review evidence, delete work, abandon a publication or lift its
visibility gate. Repeating without fixing the cause pauses again. Do not run
recovery SQL or resume an owner database merely to demonstrate this feature.
Worker calls reject an ambient transaction so reporting and page commits cannot
accidentally be rolled back together. Retry timing uses an injectable operational
clock, separate from the stage's event-processing `asOf` horizon.

These services remain internal, without a public operations endpoint. The
coordinator below uses the worker; it never bypasses pauses by calling lower-level
services directly. No owner data was migrated for these tests.

### Opt-in startup and timer processing

Automatic processing is **disabled by default**, including in the `local` profile.
The opt-in settings are `autopulse.trip-processing.enabled` (default false),
`autopulse.trip-processing.page-size` (default 100; 1..100), and
`autopulse.trip-processing.poll-delay-ms` (default 1000; 100..60000). The real
enabled Spring schedule is exercised only in a disposable test context here;
do not enable it against owner data merely to demonstrate it.

After application readiness, each fixed-delay poll selects at most one configured
primary-device vehicle from a durable round-robin UUID cursor and invokes at most
one bounded stage call. End-of-round resets the cursor; the following poll starts
the next round. Selection commits before processing begins. No fleet-wide memory
list, raw-history scan per poll, browser connection or in-memory notification is
needed. A restart resumes from committed state. Local ticks cannot overlap;
database worker claims/trip locks remain necessary for multiple app instances.
The delay is between completed polls, not a real-time latency guarantee. One
FORWARD call may do both its bounded backfill page and a bounded projection page.

The coordinator first honors a worker's lease, retry stage or pause. Otherwise it
prioritizes unfinished publication, then correction discovery/replay/publication,
backfill/policy validation, eligible review discovery, due input, open-trip timer
work and finalization. Disconnected/future input does not advance the timer or
seal data prematurely. Explicit unresolved review pauses the vehicle; valid raw
ingestion continues to queue work. A paused vehicle cannot prevent the durable
scan from visiting the next vehicle. Selection can race with new input; the
worker and stage guards recheck durable state before mutation, and later polls
discover committed arrivals rather than clearing a lossy in-memory marker.

Historical captures can arrive in separate commits. If a timer expired a start
candidate or completed a SIGNAL_TIMEOUT between chunks, nearby later evidence
is marked TIMER_CONTINUATION and sent through canonical correction. This can
recover the first running point, extend a provisional timeout or reclassify a
source-change boundary without mixing sources. The saved raw cursor provides
candidate context even when a previous correction produced no publishable trip.
An already sealed overlap still pauses for review; no automatic unsealing occurs.
V909 supports nearby timeout/discarded lookups with partial indexes, alongside
the existing exact event-identity lookup. This is not permission for an unbounded
raw rescan or a claim that every large-history query plan is already verified.

On missing signal, timer closure records SIGNAL_TIMEOUT at the last-running
measurement, not the processing time or an invented ENGINE_OFF. Final sealing
still requires five receipt-time quiet minutes and no pending/review work.
Shutdown stops future local ticks; an in-flight page remains governed by its
transaction/checkpoint. Selection/reporting errors leave durable work recoverable
and do not disable later ticks; logs contain only a safe failure class.

The concurrency/performance audit includes competing coordinators and a
5,000-reading EXPLAIN ANALYZE fixture. An earlier ten-reading query visited all
5,000 raw rows; V910's ordered work metadata/index and materialized limited page
reduce that observed raw visit count to ten. Legacy pending work gets its sort
keys through bounded backfill pages, not an unbounded migration rewrite. A
2,000-reading drive also verifies exact summaries through real paged processing.
These checks are representative, not a fleet-scale SLA or exhaustive review of
large correction-window query plans.

See [processing review and recovery](docs/trip-processing-operations.md) for the
safe handling of paused policy/finalized/overwide work and unfinished publications.
No general operator repair/rollback tool or automatic unsealing is implied.
Checkpoint 2's implementation/verification handoff is complete; processing stays
disabled by default and owner-data enablement is a separate decision. This is not
a production-scale certification or completion of the full milestone/MVP. The
trip/history REST APIs and checkpoint 4's browsing/chart UI are implemented and
verified as described below. Retention and full-milestone end-to-end verification
remain separate later checkpoints.

### Trip list and detail APIs (checkpoint 3)

`GET /api/v1/vehicles/{vehicleId}/trips` uses the registered public vehicle slug.
It returns `items`, `nextCursor` and a decimal-string `generation`. Limit defaults
to 20 and must be 1..100. Follow `nextCursor` with the same vehicle and limit;
the list sorts descending by start timestamp and PostgreSQL UUID ordering. Active
and completed trips are included, superseded/candidate/discarded records excluded.
Known empty vehicles return 200 with empty items, not 404.

`GET /api/v1/vehicles/{vehicleId}/trips/{tripId}` returns the agreed explicit public
DTO: public vehicle/device IDs, source, boundaries, status/reason, version/revision,
finalization, sample count, exact per-metric count/sum/min/max, quality and
replacement IDs. Estimated distance remains the exact speed-area/3600 ratio with
coverage/duration, not an odometer or a prematurely rounded decimal. No speed
coverage returns null kilometres, not invented zero distance. Active end/reason
are null. Unknown/wrong-vehicle trips return UNKNOWN_TRIP; unknown vehicles return
UNKNOWN_VEHICLE. Internal candidates/discarded IDs are not public trip details.
Superseded IDs previously representing active/completed trips remain resolvable.
Replacement-link responses are bounded to 100; an overwide link set returns 503,
never a silently truncated replacement list.

Success responses are `Cache-Control: no-store` and include
`X-Trip-Processing-State: CURRENT`, `PENDING` or `REVIEW`. A stored trip response
is not proof that every accepted measurement has been projected. In particular,
the default-disabled runner can leave queued work; clients must not present a
PENDING/REVIEW response as complete current history.

List cursors are fixed-size URL-safe binary tokens authenticated with HMAC-SHA256,
bound to route/version, vehicle, limit, generation and the full last time/UUID.
They expire after 15 minutes and use a random process-local key: restart or a
different app process invalidates them. Malformed/tampered/expired/context-mismatched
tokens return 400 INVALID_CURSOR. Accepted inserts, trip updates and lineage
changes advance the per-vehicle generation atomically; duplicates/rollback do
not. A changed generation returns 409 PAGE_CHANGED: restart from page one. These
cursors are not portable multi-node snapshots or a promise of unchanged history.

Each response reads vehicle, publication gate, generation, trip rows, replacement
links and processing status in one short read-only repeatable-read transaction.
An active publication returns 503 PUBLICATION_PENDING, never a misleading empty
trip list. A reader already holding the preceding committed snapshot can finish
that coherent old response; a new reader sees the publication gate. No database
writer lock is held by the API to freeze data across HTTP requests.

The APIs reject unknown/repeated query parameters, unsupported limits and malformed
trip UUIDs using the existing structured error/correlation format. V911 installs
generation triggers without scanning/rebuilding raw telemetry; V912 adds indexes
for public trip order and supersession lookups. All checks used disposable databases.
Raw and aggregate history are served below. Representative larger history query
plans are verified, without claiming identical cost for every data distribution.

### Raw and aggregated telemetry history

`GET /api/v1/vehicles/{vehicleId}/telemetry/history?from=...&to=...` returns tagged
raw points with each canonical reading and its original `receivedAt`. `from`/`to`
are required RFC 3339 timestamps with offsets, normalized to UTC, with at most six
fractional digits. The range is half-open `[from,to)`, strictly increasing and at
most 24 hours. Invalid dates, extra precision, missing/reversed/equal/too-wide
bounds and unknown/repeated parameters return structured INVALID_QUERY errors.
Offsets in URLs must be encoded (for example `%2B01:00` rather than a bare plus).

Resolution defaults to `raw`; `1m` and `5m` return UTC-aligned 1-minute and 5-minute
summaries for ranges up to 31 days. Limit defaults to 200/max 1000. Follow `nextCursor` with the same
normalized range, resolution, limit and optional `tripId`; there is no silent
truncation. Points ascend by exact measurement time, internal device UUID, boot
UUID and arbitrary-size sequence number, preserving all equal-time raw ties and
source labels. Per-sensor decimals remain exact JSON numbers, with nulls intact.

Aggregate points are tagged `aggregate`, not raw readings. Buckets are half-open,
UTC-aligned and clipped at request edges; `partial` means this clipping, not complete
sensor coverage or trip occupancy. Each sensor has exact non-null count/sum/min/max.
Its sample-weighted mean is sum/count, null if count is zero; sample coverage is
count/sampleCount, not elapsed-time coverage. When combining buckets, add sums and
counts before dividing, never average their means. No rounding is applied server-side.
Empty buckets are omitted and must break chart lines, not become zeros or fabricated
interpolation. `firstObservedAt`/`lastObservedAt` show observed limits, not continuous
coverage. There is no cross-bucket distance interpolation.

Each bucket exposes `sources` and `deviceCount`. Unfiltered history aggregates all
raw ties/devices, so mixed buckets must be labeled as mixed, not homogeneous OBD or
SIMULATED data. Optional trip filtering instead selects canonical trip evidence.
Per-source/device chart series are not provided by this summary route.

An aggregate page spans at most limit consecutive aligned buckets, beginning at
the next observed bucket; omitted empty buckets can make a page shorter than limit
even when nextCursor exists. Numeric input is capped at 100000 matching readings
per page, with one extra row used to detect overflow. Overflow returns 503
HISTORY_DENSITY_LIMIT with no partial summary; reduce limit or narrow the requested
range. A bucket that alone exceeds the cap requires narrower ranges. Each page
also has a 10-second transaction timeout. This cap bounds aggregation input, not
every index/MVCC visibility visit; sparse trip membership may examine extra candidates.

The signed 15-minute cursor binds vehicle, normalized range, limit, optional trip
and generation, including resolution. Its fixed-size reading anchor retrieves the complete native
timestamp/device/boot/NUMERIC tuple by primary key inside the read snapshot, so
large sequence numbers need not be rounded or copied into an oversized cursor.
Aggregate tokens use a distinct resolution kind and resume after the anchor's UTC
bucket, never splitting a bucket across pages.
Changed data returns PAGE_CHANGED; malformed/tampered/expired/context-mismatched
cursors return INVALID_CURSOR. These tokens use an ephemeral per-process key,
not a cross-process immutable snapshot. Restart invalidates outstanding cursors.

Optional `tripId` selects that vehicle's active/completed evidence, not every raw
measurement in its approximate time interval. It includes the completed closed
end boundary but excludes confirmation waiting and losing timestamp ties. To
include an exact completed end in a half-open chart range, request `to=end+1
microsecond` where representable. Unknown/wrong-vehicle trips return UNKNOWN_TRIP;
superseded trip filters return PAGE_CHANGED so clients can inspect replacement
links rather than mistake reassigned evidence for no readings.

The same short repeatable-read snapshot, publication gate, no-store caching and
processing-state header as trip reads apply. Publication returns PUBLICATION_PENDING
for filtered and unfiltered history. An insert committed during a response cannot
mix its new generation/rows with an earlier snapshot; a later continuation detects
the committed change. V913 supports primary-device/tuple seeks for trip-filtered
history; unfiltered history uses existing vehicle/measurement ordering. A disposable
5000-reading fixture with actual EXPLAIN ANALYZE verifies indexed raw input visits
of 11 for a 10-row tail page and 20 for a selected one-minute aggregate window.
These measurements do not certify every trip-membership/tie-density/MVCC distribution.

No retention job has run. Successful responses currently report rawState AVAILABLE,
rawUnavailableBefore null and availableResolutions `["raw","1m","5m"]`. `incomplete` is
conservatively true while derived processing is pending/review; AVAILABLE does
not certify complete hardware capture, uninterrupted signal or non-null sensors.
The expired-state schema/example remains a proposed retention shape, not implemented
pruning behavior. Known empty history returns 200
with no points, distinguishable from an unknown vehicle's 404.

The owner development database has not been migrated during these checks; its
next backend startup will apply the new migrations normally. The agreed rules
remain unchanged. Checkpoint 3's trip/history APIs are implemented and verified.
Checkpoint 4's UI is described below; opt-in retention and the full
simulator-to-trip-browser demonstration remain unfinished.

### Trip browsing and measurement charts (checkpoint 4)

The dashboard now includes Live and Trips navigation. `/#/trips` opens the newest
saved trips and `/#/trips/{tripId}` deep-links to a trip summary for the configured
vehicle. Native hash links preserve the existing vehicle query parameter and work
with browser Back/Forward. No routing dependency was added. Returning to
Trips fetches its newest page; previous list-page positions are not bookmarked.
Leaving Live closes that view's SSE subscription; returning subscribes again.

The list requests 20 trips at a time and replaces the visible page when Next page
succeeds; it does not accumulate unbounded trip rows. Refresh newest trips and Back
to newest trips restart pagination without a cursor. If a generation changes or a
cursor expires, the page explains the restart requirement. Loading, known empty,
unknown vehicle/trip, publishing corrections and other failures remain distinct.
Empty lists explicitly note that default-disabled processing may leave raw readings
without saved trips. The UI does not enable processing or migrate your database.

Trip rows show UTC measurement start, observed duration, status/completion reason,
source, finalization, revision and estimated distance with supported-speed coverage.
Detail adds precise original UTC boundary strings, observation/confirmation times,
primary device, sample count, algorithm/revision, quality flags, and sample-weighted
RPM/speed/coolant/load summaries in an accessible table. Null sensors/distance stay
Unavailable, distinct from supported zero values. Counts are sample coverage, not
proof of uninterrupted recording. Display numbers are approximate browser numbers,
rounded only for display and never used to regenerate telemetry or cursor context.

Refresh explicitly re-fetches current persisted revisions; there is no competing
per-reading trip stream or automatic polling. Active duration stops at the last
observation. Provisional and processing PENDING/REVIEW/unknown notices prevent a
stored result implying complete applied history. Superseded details are reference-only
and link to replacements. SIMULATED data stays labeled synthetic; parked-use warnings
remain visible. A failed refresh retains the last successful result, clearly labeled
last-known rather than presenting an empty list or falsely successful new response.

Requests are cancelled on navigation/vehicle/trip changes, ignore obsolete responses,
and time out after 15 seconds. Runtime response checks reject wrong identities,
malformed sensor statistics, invalid ratios and oversized pages. A trip detail response
must match its requested trip as well as vehicle. The pages share the existing visual
style, responsive cards and keyboard-accessible links/buttons. Measurement charts
and history controls are described below. This completes checkpoint 4's UI
implementation/isolated verification, not retention or the full milestone.

Trip detail charts RPM, road speed, coolant and engine load through the bounded
history API, with Raw, 1-minute and 5-minute options. Drives over 30 minutes default
to 1-minute summaries. Each request/visible page is limited to 50 or 200 points;
Next history page replaces the previous page rather than accumulating raw data.
The page explicitly says when more points exist and never labels a truncated chart
as the whole drive. Raw windows are at most 24h, aggregate windows at most 31 days;
if needed, Next time window becomes available after finishing the current window's
pages. A full 24-hour raw trip therefore keeps its exact final reading in a second
one-microsecond window instead of excluding it to fit the range cap.

Request bounds use exact BigInt microseconds. The half-open upper bound is the
completed end or active observed-through time plus one microsecond, preserving the
closed trip boundary without rounding to milliseconds. Unrepresentable bounds fail
visibly rather than wrap. Chart X positions are display-only seconds relative to
the requested window, with UTC measurement labels; original timestamp strings stay
in the accessible values table. No measurement time is replaced by receipt time.

Raw lines break at unavailable sensors, boot changes, equal-time ties and gaps over
5 seconds. This is a conservative display rule, not an automotive diagnostic rule.
No line joins pages/windows. Aggregate means are deliberately unconnected markers:
per-bucket statistics cannot establish continuous coverage within or between buckets.
All-null sensors stay unavailable, not zero-filled. Each figure labels units and
resolution and points to a keyboard-scrollable table with every displayed timestamp,
value, non-null/total count and boot/clipped-bucket context. The table remains a full
non-visual alternative; charts do not depend on hover or color alone.

Trip list/detail now return `X-Trip-Data-Generation` from the same repeatable-read
snapshot as their summaries. Chart pages must match that exact opaque generation;
otherwise no newly fetched points are shown, and the owner is asked to refresh the
trip summary. Missing generation headers also withhold charts. Summary refresh
remounts chart state and revalidates generation. Header and history generation are
not converted to JavaScript numbers. This is coherent per-page viewing, not a
promise that persisted provisional trips cannot change after a response.

History requests are cancelled on identity/resolution/page-size/window changes.
Failures retain clearly labeled last-known points for the same view, not an apparent
empty success. Changed/expired cursors require a summary refresh. Density-limit
errors suggest fewer points or Raw readings. Pending/review/incomplete and explicit
raw availability notices remain visible. EXPIRED/PARTIALLY_EXPIRED contracts are
handled by the UI with suggested available resolutions and no fake zero chart;
those states are fixture-tested, not proof that retention is implemented or enabled.
Superseded trip summaries do not request their reassigned history.

Charts use pinned Recharts 3.10.1 and a React-matched react-is peer, after checking
official compatibility/docs. The manifest and lockfile were updated. Charts load in
a separate lazy chunk so Live does not download their library initially. The final
build measured about 77 KB gzip for the main JS and 95.5 KB gzip for the chart chunk;
these are local build observations, not a long-term bundle-size guarantee.

The ordinary frontend checks include navigation/resource cleanup, paging, stale data,
revision refresh, nulls, cancellation and timeout cases. The opt-in disposable browser
harness also runs trip UI contract-fixture checks for hash deep links/Back, keyboard
use, desktop/narrow layouts, accessibility, failed-refresh retention, all three
chart resolutions, bounded paging, expired availability and generation mismatch
recovery. Those trip
fixtures do not prove the later real simulator-to-projected-trip-to-chart pipeline.
The existing real Python-ingestion/live-browser scenario remains in that harness.
Checkpoint 4 verification passed 59 frontend tests, type checking/lint/build,
175 backend tests, 94 tests on each Python version, and all five browser scenarios.
Trip/chart browser routes use shared contract fixtures; the later full simulator ->
trip projection -> browser demonstration was completed in checkpoint 6; see the
current milestone verification above. These checkpoint-4 counts remain historical.

Run migration/work checks against disposable PostgreSQL (Docker required):

```powershell
Push-Location server
.\mvnw.cmd test '-Dtest=TripWorkMigrationTests,LocalProfileMigrationTests,TripFoldTests'
Pop-Location
```

### Retention policy preview (checkpoint 5, incomplete)

[ADR 0006](docs/adr/0006-retention-preview-and-pruning-prerequisites.md) defines the
proposed policy, implemented read-only preview, and hard prerequisites for pruning.
No deletion/downsampling job, public admin route, scheduler, startup scan or report
CLI exists yet. This is an internal Java service call, not a feature to enable on
the development database to demonstrate deletion.

Defaults are `autopulse.retention.enabled=false`, `dry-run=true`, `raw-days=30` and
`receipt-hold-hours=96`. Enabling only permits an explicit `RetentionPreview.report`
call; it does not run automatically. Non-dry-run mode is rejected. Preview settings
accept 7..3650 raw days and 96..8760 receipt hours. These are proposed conservative
report settings, not owner authorization to retain only that much data.

The initial page freezes server UTC time. Measurements must be strictly before
the UTC day boundary of `asOf - rawDays`; receipts must be strictly older than the
independent 96-hour hold. Measurement maxima/future outliers cannot move the cutoff.
Pages contain at most 100 old-reading metadata records, using exact native tuple
paging, a 15-minute trusted in-process continuation, a read-only repeatable snapshot
and a 10-second timeout. Continuations are not public cursors, persisted deletion
plans or authorization. Operational protection state is reevaluated per page.

Unknown/unfinished policy, backfill, activity, projection or worker state; pending,
review, retrying/leased/paused work; active corrections/publication; and open or
provisional trips protect the vehicle. Per-reading checks additionally require
processed work, projection strictly past its timestamp, primary-device/source
membership inside a FINAL COMPLETED trip's closed evidence range, old receipts,
and preservation of the authoritative latest raw reading. Idle/unassigned and
confirmation-waiting readings are not implicitly deletable.

`policyCandidates` means only that those preliminary checks passed. Every report
still returns `wouldDelete=0`, with missing durable aggregate, retained-evidence,
write-time replay-coverage recheck and pruning-implementation prerequisites. Final
evidence and work still depend on raw foreign keys; deletion would break guarantees.
The preview does not relax constraints,
change finalized summaries/receipts/checkpoints, create aggregates, or fake expired
history availability. Existing history continues reporting raw AVAILABLE.

The tests use disposable PostgreSQL, canonical fixed-receipt fixtures, actual trip
projection/finalization and read-only transaction assertions. They check protection
states, exact cutoff/receipt boundaries, latest preservation, generation/policy
changes, duplicate/rollback behavior, native precision and concurrent insertion.
No new migration was needed for that preview step. No owner database has been started, migrated or pruned.
At the initial preview step, the complete backend suite passed 185 tests, including ten preview
cases. The subsequent fixed-receipt fixture rerun also passed all ten preview cases.
Next are durable aggregates/evidence and synchronized write-time guards, then
bounded crash-safe opt-in pruning. Actual owner-data deletion requires separate,
explicit approval of named data/ranges; Continue is not that approval.

Run only disposable preview checks from the repository root:

```powershell
Push-Location server
.\mvnw.cmd test '-Dtest=RetentionPreviewTests,RetentionPreviewUnitTests'
Pop-Location
```

#### Replay coverage in the preview

Missing or unfinished legacy replay backfill protects the vehicle even when its
current page already matches the ledger. Each returned reading also reports
`replayIdentityState` (MATCHED/MISSING/MISMATCH), comparing native identity, original
reading/vehicle UUIDs, exact timestamps, source and canonical JSONB in PostgreSQL.
Missing or mismatched evidence prevents policy candidacy; no payload enters the report.

`replayCoverage` reports backfill state and checked/matched/missing/mismatched counts
for returned rows only, not lookahead/unseen pages or a whole vehicle. Disabled mode
reports NOT_EVALUATED with zero checks and still performs no DB/clock/transaction work.
The report never backfills or repairs evidence. Backfill status is reevaluated per
page and can change at the same raw/trip generation; this is not an immutable
multi-page coverage certificate. A completed flag alone never waives row comparison.

The ledger-foundation prerequisite is now replaced by the explicit
`WRITE_TIME_REPLAY_COVERAGE_RECHECK_REQUIRED`: a future pruning transaction must
recheck complete backfill and exact coverage for its actual deletion set under the
proper ingestion barrier. A read-only snapshot cannot authorize deletion. All
existing age, receipt, trip, latest and processing protections remain in force.

Verification: **212 backend tests passed**, with zero failures/errors/skips. Seven
new coverage cases exercise missing/incomplete backfill, damaged original evidence,
page-only counts, concurrent snapshot/backfill behavior and a real pre-ledger V913
upgrade through partial/completed backfill. That coverage step needed no migration
(the latest version at that step was V915).

### Replay identity capture and retained retries (checkpoint 5, still incomplete)

V914 adds an immutable exact-payload replay ledger. Accepted raw readings capture
their original identity, canonical JSONB and receipt atomically; duplicate/conflict
attempts and rolled-back inserts do not create extra records. This capture runs as
an ingestion invariant after migration, independently of the disabled retention
runner. It currently duplicates payload storage; no storage savings are claimed.

Existing payloads are **not copied during migration**. An internal explicit
`ReplayIdentityBackfill.copyPage(vehicleUuid, limit)` call copies/verifies 1..500
rows per transaction with a durable per-vehicle cursor, a 10-second timeout and a
busy response for a competing worker. It has no automatic startup runner, scheduler,
CLI or public route. Recreated services resume the persisted cursor. Conflicting
ledger evidence rolls the entire page back; accepted evidence cannot be overwritten.

V915 adds a device-scoped database gate: retries use retained canonical evidence
even if their raw fixture has been removed, returning duplicate/IDENTITY_CONFLICT
without recreating raw rows/work, changing receipts or publishing Live updates.
Concurrent submissions serialize per device, with exact device/boot/sequence identity
comparison; failed
transactions leave no reservation. Hashing is only for lock selection, not payload
comparison. Older unbackfilled raw identities retain their exact comparison fallback.

This does not make any row deletable. Coverage is assessed by the preview above,
but synchronized write-time coverage still needs a future pruning transaction.
Raw data still supplies history, latest state and trip evidence. Next are durable
aggregates/evidence and crash-safe pruning/availability. See ADR 0006 for concurrency and storage
limitations. Replay tests remove only unprojected synthetic rows in disposable
databases; they are not a production pruning demonstration.
No owner database has been migrated to demonstrate this feature.

At the V914 checkpoint, the complete backend suite passed 193 tests, including eight
ledger cases, with zero failures/errors/skips. The older HTTP/Live fixtures now use
a fresh boot per test case rather than erasing durable replay evidence during raw
fixture cleanup. Replay/conflict/concurrency assertions within each case remain.

With V915 retained replay, the complete backend suite passed **205 tests**, with
zero failures/errors/skips. Twelve additional cases cover retained replay and HTTP
receipts, exact comparisons, concurrent commit/rollback, legacy/upgrade behavior,
bounded lock use and ignored-insert recovery. This does not verify production pruning.

Run the disposable migration/ledger tests from the repository root:

```powershell
Push-Location server
.\mvnw.cmd test '-Dtest=TripWorkMigrationTests,LocalProfileMigrationTests,ReplayIdentityTests,TelemetryApiTests'
Pop-Location
```

### Retained final-trip membership foundation (checkpoint 5, incomplete)

V916 adds immutable retained membership backed by the exact replay ledger, plus
per-trip copy progress. Migration copies no raw payloads or trip membership.
`FinalTripEvidence.copyPage(vehicleUuid, tripUuid, limit)` explicitly copies/verifies
1..500 original links in a new 10-second transaction. It has no startup runner,
scheduler, CLI or public route. Concurrent calls for a trip return busy; recreated
services resume committed progress. Copies and progress roll back together on failure.

Only FINAL COMPLETED trips qualify. Membership must already belong to that trip,
match its vehicle/primary device/source and closed start/end interval, and have exact
original ledger identity, payload and timestamps. Trailing confirmation links consume
bounded work but are not retained as trip history. A late reading cannot be assigned
to a final trip merely because its timestamp overlaps. Accepted evidence and completed
progress cannot be reset or deleted. See [ADR 0007](docs/adr/0007-retained-final-trip-membership.md).

This is additive storage, not a public-history or deletion switch: existing final
links/FKs, raw rows, work, summaries, revisions, public queries and generations stay
unchanged. RETAINED_FINAL_EVIDENCE_REQUIRED remains until the independent reader/FK
path and write-time checks are completed. Durable aggregates, clipped-range ownership,
truthful expired availability and pruning are not implemented by this step.
No owner database has been migrated or pruned, and no storage savings are claimed.

Verification: **220 backend tests passed**, with zero failures/errors/skips. The
eight new cases cover exact closed membership, small-page restart/retry, rollback,
busy overlap, immutability, invalid/nonfinal requests, legacy-ledger prerequisites,
and protection against inventing membership from trailing or new late readings.
Actual raw pruning and archived public-history behavior remain unverified.

Run only disposable evidence/migration tests from the repository root:

```powershell
Push-Location server
.\mvnw.cmd test '-Dtest=FinalTripEvidenceTests,TripWorkMigrationTests,LocalProfileMigrationTests'
Pop-Location
```

### Durable trip aggregate contributions (checkpoint 5, incomplete)

V917 adds exact persisted 1m/5m contributions for FINAL COMPLETED trips with complete
retained membership. Each bucket keeps trip/vehicle/device/source ownership, sample
count, observed bounds and all nine sensors' non-null count/sum/min/max. All-null
statistics remain null; supported zero stays distinct. Means must use combined
sum/count, not averages of averages. Buckets do not imply continuous time coverage.

`TripAggregateBuilder.buildPage(vehicleUuid, tripUuid, limit)` explicitly visits
1..500 retained identities per new 10-second transaction. Both resolutions and the
cursor commit together, so second-resolution failure rolls the entire page back.
Recreated services resume; complete builds and their ownership cannot be rewritten
or reset. Competing cooperative builders return busy. No startup runner, scheduler,
CLI/admin route, automatic backfill or migration-time payload scan was added.

These are per-trip contributions, not complete vehicle-wide buckets or public
archive responses. Current queries still read raw data. Clipped query edges need
exact retained-evidence handling, and raw/archive ownership must prevent double
counting before a public switch. Availability, public archive integration and
pruning remain unfinished; independent verification is described below. No raw
data is expired/deleted and no storage savings are claimed.
See [ADR 0008](docs/adr/0008-durable-trip-aggregate-contributions.md) for the guarantees
and limitations. No owner database has been migrated or pruned.

At the storage/build checkpoint, **229 backend tests passed**, with zero failures/errors/skips. Nine
new cases cover exact/null/weighted statistics, boundary/provenance handling,
page/restart equivalence, atomic two-resolution recovery, validation/immutability,
busy overlap and an upgrade that builds nothing automatically. No public archive
query or actual-pruning behavior is claimed by these tests.

Run only disposable aggregate/migration checks from the repository root:

```powershell
Push-Location server
.\mvnw.cmd test '-Dtest=TripAggregateBuilderTests,TripWorkMigrationTests,LocalProfileMigrationTests'
Pop-Location
```

### Independent aggregate verification (checkpoint 5, incomplete)

V918 adds an explicit, restart-safe `TripAggregateVerifier.verifyPage(vehicleUuid,
tripUuid, limit)` for completed trip builds. It independently recalculates both
resolutions with exact Java decimal arithmetic from retained readings, then walks
bounded pages of BOTH expected and stored bucket keys. Missing/extra buckets and
incorrect counts, bounds, provenance or statistics fail verification. A completed
builder cursor is not automatically treated as proof of correct aggregates.

Each 10-second transaction handles at most 1..500 input readings OR bucket keys.
Reference writes/cursors commit together; reconstructed services resume after an
outage. Results end in VERIFIED or FAILED, with durable failure code/key, and do
not silently reset. Sealed reference rows and terminal progress are guarded.
Reference statistics remain stored; this adds verification storage, not savings.

No public reader, retention configuration or availability response has changed.
VERIFIED is not permission to prune: public archive integration, raw/archive ownership,
write-time safeguards and raw-independent evidence/FK paths remain unfinished.
There is no automatic verifier or pruning runner, and owner data is untouched.
See [ADR 0009](docs/adr/0009-bounded-trip-aggregate-verification.md) for the algorithm,
disposable tests and limits. The disposable command above includes these tests.

At the verification-foundation checkpoint, **241 backend tests passed**, zero failures/errors/skips,
including 11 new verifier cases and a V917 upgrade case. Tests cover independent
decimal/null calculations, bounded restart/resume, missing/extra/wrong buckets,
microsecond/pre-epoch boundaries, phase rollback, immutability and busy overlap.
These are disposable database checks, not a public archive or pruning demonstration.

### Exact retained-trip history (checkpoint 5, internal only)

`RetainedTripHistory.read(vehicleUuid, historyRequest, trustedCursor)` reads one
FINAL COMPLETED trip's independently VERIFIED archive. Complete UTC buckets reuse
stored contributions; clipped query edges recompute only retained trip members in
the requested half-open interval. Whole-minute totals are never returned as the
answer for only part of that minute. Nulls, supported zeroes, exact decimals,
source/device ownership and observed bounds match the current raw-derived reader.

The read-only 10-second snapshot visits at most limit+1 stored bucket keys. Clipped
edges share a 10,000-ledger-candidate budget, capped BEFORE membership filtering
or payload lookup; excessive density fails the whole page without partial totals.
V919 adds a ledger metadata range index, without payload copying or automatic
archive publication. Empty clipped pages can have a continuation; follow it until
null. This is a trusted internal cursor, not a signed public HTTP cursor.

Only this trip's retained membership owns the result. Raw rows are never added
again, and unlinked late readings cannot silently become members of a final trip.
This does not settle unfiltered vehicle-wide raw/archive ownership. No public API,
availability, processing/retention default or owner database has changed; pruning
remains unimplemented. See [ADR 0010](docs/adr/0010-exact-retained-trip-history.md)
for query/index costs, snapshot/cursor limits and remaining work.

At the retained-trip reader checkpoint, **251 backend tests passed**, zero failures/errors/skips,
including nine new retained-history cases and a V918 index-upgrade case. These
prove internal clipped/full-bucket results, not public archive availability or
safe raw removal. The disposable aggregate/migration command above includes them.

### Whole-vehicle ownership proof (checkpoint 5, internal only)

`OwnedVehicleHistory.read(publicVehicle, historyRequest, trustedCursor)` gives each
included original identity one owner: a member of independently VERIFIED final
archive evidence is ledger-owned; other readings stay raw-owned. It excludes owned
raw mirrors before combining the branches, keeps unlinked late/secondary-device
readings visible and checks overlapping evidence for exact equality. Missing owners
or disagreeing evidence fail the entire page instead of returning partial totals.

The read-only 10-second snapshot covers at most limit UTC buckets with a shared
10,000-PHYSICAL raw/ledger metadata-candidate cap before filtering or payload work.
Both copies consume budget but enter statistics once. Counts/sums and distinct
source/device coverage use disjoint observations, not averages of means or added
device counts. V920 adds a vehicle/time ledger metadata index. Archive values are
recalculated from the ledger; persisted bucket reuse for this path remains unfinished.

Trusted internal continuations bind filters and vehicle generation; accepted raw
input requires paging to restart. They are not signed public tokens or expiration
claims. Public APIs, availability and retention defaults are unchanged. Public
integration, legacy raw-independent FKs and safe pruning remain unfinished. No owner
DB has been used/migrated and no raw data deleted. See
[ADR 0011](docs/adr/0011-disjoint-whole-vehicle-history-ownership.md).

At the internal ownership checkpoint, **261 backend tests passed**, zero failures/errors/skips,
including nine ownership cases and a V919 upgrade case. Checks cover disjoint
sources, late/secondary-device inputs, shared-bucket weighting/coverage, exact
clipping, pagination/handoff, generation changes, density, gaps and mismatches.
This is internal correctness evidence, not public expiration or actual raw pruning.

### Opt-in ownership-aware public aggregates (checkpoint 5, incomplete)

`autopulse.history.owned-aggregates-enabled=false` keeps existing behavior by
default. If explicitly enabled, only unfiltered 1m/5m history uses disjoint
raw/verified-retained ownership; raw and trip-filtered queries keep their original
reader. Response fields and processing/no-store headers are unchanged. The opt-in
cap is 10,000 combined physical raw/ledger candidates per page, not the default
100,000 matching readings. Mirrored copies enter statistics once.

Points, generation and processing/availability share one read-only 10-second
snapshot. A signed 15-minute process-local cursor binds filters, vehicle, generation
and the next UTC bucket boundary. Tampering or cross-reader tokens return
INVALID_CURSOR; new input yields PAGE_CHANGED. Changing modes/process requires
paging to restart. Trusted internal cursor records are not public HTTP tokens.

Successful availability remains AVAILABLE with no expired cutoff: no raw data has
been removed. incomplete tracks processing uncertainty. Ownership gaps/mismatches
and excessive density return explicit 503 errors, never partial points. The setting
remains false in saved project configuration; no owner database has been used or
migrated. Pruning, actual expired availability, public trip-archive integration
remain unfinished. Verified full-bucket arithmetic reuse is described below. See
[ADR 0012](docs/adr/0012-opt-in-public-owned-aggregate-history.md).

Run only disposable opt-in checks from the repository root:

```powershell
Push-Location server
.\mvnw.cmd test '-Dtest=OwnedHistoryApiTests,OwnedHistoryCursorTests,HistoryApiTests'
Pop-Location
```

At the public-adapter checkpoint, **276 backend tests passed**, zero failures/errors/skips,
including nine opt-in API/snapshot cases, five signed-cursor cases and a default-mode
regression. These checks do not demonstrate actual raw expiration or pruning.

### Verified whole-vehicle bucket reuse (checkpoint 5, incomplete)

The ownership-aware reader now reuses stored per-trip contributions for complete
UTC buckets within the page. Raw-owned readings and clipped archive edges remain
exact recalculations. The final rollup adds sensor counts/sums and takes extrema;
it never averages means or adds device counts. Original identity ownership keeps
the cached/residual inputs disjoint, including multiple trips sharing one bucket.

Each required cached row must match selected owned membership count, observed bounds
and trip provenance; missing/inconsistent contributions fail with an ownership gap
instead of returning partial totals. Internal reuse/recalculation diagnostics are
not exposed by HTTP. Bounds, signed cursors, one-snapshot metadata and public response
shape are unchanged; the feature remains disabled by default.

This avoids sensor recalculation for fully covered archive readings, but still
performs bounded metadata scans and canonical equality checks that can access
payloads. It does not increase the 10,000-candidate cap or prove a throughput/storage
gain. Raw expiration, legacy raw-independent FK paths and pruning remain unfinished.
See [ADR 0013](docs/adr/0013-reuse-verified-whole-vehicle-contributions.md).

At the contribution-reuse checkpoint, **282 backend tests passed**, zero failures/errors/skips.
Six new cases check complete reuse, exact clipped edges, cursor-page reuse, shared
weighted/device/source composition, missing required contributions and unchanged
HTTP output. These are correctness checks, not throughput or raw-pruning evidence.

### Verified final-trip evidence handoff (checkpoint 5, incomplete)

V921 adds one shared trip_verified_archive eligibility view and switches
trip_evidence membership to retained identities/canonical ledger timestamps ONLY
for FINAL COMPLETED trips with complete matching evidence/build/VERIFIED counts.
Other trips keep their original raw-link path. Both arms preserve publication
visibility and the same two evidence columns; they cannot double-count a trip.

The handoff preserves exact member IDs, summaries and generation while both copies
coexist. It is a membership read change for all evidence-view consumers, not a new
public trip-metric reader: those queries still select raw values. The fallback arm
also retains a schema dependency on raw. All three raw FKs (trip links, work items,
correction staging) and all final/evidence guards remain intact. Raw deletion still
fails; no actual-pruning or post-removal summary result is claimed.

Migration creates views, not payload backfills or automatic archive jobs. Saved
feature/retention flags remain disabled; no owner DB is used/migrated. See
[ADR 0014](docs/adr/0014-verified-final-evidence-read-handoff.md) for the exact boundary
and remaining phased reference/read-path work.

Latest verification: **288 backend tests passed**, zero failures/errors/skips.
Six new cases cover eligibility/source handoff, exact unchanged membership and
generation, canonical time, publication gating, preserved FKs/guards, inconsistent
markers and legacy upgrade behavior. These do not prove safe raw pruning.

### Dual durable reading references (checkpoint 5, incomplete)

V922 adds a second, ledger-backed foreign key for new processing-work, trip-link
and correction-reading references. A one-row write-time check captures missing
legacy identity exactly and rejects conflicts; capture/reference changes roll
back together. Existing raw FKs and all immutable-final/evidence guards remain.

The new constraints are deliberately NOT VALID for historical rows: migration
does not scan/copy payload history or automatically certify legacy coverage.
Explicit bounded replay backfill remains necessary. These dual references do
not enable deletion or convert remaining raw-backed readers/processing paths.
Saved retention/public ownership flags remain disabled. See
[ADR 0015](docs/adr/0015-dual-durable-reading-references.md) for transition semantics,
write cost, legacy compatibility and remaining validation/removal prerequisites.

Latest backend verification: **294 tests passed**, zero failures/errors/skips,
including six new dual-reference cases. Tests used disposable PostgreSQL only;
no owner database migration or data deletion was performed. Checkpoint 5 remains
incomplete, and no fresh frontend/Python/browser or actual-pruning result is claimed.

### Read-only legacy identity audit (checkpoint 5, incomplete)

ReplayIdentityAudit adds an explicitly called internal check of 1..500 raw UUIDs
per page. It reports exact matched/missing/mismatched ledger identities without
copying payloads, changing backfill checkpoints or validating constraints. Each
page has its own read-only snapshot; completing one vehicle's scan is not a global
deletion certificate. No HTTP route, runner or startup job was added.

Disposable tests rehearse legacy FK validation after bounded backfill, preserving
finalized links and summaries. Table-wide constraint validation is not a bounded
job and has not been installed as a migration or run on the owner database.
Fresh/default V922 constraints remain NOT VALID; all raw protections remain.
See [operations notes](docs/trip-processing-operations.md) and
[ADR 0015](docs/adr/0015-dual-durable-reading-references.md) for the audit's limits.

Latest verification: **300 backend tests passed**, zero failures/errors/skips,
including six new audit/legacy-validation cases. No owner DB or data was changed.
Checkpoint 5 remains incomplete; frontend/Python/browser and actual raw pruning
were not reverified in this backend-only step.

### Canonical correction reads (checkpoint 5, incomplete)

V923 lets correction planning, replay and publication read canonical ledger data
after the vehicle's exact identity backfill completes. Incomplete legacy vehicles
keep the raw path. Source selection is fixed within each page; correction evidence
uses matching disjoint source branches. A ledger-native ordering index supports
the existing timestamp/native-tie rules. No automatic backfill was introduced.

All raw FKs and original-raw write checks remain. Forward processing/finalization,
public raw/trip metric reads and other paths still depend on raw. This is not raw
pruning or changed historical availability. Saved retention/ownership flags stay
disabled. See [ADR 0016](docs/adr/0016-canonical-correction-reading-source.md) for
upgrade behavior, query limits and remaining safeguards.

Latest backend verification: **306 tests passed**, zero failures/errors/skips,
including six new canonical-correction cases. Tests used disposable PostgreSQL;
no owner database migration or data deletion occurred. Checkpoint 5 remains
incomplete, with no fresh frontend/Python/browser or actual-pruning result claimed.

### Canonical forward and finalization reads (checkpoint 5, incomplete)

Forward projection, finalization and coordinator late-review discovery now use
the same ready-ledger/legacy-raw rule as corrections. Work pages remain ordered
and limited before payload lookup, with native tie winners and timer-continuation
checks on the selected source. Finalization retains original receipt times,
the exact quiet period and all pending/review/correction fences.

No migration or automatic backfill was added. Work preparation/hydration, archive
copying, public history and raw-backed write protections still need raw. Ledger
reading queries are not a raw-free pipeline or permission to delete. See
[ADR 0016](docs/adr/0016-canonical-correction-reading-source.md) and the
[operations notes](docs/trip-processing-operations.md) for the remaining boundaries.

Latest backend verification: **314 tests passed**, zero failures/errors/skips,
including eight new forward/finalization/coordinator cases. Tests used disposable
PostgreSQL only; the development database was untouched. Retention remains disabled
and checkpoint 5 is incomplete. No fresh frontend/Python/browser or actual-pruning
result is claimed for this backend-only step.

### Verified public trip history (checkpoint 5, incomplete)

Trip-filtered history now reads original ledger values only after the final trip's
membership, aggregates and independent verification qualify in the shared archive
readiness view. Raw and aggregate anchors/lookahead use the same source inside the
existing repeatable snapshot. Signed cursor formats, native ordering, exact
receipts/metrics and public response schemas are unchanged.

Incomplete/unverified trips and default whole-vehicle history keep their raw path.
V924 adds an ascending ledger history index, not a backfill or deletion job.
Availability remains AVAILABLE with no expiry cutoff because no expiration has
been performed. Raw FKs and all final/evidence guards remain; retention stays
disabled. See [ADR 0017](docs/adr/0017-verified-public-trip-history-source.md) for
cursor compatibility, source eligibility and remaining pruning prerequisites.

Latest backend verification: **322 tests passed**, zero failures/errors/skips,
including seven public-history cases and one ascending-index case. Tests used
disposable PostgreSQL only. No development database was changed; no raw deletion
or new expiry behavior is claimed. Checkpoint 5 remains incomplete.

### Explicit retention barrier inspection (checkpoint 5, incomplete)

The internal reportUnderBarrier check inspects a bounded retention-preview page
while holding processing/policy/receipt locks. It returns BUSY on contention or
CHECKED_NOT_AUTHORIZED with wouldDelete=0. Unlike the ordinary read-only snapshot,
it uses fresh READ COMMITTED statement snapshots after acquisition and can briefly
delay ingestion. Disabled mode still makes no database calls.

It also pins only the returned raw rows, not lookahead, using non-blocking row-lock
acquisition. A conflicting writer produces BUSY with no partial report and all
locks released. This avoids waiting on a raw writer that is itself waiting for
the receipt barrier; successful checks and failures also release the row pins.

The barrier result also checks each returned reading's exact replay data and
verified retained archive owner. Finalization/copy/build alone do not count as
verified coverage. Checks are page-bounded, preserve reading order and never
override pending-work or other protections. They are a later statement snapshot,
not a frozen certificate or a write-time deletion guard.

This is not a deletion or expiry-publication API. Locks release before return;
the result is not a permit for later writes. No raw FK or guard was removed, no
availability cutoff was published and retention stays disabled. See
[ADR 0018](docs/adr/0018-retention-barrier-inspection.md) for concurrency limits and
remaining same-transaction pruning requirements.

Latest backend verification: **338 tests passed**, zero failures/errors/skips,
including four new raw-row pin/release cases, six archive-coverage cases and six
vehicle-barrier cases, with enhanced disabled/bounds and non-mutation assertions.
Tests used disposable PostgreSQL only; the development database was untouched.
Checkpoint 5 remains incomplete. No fresh frontend/Python/browser or actual
expiration/pruning result is claimed for this backend-only step.

### Canonical work preparation (checkpoint 5, incomplete)

Work discovery now uses original ledger metadata only after the ledger view exists
and that vehicle's exact identity backfill is complete. Legacy/incomplete vehicles
keep raw reads. The same bounded UUID pages and checkpoints resume across source
handoff; completing identity copy does not complete work preparation.

Forward-only V925 changes the queue-order trigger without scanning or hydrating
existing rows during migration. Explicit recovery pages fill missing pending sort
metadata from ledger for ready vehicles, preserving receipts, retries and status.
Original-raw write checks and FKs remain; a mismatched original still rolls back
the insert and cursor. This is not a raw-free processing pipeline or pruning.
See [ADR 0019](docs/adr/0019-canonical-work-preparation.md) for migration and safety
limits. Retention remains disabled/dry-run; availability is unchanged.

Latest backend verification: **344 tests passed**, zero failures/errors/skips,
including six new work-preparation cases and all 141 migration cases. V925 was
verified only in disposable databases; the development database was untouched.
No fresh frontend/Python/browser or actual-expiration/pruning result is claimed.

### Canonical retained-copy bounds (checkpoint 5, incomplete)

Unfinished final-evidence copy pages now use original ledger timestamps to classify
closed membership only after exact vehicle identity backfill is complete. A later
raw timestamp edit cannot hide an original member from the unchanged exact-data
guard, or invent an outside confirmation link as a retained member. Copies and
progress still roll back together on a rejected original.

Legacy/incomplete vehicles keep raw classification. Original-raw write checks,
raw FKs and final guards remain; completed copies are not reopened or re-audited.
No migration, public-view/API change or pruning was added. Schema remains V925
and retention remains disabled/dry-run. See
[ADR 0020](docs/adr/0020-canonical-retained-copy-bounds.md) for limits.

Latest backend verification: **349 tests passed**, zero failures/errors/skips,
including five new copy-boundary cases and all 12 retained-evidence cases. Only
disposable databases were used; the development database was untouched. No fresh
frontend/Python/browser or actual expiration/pruning result is claimed.

### Canonical linked membership (checkpoint 5, incomplete)

V926 lets identity-ready vehicles read linked trip membership using original ledger
timestamps, including active/provisional and not-yet-verified final trips. Missing
or incomplete identity checkpoints keep raw-linked membership. Independently
verified final trips retain their separate retained-membership path. The three
paths are disjoint and all honor the existing publication-visibility fence.

Handoff does not itself alter membership UUIDs, summaries or generation, and does
not make an unverified trip safe to prune. The migration changes a view definition,
not stored rows or archive readiness. Raw FKs and original-data write checks remain;
default whole-vehicle and unverified payload reads are not made raw-free. Retention
remains disabled/dry-run, with no expiry cutoff. See
[ADR 0021](docs/adr/0021-canonical-linked-trip-evidence.md) for scope and limits.

Latest backend verification: **355 tests passed**, zero failures/errors/skips,
including six new membership/source/publication cases, all 143 migration cases
and all 16 retained-evidence cases. Only disposable databases were used; the
development database was untouched. No fresh frontend/Python/browser, actual
expiration or successful-pruning result is claimed for this backend-only step.

### Durable work-vehicle ownership (checkpoint 5, incomplete)

V927 adds a composite database reference binding each new stored work item's
vehicle and reading UUID to the same canonical ledger row. Cross-vehicle inserts
and vehicle-only/reading-only reassignment are rejected. Original raw FKs and
exact-data write checks remain unchanged.

The supporting unique index scans existing ledger UUID keys, and the migration
takes normal DDL/index locks. The work constraint is NOT VALID: no historical
work scan or automatic repair is performed, and legacy mismatches are not declared
valid. Explicit validation needs a separate rollout plan. No pruning or availability
change is added; retention stays disabled/dry-run. See
[ADR 0022](docs/adr/0022-durable-work-vehicle-reference.md) for rollout/conflict limits.

Latest backend verification: **361 tests passed**, zero failures/errors/skips,
including six new ownership cases and all 149 migration cases. Verification used
disposable databases only; the development database was untouched. No new frontend,
Python, browser, historical-owner validation or actual pruning result is claimed.

### Trip-link vehicle ownership (checkpoint 5, incomplete)

V928 checks reading/trip vehicle agreement for new links and retargeting, including
trip-ID-only changes. Retargets still require exact original raw data. A trip's
vehicle cannot later be reassigned; other provisional changes keep their existing
rules. Final-trip/link immutability keeps its original precedence and errors.

Migration changes trigger definitions without scanning or repairing historical
links. Wrong-owner link proposals fail even on conflict retries; no old reference
is declared safe for pruning. Raw FKs and existing guards remain. Correction-link
ownership is still a separate remaining item. See
[ADR 0023](docs/adr/0023-trip-link-vehicle-ownership.md) for lock order and limits.
Retention remains disabled/dry-run and availability is unchanged.

Latest backend verification for trip-link ownership: **367 tests passed**, zero
failures/errors/skips, including six new guard/lock-order cases and all 155
migration cases. Only disposable databases were used; the development database
was untouched. No new frontend/Python/browser, historical-link certification or
actual expiration/pruning result is claimed.

### Correction-reference vehicle ownership (checkpoint 5, incomplete)

V929 checks correction-reading vehicle agreement with its plan, including parent-
only retargets that must still match exact original raw data. It locks plan, replay
and segment before raw verification and makes that parent ownership path stable.
Normal progress, invalidation and publication continue under their existing rules.

These are future-write safeguards, not a historical scan/repair or pruning permit.
Original raw FKs, immutable guards and publication visibility remain. No route,
availability cutoff or pruning job is added; retention stays disabled/dry-run.
See [ADR 0024](docs/adr/0024-correction-reference-vehicle-ownership.md) for scope,
parent lock order and remaining limitations.

Latest backend verification: **373 tests passed**, zero failures/errors/skips,
across 23 suites, including six new correction-ownership cases and all 161
migration cases. Only disposable databases were used; the development database
was untouched. No historical-reference certification or actual pruning is claimed.

### Legacy-reference diagnostic (checkpoint 5, incomplete)

`TripReferenceAudit.readPage(kind, after, limit)` explicitly checks bounded pages
of existing work, trip and correction references for missing raw/ledger targets,
changed original data and wrong-vehicle ownership. It is an internal read-only
diagnostic, not an automatic job, repair, FK validator or pruning permit. Global
primary-key traversal avoids trusting a possibly incorrect stored vehicle owner.
Page size is 1..500; each page has its own snapshot, not a durable history-wide
certificate. See [ADR 0025](docs/adr/0025-bounded-legacy-reference-audit.md).

Latest backend verification: **378 tests passed**, zero failures/errors/skips,
across 23 suites, including five new diagnostic cases and all 166 migration cases.
Only disposable databases were used. No pruning or historical certification is
claimed; retention remains disabled/dry-run.

### Selected-reference retention inspection (checkpoint 5, incomplete)

The explicit barrier inspection now reports work/trip ownership and retained-link
agreement for its selected raw rows. Any correction reference remains a blocker,
including inactive replay history. V930 adds a reading-first correction-reference
index; it does not validate or repair old data. Checks run while existing raw pins
are held, but locks end before return: results never authorize a later deletion.
All raw FKs and hard prerequisites remain; retention stays disabled/dry-run with
wouldDelete=0. See [ADR 0026](docs/adr/0026-retention-selected-reference-inspection.md).

The barrier also pins existing selected work/link rows and their trip parents
before raw, using non-waiting shared locks. Contention returns BUSY and releases
earlier locks; lookahead and unrelated parents are not pinned. Existing reference
updates cannot commit during inspection, but can resume immediately after return.
After raw acquisition, a fresh count check also rejects references inserted into
a previously missing slot between the two pinning steps.
No new migration or deletion permission is introduced; schema remains V930.

Latest backend verification: **385 tests passed**, zero failures/errors/skips,
across 23 suites, including seven new reference/index/concurrency cases and all
168 migration cases. Only disposable databases were used; no actual pruning,
historical certification or development-database operation is claimed.

After selected-reference/parent pinning and the missing-reference race check,
the latest full backend run passed **390 tests**, zero failures/errors/skips,
across 23 suites, including all 168 migration and 40 retention integration cases.
Only disposable databases were used. No new migration, pruning or availability
change was introduced by this continuation.

## License

No license has been selected yet.
