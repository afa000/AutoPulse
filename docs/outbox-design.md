# Offline outbox: schema and verification matrix

The local store, upload cycles, finite independent collector, capacity/health
controls and isolated whole-milestone outage demonstration are implemented. See
[ADR 0032](adr/0032-device-offline-outbox.md) for guarantees and decisions.

## Local schema (version 4)

- `metadata`: exactly one association (device ID, vehicle ID, normalized
  destination), schema version and bounded aggregate counters/health. Credentials
  must never be persisted. Schema creation and association initialization are
  atomic; mismatch or an unrecognized version fails without altering evidence.
- `outbox`: monotonic enqueue ID, kind, identity components and wire version,
  immutable UTF-8 payload, byte length, SHA-256 digest, enqueue UTC time, state,
  attempt count, retry deadline and bounded failure reason. Unique constraints
  include the kind and identity. Same identity and identical encoded bytes is a
  local duplicate; differing bytes are a visible conflict, even where the server
  might normalize them as equivalent. Verify digest and length before upload.
- `health`: bounded current pause/resume information and last successful upload,
  not an unlimited receipt or audit archive. Local explicit discard must record
  its consequence in bounded accounting; it cannot pretend delivery occurred.
- `review_audit`: a bounded aggregate release count and most recent action,
  affected enqueue ID and time. Discard totals live in `health`. Known v1/v2
  schemas are upgraded in atomic steps after integrity and association checks;
  unknown versions fail closed.
- `diagnostic_receipt`: the last acknowledged diagnostic row and the original
  server snapshot ID, result and receipt time. Updating it and deleting its
  matching queued row occur together. Known v2 stores are upgraded atomically.
- `capacity`: validated limits persist so a restart cannot silently change them.
  Version 3 stores upgrade transactionally; explicit mismatches fail closed.

The store uses a persistent adjacent `.lock` file solely for operating-system
ownership; never remove it while a runner may be alive. The lock is released by
the operating system on process exit. A file path should be selected outside the
repository and synchronized folders; `device-agent/runtime/` is ignored as a
fallback for local development. SQLite uses a rollback journal, FULL synchronous
mode and short independent connections. By default, a 16 MiB logical payload and
10000-row cap is enforced atomically at enqueue, including blocked rows. The
collector reserves conservative space for the next maximum-size observation
before reading. Pending age is measured from local enqueue time; at seven days
it pauses new reads without deleting or preventing upload of old evidence. The
collector resumes after both logical totals reach 75% or less, the oldest item
is younger than the age limit, and disk/database headroom is available. A pause
and the most recent resume interval are persisted when storage permits; backward
clock jumps leave an unknowable interval as null and set a status clock warning
when the oldest enqueue timestamp is in the future.

The default database maximum is 64 MiB; data inserts use a 60 MiB active-page
budget, reserving 4 MiB for control operations. Allocated free pages count as
reusable space, including after control writes have grown the file above that
budget. Such inserts cannot grow the file further and must roll back if their
active pages exceed the budget. A rollback journal and filesystem
allocation can use additional space, so this is not a hard whole-filesystem
quota. At least 80 MiB of free disk is checked before each read and enqueue;
actual write failures between checks remain possible. Repeated drains reuse
SQLite free pages. Failures are reported to the process; even a health record
cannot be guaranteed if the filesystem rejects writes. Stop all store-using
threads before closing the owner.

Select the first outstanding enqueue ID, including retry-delayed and blocked
rows. Batch only a contiguous prefix of telemetry fitting both limits. Validate a
whole receipt against the exact submitted IDs and indexes before starting a
settlement transaction. Crash before settlement leaves the request retryable.
The uploader and collector each own their connections; transaction scope excludes
HTTP, rate limiting and sleeping. Store ownership is independent of SQLite's
short transaction locks and is held for the runner lifetime.

`TelemetryUploader.cycle()` performs at most one request, returning `empty`,
`waiting`, `blocked`, `diagnostic_head`, `rate_limited` or `settled`. It uses the
store's pinned destination, leaves collection free to write during HTTP, and
never starts a nested transport retry. Retry deadlines persist with jitter and
are clamped after a backward wall-clock jump. The in-process one-request-per-
second limit restarts when the owning process restarts. `Outbox.blocked()`
returns bounded pages including original bytes for inspection or export;
`review_action(id, 'release')` explicitly retries a reviewed row, while
`review_action(id, 'discard')` irreversibly removes that undelivered evidence and
increments the discard count. The operations CLI exposes bounded inspection,
export, release and explicit discard; see the [operations guide](device-outbox-operations.md).
A blocked row anywhere pauses delivery until it is explicitly reviewed.
`settled` reports ingestion acknowledgment only, not downstream processing.

