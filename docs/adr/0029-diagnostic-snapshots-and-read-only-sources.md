# ADR 0029: diagnostic snapshots, completeness and simulated source capabilities

Date: 2026-10-22. Status: checkpoint 1 approved by the owner before checkpoint 2.

The implementation boundary below records checkpoint 1 historically. Durable
processing, implemented HTTP routes and catalog decisions now follow [ADR 0030](0030-durable-diagnostic-history-and-catalog.md).

## Scope and separation

A snapshot is immutable evidence of one device poll, not a confirmed mechanical
diagnosis. A diagnostic event is a derived, vehicle/device/source/ECU/category/code
observation cycle. A recommendation is a separate versioned advisory/workflow;
acknowledgment, dismissal or service entry never clears a code or changes an ECU.
This ADR supersedes the blueprint's overloaded pending/active/historical/resolved
code labels: category and observation state must be independent. No persistence,
event processor, catalog, recommendation engine, physical adapter or new HTTP
handler is implemented at checkpoint 1. Proposed routes are marked planned.

## Snapshot v1

One POST /api/v1/device/diagnostic-snapshots submits at most 65536 UTF-8 bytes.
Fields are snapshotVersion=1, deviceId, vehicleId, bootId, diagnosticSequence,
capturedAt, source and ecus. Identity is (internal registered device UUID, boot UUID,
diagnosticSequence), in a namespace independent from telemetry sequence numbers.
Sequence is a JSON integer 0..9223372036854775807. A boot restart uses a new UUID;
reconnect/retry preserves an existing identity and payload. Capture time is not
receipt time: receivedAt is assigned once by the future accepting transaction,
returned to the caller and preserved on duplicate replay. Inbound receivedAt is
an unknown field and invalid. Full UTC microseconds are preserved, not Date-rounded.

IDs are nonempty bounded ASCII identifiers, UUID text is canonical lowercase, and
capturedAt is a valid four-digit UTC calendar timestamp with 0..6 fractional
digits and uppercase Z. Offset timestamps must be normalized by the caller before
encoding. Canonical comparison normalizes the captured instant/UUID and schema
field order; list order is canonical: ECU IDs sorted, categories exactly PENDING,
CONFIRMED, PERMANENT, code strings sorted/unique. Unknown keys, duplicate JSON
object keys, NaN, floats/bools masquerading as sequence/version, invalid dates,
unknown enums and over-limit content are rejected, not silently discarded.

SIMULATED and future OBD remain separate sources. Acceptance must verify the
device's vehicle association; different devices/sources do not implicitly resolve
each other's observations. Each snapshot contains 1..8 unique ECU observations,
with stable bounded logical IDs (no raw command/address passthrough). Each ECU
has its own outcome and MIL read, and exactly the three category reads. Each
category has 0..32 unique uppercase five-character P/B/C/U + [0-3] + three hex
code strings. Unknown/manufacturer-specific codes remain evidence; a matching
syntax does not establish a verified catalog definition. No copied catalog or
component diagnosis is introduced here.

Read outcomes are COMPLETE, PARTIAL, UNSUPPORTED, UNAVAILABLE, TIMEOUT and FAILED.
COMPLETE means successful, complete coverage for the stated ECU/category;
COMPLETE plus [] is actual observed absence. PARTIAL may contain positive code
evidence but an omitted code is NOT absence. All other category outcomes carry
[] and cannot establish absence. MIL is independently COMPLETE + boolean, or
a non-PARTIAL failure/unsupported outcome + null. MIL off is not code absence.
An ECU is COMPLETE iff all its category reads and MIL read are COMPLETE. PARTIAL
is an aggregate incomplete result (including mixed failures); any other ECU
outcome requires that same outcome on all its constituent reads. A wholly
unsupported simulated source explicitly returns UNSUPPORTED, not an empty success.

