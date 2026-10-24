# ADR 0031: manual maintenance history and three bounded advisory rules

Date: 2026-10-23. Status: checkpoint 3 complete and verified locally; owner approved
by authorizing checkpoint 4. The authorized live-stream shutdown repair also passes
the final full regression workflow.
Not an owner-data migration. See milestone-verification.md for exact results.

## Contract review before implementation

This slice adds one service type (OIL_FILTER), manual odometer observations,
revisioned service profile and recommendation actions. It does not add accounts,
vehicle CRUD, costs/providers, attachments, notifications or any ECU command.
All routes are vehicle-scoped under `/api/v1/vehicles/{vehicleId}`. Profiles use
GET/PUT `/service-profile`; entries use GET/POST `/maintenance-records` and
GET/PATCH `/maintenance-records/{entryId}`; recommendations use GET
`/recommendations` and GET/PATCH `/recommendations/{recommendationId}`.

JSON requests are strict bounded UTF-8 objects (8192 bytes), with requestId UUID
for every mutation and expectedRevision as an integer for concurrency. Creation
expects revision 0. A command ledger binds vehicle/requestId to operation/target
and exact canonical body; retry returns the original receipt even after later
changes, while reused IDs with different content conflict. Revision mismatch is
409. A vehicle lock serializes writes and projection. Append-only revision and
action history preserves prior evidence; there is no DELETE or silent overwrite.

Profile fields are applicability (UNCONFIRMED/US_2021_VENUE_G16), usageSchedule
(UNSET/NORMAL/SEVERE), distanceUnit (MI/KM), timeZone (IANA) and
normalOilSpecificationConfirmed boolean. Defaults are unknown/unset, never an
automatically inferred normal schedule. The owner confirmed U.S. market in chat;
no profile or odometer is silently installed in their database. Synthetic trips
are not the vehicle's odometer. Notes/correction reasons are bounded plain text.

Entries carry kind ODOMETER or SERVICE, serviceType null or OIL_FILTER,
observedAt (UTC microseconds), odometerValue (decimal string, nonnegative, at
most 7 integer/3 fractional digits), odometerUnit MI/KM, notes and correctionReason
(null on create, required on correction). Instant time avoids ambiguous same-day
ordering. Future timestamps reject. Effective observations must form a
nondecreasing chronological odometer history (equal instants require equal
converted values), checked against indexed adjacent observations under the vehicle
lock. Plausible backdated entries do not replace the latest reading. Corrections
can lower a mistaken current reading only if the entire resulting chronology is
consistent; otherwise correct neighboring entries in a consistent order. MI/KM
conversion uses exact decimal 1.609344. No estimated trip distance is included.

## Source verification and calendar policy

