# ADR 0021: Canonical linked-trip membership before archive verification

Status: accepted read-path transition; checkpoint 5 remains incomplete.

## Decision

Forward-only V926 replaces trip_evidence with three disjoint arms evaluated in
one statement snapshot. Its existing (trip_id, reading_id) shape is unchanged.

| Trip eligibility | Membership and bound metadata |
| --- | --- |
| No verified archive, identity checkpoint missing/incomplete | Original raw-linked path |
| No verified archive, vehicle identity backfill complete | Original links plus canonical ledger times |
| Shared trip_verified_archive eligibility satisfied | Retained membership plus ledger, as before |

The linked arms retain existing ACTIVE/COMPLETED status and inclusive started_at
through coalesce(ended_at, observed_through) bounds. The ledger arm never invents
links from all vehicle readings or derives membership from a summary. All arms
join trip_visible_record: REBUILDING trips and unfinished correction publication
remain hidden. The retained arm keeps its final-readiness/provenance/bounds rules.

Both linked arms exclude verified archives independently of the vehicle identity
checkpoint. Thus a verified retained trip cannot duplicate through raw when that
vehicle registration is missing. Unverified trips with missing/incomplete vehicle
registration remain raw-backed, not implicitly ready.

## Readiness is not deletion authority

Identity completion selects original ledger metadata for linked reads; it does
NOT make an active/provisional/unverified trip archived, final or safe to prune.
Copying, aggregate building and independent verification remain separate. Source
handoff alone preserves member UUIDs, summaries, generation and availability when
original raw/ledger values agree. Normal processing/publication still changes
durable state and generation under its existing rules.

For ready but unverified trips, later privileged raw timestamp edits no longer
change the membership view's bound classification. Incomplete vehicles keep raw
behavior. This is not raw repair or detection of every privileged corruption.
Unverified-trip payload reads and default whole-vehicle history keep their source
rules; the complete API is not newly raw-free. No payload/public contract changes.

## Rollout and remaining dependencies

Migration changes a view definition only: no payload history scan/copy, member
insertion, archive checkpoint advancement, FK removal, guard relaxation, route,
runner, saved flag, expiry cutoff or pruning job. Normal view-DDL locks still
apply; no zero-lock deployment, query-plan or universal physical-I/O guarantee is
claimed. Previously applied migration files are unchanged.

Original-raw reference/copy checks, raw FKs and immutable final/link/ledger/evidence/
aggregate guards remain. Linked reads still use original association rows and
incomplete vehicles still need raw. This does not authorize dropping references,
reopening final trips or reusing inspection results for later deletion.

## Disposable checks

Six new cases cover exact identity handoff without archival/generation changes;
mixed incomplete/missing-registration and ready vehicles; active/provisional/
rebuilding visibility; linked -> partial copy -> build -> COMPARE -> VERIFIED
membership without duplicates, including missing vehicle registration after
verification; V925 upgrade/restart without changing stored data/checksums; and
every real bounded correction-publication stage remaining hidden until the
expected corrected 27-member summary becomes visible.

Full offline backend regression passed 355 tests across 23 fresh suites, zero
failures/errors/skips, including all 143 migration and 16 retained-evidence cases.
The initial focused 18 cases also passed; the full run additionally covers the
missing-registration-after-verification assertion. No production/test/migration
changes followed the full passing run. No new frontend/Python/browser, hosted CI,
query-plan benchmark, expiration or successful pruning evidence is claimed.

Retention stays disabled/dry-run with wouldDelete=0 and availability unchanged.
Next: protected-reference transition and complete same-transaction deletion checks
with atomic generation/availability publication and truthful expired whole-vehicle
history. Checkpoints 5 and 6 remain incomplete. Owner-data deletion requires
separate explicit approval of concrete targets and safeguards.
