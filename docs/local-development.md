# Local development

AutoPulse is a local simulator-backed prototype. Prefer the isolated tests/demos
for a first look: they create their own PostgreSQL containers and need no Compose
services. An interactive instance uses a separate database you intend to migrate.

## Prerequisites

- Python 3.12+; CI covers 3.12 and 3.14.
- Java 21 and the checked-in Maven wrapper.
- Docker Desktop/Engine with Linux containers running.
- Node 24.15+ in the 24.x line and npm 11.
- PowerShell (PowerShell 7 on Unix) for `scripts/verify.ps1`.

Commands use PowerShell from the repository root unless noted. On Unix use
`python3`, `.venv/bin/python`, `bash ./mvnw`, and an absolute installed Python
path for Java subprocesses. Never put database credentials in Vite variables;
frontend environment variables are public bundle configuration.

## Install and preview offline

```powershell
py -3.12 -m venv device-agent/.venv
./device-agent/.venv/Scripts/python.exe -m pip install -e "./device-agent[dev]"
./device-agent/.venv/Scripts/python.exe -m autopulse_agent.simulate
./device-agent/.venv/Scripts/python.exe -m autopulse_agent.simulate --scenario normal-commute
Push-Location web
npm ci
npm exec -- playwright install chromium
Pop-Location
```

The default preview prints three snapshots: engine off, cold idle, acceleration.
The six-phase commute produces 251 readings over 500 simulated seconds without
sleeping. Null sensors are unavailable, not zero. Normal runs use a fresh boot ID
and start time; explicit `--boot-id`/`--start-at` support controlled reproduction,
not reusing an identity for genuinely new measurements.

## Isolated demonstrations

Do not start Compose or a development backend for these tests. Docker must be
running. Each demo owns its random-port backend, database and temporary files.
The browser starts Vite on port 5174 and refuses to reuse another server.

```powershell
Push-Location server
./mvnw.cmd test "-Dtest=CareMilestoneDemo" "-Dautopulse.python=../device-agent/.venv/Scripts/python.exe"
./mvnw.cmd test "-Dtest=TripMilestoneDemo" "-Dautopulse.python=../device-agent/.venv/Scripts/python.exe"
./mvnw.cmd test "-Dtest=DashboardBrowserDemo" "-Dautopulse.python=../device-agent/.venv/Scripts/python.exe"
./mvnw.cmd test "-Dtest=OutboxTelemetryRecoveryDemo" "-Dautopulse.python=../device-agent/.venv/Scripts/python.exe"
Pop-Location
```

The Python path is relative to `server/` on Windows; use your actual environment
or an absolute path. Care exercises actual diagnostic delivery, committed-work
crash recovery, fault cycles, uncertainty, manual service retries and matching-only
completion. Trip covers boundaries, replay, charts and disposable retention.
Dashboard covers persisted SSE updates, reconnect, freshness and isolation.
The outbox demonstration exercises simulated offline collection, restart,
lost-response replay, local capacity pause and unchanged historical diagnostics.

Logs are under ignored `server/target/`; screenshots/traces are under ignored
`web/test-results/` and `web/playwright-report/`. Tests clean up their own
infrastructure and do not validate a physical vehicle.

## Interactive local instance

Backend startup applies pending Flyway migrations. Use a throwaway development
database when exploring; do not point new features at owner data or rewrite
applied migrations/reset volumes to work around errors.

Copy the example only if `.env` does not exist. Replace its placeholder password
locally and keep the file private; it is Git-ignored.

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
docker compose config -q
docker compose up -d
docker compose ps
```

Compose exposes PostgreSQL only at `127.0.0.1:5433`; database name/user are in
`compose.yaml`, password in `.env`. Do not share resolved credential-bearing
Compose output. The named volume retains data between ordinary stops.

In a backend terminal:

```powershell
Set-Location server
./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

The `local` profile explicitly and idempotently registers `venue-2021`,
`empty-vehicle`, `sim-device-001` and the primary trip policy after migrations;
conflicting existing identities are not overwritten. Default startup does not
seed these identities. Existing V900 migration history has explicit compatibility
handling: use application startup, not out-of-order/checksum workarounds.

