# ADR 0032: Device-local offline outbox

Status: accepted and implemented; local milestone verification and subsequent
capacity recovery regressions are recorded in the verification notes.

## Context and decision

The direct sender waits for HTTP and stops collecting when delivery fails. JSONL
capture is an export/replay format, not a transactional queue. An opt-in runner
will commit each validated observation to a local SQLite outbox before reporting
collection or making an HTTP attempt. Reads interrupted before commit, filesystem
corruption, defective storage and arbitrary power failures are outside the tested
guarantee. SQLite's transaction and durability settings reduce risk; they do not
prove power-loss durability on every device.

Delivery is at least once. A lost response is ambiguous: the server may already
have committed, so the same immutable bytes and identity must be retried. A
validated rejection is different: the server has explicitly reported a problem
and the item must be retained for review. Server idempotency prevents a second
stored observation for the same identity and content; it does not make network
transmission exactly once. An ingestion receipt does not mean downstream trips,
diagnostics or maintenance processing has completed.

Keep the original association, boot, independent sequence, source, measurement
time, nulls and exact decimal content. New process runs use a fresh boot only for
new observations. The telemetry identity is device/boot/sequence and the
diagnostic identity is device/boot/diagnostic sequence, in separate namespaces.
Pin device, vehicle and destination base URL without credentials to the database;
reject an association mismatch. Follow local enqueue order across event kinds and
boots. A retry-delayed or blocked head stops delivery; a telemetry batch may not
cross a diagnostic row. Local order is not cross-device event-time order. Existing
server freshness, diagnostic completeness, history and identity policies remain
unchanged (ADRs 0001–0003, 0029 and 0030).

Use one exclusive runner per file, with an operating-system lock released on
process death, and thread-owned SQLite connections. HTTP and waiting occur outside
transactions. SQLite schema version is independent of server migrations; unknown
versions or corruption fail closed without replacing or clearing the file. Use
rollback journal and synchronous FULL, a bounded busy timeout, and an explicit
bounded database size; verify the process-crash boundary on real subprocesses.
The selected storage path should be local and outside synchronized folders.

## State and receipt rules

Rows start queued. A transient or ambiguous failure leaves them waiting until a
persisted retry time; an explicit permanent item rejection or whole-request
configuration error blocks delivery for review. Only a fully validated receipt
can settle its associated rows. Telemetry accepted/duplicate items settle in one
transaction; rejected items and their bounded reasons remain. A malformed or
incomplete response settles none. Diagnostics settle only on a validated ACCEPTED
or DUPLICATE receipt, associated with the exact submitted row. Acknowledged
payloads may be removed while bounded aggregate accounting is retained. Review
release or discard must be an explicit local action with recorded consequences.

The first capacity policy preserves committed evidence and pauses collection
instead of evicting or compacting it. This intentionally narrows the blueprint's
future downsampling proposal: no lossless rule exists for all event types yet.
Age also pauses new reads, but never deletes or prevents draining. A storage
failure is reported through process output and exit status; persistence of that
failure cannot be promised when the disk itself cannot be written. Pause/resume
records describe the interval, not invented measurements or loss counts.

## Initial configuration contract

Defaults are deliberately finite and must be validated at startup. Binary units
below mean 1 KiB = 1024 bytes and 1 MiB = 1048576 bytes.

| Setting | Initial default | Purpose |
| --- | --- | --- |
| Pending payload | 16 MiB and 10000 rows | Bound logical backlog, including blocked rows |
| Oldest pending age | 7 days | Pause collection, never expire evidence |
| Resume threshold | 75% of both byte and row limits; oldest below age limit | Avoid oscillation while allowing drain |
| SQLite database | 64 MiB maximum pages, 4 MiB control reserve | Bound main file and reserve space for state/health |
| Disk free space | 80 MiB minimum before new collection | Leave room for rollback journal and filesystem overhead; recheck before writes |
| HTTP timeout | 5 seconds per attempt | Bound one network operation |
| Telemetry request | 100 items, 262144 UTF-8 bytes | Match server contract; contiguous rows only |
| Diagnostic request | 65536 UTF-8 bytes, one item | Match diagnostic contract |
| Upload cycle | At most 100 rows and one request per second | Bound memory, work and traffic |
| Retry | 1 second base, exponential cap 5 minutes, jitter 0.5–1.5 | Spread retries; persist schedule, cap excessive wall-clock delays |
| SQLite busy timeout | 2 seconds | Bounded contention, no HTTP inside transactions |
| Failure reason | 1024 UTF-8 bytes | Prevent unbounded review metadata |

The main database page limit is not a whole-filesystem quota: a rollback journal,
filesystem allocation and transient operations consume additional space. Data
insertion has a lower active-page budget to leave the control reserve. If control
writes grow the allocated file beyond that budget, inserts may reuse free pages
without growing the file further; an insert exceeding the active-page budget is
rolled back. SQLite reuses freed pages after drains or explicit review actions.
Actual disk-full and permission failures can still
happen between checks. Retry
clocks must clamp unreasonable deadlines after clock jumps, never busy-spin or
sleep indefinitely. No nested transport retries are used by the runner.

The primary CLI modes are `collect-upload`, `collect-only`, `drain-only` and `status`.
Finite collection exits after the scenario; drain reports a distinct nonzero
status when backlog remains blocked or waiting. Signals stop new reads, finish or
bound the active operation and close resources. Status distinguishes queued,
waiting, blocked, paused and storage failure, and says delivery unknown/offline
without asserting vehicle connectivity or safety.

Before a source read, the runner checks a conservative upper bound capped to the
configured logical budget. Once encoded, an observation must fit at its actual
size. If it exceeds the entire logical budget, both collection modes record a
pause and exit with pending/paused status instead of retrying an impossible fit.
An observation read but not committed is not declared collected.

## Implementation and verification

The local schema and named failure matrix are in
[outbox design](../outbox-design.md). Observed local outcomes and the distinction
between local and hosted results are in [verification](../milestone-verification.md).
Direct send, capture, replay and offline preview remain compatible. No hardware,
authentication, deployment or server processing policy is added by this decision.
