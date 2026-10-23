# Maintenance and advisory operations (checkpoint 3)

This is a local/private backend feature, not authentication, a diagnostic sender,
an ECU integration or the checkpoint-4 user interface. Review
[ADR 0031](adr/0031-maintenance-records-and-evidence-backed-advisories.md) and
[OpenAPI](../contracts/openapi.yaml) before changing stored evidence.

## Safety and verification

V933 is additive and has been exercised only in disposable test databases. Normal
application startup may apply pending Flyway migrations: do not start it against
the owner's existing database to test this feature. Do not rewrite applied
migrations, clean/reset databases, change Docker storage or enable deletion.

All saved switches stay safe: trip processing, diagnostic processing, maintenance
processing, retention and owned aggregates are false; retention dry-run is true.
The new `autopulse.maintenance.processing-enabled=true` is an explicit opt-in for
an authorized isolated instance only, not permission to process owner data.
No service/diagnostic/advisory retention or deletion job exists.

From `server/`, with Docker healthy and development services stopped:

```powershell
.\mvnw.cmd test '-Dtest=MaintenanceRuleTests,MaintenanceApiTests,MaintenanceMigrationTests,MaintenanceStartupTests'
..\device-agent\.venv312\Scripts\python.exe ..\scripts\verify_maintenance_http.py
```

The tests create their own PostgreSQL containers. The schema bridge reads actual
HTTP output in ignored `server/target/maintenance-http-examples.json`, not manual
examples. The full `scripts/verify.ps1 -Python <python-path> -Browser` workflow
also retains existing diagnostic/live/trip/retention/numeric regressions. A local
pass is not hosted CI or a diagnostic/maintenance end-to-end browser demonstration.

## Write commands and corrections

Every mutation is one strict JSON command of at most 8192 UTF-8 bytes, with a new
lowercase UUID `requestId` and native integer `expectedRevision`. Creation expects
0. Reuse the identical request ID/body/target after an ambiguous network result;
the original receipt is returned. Do not generate another ID merely because the
first response was lost. The command ledger binds operation and target too.
Different content with an existing ID conflicts; stale revision returns 409.
Read the latest detail before deciding on a genuinely new revision/action.

GET/PUT `service-profile` uses explicit applicability, usageSchedule, distanceUnit,
timeZone and normalOilSpecificationConfirmed. Missing profile is null/revision0.
U.S. vehicle confirmation does not select normal versus severe use or confirm oil
specification. No profile is inferred from simulation, and no initial odometer or
factory service is invented.

POST `maintenance-records` creates kind ODOMETER or SERVICE. SERVICE supports only
OIL_FILTER; ODOMETER has serviceType null. Include UTC-microsecond observedAt,
odometerValue decimal string, odometerUnit MI/KM, nullable notes and null
correctionReason. Records are manual, never estimated trip distance. A value such
as `"12345.001"` is a string intentionally; exponent notation, negatives and more
than three fractional digits reject. Unit conversion is decimal, not binary float.

PATCH `maintenance-records/{entryId}` supplies a complete replacement revision
with a nonempty correctionReason; kind cannot change. Old revisions remain.
Future observations reject. Neighboring effective readings must be nondecreasing
chronologically, including service odometers; equal instants require equal converted
values. A plausible old service need not be the last inserted record. It cannot
roll back the current odometer. To correct inconsistent neighboring records, plan
a chronology-consistent order rather than bypassing constraints.

All paths are under `/api/v1/vehicles/{vehicleId}`. Wrong-vehicle resources return
404 indistinguishably from unknown. This is resource isolation, not access control.
Notes are bounded plain text; do not include credentials or private adapter logs.

## Processing and uncertainty

New manual commands freeze accepted input revisions in durable work. Later edits
cannot erase the earlier basis during worker downtime. New confirmed-diagnostic
transitions and simulated telemetry identities also enqueue transactionally. There
is no historical migration backfill, SSE subscription or browser dependency.

The managed opt-in worker starts after application readiness, processes at most
one queued item per tick and stops on context close. Each page has a ten-second
transaction limit. A vehicle lock coordinates commands and projection. The oldest
pending work must finish first; a future timestamp/backoff cannot be overtaken.
Calendar oil reevaluation uses current inputs after queued work drains and is
scheduled no more frequently than once per minute for manually configured vehicles.

Page failure rolls back the decision, recommendation and progress. A separate
sanitized failure record sets a 60-second retry delay; three failures produce
REVIEW. That vehicle stops projection, while other eligible vehicles can proceed.
REVIEW is not healthy, successful completion or permission to delete a work item.
Investigate the cause and plan a controlled authorized retry; no public repair
endpoint or automatic review release exists. Do not edit immutable evidence or
blindly flip status flags. Frozen work/decision/advisory evidence has no dependency
on the survival of raw telemetry rows.

`processingState` CURRENT/PENDING/REVIEW describes work, not vehicle health. Lists
default20/max100, with resource/vehicle/limit/generation-bound signed cursors.
Restart from the first page on PAGE_CHANGED; process restart invalidates signing
keys and cursors expire after 15 minutes. Detail returns newest100 revisions and
`hasEarlierRevisions`; earlier evidence remains stored, but there is not a full
revision-pagination endpoint yet. Checkpoint4's browser keeps native revision/count
identities losslessly, including values above JavaScript's safe-integer range.

## Rules and workflow are separate

Oil assessment is DUE, NOT_DUE or UNKNOWN, with pinned manual provenance and
selected normal/severe schedule. Missing applicability, schedule, oil confirmation
or service history is UNKNOWN. A manual odometer older than30 days cannot establish
NOT_DUE. An independently established calendar/distance boundary can still establish
DUE. Timezone-local dates and clamped month ends control calendar comparisons.
The manual's native-unit intervals are not falsely exact conversions of each other.