Only these structured fields are accepted: no notes, arbitrary adapter output,
credentials, raw frames, freeze-frame blobs or unbounded exception messages. This
keeps evidence bounded/sanitized. Later rules must freeze specific permitted
evidence rather than silently adding free-form logs to this contract.

Future diagnostic snapshots/event transitions are independent from routine raw
telemetry expiry; no diagnostic deletion is introduced. Later recommendations must
freeze the required source/rule evidence before referenced raw data can expire.

## Time, ties, replay and planned event semantics

Future capturedAt more than 120 seconds after original receipt is invalid. An
accepted slightly future poll cannot establish absence before its capture time.
Absence is eligible only when its category is COMPLETE, capture is not future,
receipt minus capture is <=300 seconds, and its canonical key strictly exceeds
that scope's applied observation watermark. A complete old capture is historical
evidence, not current health. These are project policy defaults, not automotive
thresholds. Pure eligibility helpers use explicit clocks; no real-time sleeps.

Order by (capturedAt microseconds, boot UUID unsigned numeric order,
diagnosticSequence); sequence is not global chronology. The category watermark
advances for newer failed/partial observations too, preventing an older successful
empty poll from overwriting a newer unknown read. Exact ties need the native UUID
and integer values, never JavaScript numbers. Late unique snapshots are retained
but do not change already-applied current event state/counts; record why they were
historical-only. No automatic unbounded event rebuild is promised. Duplicate
identity and canonical content returns DUPLICATE with original snapshotId/receipt;
different content under the same identity is 409 IDENTITY_CONFLICT. Neither is
another observation. Rejected/rolled-back snapshots create no work/events.

Planned event states are PRESENT and NOT_OBSERVED, scoped separately by category.
A new qualifying code presence starts a cycle; further timely positive snapshots
increment that cycle's positiveObservationCount once each. Complete qualifying
absence closes the observation cycle as NOT_OBSERVED. Missing/stale/failed input
does not prove absence: preserve prior evidence with an explicit unknown/stale
current read indicator. Recurrence after qualified absence starts a new cycle;
boot restart alone does not. For PERMANENT, NOT_OBSERVED means only that the code
was not reported in a complete read; never label it cleared, repaired or a generic
historical category. First/last positive times, category watermark, transitions
and originating snapshot IDs must remain inspectable in checkpoint 2.

The deterministic fixture's future diagnostic advisory rule uses confirmed-code
cycles only: pending/permanent observations do not multiply a confirmed advisory.
Unknown definitions still get cautious observed-code evidence. Each confirmed
code cycle yields one recommendation; repeat/replay/boot restart does not create
another. Recommendation workflow state is not event observation state. This is
an expected fixture contract, not an implemented rule or verified DTC catalog.

## Proposed read/query contract (not implemented yet)

POST returns 200 DiagnosticSnapshotReceipt for ACCEPTED or DUPLICATE. Use shared
ApiError/correlation IDs: 400 INVALID_REQUEST, 404 UNKNOWN_DEVICE/UNKNOWN_VEHICLE,
409 DEVICE_VEHICLE_MISMATCH/IDENTITY_CONFLICT, 413 REQUEST_TOO_LARGE (65536 bytes),
415 UNSUPPORTED_MEDIA_TYPE, 503 SERVICE_UNAVAILABLE with no acknowledged work loss.
The eventual transaction must persist accepted evidence and durable required work
atomically. Snapshot acceptance does not imply derived events are ready.

GET /api/v1/vehicles/{vehicleId}/diagnostics lists event cycles, not raw snapshots:
default limit20, max100, descending lastObservedAt/eventId; optional category and
observationState filters, opaque <=2048-character cursor bound to vehicle, filters,
limit, generation and TTL15 minutes. Changed generation returns PAGE_CHANGED;
bad/mismatched/expired cursor returns INVALID_CURSOR. CURRENT/PENDING/REVIEW
processing state is explicit, not proof of capture completeness. Known empty is
200 items[], unknown vehicle is 404. Detail is consistently vehicle-scoped at
/api/v1/vehicles/{vehicleId}/diagnostics/{eventId}; wrong-vehicle/unknown event is
404 UNKNOWN_DIAGNOSTIC_EVENT. Response views include source/category, cycle/count,
positive timestamps, current read outcome/freshness, observation state, bounded
snapshot provenance and verified/unknown definition metadata. Event identity is
stable within a cycle. URLs are not authorization: local/private use only until
separate authentication/authorization work. Existing telemetry routes are unchanged.

