# 0012: Opt-in public ownership-aware aggregate history

Status: disabled-by-default public integration for unfiltered 1m/5m history, not
expiration, persisted-bucket reuse, trip archive publication or pruning. Builds on
[ADR 0011](0011-disjoint-whole-vehicle-history-ownership.md).
Verified full-bucket arithmetic reuse now follows in
[ADR 0013](0013-reuse-verified-whole-vehicle-contributions.md); expiration/pruning remain unfinished.

## Explicit route selection and one snapshot

`autopulse.history.owned-aggregates-enabled=false` preserves all current routes,
default cursor kinds and the 100,000 matching-reading aggregate cap. With true,
ONLY unfiltered 1m/5m requests use PublicOwnedHistory/OwnedVehicleHistory. Raw and
tripId-filtered requests keep the original reader. No query parameter overrides
this setting, and the local profile does not enable it.

The opt-in path preserves HistoryPage/AggregatePoint fields, no-store and processing
headers. Internal ownership diagnostics are not exposed. Its conservative budget
is 10,000 combined PHYSICAL raw/ledger candidates before filtering, not 100,000
matching observations: mirrored copies consume two candidates, but enter statistics
once. Archived members plus raw-owned late/secondary/confirmation readings remain
visible. This initial adapter recalculated archived values from ledger evidence;
ADR 0013 adds full-bucket contribution reuse while keeping exact edge calculations
and the existing metadata/equality checks.

PublicOwnedHistory opens one new read-only REPEATABLE READ transaction with a
10-second timeout. Vehicle resolution, publication gate, cursor checks, ownership,
returned generation and processing/availability metadata share that SAME snapshot.
It invokes a package-private in-snapshot reader helper, not another independently
committed snapshot. The helper rejects use without an active read-only REPEATABLE
READ transaction. The existing internal read() still opens its own correct snapshot.

A concurrent-ingestion test pauses after vehicle lookup establishes the snapshot,
commits another reading and resumes. Old points, old generation and CURRENT/
non-incomplete metadata remain coherent. Continuing that signed page against the
new generation returns 409 PAGE_CHANGED; a fresh page reports the new state.

## Authenticated boundary cursors

OwnedHistoryCursor uses HMAC-SHA256, a random process-local key, constant-time
signature comparison and canonical unpadded Base64URL. Its 142-character token
binds version, resolution-specific reader kind, vehicle UUID, normalized microsecond
from/to, limit, generation, aligned exclusive next-bucket boundary and issue time.
It contains no payload or raw row anchor. Expiry is 900 seconds; future issue times
are rejected. Negative-epoch microsecond bounds remain exact.

Signatures are checked before trusting fields. Changed filters/vehicle, raw/trip
contexts, malformed/noncanonical input, tampering, another process/key, negative
generation and invalid boundaries produce 400 INVALID_CURSOR. Changed vehicle
generation separately yields 409 PAGE_CHANGED. Reader kinds, keys and token lengths
are deliberately incompatible with the existing raw-row-anchored cursor. Switching
configuration/process requires restarting pagination; no silent fallback interprets
tokens under another ownership mode. Trusted internal cursor records are never
accepted directly from HTTP. This remains a single-instance cursor design, not
distributed key management or authentication.

## Truthful availability and explicit failure

No raw data has expired or been deleted. Successful availability therefore remains
rawState=AVAILABLE, rawUnavailableBefore=null, resolutions raw/1m/5m. incomplete
conservatively follows SAME-snapshot CURRENT/PENDING/REVIEW processing, not a claim
of complete hardware capture or supported sensor coverage. Ownership alone cannot
publish an expired cutoff or AGGREGATED_ONLY state.

HISTORY_OWNERSHIP_GAP, HISTORY_OWNERSHIP_MISMATCH and HISTORY_DENSITY_LIMIT become
explicit 503 ApiErrors with no points/partial success. Existing publication gating,
correlation and error handling remain. Raw/trip-filtered queries retain their
original semantics. OpenAPI documents both caps, mode-specific cursors and errors.

## Disposable checks and remaining boundary

Five cursor tests cover expiry, filters, canonical encoding/tampering, other keys,
raw-token cross-use, invalid positions/generations, future issues and pre-epoch
bounds. Nine opt-in HTTP/snapshot cases cover exact mixed-source/device points,
late input and truthful availability, signed paging, generation invalidation,
ownership handoff, unchanged raw/trip routes, gap/mismatch/density failures,
empty/unknown/invalid/five-minute queries and concurrent snapshot coherence.
The default-mode API suite adds a behavior check: an orphan ledger identity does
not alter raw-derived aggregate success with the flag false.

All databases are disposable Testcontainers. No owner DB is started, connected,
migrated or cleared. No new migration is added; schema remains V920. No guard/FK
is weakened and no final raw evidence deleted. The saved feature setting remains
false. Default regressions and internal reader tests remain enabled alongside the
opted-in cases.

With contribution reuse covered by ADR 0013, next are raw-independent legacy evidence/read/FK paths,
synchronized generation/availability publication and write-time replay/ownership
checks before crash-safe opt-in pruning. Actual expired availability, raw removal
and final-summary invariance afterward remain unverified. Public trip-archive
integration and checkpoint 6's real simulator-to-trip-browser/retention demo also
remain unfinished. Checkpoint 5 is incomplete; no storage savings are claimed.

The full offline backend regression passed 276 tests with zero failures/errors/skips
across 23 fresh suites. Fifteen new cases extend the previous 261. No fresh frontend,
Python, browser, hosted-CI, throughput or actual-pruning result is claimed here.
