# ADR 0030: durable diagnostic history and a bounded verified catalog

Date: 2026-10-23. Status: checkpoint 2 approved by the owner by authorizing checkpoint 3.

## Acceptance and durable work

V932 is additive to V931; no applied migration is rewritten. It creates seven
diagnostic tables. Immutable snapshots retain canonical JSON and original receipt,
independent of raw telemetry and its retention. There is no diagnostic deletion job.
Snapshot acceptance and its work row/generation change share one transaction via
an insert trigger. Acknowledgment never depends on a callback or the SSE stream.
Device/boot/diagnostic-sequence is the identity: exact replay returns the original
receipt/ID; different content conflicts. Vehicle/device association is checked.

The server accepts one strict UTF-8 JSON object up to 65536 bytes, rejecting BOM,
duplicate keys, trailing data, unknown fields and invalid cross-field evidence.
Native int64 identities and microsecond UTC timestamps are preserved. Capture
more than 120 seconds beyond original receipt is rejected. Registry scoping is
not authentication; this remains a local/private application.

## Projection and lifecycle

Each transaction processes one ECU/category (at most 24 per snapshot), with a
10-second transaction timeout and a locked vehicle coordination row. Durable work
is ordered by capture time, native unsigned boot UUID and sequence, with device/ID
tie-breakers. Independent scopes are vehicle/device/source/ECU/category. Scope
watermarks use capture microseconds, unsigned boot UUID and diagnostic sequence.
Older/equal evidence is retained as HISTORICAL_ONLY without mutating current state.

COMPLETE or PARTIAL positives create one PRESENT cycle or add one positive
observation to its existing cycle. Duplicate deliveries never increment counts.
Only a strictly newer COMPLETE same-scope read, captured no later than original
receipt and no more than 300 seconds before it, may close missing codes as
NOT_OBSERVED. Partial/failed/unsupported reads and MIL off cannot establish absence.
Newer uncertain reads still advance the watermark. Stale or future evidence may
advance metadata with STALE_HISTORICAL/CLOCK_WARNING but cannot change events.
A later positive after qualifying absence creates a new cycle. NOT_OBSERVED is
not proof of repair, ECU clearing or safety; PERMANENT remains a separate category.

Every applied category records immutable provenance. Event transitions and
progress commit atomically; rollback leaves the page unprocessed. Fresh projector
instances resume from the database. PostgreSQL guards protect immutable evidence,
scope identity, event-cycle identity and monotonic page progress. A page permits
at most 100 active codes including the incoming union; exceeding this conservative
bound publishes no partial category and marks work REVIEW. Transient page failure
rolls back, then records a sanitized error and 60-second retry delay; three failures
mark REVIEW. Operators must inspect the cause before an explicit controlled retry;
there is no public retry/repair route or automatic forgiveness of review work.

The opt-in Spring-managed poll processes one page per second after application
readiness and stops on shutdown; no browser/new telemetry is required. Saved
`autopulse.diagnostics.processing-enabled=false` remains off. No owner-data
processing has been enabled. Tests enable the poll only in disposable databases.

## Query boundary

POST `/api/v1/device/diagnostic-snapshots`, vehicle-scoped GET
`/api/v1/vehicles/{vehicleId}/diagnostics`, and its `/{eventId}` detail are
implemented. Responses are no-store. List size defaults to 20, maximum 100, with
category/observation-state filters and signed 15-minute native-time/UUID keysets.
Cursor binding includes vehicle, filters and limit; acceptance or projection
changes generation and returns PAGE_CHANGED for an old cursor. Process restart
invalidates the in-memory signing key. Queries use bounded indexed limits and a
repeatable-read transaction; tests inspect an actual 1000-event query plan.

Processing state is CURRENT, PENDING or REVIEW, independent of an empty event
list. Latest snapshot means latest capture, not complete coverage of every scope.
Incomplete latest evidence reports UNKNOWN rather than a healthy result. Events
expose MIL read outcome/value, scope freshness, positive evidence and catalog
provenance. Detail returns the newest 100 transitions and explicitly reports
`hasEarlierTransitions`; the database retains earlier history but no full-history
pagination route is implemented. Native cycle/count/sequence numbers must be
decoded losslessly by the future diagnostic UI, like existing trip/history data.

## Catalog provenance and rights

`diagnostic/catalog-v1.json` is a pinned two-entry generic-code catalog, not a
downloaded proprietary database. P0117 and P0300 have original cautious summaries
based on these primary manufacturer documents hosted by NHTSA:

- [Audi A6/S6 quick reference, December 2012, printed page 179 / PDF page 191](https://static.nhtsa.gov/odi/tsbs/2012/MC-10243910-9999.pdf#page=191).
- [FCA STAR case S2018000014, December 12, 2020, page 1](https://static.nhtsa.gov/odi/tsbs/2020/MC-10187651-9999.pdf#page=1).

NHTSA hosting does not make these NHTSA-authored or public-domain documents.
Only AutoPulse's original paraphrases receive the supplied MIT license; cited
manufacturer text/tables and rights are not included in that grant. Generic meaning
does not establish Hyundai applicability, component failure, repair instructions,
thresholds or safe-to-drive advice. Version, source title/URL, scope and license are
returned by the API. Any code outside the verified subset (including P1999) remains
visible with UNKNOWN definition and no invented description. No runtime scraping
or network catalog lookup occurs.

## Proof and remaining work

Actual PostgreSQL/HTTP tests cover acceptance atomicity, replay/conflict, fixture
lifecycle/counts, independent scopes, late/partial evidence, time/UUID ties,
rollback, bounded retries/review, fresh-instance recovery, independent timer work,
query bounds, immutable evidence and fresh/931-upgrade migrations preserving old
rows/checksums. Actual HTTP list/detail bodies are validated against OpenAPI.
The timer test is not a separate-JVM crash demonstration: the full diagnostic
simulator-to-API-to-browser/restart exercise remains checkpoint 5. No diagnostic
UI, maintenance data, recommendation engine, physical driver, authentication or
diagnostic sender is claimed here. Exact executed counts are recorded separately
in milestone-verification.md; stop for owner review before checkpoint 3.
