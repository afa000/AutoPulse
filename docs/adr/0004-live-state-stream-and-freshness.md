# 0004: Committed current-state SSE and independent freshness

Status: accepted for the single-instance loopback development slice (2026-10-20).

`GET /api/v1/vehicles/{vehicleId}/live` resolves the public vehicle before starting
the response: unknown returns structured 404; capacity exhaustion returns 503.
Success is `text/event-stream`, no-cache. Named `telemetry` events contain exactly
the existing LatestTelemetry envelope (reading plus original receivedAt). A known
vehicle without data receives `event: empty` with `{"vehicleId":"slug"}` and
stays subscribed. Comment heartbeats occur every 15 seconds and are not readings.
No event IDs or Last-Event-ID resume are promised. Reconnect always resnapshots.

Ingestion publishes a per-vehicle dirty notification through an AFTER_COMMIT
listener only when a new row is accepted. No duplicate, rejected or rolled-back
batch publishes it. A fixed coordinator re-reads authoritative PostgreSQL latest
state, using the complete ADR 0002 ordering; it never orders public sequence
numbers or parses timestamps to lower precision. Concurrent notifications and a
batch can be coalesced into newest current state; this is not an event history.
Unchanged latest state, including an older accepted arrival, emits no update.

Subscription registration and coordinator reads are synchronized per vehicle.
Registration marks the state dirty; the subscriber exists before its initial
read. A commit during that read leaves the dirty flag set for the next pass.
One coordinator serializes snapshots. A writer's single pending slot can replace
an unsent older snapshot, never enqueue an older result after a newer one. Thus
there is no racing REST fetch, missed setup commit, or old initial overwrite.

Resources: at most 64 global subscriptions, 8 per vehicle, one coordinator and
four network writers with a bounded 64-task queue, plus two independent transport
cleanup workers with a bounded 64-task queue. Per client, at most one
pending snapshot and one in-flight write. Writes taking over five seconds are
retired by the coordinator; errors/completion/timeout retire subscriptions.
Retirement stops further updates immediately and schedules transport completion
off the coordinator and writer pool. Spring completion can wait on the emitter's
write lock and can flush the socket, so it must never run inline on either pool.
Each retired client retains its global/per-vehicle capacity slot until both its
writer and completion task have returned. Cleanup submission rejection retains
that slot and is retried on a later tick, never with an inline fallback. This
bounds active plus retiring resources under churn; saturation returns 503 rather
than admitting an unbounded collection of stuck transports.
Streams retire after two minutes (native EventSource reconnects once the actual
transport ends). This is a logical lease, not a guaranteed socket-close deadline.
Heartbeats use
the shared coordinator, not one scheduled task per client. Transport writes
never execute on ingestion or database-transaction threads. Saturation closes
clients and requires resnapshot; this is bounded best-effort local fan-out, not
production backpressure. Servlet/socket termination may depend on the container;
both pools remain bounded even during blocked sockets, and a blocked completion
does not prevent other vehicles' snapshots, heartbeats or lease retirement.
Database faults
close affected streams rather than keep claiming a healthy live stream.

The local Vite proxy propagates upstream response termination to the browser and
destroys upstream request/response objects when downstream closes. MVC response
attachment also has a five-second handshake bound, avoiding invisible early-send
buffers on requests abandoned before attachment. TCP close observation is not
instantaneous: ordinary disconnected clients can be detected by a subsequent
write or retired by the two-minute lease. A genuinely blocked transport may keep
its capacity reservation beyond the lease until container I/O/completion returns.
Automated ordinary-disconnect browser checks allow the lease plus cleanup time;
a separate real-emitter lock regression proves the coordinator remains responsive
while completion is blocked. Rapid reconnect/tab churn can therefore
temporarily hit the per-vehicle cap; reconnect/resnapshot after capacity frees.

Notifications are process-local: a single backend instance handles this slice.
Multiple backend instances, durable replay, auth and brokers are deferred.
No transaction is held for a stream's lifetime. Loopback-only defaults remain.

## UI rules

- Server connection: connecting, live, reconnecting, unavailable. It is not a
  vehicle/device connection indicator. Last-known values survive connection loss.
- Measurement age uses recordedAt and ticks locally once per second without new
  events. Default stale threshold is six seconds, configurable in public frontend
  settings. It is a demonstration cadence setting, not an automotive threshold.
- Receipt timestamp/age is shown separately; newly received historical backlog
  remains stale. Heartbeats and reconnecting the same snapshot do not reset age.
- Future timestamps beyond a two-second clock-skew tolerance show an explicit
  clock/simulation-time warning and unknown freshness until clock time catches up.
  Within tolerance, nonnegative displayed age is clamped to zero.
- Zero RPM labels a *last-known engine-off reading*, which can also be stale.
  Zero speed alone makes no engine-state claim. Null RPM means engine state unknown.
- Null sensors show Unavailable; false MIL shows Reported off; null MIL Unknown.
  No diagnosis, device heartbeat, buffering, or hardware-online state is inferred.
- SIMULATED is conspicuous. OBD means reported source, not verified hardware
  connectivity. A malformed event leaves last-known state visible with a warning;
  unknown named events are ignored. The server is authoritative for ordering.

Frontend numbers are display approximations only, never canonical replay input.
The browser guards envelopes and vehicle identity, closes old EventSource objects
on unmount/switch, and ignores callbacks from obsolete subscriptions.

For human demos, measurement and wall-clock send intervals both stay at two
seconds. Accelerated fixtures must be explicitly labeled; accelerating only
delivery with a current start otherwise makes measurements future-dated.