API default: `127.0.0.1:8080`, using the ignored root `.env`. Optional backend
overrides: `DATABASE_URL`, `DATABASE_USER`, `SERVER_PORT`. Health endpoints are
`/actuator/health/liveness` and `/actuator/health/readiness`.

In a frontend terminal:

```powershell
Set-Location web
npm run dev
```

Open `http://127.0.0.1:5173`; default vehicle `venue-2021`. Use
`?vehicle=empty-vehicle` for the empty fixture. Public settings are documented in
`web/.env.example`. The Vite-only `AUTOPULSE_BACKEND_URL` proxy is loopback-only;
change it locally if you change the backend port. No broad CORS is configured.

### Send and replay

From a root terminal, with the backend running:

```powershell
./device-agent/.venv/Scripts/python.exe -m autopulse_agent.send --capture device-agent/.venv/demo-readings.jsonl
./device-agent/.venv/Scripts/python.exe -m autopulse_agent.send --replay device-agent/.venv/demo-readings.jsonl
```

Use a new filename if the capture exists. The normal run accepts three readings;
replay reports three duplicates and keeps original receipt times. Capture is
streaming JSONL, not a durable edge outbox or guaranteed crash resume. Never
reconstruct replay identities or exact decimals from rounded browser values.

For a human-paced 60-second synthetic commute (31 samples):

```powershell
./device-agent/.venv/Scripts/python.exe -m autopulse_agent.send --scenario normal-commute --phase-seconds 4 8 16 20 8 4 --sample-interval 2 --interval 2 --batch-size 1 --capture device-agent/.venv/live-commute.jsonl
```

`--sample-interval` advances measurement time; `--interval` controls delivery
pacing. Keep them aligned for a live demonstration. Accelerating delivery from
the current start can create future samples and a legitimate clock warning.
Heartbeats do not renew measurement age. Synthetic values are not verified
Hyundai characteristics. The ordinary CLI sends telemetry, not physical diagnostics.

The optional device-local outbox supports separate collection and recovery
modes for simulated telemetry and diagnostics. Follow the [outbox operations
guide](device-outbox-operations.md) for a local path, queue status, blocked work,
limits and the exact tested durability boundary.

### Processing and safety defaults

Trip, diagnostic and maintenance workers are **off by default**. Accepted data
can remain PENDING until explicitly processed, so a normal local server does not
automatically project trips/events/advisories. Navigation does not enable workers.
The isolated demos control only their own disposable instances. Review the
operations docs and choose an intended data scope before enabling a worker; do
not copy test flags onto owner data as a shortcut.

Retention is off, dry-run is on, and owned aggregate reads are off. No automatic
diagnostic/service deletion or public ECU-control endpoint exists. Raw expiry,
retained evidence and compact storage are distinct; full canonical replay data
currently remains stored.

- [Trip/retention operations](trip-processing-operations.md)
- [Diagnostics/maintenance operations](maintenance-operations.md)
- [API contract](../contracts/openapi.yaml)
- [Architecture decisions](adr/)

Stop backend/Vite with Ctrl+C, then `docker compose down`. The named volume
preserves the database. Volume removal deletes that data and is not part of the
normal shutdown workflow.

## Verify a change

```powershell
./scripts/verify.ps1 -Python ./device-agent/.venv/Scripts/python.exe -Browser
```

This runs Python/contracts, real PostgreSQL integration tests, actual HTTP schema
checks, optional integrated browser demos, frontend typecheck/lint/tests/build.
It fails on missing requirements rather than substituting the development database.
Hosted CI checks Python 3.12/3.14. Offline Maven is optional only after dependencies
are cached. For a focused regression, run `./mvnw.cmd test -Dtest=DiagnosticRecoveryTests`
from `server/`. See [verification](milestone-verification.md) for actual results.

Keep `.env`, personal notes, captures, environments, reports and browser storage
private. This unauthenticated loopback setup is not a deployment recipe. Do not
expose it to the internet or use the dashboard while driving.