## Minimal typed source and safety boundary

Add structural TelemetrySource/DiagnosticSource protocols and immutable typed
SourceCapabilities. Preserve capabilities()->set[str], existing CLI options,
snapshots and normal-commute defaults. Add source_capabilities() for explicit
telemetry fields, diagnostic support, ECU IDs/categories and SIMULATED provenance.
read_diagnostics has an independent counter/clock and never consumes a telemetry
reading. Reconnect does not reset sequence. A new boot is a new source instance.
Unsupported diagnostics still produce explicit structured UNSUPPORTED evidence.

The implemented logical allowlist contains only READ_CAPABILITIES,
READ_TELEMETRY and READ_DIAGNOSTICS. Unknown/control names are rejected and no
generic execute/raw-command API exists. Test clear-DTC, reset, coding, actuation,
security access, raw CAN and unknown-operation rejection. This is a simulated
interface guard, NOT proof of a physical adapter's safety. A future hardware
adapter must separately map supported, independently verified read-only discovery,
allowlisted telemetry and DTC/MIL queries; no command bytes/adapter driver are
introduced here. Do not infer physical support from a source enum.

## Hand fixtures and checkpoint boundary

diagnostic-contract-fixtures.json fixes baseline, continuing DTC/MIL evidence,
pending/confirmed/permanent, partial/unsupported/unavailable/failed/timeout reads,
qualifying absence, late input, recurrence, unknown code, reboot and exact replay.
Expected counts are stated before simulator/model implementation: 15 deliveries,
13 unique snapshots, two duplicates, five event cycles and three confirmed-code
advisory cycles. These event/advisory counts remain expectations for later
checkpoints, not executed database/rule results. Detailed positive counts and
transitions are in the fixture. Same-time tie and stale/future boundaries have
separate pure cases. Invalid fixtures distinguish JSON-schema and semantic checks.

synthetic-temperature-fixture.json specifies a separately opt-in 2-second
telemetry scenario: synthetic >=110 C for >=30 supported seconds, max gap5s,
recovery <=95 C for2s. One condition/advisory cycle opens at32s, null34s does not
mean recovery, later high input remains the same cycle, and supported recovery is
observed at70s. All values/thresholds are synthetic, NOT Hyundai specifications.
No DTC is inferred from temperature. The future recommendation remains an
advisory workflow, not a guaranteed safety/healthy conclusion.

Checkpoint 1 adds fixtures, validated immutable Python models/codec, source
protocols/capabilities and opt-in deterministic simulation only. No migration,
HTTP handler, event projector, catalog, service record or recommendation engine.
Review these contracts/outcomes before checkpoint 2. Keep private files excluded,
owner DB untouched and saved background processing/retention defaults unchanged.

## Checkpoint 5 follow-through (October 23)

The paragraphs above describe checkpoint1's original boundary and preimplementation
expectations, retained as decision history. Checkpoints2–4 subsequently add durable
projection, maintenance rules and views. Checkpoint5's CareMilestoneDemo now exercises
the actual Python simulator/strict model/new bounded diagnostic HTTP sender,
committed-work child-JVM termination/restart, replay and staged real UI. The hand
fixture's original EXPECTED ONLY labels remain authorship history, not a current
test result. Executed outcomes belong in milestone-verification.md, including any
unfinished checks. No physical source, ECU-command client, automatic diagnostic
polling CLI, durable outbox or deployment/authentication is implied.
