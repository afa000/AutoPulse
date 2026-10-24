# ADR 0018: Explicit non-deleting retention inspection under write barriers

Status: accepted synchronization rehearsal; checkpoint 5 remains incomplete.

## Decision and lock order

RetentionPreview.report remains the existing read-only REPEATABLE READ diagnostic.
A separate, explicitly invoked reportUnderBarrier method examines the same bounded
page while holding vehicle processing, policy and receipt barriers. It is not a
pruner, a commit API or an availability publication mechanism.

Disabled still means no clock, database or transaction calls. Enabled checks keep
dry-run-only options, trusted continuation context, policy cutoffs, a 1..100 page
size and ten-second timeout. No public route, scheduler, migration, new flag,
startup scan or automatic use of this method is introduced.

The method opens a new READ COMMITTED transaction, not a database read-only one:
row locks require a writable transaction even though this method changes no data.
Lock order matches processing stages: try the vehicle advisory transaction lock
in namespace 902, share-lock the existing policy row, then update-lock the vehicle
activity/receipt row. Contended rows are skipped. BUSY contains no report. Unknown
vehicles and missing receipt registrations fail explicitly. Missing policy remains
protected by the existing checks; absence never authorizes pruning.

After selecting the bounded page, the method now pins exactly its returned raw
UUIDs in deterministic UUID order using FOR UPDATE SKIP LOCKED. Lookahead and
unseen history are not pinned. If any requested row is locked or absent, return
BUSY with no preview or archive checks and release all acquired locks. Do not wait
for a raw row: an existing writer may already hold it while its generation trigger
waits for our receipt lock. Waiting would create a lock-order cycle. Table/DDL
locks are not made universally non-blocking; the transaction timeout remains.

## Fresh checks, not frozen history

READ COMMITTED obtains fresh statement snapshots after acquisition. Ingestion
that commits between policy and receipt locks must be visible to the ensuing
generation/context check. A repeatable snapshot established before that commit
would defeat the intended fresh check. Ingestion already waiting at the receipt
barrier commits and advances generation after inspection ends. Existing policy
rows cannot change while share-locked; stage workers cannot overlap the processing
lock. This does not freeze every archive writer or arbitrary privileged SQL, nor
certify an unseen uncommitted input as absent forever.

The normal preview remains preferable when blocking ingestion is unnecessary.
The explicit barrier inspection can briefly delay ingestion despite being
non-mutating. It is not installed on a public request or scheduler path.

## Result is not authority

Success is CHECKED_NOT_AUTHORIZED with the existing preview page. wouldDelete
remains zero and all hard prerequisites remain, including the required write-time
replay recheck and PRUNER_NOT_IMPLEMENTED. The inner READ_ONLY_PREVIEW label means
non-mutation, not that this connection has its read-only flag set. Existing age,
receipt, latest, policy, work, reconciliation, replay and cursor protections remain.

All locks release before return, so this result can become stale immediately and
must NEVER authorize a later deletion transaction. A future pruner must combine
complete ownership/replay/protection checks, bounded deletion and generation/
availability publication in the SAME transaction. This method alone does none of
that. Availability stays AVAILABLE without an expiry cutoff; all raw FKs and
final/evidence guards remain. No owner database is inspected or changed.

## Bounded per-reading archive coverage extension

The explicit barrier result now includes archiveChecks for exactly the returned
page, not its lookahead or all members of a trip. One fresh statement joins those
reading UUIDs to raw, replay identity, retained membership and the shared
trip_verified_archive readiness view. Payloads stay in PostgreSQL; comparison is
exact JSONB plus original UUID, vehicle/device/boot/NUMERIC sequence, measurement
time, receipt time and source. Results preserve the page's native reading order.

Coverage reports missing raw/replay data, replay mismatch, missing retained
membership, unverified archive or inconsistent provenance. VERIFIED_COVERAGE
requires matching original data and a verified final owner with correct vehicle,
device, source and closed bounds. A finalized trip, partial/complete copy or
aggregate build without independent verification is insufficient. verifiedTrip
is present only for VERIFIED_COVERAGE. Disabled, BUSY and empty pages have no
archive checks; they do not imply that an entire vehicle is covered.

These checks do not alter policyCandidate or erase any existing protection or
missing prerequisite. Even complete page coverage cannot override pending work,
an incomplete replay checkpoint or authorize pruning. The earlier preview and
later coverage statement intentionally use separate READ COMMITTED snapshots;
archive completion between them can appear in the later coverage. Do not treat
the two as one frozen snapshot. Archive writers are not newly serialized here.
This is inspection plumbing, not complete write-time deletion enforcement. The
returned raw rows are pinned before this query, but locks still end before return
and no subsequent write may rely on it as a certificate. No schema, route, runner,
availability behavior or saved flag changes accompany this extension.

Row pins prevent later raw edits/deletes while the inspection continues; they do
not rewrite the earlier diagnostic into a global snapshot, lock every archive
writer or permit dropping original raw FKs. A pre-existing uncommitted raw writer
causes BUSY rather than a partial or apparently clean report. Commit, rollback
and an exception in the later archive query all release the pins. No pruning or
availability/generation publication is added by taking these locks.

## Disposable verification and remaining work

Tests compare the page with normal preview and fingerprint existing data, check
actual READ COMMITTED/writable connection settings, exercise processing/policy/
receipt contention and retry, reject stale generation/policy continuations, observe
a real PostgreSQL blocking-pid wait for ingestion, and test an ingestion commit
between policy and receipt acquisition. Disabled/bounds checks keep their
no-interaction assertions. Missing registration fails closed.

The initial barrier regression passed 328 tests. With the archive coverage
extension, the complete offline backend regression passed 334 tests across 23
suites, zero failures, errors or skips: six additional integration cases cover
readiness stages, page ordering/isolation, exact replay mismatch, retained vehicle
protections, concurrent archive completion and empty coverage. The 26 retention
integration cases and two enhanced unit tests passed. Non-mutation fingerprints
also include policy, retained progress and aggregate/reference state. Only
disposable PostgreSQL was used; the development database was untouched. Frontend,
Python and browser checks were not rerun for this backend-only step. No new query
plan benchmark or universal physical-I/O guarantee is claimed.

With raw-row pinning, the full offline regression passed 338 tests across 23
suites, zero failures/errors/skips. Four additional cases observe raw UPDATE and
SHARE contention, exact one-row pin scope versus lookahead, a real raw-writer /
receipt-barrier wait resolved by BUSY, and rollback release after an injected
archive-query failure. All 30 retention integration cases and two enhanced unit
tests passed; prior archive completion, fingerprint and ingestion tests remain.
Only disposable databases were used. These tests do not demonstrate actual pruning
or completion of all same-transaction deletion/publication prerequisites.

No deletion, expiry, storage savings or post-pruning summary result is claimed.
Next: full write-time ownership/replay checks, atomic availability/generation
publication, protected reference transition and a bounded crash-safe opt-in pruner.
Checkpoints 5 and 6 remain incomplete; named owner-data deletion requires separate
explicit approval.

ADR 0026 subsequently adds selected-row referenceChecks under these same locks,
including work/trip/retained ownership and conservative correction-reference
presence. V930 adds a reading-first correction lookup index. These observations
end with the same transaction and never authorize later pruning; all existing
hard prerequisites and original raw references remain.
