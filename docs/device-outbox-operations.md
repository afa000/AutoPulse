# Device outbox operations

The opt-in outbox stores simulated telemetry and diagnostic snapshots locally
before attempting delivery. It is an at-least-once sender: a lost response can
cause a retry, and the server recognizes the same identity and content. An
accepted ingestion receipt does not mean trip, diagnostic or maintenance work has
finished processing. The ordinary offline preview and direct sender remain
available separately.

## Prepare a local path

Choose a local directory outside synchronized folders and source control. For
example, in PowerShell from the repository root:

```powershell
$outboxDir = Join-Path $env:LOCALAPPDATA 'AutoPulse'
New-Item -ItemType Directory -Force -Path $outboxDir | Out-Null
$outbox = Join-Path $outboxDir 'simulator.sqlite'
$agent = './device-agent/.venv/Scripts/python.exe'
```

Install the agent as described in [local development](local-development.md).
On Linux/macOS choose an appropriate local directory and your environment's
Python executable. The examples use the local-profile fixture device/vehicle and
`http://127.0.0.1:8080`; a networked operation requires a running backend that
has registered that association. A store pins its device, vehicle and destination
without storing credentials. Reopening it with different settings fails; do not
redirect an existing backlog by changing its association.

## Collect, drain and inspect

```powershell
& $agent -m autopulse_agent.outbox collect-only --outbox $outbox
& $agent -m autopulse_agent.outbox status --outbox $outbox
& $agent -m autopulse_agent.outbox drain-only --outbox $outbox --drain-seconds 30
& $agent -m autopulse_agent.outbox collect-upload --outbox $outbox
```

`collect-only` never contacts the server. `collect-upload` uses one source owner
and an independent upload worker; when capacity permits, it continues collecting
while delivery is unavailable. Both are finite simulated scenarios. Each new
collection process gets a fresh boot ID, while previously queued evidence keeps
its original identity, timestamps and numeric content. `--pace` controls real
time between reads; `--sample-interval` controls simulated measurement time.
Accelerating simulated time can generate future measurements. The diagnostics
scenario defaults to `baseline`; `lifecycle`, `restart` and `unsupported` are
available with `--diagnostic-scenario`.

Exit 0 means the scenario completed and the queue is empty; 2 means backlog,
interruption, capacity pause or a scenario stopped at its reading limit; 1 means
an error. A successful collect-only run with pending observations normally exits
2. Ctrl+C asks a running collector to stop. A drain timeout is bounded to 60
seconds; repeat the drain if healthy backlog remains. Status can be read while a
runner owns the outbox and reports the local delivery state, not whether a car
is connected or safe.

## Review blocked evidence

Stop the owning runner before inspecting, exporting or changing blocked rows.
Status shows whether an owner was present at the time of its lock probe. Inspect
returns up to 100 blocked rows; use `--after-id` for the next page.

```powershell
& $agent -m autopulse_agent.outbox inspect --outbox $outbox
& $agent -m autopulse_agent.outbox export-blocked --outbox $outbox --enqueue-id 1 --output (Join-Path $outboxDir 'blocked-1.json')
& $agent -m autopulse_agent.outbox release --outbox $outbox --enqueue-id 1
```

Use the ID reported by `inspect`, not necessarily 1. Export creates a new file
and refuses to overwrite one. Treat exports as private collected data. Release
retries the unchanged evidence after its cause has been corrected; it does not
change the identity. A blocked item pauses delivery of the entire outbox.

If evidence must deliberately be abandoned, `discard --enqueue-id ID --confirm-discard`
permanently removes that undelivered item and increments a
local discard counter. It cannot be undone by the agent and is not an
acknowledgment. Export and understand the consequence before using it.

## Capacity and recovery limits

Defaults are 16 MiB of pending payloads, 10000 pending rows, seven days of
pending age, a 64 MiB SQLite database ceiling with 4 MiB reserved for control
work, and at least 80 MiB free disk before collection. Blocked rows count. A
limit pauses collection rather than evicting evidence; the upload worker can
drain the backlog. Collection resumes below the documented low-water thresholds.
`collect-only` reports a pause and exits; a later collection run can resume after
space is available. Status exposes the pause and its most recent duration when
known, not an invented number of missed samples.

Optional `--max-payload-bytes`, `--max-pending-rows`, `--max-age-seconds` and
`--min-free-bytes` choose finite limits when creating a store. They are persisted;
omit the flags on later opens to reuse them. The database ceiling covers its main
file, not all filesystem use: a rollback journal and allocation overhead need
additional space. If a disk or permission error prevents a write, the process
reports failure, but it may be impossible to persist even that health record.

If one encoded observation exceeds the entire logical byte limit, both collection
modes record a logical-capacity pause and exit 2 instead of retrying indefinitely.
That observation was not committed or declared collected. Choose a sufficiently
large limit for a new store; existing stores retain their pinned limits and queued
evidence. After blocked evidence is explicitly reviewed or acknowledged, free
SQLite pages can be reused even if the allocated file remains larger than its
data budget. No automatic evidence deletion or file compaction is required.

The tested boundary covers observations successfully committed before an agent
process terminates. Reads interrupted before commit, faulty media, corruption and
arbitrary power loss are not covered. The adjacent persistent `.lock` file is an
OS ownership lock; do not remove it while an agent may be running. The OS releases
ownership when the process exits. Keep the database, journal, lock and exports out
of Git and synchronized folders. If the store reports corruption or an unknown
schema, preserve it for inspection instead of replacing or clearing it.

Buffered old observations retain their measurement times and can remain stale or
historical. Upload acknowledgment does not refresh their measurement age, and
backend workers remain opt-in. There is no physical OBD adapter or authenticated
deployment in this milestone. See [ADR 0032](adr/0032-device-offline-outbox.md)
and the [verification record](milestone-verification.md) for the exact policy and
observed tests.
