# ADR 0026: Selected-reference inspection under retention barriers

Status: accepted inspection only; checkpoint 5 remains incomplete.

## Decision

Extend RetentionPreview.reportUnderBarrier with a separate referenceChecks list
alongside archiveChecks. The standalone TripReferenceAudit remains a global,
page-local diagnostic, not a deletion attestation. The barrier inspection instead
checks only the selected raw IDs in its existing transaction after processing,
policy, receipt and raw-row locks have been acquired. No deletion or permit is
introduced. The ordinary read-only report is unchanged except for an additional
hard prerequisite, WRITE_TIME_REFERENCE_RECHECK_REQUIRED.

Each ReferenceCheck reports readingId, workState, linkState and a conservative
correctionReference boolean. Results preserve selected page order and have exactly
one row per returned reading, never the lookahead or another vehicle's raw rows.
The list is copied on construction. Disabled/BUSY/empty inspections return no
reference checks; failures roll back and release all locks.

Work states distinguish RAW_MISSING, REPLAY_MISSING, MISSING, OWNER_MISMATCH,
NOT_PROCESSED and MATCHED. MATCHED requires canonical vehicle agreement and
PROCESSED status. Link states distinguish RAW_MISSING, REPLAY_MISSING, MISSING,
OWNER_MISMATCH, RETAINED_MEMBERSHIP_MISSING, RETAINED_OWNER_MISMATCH and MATCHED.
MATCHED requires the linked trip's vehicle to agree with canonical identity and
the link's trip UUID to agree with retained membership. This is not a substitute
for the separate exact-original/verified archive/provenance checks, policy
protections or eventual constraint transition.

Any correction-reading reference sets correctionReference=true, including
invalidated replays, discarded segments and wrong-owner legacy references.
Inactive does not mean unreferenced: no code removes or ignores those rows or
their original raw FK. Until a separately proven transition exists, correction-
referenced rows remain conservative blockers. A false boolean does not by itself
make a row deletable, and policyCandidates remains a preliminary count, not a
count passing all reference/archive checks.

## Bounded lookup and migration cost

The query uses a requested-ID values page of at most 100 rows, unique-key work/
link/retained/ledger lookups and EXISTS for correction references. It does not
expand each reading into every replay that mentions it or fetch payloads into
Java. V930 adds trip_correction_reading_lookup(reading_id,replay_id), complementing
the existing replay-first primary key. This builds an index over existing UUID
keys with normal index/DDL locks and future index maintenance cost. It is not a
zero-lock migration, historical validation/repair or a bounded index build.
No new payload scan or universal physical-query/throughput guarantee is claimed.

## Lock lifetime and limitations

The added statement runs at READ COMMITTED inside the existing barrier transaction,
after raw pinning and archive checks. It takes no extra parent row locks and does
not change the normal plan/replay/segment-before-raw write order. Exact-original
reference writers still need raw FOR SHARE and therefore cannot create a new
reference to a pinned raw row until the inspector releases its FOR UPDATE lock.
Contention while pinning raw still returns BUSY without partial results, preventing
the existing raw-writer/receipt-lock cycle.

All locks end before the method returns. A waiting reference writer can commit
immediately afterward; an old result is not a certificate for a later transaction.
Concurrent arbitrary SQL edits of existing references/parents or operational state
are not newly made safe by this observational check. A real pruner must enforce
all required invariants and reference/availability changes in its own synchronized
write transaction, not consume these returned observations as authorization.

Original raw/durable FKs, immutable guards, finality restrictions and exact-original
checks remain. No constraints are automatically validated, no parent/link/work is
repaired or removed, and no availability/generation publication or runner is added.
All hard prerequisites remain; wouldDelete=0 and retention stays disabled/dry-run.

## Disposable verification

Seven new cases cover V929-to-V930 upgrade/restart preserving references and raw
FK rejection; old wrong-owner work/trip/correction paths reported without repair;
verified ownership/page ordering and unchanged state; missing/review work and
uncopied membership; multiple inactive correction references without output
fanout; rollback release after an injected reference-query failure; and an actual
PostgreSQL reference writer waiting behind the raw pin, then becoming visible to
a fresh inspection after release. Existing contention/receipt-cycle, empty,
disabled, archive, ingestion and processing regressions remain enabled.

