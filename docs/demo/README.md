# AutoPulse screen tour

[MP4: 36 seconds, silent with captions](autopulse-demo.mp4) ·
[Animated preview](autopulse-demo.gif) · [Still preview](preview.png)

## What this shows

Real React application captures from passing isolated browser demonstrations,
assembled into a captioned screen tour. This is **not** a continuous live recording,
physical OBD-II test, driving recording, benchmark or proof of production readiness.
Telemetry/diagnostic observations are SIMULATED. Manual records and selected
maintenance profiles are disposable demonstration fixtures, not owner information.
Screens come from independent test runs; timestamps are not a single timeline.

| Time | View / transcript |
| --- | --- |
| 00:00-00:03 | AutoPulse: simulator-first automotive telemetry. Python / Spring Boot / PostgreSQL / React. Real UI captures; simulated data. |
| 00:03-00:08 | Live telemetry: committed server updates, with measurement age shown separately. Missing sensors remain unavailable. |
| 00:08-00:13 | Trip evidence: one simulated eight-minute commute, 241 trip samples, estimated 6.5 km. Distance includes coverage; it is not the vehicle odometer. |
| 00:13-00:18 | Measurement history: persisted RPM, speed and temperature plotted in UTC. Bounded pages/gaps preserve uncertainty. |
| 00:18-00:23 | Diagnostic observations: continuing code cycles, partial reads and unknown definitions stay explicit. Observations are not mechanical diagnoses. |
| 00:23-00:28 | Maintenance context: manual inputs, explicitly selected schedules and versioned rule provenance. |
| 00:28-00:33 | Service history: manual records/corrections preserve history; matching service updates its reminder, not unrelated diagnostic events. |
| 00:33-00:36 | Hardware, durable edge buffering and deployment remain future work. No vehicle hardware connected; no ECU-control features. |

The visible PENDING state in a service-history capture is intentional: accepted
work and projected recommendations are separate. No labels or evidence were
removed to imply a healthier or more complete state.

## Provenance

Captured from the actual backend/PostgreSQL/UI path, not mocked API responses or
AI-generated interface images:

- `DashboardBrowserDemo` / `web/e2e/dashboard.spec.ts`: `desktop.png`, recaptured
  October 24, 2026, with persisted Python simulator readings and real SSE delivery.
- `TripMilestoneDemo` / `web/e2e/trip-milestone.spec.ts`:
  `real-trip-detail-desktop.png`, from the independent October 23 review run.
- `CareBrowserDemo` / `web/e2e/care.spec.ts`: `diagnostics-desktop.png` and
  `maintenance-desktop.png`, from the October 24 verification of the retry fix.

Only viewport crops, caption panels and video encoding are applied. Crops omit
off-screen content but do not alter displayed values. The trip input is the
deterministic simulator commute, not a measurement of the intended Hyundai.
The source files are ignored generated test output. Only the selected tour,
poster and its reproducible build helper are committed.

## Reproduce

Install the [development prerequisites](../local-development.md), including the
frontend/Chromium dependencies. Run the existing isolated Java browser demos with
your installed Python path. No owner database or Compose service is needed.

```powershell
Push-Location server
./mvnw.cmd test "-Dtest=TripMilestoneDemo" "-Dautopulse.python=../device-agent/.venv/Scripts/python.exe"
Pop-Location
```

Copy its `real-trip-detail-desktop.png` from the ignored `web/test-results/`
subdirectory to `server/target/readme-demo-sources/trip.png` before another demo
cleans that output directory. Create the cache directory if needed. Run
`CareBrowserDemo` and copy its ignored `web/playwright-report/care-artifacts/`
`diagnostics-desktop.png` and `maintenance-desktop.png` to `diagnostics.png` and
`maintenance.png` in the same cache. Run `DashboardBrowserDemo` and copy its
`web/test-results/` `desktop.png` capture to `live.png`. Use those exact source
filenames, not narrow captures or files containing personal data.

With FFmpeg and ffprobe installed on PATH, from the repository root:

```powershell
./device-agent/.venv/Scripts/python.exe scripts/build_demo.py --source-dir server/target/readme-demo-sources --overwrite
```

The helper uses Python's standard library. On Windows it uses the installed
Arial font; on Linux it tries DejaVu Sans. Supply `--font` with a local TrueType
path if needed. Font files are not distributed. The script validates screenshot
dimensions, refuses existing outputs without `--overwrite`, bounds child-process
execution, strips media metadata and writes only the named MP4/GIF/poster plus
temporary files under ignored `server/target/`.

If layout/capture dimensions change, review the explicit crop coordinates before
regenerating. Inspect the whole tour for clipped captions and misleading context;
do not publish a successful encode without visual review. FFmpeg is optional
presentation tooling, not an application dependency or CI prerequisite.
