# ADR 0024: Correction-reading vehicle ownership and stable parents

Status: accepted future-write guard; checkpoint 5 remains incomplete.

## Decision and lock order

V929 checks a correction reading's canonical vehicle against its plan's vehicle
on INSERT and UPDATE OF reading_id,replay_id,segment_id. Parent-only retargets
also run the unchanged exact-original raw capture/verification function; an
unchanged reading UUID no longer skips that check when its parent is changed.

Three ordered BEFORE triggers acquire parent barriers, verify original raw data,
then compare vehicles. The parent barrier first looks up the replay's plan ID,
then share-locks plan -> replay -> segment before raw verification. It rechecks
the replay/plan and segment/replay relationships after acquisition. Missing or
inconsistent parents fail before raw capture. This matches the stage worker's
parent acquisition order rather than introducing raw-before-parent waits.

Plan.vehicle_id, replay.plan_id and segment.replay_id are immutable on UPDATE,
so parents cannot later move the reference to another vehicle. No-op parent-key
assignments and otherwise allowed progress/status/invalidation/checkpoint/source
changes retain their existing rules. Existing parent FKs protect existence and
replay/segment pairing. This does not freeze all correction state or new plan
requests, device/source provenance, bounds or publication metadata.

## Exact originals, conflicts and historical limits

The final owner comparison uses immutable ledger identity after exact-original
verification. A wrong owner fails before INSERT, including conflict proposals.
Any legacy ledger capture and reference write roll back together. A valid
same-vehicle retarget can establish an exact missing legacy target; a mismatched
raw receipt/payload still rejects it. All original raw/durable FKs, immutable final/
ledger/evidence/aggregate guards and publication visibility gates remain.

Migration changes trigger/function definitions with normal DDL locks and extra
per-write parent point lookups/row locks. It does not scan, repair or certify
historical references/parents, rewrite payloads or build a new ledger index.
Invalid old references can survive upgrade; touching them then runs the checks.
No historical validation/repair runner, zero-lock or universal deadlock/throughput
guarantee is introduced. Arbitrary SQL that ignores normal stage lock ordering
is not newly certified safe.

## Disposable checks and remaining work

Six new cases cover unchanged legacy cross-vehicle references during upgrade;
wrong-owner inserts/conflict proposals and rollback of legacy identity capture;
parent-only retarget exactness and replay/segment mismatch rejection; immutable
parent ownership with ordinary progress preserved; an actual PostgreSQL plan-row
wait leaving raw unlocked until parent acquisition; and a real one-row replay/
publication preserving the hand-checked corrected summary and raw readings.

The complete offline backend suite passed: 373 tests across 23 suites, including
all 161 migration cases, with zero failures/errors/skips. Verification used only
disposable PostgreSQL databases; the development database was untouched. This
does not establish historical validity, production throughput or safe pruning.

No public source routing/schema contract, route, runner, saved flag, expiry cutoff,
availability publication or pruning changes. Retention stays disabled/dry-run and
wouldDelete=0. Next: remaining legacy-reference restrictions and complete same-
transaction deletion enforcement with atomic availability/generation publication
and truthful expired whole-vehicle history. Checkpoints 5 and 6 remain incomplete;
owner-data deletion needs separate explicit approval of concrete targets/safeguards.