One production interval rule is pinned for U.S. 2021 Venue Smartstream G1.6.
Source: Hyundai Motor America, copyright 2020, 2021 Venue owner's manual,
engine p2-7, schedule/applicability p8-7, normal p8-8 and severe p8-11.
The manufacturer-authored PDF is available through this third-party mirror:
[manual source](https://d2l2hpow37r9fd.cloudfront.net/wp-content/uploads/2023/11/22134208/2021-Hyundai-VenueOM.pdf).
This is a primary manufacturer document, not a third-party interval summary;
mirror hosting is not Hyundai endorsement or a license to republish its manual.
Normal/severe tables and the oil-grade footnote were visually checked. Only
factual intervals and original cautious explanations are stored, not copied tables.

Normal OIL_FILTER: 8000 miles / 13000 km or 12 months. Severe: 5000 miles /
8000 km or 6 months (the 8000 km value is in the normal-table oil-grade footnote).
Use the native profile unit's printed interval, not a falsely exact conversion of
the manual's independently rounded equivalents. Normal requires confirmation of
the manual's oil specification; unset schedule/applicability or missing service
history is UNKNOWN, not up-to-date. No factory service at zero is invented.
The profile timezone converts service/as-of instants to dates, plusMonths clamps
month-end (including leap years); due is inclusive on either calendar or distance
boundary. Missing current manual odometer leaves distance UNKNOWN even if time
can independently establish DUE. Manual odometer older than 30 days is STALE and
cannot establish NOT_DUE. The UI must show per-input unknown/freshness/provenance.

## Durable, bounded recommendation processing

Three pinned rules only: oil-filter interval v1; CONFIRMED diagnostic event v1;
SIMULATED sustained-temperature v1. No generic engine or user-editable thresholds.
New rule versions require an explicit reviewed migration/replay policy, not silent
reinterpretation of old evidence. Recommendations retain immutable revision
evidence, rule/version, sources, times/units and semantic keys. Terminal user
actions never cause the same continuing condition to open a duplicate item.

Additive V933 queues new diagnostic transitions and new telemetry identities
transactionally, with no historical backfill or modification of applied migrations.
Manual writes also enqueue evaluation. Bounded explicit worker pages process one
accepted input basis at a time: profile/service/current-odometer revisions are
frozen with each manual command, so later entries cannot erase queued due/service
history during downtime. Calendar evaluation uses current inputs after the queue
drains. Each rule decision retains its exact basis and explicit evaluation clock.
Each queued item and checkpoint commit atomically; optional managed scheduling is off by
default. Existing telemetry identity ledger survives raw retention, and temperature
evidence/counters are frozen separately before raw rows can expire. No SSE reliance
or new diagnostic/service deletion. Failures leave queued work unchanged; tests
reconstruct workers/retry after removing injected failures. Error review/retry is
an operator concern, not a public repair endpoint.

CONFIRMED code positive transitions open one advisory per event cycle, not per
poll; qualifying NOT_OBSERVED expires an open/acknowledged advisory without claiming
repair. Dismiss/complete never changes diagnostic state. Diagnostic applicability
is observation-based, including unknown definitions without guesses.

Temperature is explicitly synthetic: >=110C for 30 supported seconds, adjacent
gap<=5s, recovery <=95C for 2s. Nulls/gaps break candidate/recovery continuity but
do not clear an established condition. Process exact individual values, not chart
means. Older/equal native device/source/capture/boot/sequence evidence is historical;
future or originally >300s stale evidence cannot trigger/recover. Source/device
scopes are independent, non-SIMULATED values never invoke the synthetic rule.
An open condition stays uncertain across null/stale input; recurrence after
qualified recovery creates a new cycle. Every rule decision freezes compact
identity/value/timestamp evidence independently of raw telemetry retention.

Oil due state has a durable condition cycle as well as a service/profile basis.
UNKNOWN cannot end an established due cycle. A qualified corrected NOT_DUE ends
it; genuinely reaching DUE again opens a new cycle, while user dismiss/complete
does not reset the condition and cannot generate another item on the next poll.

Recommendation states: OPEN -> ACKNOWLEDGED, DISMISSED, or COMPLETED;
ACKNOWLEDGED -> DISMISSED or COMPLETED. User completion is available only for
observation advisories and means workflow completion, never repair. OIL_FILTER
completion requires a matching new service record; completing that service changes
only matching service recommendations, not diagnostic/temperature items. System
EXPIRED means supporting condition/applicability changed, not healthy. Terminal
COMPLETED/DISMISSED/EXPIRED history cannot be reopened by refresh/replay; genuine
new service interval/diagnostic cycle/temperature recurrence has a new semantic key.
All actions require requestId and expectedRevision and preserve transition history.

## Verification boundary

Tests must use disposable PostgreSQL and actual HTTP for validation, retries,
concurrency, isolation, history/corrections, timezones/month ends, unknown inputs,
normal/severe selection, exact unit boundaries, rule semantic identity, interrupted
work, temperature fixture lifecycle and matching-only service completion. Shared
fixtures precede implementation. No UI is added until checkpoint 4; full actual
simulator/browser/JVM restart demonstration remains checkpoint 5.
