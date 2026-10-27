"""Opt-in finite simulated outbox collection, drain and local inspection."""

import argparse
import json
import signal
import sys
import threading
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import UUID

from .buffering import Association, CapacityPolicy, Outbox, OutboxError
from .buffering.runner import WorkerStillRunning, run
from .models.telemetry_json import sample_from_payload
from .sources.simulated import SimulatedTelemetrySource


def main(argv=None):
    parser = argparse.ArgumentParser(description="Finite local simulated collection and durable outbox delivery")
    parser.add_argument("mode", choices=("collect-upload", "collect-only", "drain-only", "status", "inspect", "export-blocked", "release", "discard"))
    parser.add_argument("--outbox", type=Path, required=True, help="Local SQLite path, preferably outside synchronized folders")
    parser.add_argument("--server-url", default="http://127.0.0.1:8080")
    parser.add_argument("--device-id", default="sim-device-001")
    parser.add_argument("--vehicle-id", default="venue-2021")
    parser.add_argument("--scenario", choices=("snapshots", "normal-commute"), default="snapshots")
    parser.add_argument("--diagnostic-scenario", choices=("baseline", "lifecycle", "restart", "unsupported"), default="baseline")
    parser.add_argument("--sample-interval", type=float, default=2.0, help="Simulated measurement interval in seconds")
    parser.add_argument("--pace", type=float, default=2.0, help="Wall-clock seconds between source reads")
    parser.add_argument("--start-at", help="Optional fixed timestamp for simulation")
    parser.add_argument("--max-readings", type=int, default=1000)
    parser.add_argument("--drain-seconds", type=float, default=10.0)
    parser.add_argument("--max-payload-bytes", type=int, help="New outbox logical byte limit (default 16777216)")
    parser.add_argument("--max-pending-rows", type=int, help="New outbox row limit (default 10000)")
    parser.add_argument("--max-age-seconds", type=int, help="New outbox oldest-pending age limit (default 604800)")
    parser.add_argument("--min-free-bytes", type=int, help="New outbox minimum free disk bytes (default 83886080)")
    parser.add_argument("--enqueue-id", type=int, help="Blocked row ID for export, release or discard")
    parser.add_argument("--after-id", type=int, default=0, help="Inspect blocked rows after this ID")
    parser.add_argument("--output", type=Path, help="New file for one blocked payload; never overwrites")
    parser.add_argument("--confirm-discard", action="store_true", help="Explicitly discard undelivered evidence")
    args = parser.parse_args(argv)
    stop = threading.Event()
    previous = {}
    store = None
    leave_owned = False
    try:
        association = Association(args.device_id, args.vehicle_id, args.server_url)
        if args.mode not in ("collect-upload", "collect-only") and not args.outbox.is_file():
            raise ValueError("outbox does not exist")
        if args.mode == "status":
            print(json.dumps(Outbox.inspect_status(args.outbox, association)))
            return 0
        requested = (args.max_payload_bytes, args.max_pending_rows, args.max_age_seconds, args.min_free_bytes)
        policy = None
        if any(value is not None for value in requested):
            defaults = CapacityPolicy()
            policy = CapacityPolicy(payload_bytes=args.max_payload_bytes if args.max_payload_bytes is not None else defaults.payload_bytes,
                rows=args.max_pending_rows if args.max_pending_rows is not None else defaults.rows,
                oldest_seconds=args.max_age_seconds if args.max_age_seconds is not None else defaults.oldest_seconds,
                disk_free_bytes=args.min_free_bytes if args.min_free_bytes is not None else defaults.disk_free_bytes)
        store = Outbox(args.outbox, association, policy=policy)
        if args.mode == "inspect":
            rows = store.blocked(after_id=args.after_id)
            print(json.dumps([{"enqueue_id": row.enqueue_id, "kind": row.kind, "bytes": len(row.payload), "reason": row.reason} for row in rows]))
            return 0
        if args.mode in ("export-blocked", "release", "discard"):
            if args.enqueue_id is None or args.enqueue_id < 1:
                raise ValueError("a positive --enqueue-id is required")
            if args.mode == "export-blocked":
                if args.output is None:
                    raise ValueError("--output is required for export")
                rows = store.blocked(after_id=args.enqueue_id - 1, limit=1)
                if not rows or rows[0].enqueue_id != args.enqueue_id:
                    raise ValueError("blocked row not found")
                with args.output.open("xb") as stream:
                    stream.write(rows[0].payload)
                print(json.dumps({"exported": args.enqueue_id, "bytes": len(rows[0].payload)}))
                return 0
            if args.mode == "discard" and not args.confirm_discard:
                raise ValueError("discard permanently removes undelivered evidence; pass --confirm-discard")
            store.review_action(args.enqueue_id, args.mode)
            print(json.dumps({"action": args.mode, "enqueue_id": args.enqueue_id}))
            return 0
        source = None
        if args.mode.startswith("collect-"):
            start = datetime.now(timezone.utc)
            if args.start_at:
                start = sample_from_payload({"deviceId": args.device_id, "vehicleId": args.vehicle_id,
                    "bootId": str(UUID(int=0)), "recordedAt": args.start_at,
                    "sequenceNumber": 0, "source": "SIMULATED"}).recorded_at
            source = SimulatedTelemetrySource(args.device_id, args.vehicle_id, start,
                        interval=timedelta(seconds=args.sample_interval), scenario=args.scenario,
                        diagnostic_scenario=None if args.diagnostic_scenario == "unsupported" else args.diagnostic_scenario)
        for sig in (signal.SIGINT, signal.SIGTERM):
            previous[sig] = signal.signal(sig, lambda *_: stop.set())
        result = run(store, source=source, upload=args.mode != "collect-only", pace=args.pace,
                     drain_seconds=args.drain_seconds, max_readings=args.max_readings, stop=stop)
        print(json.dumps(result))
        pending = bool(result["status"]["groups"])
        return 2 if pending or result["interrupted"] or result["capacity_paused"] or not result["scenario_completed"] else 0
    except WorkerStillRunning as error:
        leave_owned = True
        print(f"Outbox failed: {error}", file=sys.stderr)
        return 1
    except (OutboxError, ValueError, OSError, RuntimeError, OverflowError) as error:
        print(f"Outbox failed: {error}", file=sys.stderr)
        return 1
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)
        if store is not None and not leave_owned:
            store.close()


if __name__ == "__main__":
    raise SystemExit(main())
