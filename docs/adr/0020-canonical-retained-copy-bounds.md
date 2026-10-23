# ADR 0020: Canonical bounds for retained final-evidence copying

Status: accepted copy-safety extension; checkpoint 5 remains incomplete.

## Decision

For each unfinished FinalTripEvidence copy page, choose the existing closed
TripReadingSource once inside the transaction. If the ledger view exists and the
vehicle's exact identity backfill is complete, classify linked readings against
the final trip's closed bounds using original ledger measurement times. Older
schemas and incomplete vehicles retain raw-backed classification.

The copier still pages ALL linked UUIDs before metadata lookup, at most 500 per
call. Out-of-range legacy confirmation links consume visited work and advance
the same durable UUID boundary without becoming retained members. Source handoff
between pages does not reset progress, change summaries or duplicate membership.
No payload is loaded into Java and no new history-wide query is introduced.

## Why classification must precede the unchanged exact guard

Previously, a later privileged raw timestamp edit could move an original member
outside the trip. Raw classification skipped it before the retained-insert guard
could reject the raw/ledger mismatch. For ready vehicles, canonical classification
now keeps that member eligible for checking; the unchanged exact-original guard
rejects it and rolls back copies and progress together. A moved raw timestamp
cannot instead invent an original outside confirmation link as an inside member.

This is not raw repair or permission to accept inconsistent data. Every actual
retained INSERT, including conflict retries, still needs the exact original raw
reading and immutable FINAL closed association, with matching ledger identity,
vehicle/device/source, timestamps, original receipt and JSONB payload. Missing or
mismatched originals abort the page. Raw FKs and final/link/ledger/evidence guards
remain. The copier is still raw-dependent at the WRITE boundary.

Incomplete vehicles deliberately keep their old classification path; retention
must continue protecting them until exact replay coverage is complete. The
completed-copy no-op is unchanged: this extension does not reopen, re-audit or
repair previously completed copies. A copy checkpoint is not a deletion permit.
Per-reading coverage, independent aggregate verification and future same-
transaction pruning checks remain necessary. Archive progress is not globally
frozen against every writer by this source choice.

## Scope and verification

No migration, schema/version change, route, runner, saved flag, public view/API,
availability cutoff or pruning implementation is added. Schema remains V925.
Ordinary trip_evidence still has its raw-backed unverified/legacy arm. Neither
this extension nor ledger-based Java query tracing proves a raw-free pipeline.

Five new disposable cases cover ready-ledger classification with original raw
FK/final guards intact; incomplete raw fallback without setting readiness; source
handoff between one-link pages; an original member moved outside in raw causing
atomic rejection; and an outside confirmation link moved inside in raw remaining
excluded from retained membership. The 27 hand-checked closed members, original
trip and raw metadata are preserved except for explicit corruption fixtures.

The full offline backend regression passed 349 tests across 23 fresh suites,
zero failures/errors/skips, including all 12 retained-evidence and 141 migration
cases. The 12 focused retained-evidence cases also passed separately. No
production/test/migration code changed after the full passing run. No new
frontend/Python/browser, hosted CI, query-plan benchmark, actual expiration or
successful pruning evidence is claimed for this backend-only extension.

Retention remains disabled/dry-run with wouldDelete=0. Next: remaining protected
raw dependencies, reference transition and complete same-transaction deletion/
generation/availability checks. Checkpoints 5 and 6 remain incomplete. Owner-data
deletion requires separate explicit approval of concrete targets and safeguards.
