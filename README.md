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

The repository has a local live telemetry slice: Python offers snapshots and a complete synthetic commute; Spring Boot validates and persists bounded batches in PostgreSQL; React receives committed latest state over SSE without refresh. Multi-batch capture/replay, bounded stream resources, freshness states, local verification and CI configuration are included. The broader MVP remains future work.

No vehicle hardware is required during the simulator-based development phases.

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

The server reads the ignored root `.env` for `POSTGRES_PASSWORD`. Optional `DATABASE_URL`, `DATABASE_USER`, and `SERVER_PORT` overrides are supported. It binds to `127.0.0.1:8080` by default. The `local` profile registers `venue-2021`, `empty-vehicle`, and `sim-device-001`; fixture migrations are excluded from normal production migrations. Tests use a disposable PostgreSQL 17 Testcontainer, not your local database.

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
in the latter during review. Java uses 21 and the Maven wrapper. The local
workflow YAML/structure and PowerShell syntax are checked, but no successful
hosted run is claimed until its results are observed. Python 3.12 compatibility
is also checked in a separate ignored `device-agent/.venv312` environment when
available; verification results are recorded separately from workflow configuration.
The web CI job uses the Node version file, lockfile-based `npm ci`, explicit
Chromium/system dependency installation and a disposable Java/PostgreSQL browser
test. No push or external integration was performed. Action setup follows the
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
its four existing rows. CI also targets this opt-in demonstration with Python
3.12, but its hosted execution is still unverified.

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
or durable event replay is provided. Hosted CI is configured, not observed passing.

## License

No license has been selected yet.
