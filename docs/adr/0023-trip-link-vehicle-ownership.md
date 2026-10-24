# ADR 0023: Stable trip vehicle ownership and checked link retargeting

Status: accepted future-write guard; checkpoint 5 remains incomplete.

## Decision and ordering

V928 checks a trip link's reading vehicle against the target trip's vehicle on
INSERT or UPDATE OF reading_id,trip_id. It reads the immutable canonical ledger
owner, not a request-supplied vehicle. The existing exact-original reference
trigger now also runs on trip_id-only retargets, capturing an exact missing legacy
ledger target or rejecting a changed/missing original as before.

Trigger order is intentional: existing trip_evidence_immutable first checks old/
new parents and holds FOR SHARE on the target trip; trip_link_replay_reference
then locks/verifies original raw data; trip_link_vehicle_owner finally compares
owners. The new guard adds no reversed blocking lock acquisition. A same-vehicle
provisional retarget remains possible, subject to all existing raw/final guards.

Trip vehicle_id is now immutable on UPDATE, including for non-final trips, so a
parent cannot subsequently move to another vehicle behind already checked links.
A no-op vehicle assignment remains allowed subject to existing final rules.
Other provisional metadata/source/status changes retain their existing rules.
Finalized-trip and final-link guards still run first and keep their original
rejections, including final-link no-op updates. No final association is rewritten.

## Scope and limits

This checks VEHICLE ownership, not new device/source/bound invariants. Internal
pending/trailing links remain possible; closed membership and exact retained-copy
provenance still use their existing checks. Correction-link ownership is not
strengthened in this migration. Raw/durable FKs, exact JSONB/original receipt
verification and immutable ledger/evidence/aggregate guards remain unchanged.

Unlike V927's AFTER INSERT work FK, this link owner guard runs BEFORE INSERT,
including proposals later subject to ON CONFLICT DO NOTHING. A wrong-vehicle link
proposal therefore fails even if its reading UUID already has a stored link.
Failure rolls back any new legacy ledger capture and the attempted link together.

Migration creates/replaces trigger definitions only, with normal DDL locks and
additional per-reference point lookups on writes. It scans/repairs/certifies no
historical link population and adds no ledger index or payload copy. Existing
invalid legacy links can survive upgrade unchanged; later touched non-final
links are checked, but immutable final associations are not repaired or bypassed.
No automatic audit/validation runner or zero-lock/throughput guarantee is added.

## Disposable checks and remaining work

Six new cases cover unchanged legacy cross-vehicle links during upgrade/restart;
wrong-owner inserts/conflict attempts with atomic legacy-capture rollback;
trip_id-only retarget capture and exact-original/owner rejection; immutable trip
vehicle with otherwise permitted provisional edits; unchanged final guard errors;
and a real PostgreSQL parent-lock wait that leaves raw unlocked until parent
acquisition, then succeeds after release.

Full offline backend regression passed 367 tests across 23 fresh suites, zero
failures/errors/skips, including all 155 migration cases. The six new focused
ownership/lock-order cases also passed separately. No production/test/migration
changes followed the full passing run. No new frontend/Python/browser, hosted CI,
write-throughput benchmark, historical-link certification, expiration or successful
pruning evidence is claimed.

No source routing, public API/view, history availability, saved flag or pruning
change is introduced. Retention stays disabled/dry-run with wouldDelete=0. Next:
remaining correction/reference ownership restrictions, complete same-transaction
deletion checks and atomic availability/generation publication with truthful
expired whole-vehicle history. Checkpoints 5 and 6 remain incomplete. Owner-data
deletion requires separate explicit approval of concrete targets and safeguards.