The opt-in command `python -m autopulse_agent.outbox` accepts `collect-upload`,
`collect-only`, `drain-only` and `status`, and requires `--outbox PATH`. It uses
one source owner and an independent upload thread, with one diagnostic read after
each telemetry read until the selected diagnostic scenario finishes. Each new
collection process generates a new boot ID. `collect-only` never opens HTTP;
`status` reads a consistent snapshot without taking runner ownership. It reports
counts and bytes by kind and state, local oldest age, head retry time, last
successful upload, pause details, bounded review reason, database/journal size,
free disk and last diagnostic receipt. A lock probe marks whether an owner was
present; after an unclean stop, stale `collecting` is reported as `stopped`.
This describes local delivery, not vehicle connectivity or safety. `--pace` controls wall-clock pacing;
`--sample-interval` controls simulated timestamps. The finite source is bounded
by `--max-readings` (default 1000) and upload draining by `--drain-seconds`
(default 10, maximum 60). SIGINT/SIGTERM request a stop; the worker gets a
bounded shutdown interval. If it cannot stop, ownership remains held until the
process exits. Exit 0 means the scenario finished and the queue is empty; exit 2
means pending, interrupted, capacity-paused or a scenario limited before its
end; exit 1 reports an error. Thus a successful `collect-only` with a pending
backlog normally exits 2. Existing preview/direct send modes are unchanged.
`collect-upload` waits at a capacity pause while the independent uploader can
drain; `collect-only` exits with the pause preserved. Optional
`--max-payload-bytes`, `--max-pending-rows`, `--max-age-seconds` and
`--min-free-bytes` set finite limits for a new outbox; omit them on later opens
to reuse persisted settings. Small limits can pause early, and database/control
limits remain bounded separately.

## Named verification matrix

| Test | Required observation |
| --- | --- |
| `test_enqueue_reopen_and_fifo_across_kinds_and_boots` | Committed original bytes and identities survive reopen in enqueue order. |
| `test_local_identity_duplicate_and_conflict` | Same identity/bytes is a duplicate; different bytes never overwrite. |
| `test_terminated_writer_before_and_after_commit` | A killed subprocess leaves either no row or one complete committed row. |
| `test_wrong_association_unknown_version_and_corruption` | Each fails visibly and preserves existing storage. |
| `test_single_owner_released_on_process_death` | A second process cannot operate; ownership is released after termination. |
| `test_http_never_holds_store_transaction` | Collector commits while the upload connection is stalled. |
| `test_mixed_ack_maps_original_indexes_atomically` | Accepted and duplicate rows settle; rejected rows remain blocked. |
| `test_malformed_or_ambiguous_ack_settles_nothing` | Missing/duplicate indexes, invalid counts, status or rejection detail preserve all rows. |
| `test_crash_before_send_after_commit_and_before_settlement` | Restart retries unchanged rows; lost response produces a duplicate receipt and no second server record. |
| `test_head_retry_and_diagnostic_order` | No later telemetry or diagnostic passes a waiting or blocked head. |
| `test_permanent_request_failure_preserves_batch` | Whole-request 4xx is reviewable, not attributed to individual items. |
| `test_backoff_persists_and_clock_jumps_are_bounded` | Restart preserves scheduling; backward/forward jumps neither spin nor stall indefinitely. |
| `test_diagnostic_receipt_and_partial_evidence` | Only matching valid receipts settle; partial/unknown evidence remains explicit. |
| `test_independent_collection_during_network_outage` | In-capacity collection continues while uploader fails; restart keeps original identities. |
| `test_capacity_age_pause_and_low_water_resume` | Pending and blocked rows count; pause preserves all evidence and drain resumes safely. |
| `test_cli_oversized_observation_pauses_without_retry_loop` | Both collection modes exit paused when one observation exceeds the entire logical budget. |
| `test_control_reserve_growth_can_resume_using_freed_pages` | After rejection metadata grows into the reserve, explicit discard or release/acknowledgment allows collection to resume across reopen without growing the file. |
| `test_disk_failure_and_repeated_fill_drain` | Failure is visible; physical storage and retained metadata remain bounded across cycles. |
| `test_actual_isolated_outage_crash_and_lost_response` | Real Python/Java/PostgreSQL path matches the exact identity/content manifest and cleans up disposable resources. |
| `test_buffered_evidence_freshness_and_processing` | Real API results retain original measurement age, completeness and downstream policy. |

Existing direct sender, capture/replay, source, contract and server regressions
remain required. The final gate must distinguish observed results from historical
CI and must not use the owner's development database.