CONFIRMED-code advisories refer to diagnostic event cycles, including unknown
definitions without component guesses. Temperature advisories are SIMULATED under
synthetic thresholds only, not a Hyundai overheating limit. Null/stale/future/gapped
evidence cannot fabricate recovery. Inspect currentObservation freshness separately
from the frozen generation evidence and workflow state.

OPEN can become ACKNOWLEDGED, DISMISSED or COMPLETED; ACKNOWLEDGED can become
DISMISSED or COMPLETED. PATCH recommendations requires requestId/expectedRevision.
User completion is workflow-only for observation advisories. Oil completion needs
a matching service record; an oil record cannot resolve a DTC or temperature
condition. System EXPIRED means changed supporting evidence/applicability, not a
repair claim. Terminal history stays terminal. Repeated input/refresh/restart does
not reopen the same continuing condition; genuine recurrence has a new cycle.
There are no ECU-clear/reset controls or safe-to-drive guarantees.

## Local dashboard workflow (checkpoint 4)

The existing hash navigation now includes Diagnostics (`#/diagnostics`) and
Maintenance (`#/maintenance`), with linked recommendations (`#/recommendations`).
These use the configured VITE_VEHICLE_ID, not a new vehicle registry or router.
Diagnostic details show the ECU/category/cycle, per-cycle positive count,
first/last-positive/applied times, complete versus incomplete reads, independent
MIL outcome, definition provenance/unknown fallback and up to100 transitions.
The latest accepted capture is explicitly not complete all-device coverage.
Browser measurement age advances without polling; a refresh is not a new reading.

Maintenance requires explicit manual applicability and normal/severe selection.
Enter completed oil/filter service and actual odometer readings with UTC times;
trip-distance estimates never populate the odometer. The backend validates the
entire chronological result. Follow a record link to review the newest100 revisions
and append a correction with a reason. The record kind cannot change. Earlier
history is retained but not fully paginated in this UI. Profile editing uses the
latest revision; there is not a profile-history browsing API/view.

The advisory view separates the frozen rule basis from current observation
freshness and workflow state. Actions are explicitly named acknowledge, dismiss
(not repair), or complete workflow (not repair). Oil has no manual completion
button; record the actual matching service instead. Simulated diagnostic/thermal
bases remain labeled, including in detail/action views. There are no new charts;
evidence and history are accessible structured text, so no chart-only data exists.

Every write stores its exact body/requestId in this browser tab's session storage
before sending. After an ambiguous timeout/network/malformed receipt, fields lock
and **Retry identical submission** resends the same command, even after navigation
or reload. This is not automatic replay and is not the device's durable edge outbox.
Do not clear session storage, close the tab or copy a pending command into another
tab to work around a missing acknowledgment: first reconcile the existing record.
Tab close/storage clearing can lose that identity; no cross-browser recovery is
claimed. Manual notes may remain in that tab's pending command until a confirmed
receipt or definite rejection. Storage being unavailable prevents a new write.
409 conflicts ask for refresh/review instead of silently overwriting another change.
If a retry returns a definite conflict, refresh and inspect current history before
making a genuinely new command.

This remains loopback-only, unauthenticated local development software. Manual
record writes do not authorize ECU commands. Production authentication, access
controls and hardware validation remain separate work. Saved processing/retention
defaults are unchanged; UI navigation does not start workers or migrate a database.
The explicit `CareBrowserDemo` opts workers in only against its new Testcontainers
database; never copy those test overrides onto owner data merely to try the UI.

## Actual simulator/crash/replay demonstration (checkpoint 5)

`CareMilestoneDemo` owns one new PostgreSQL container and invokes
`scripts/demo_care.py` with an explicit loopback URL and JUnit-temporary capture.
There is no production HTTP test-control route. The Python helper collects actual
source observations, preserves their identities, uses the production diagnostic
transport/telemetry batch client, and never accesses the database. Bounded staged
test orchestration invokes the existing processors with every saved worker flag
still off. A separate accepting child JVM is killed after committed pending work;
the restart proof compares original rows/receipts, not an in-memory continuation.

The diagnostic HTTP sender encodes one immutable model once, retries only transient
connection/408/429/5xx or unconfirmed/malformed receipts, and never recollects on
retry. Default3 attempts,5s request timeout (maximum30s),4096-byte acknowledgment
bound, strict receipt shape/UUID/time/duplicate-key rejection. Permanent rejection
such as400/409 stops. HTTP is independent of telemetry batch identity. This is
not a durable queue, polling scheduler, generic hardware command client or a
physical-source implementation; a process crash can still lose uncaptured source
observations. The ordinary CLI has not gained hardware/diagnostic polling.

The fixture includes null34s, an intentionally stale/older low, a receiver gap
before the first low68s and eventual qualifying low70s. The stale control is also
older than the scope watermark; it proves non-recovery but does not independently
isolate clock freshness from ordering. Existing pure rule tests isolate those
boundaries. Intermediate thermal samples36..66s arrive later and are historical-
only, never used to bridge the gap. Final replay preserves stored receipts,
snapshots, transitions, rule decisions and recommendation history exactly.

Summary times use UTC. Structured raw rule evidence intentionally preserves native
explicit timezone offsets rather than rewriting the forensic values for display.
A COMPLETE empty category can provide scoped absence even when another category
or MIL makes the ECU overall PARTIAL; the UI now names both levels accurately.
This never establishes all-ECU coverage, repair or vehicle health. Full run commands,
actual timestamps/counts and screenshots are in milestone-verification.md.
