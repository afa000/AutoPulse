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

The repository is in Sprint 0: foundation and contracts. The immediate goals are to establish the project structure, development tooling, local PostgreSQL environment, initial telemetry contract, and test runners.

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

Instructions for the device agent, backend, and web application will be added as those components are built.

## License

No license has been selected yet.
