"""Disposable JUnit-owned HTTP/SQLite crash probe; never target a normal server."""

import argparse
import json
import sys
import time
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "device-agent/src"))
from autopulse_agent.buffering import Association, Outbox
from autopulse_agent.buffering.telemetry_upload import TelemetryUploader
from autopulse_agent.buffering.diagnostic_upload import DiagnosticUploader


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--server-url", required=True)
    parser.add_argument("--database", type=Path, required=True)
    parser.add_argument("--marker", type=Path, required=True)
    parser.add_argument("--boot", required=True)
    parser.add_argument("--phase", choices=("before", "committed", "receipt", "resume", "mixed", "diagnostic-committed", "diagnostic-resume"), required=True)
    parser.add_argument("--isolated-demo", action="store_true")
    args = parser.parse_args()
    parsed = urlsplit(args.server_url)
    if not args.isolated_demo or parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or parsed.path not in ("", "/") or parsed.query or parsed.fragment or parsed.username or parsed.password:
        parser.error("requires an explicit JUnit-owned loopback URL and --isolated-demo")
    association = Association("sim-device-001", "venue-2021", args.server_url)
    payload = json.loads((Path(__file__).resolve().parents[1] / "contracts/examples/telemetry-sample.json").read_text())
    payload["bootId"] = args.boot
    payload["sequenceNumber"] = 77
    encoded = json.dumps(payload, separators=(",", ":")).encode()
    diagnostic = json.loads((Path(__file__).resolve().parents[1] / "contracts/examples/diagnostic-api-examples.json").read_text())["snapshot"]
    diagnostic["bootId"] = args.boot
    diag_bytes = json.dumps(diagnostic, separators=(",", ":")).encode()

    if args.phase == "mixed":
        conflict = dict(payload, rpm=5)
        duplicate = dict(payload, sequenceNumber=78)
        body = json.dumps({"readings": [conflict, duplicate]}).encode()
        request = Request(args.server_url + "/api/v1/device/telemetry/batches", data=body,
                          headers={"Content-Type": "application/json"}, method="POST")
        with urlopen(request, timeout=5) as response:
            seed = json.load(response)
        if seed["accepted"] != 2:
            raise RuntimeError("disposable mixed fixture registration failed")
        with Outbox(args.database, association) as store:
            for number in (77, 78, 79):
                store.enqueue("telemetry", json.dumps(dict(payload, sequenceNumber=number), separators=(",", ":")).encode())
            outcome = TelemetryUploader(store).cycle()
            rows = store.pending()
            print("MIXED", outcome, len(rows), rows[0].state, rows[0].reason, flush=True)
            if outcome != "blocked" or len(rows) != 1 or rows[0].state != "blocked" or "IDENTITY_CONFLICT" not in rows[0].reason:
                raise SystemExit(1)
        return

    def pause():
        args.marker.write_text("ready", encoding="utf-8")
        while True:
            time.sleep(1)

    def open_request(request, timeout):
        if args.phase == "before":
            pause()
        response = urlopen(request, timeout=timeout)
        if args.phase == "diagnostic-committed":
            print("INITIAL_RECEIPT " + response.read().decode(), flush=True)
            pause()
        if args.phase == "committed":
            pause()
        if args.phase == "resume":
            class Response:
                def __enter__(self):
                    response.__enter__()
                    return self
                def __exit__(self, *values):
                    return response.__exit__(*values)
                def read(self, size):
                    value = response.read(size)
                    print("RECEIPT " + value.decode(), flush=True)
                    return value
            return Response()
        return response

    with Outbox(args.database, association) as store:
        if args.phase not in ("resume", "diagnostic-resume"):
            if args.phase != "diagnostic-committed":
                store.enqueue("telemetry", encoded)
            store.enqueue("diagnostic", diag_bytes)
            if args.phase == "diagnostic-committed":
                store.enqueue("telemetry", encoded)
        original = store._change_delivery
        def change(rows, action, **kwargs):
            if args.phase == "receipt" and action == "settle":
                pause()
            return original(rows, action, **kwargs)
        store._change_delivery = change
        uploader = DiagnosticUploader(store, opener=open_request) if args.phase.startswith("diagnostic-") else TelemetryUploader(store, opener=open_request)
        outcome = uploader.cycle()
        print("OUTCOME " + outcome, flush=True)
        print("REMAINING " + ",".join(row.kind for row in store.pending()), flush=True)
        if args.phase == "diagnostic-resume":
            print("DIAGNOSTIC_RECEIPT " + json.dumps(store.status()["last_diagnostic_receipt"]), flush=True)
            if outcome != "settled" or [r.kind for r in store.pending()] != ["telemetry"]:
                raise SystemExit(1)
        if args.phase == "resume" and (outcome != "settled" or [r.kind for r in store.pending()] != ["diagnostic"]):
            raise SystemExit(1)


if __name__ == "__main__":
    main()