The complete offline backend regression passed 385 tests across 23 fresh suites,
with zero failures/errors/skips, including all 168 migration cases and 35 retention
integration cases. No production/test/migration code changed after the passing run.
No new frontend/Python/browser, hosted-CI or performance result is claimed.

The owner development database is not used. This does not demonstrate pruning,
historical reference certification, production query plans or storage savings.
Remaining work includes deliberate legacy rollout/validation restrictions, full
same-transaction prune enforcement, atomic availability/generation publication,
truthful expired whole-vehicle history and a crash-safe bounded opt-in pruner.
Checkpoints 5 and 6 remain incomplete. Owner-data deletion requires separate
explicit approval of concrete targets and safeguards.

## Continuation: pin selected existing references and trip parents

The original inspection pinned raw and thereby blocked new exact-raw reference
writes, but did not freeze an already-existing work/link row or its trip parent.
reportUnderBarrier now pins existing selected work rows FOR SHARE, then selected
link rows FOR SHARE, then their distinct trip parents FOR SHARE, before pinning
raw. Reference keys and parent UUIDs are acquired in deterministic order. Each
step uses SKIP LOCKED and compares locked count with matching-row count in the
same statement snapshot. Missing references remain reportable missing states;
an existing contended row instead returns BUSY with no partial page/checks and
releases all earlier locks. Query failure likewise rolls back the transaction.

Missing references cannot themselves be row-locked. A reference writer could
commit between the reference-pin step and raw acquisition. After raw is pinned,
the inspector therefore checks fresh work/link counts against the pinned counts.
Existing pinned rows cannot change/disappear, so any increase means a newly
inserted, unpinned reference and yields BUSY with all locks released. The raw
guard then prevents further new references during the remaining transaction.
This closes the gap without taking a parent lock after raw or waiting on writers.

This remains bounded to returned reading IDs (at most 100) and the actual trip
parents of those links, not lookahead, all trips or every correction replay. A
wrong-vehicle legacy parent is still an actual selected reference parent and is
pinned if available, rather than filtered out and mistaken for safe absence.
The point lookups use existing primary keys; no new index, migration, payload
copy or saved flag is needed. Schema remains V930.

No added step waits on a reference/parent that might be waiting for the already
held receipt lock. Parent pinning precedes raw acquisition, retaining normal
reference writers' parent-before-raw discipline. While these pins are held,
updates/deletes to selected existing work/link rows and updates to their existing
trip parents cannot commit. Normal progress is not permanently frozen: pins end
with the inspection transaction, and a waiting writer can immediately commit.
Correction references remain conservative blockers, not newly pinned/retired
history. New reference writes still require the existing exact-raw guard.

These locks stabilize selected references for the fresh reference/coverage checks,
not every operational table or historical row. The initial preview and its
policyCandidates remain preliminary observations, not a final write-time verdict.
An eventual pruner must refresh/enforce all policy/replay/archive/reference and
availability invariants in its own write transaction. There is still no deletion,
constraint transition or reusable permit. Privileged trigger disabling and
arbitrary out-of-protocol SQL are not certified safe.

Four new disposable cases observe work/link/parent contention yielding BUSY and
releasing earlier pins; one-row scope excluding lookahead and unrelated parents;
injected link-pin query failure releasing previously acquired work locks; and an
actual existing-work UPDATE blocked until return, followed by a fresh inspection
reporting REVIEW/unprocessed work. All 39 retention integration cases and both
retention unit cases passed in the focused run. Original raw/durable FKs and all
immutable/exact-original guards remain; retention stays disabled/dry-run.

A fifth case inserts missing work in another committed transaction exactly
between reference pinning and raw acquisition. The changed count yields BUSY,
the raw pin is released, and a fresh inspection sees the new unprocessed work.
The earlier focused count describes the initial four-case revision only. After
adding the post-raw gap check and fifth case, the final complete offline backend
run passed 390 tests across 23 fresh suites, zero failures/errors/skips, including
all 168 migration and 40 retention integration cases. No production/test/migration
code changed after this final successful run. Development data was untouched;
no new frontend/Python/browser, hosted-CI, performance or actual-pruning evidence
is claimed. Retention remains disabled/dry-run and checkpoint 5 remains incomplete.
