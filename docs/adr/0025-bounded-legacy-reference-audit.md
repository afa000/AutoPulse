# ADR 0025: Bounded diagnostic for legacy reading references

Status: accepted diagnostic; checkpoint 5 remains incomplete.

## Why this is separate from write guards

V927-V929 protect future work/link/correction ownership writes. Their upgrades
deliberately do not certify existing references. ReplayIdentityAudit checks raw
rows against canonical identities, but does not independently traverse every
stored reference or compare its owner. Neither diagnostic permits pruning.

## Decision

TripReferenceAudit.readPage(kind, after, limit) is an explicit internal service,
with no HTTP route, startup invocation, scheduler, repair or constraint-validation
runner. Kind is the closed enum WORK, LINK or CORRECTION. Each call opens a new
read-only REPEATABLE READ transaction with a ten-second timeout, and visits at
most 500 reference rows. Invalid kinds, page sizes and mismatched cursor kinds
are rejected before opening a transaction.

Traversal is global, not vehicle-filtered: a legacy row's vehicle can itself be
wrong. WORK and LINK use their reading_id primary keys; CORRECTION uses its
(replay_id, reading_id) primary key, so the same reading in multiple replays is
not skipped. The kind-bound trusted cursor contains those keys, not a public or
signed token. A materialized candidate page bounds subsequent raw, ledger and
parent point lookups. Payloads remain in PostgreSQL; Java receives only IDs,
booleans and page counts. No table-wide payload scan or new index is added.

The page reports separate, potentially overlapping counts:

- rawMissing: referenced original raw row absent. Original raw FKs still prevent
  this in the supported current schema; reporting it does not relax those FKs.
- ledgerMissing: canonical identity absent for the referenced reading UUID.
- originalMismatch: both rows exist, but UUID, native device/boot/sequence,
  vehicle, measurement time, original receipt, source or JSONB payload differs.
  Missing rows are reported separately, not counted as originalMismatch.
- ownerMismatch: canonical vehicle cannot be shown equal to the work vehicle,
  linked trip vehicle or correction plan vehicle through the matching segment/
  replay relationship. Missing ledger identity also increments this count.

These are counts of reference rows, not distinct readings or vehicles. A reading
referenced in several places may contribute once per place. Zero counts only
mean these checks found no discrepancy in this page at its snapshot; they do
not establish valid trip boundaries, device/source provenance, aggregates,
publication state, retained membership, late-arrival closure or pruning readiness.

## Continuation and safety limits

Return lastCursor to a fresh instance for the next page of the same kind. A page
with fewer than limit rows is complete; an exactly full last page requires one
additional empty call. An empty page preserves its input boundary. complete means
only that this page exhausted that kind's key range in its own snapshot. There
is no saved completion marker, multi-page attestation or reusable deletion permit.
Concurrent inserts behind a previous cursor and changes between pages can escape
one traversal. Cursor values are caller-held, trusted boundaries, not durable
scan state or validation authorization.

The audit does not capture missing identities, update work/links/parents, advance
backfill or generations, validate NOT VALID FKs, scan raw-free ledger history,
remove raw references or change availability. Explicit backfill/rollout decisions
remain separate. Normal read locks, bounded point-lookup cost and timeout still
apply; no production throughput or physical-I/O benchmark is claimed.

## Verification and remaining work

Five disposable PostgreSQL cases cover empty kinds/input validation, missing
legacy references before and after explicit backfill, all three old wrong-owner
paths, changed original receipt independent of owner agreement, and composite
correction pagination across fresh service instances. State comparisons confirm
that diagnostics leave reference/raw/ledger/parent maps unchanged, and no FK
validation is performed.

The complete offline backend suite passed 378 tests across 23 fresh suites,
including all 166 migration cases, with zero failures/errors/skips. No production
or test code changed after that successful run. No new frontend/Python/browser,
hosted-CI, performance or actual pruning result is claimed.

Retention remains disabled/dry-run, wouldDelete=0, owned history remains opt-in
and disabled, and original raw FKs/immutable/exact-original guards remain. The
development database is not accessed. Full legacy rollout/validation restrictions,
same-transaction prune enforcement, atomic availability/generation publication,
truthful expired whole-vehicle history and a crash-safe bounded pruner remain.
Checkpoints 5 and 6 are incomplete; owner-data deletion requires separate explicit
approval of concrete targets and safeguards.
