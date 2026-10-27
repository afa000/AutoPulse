"""Disposable real HTTP outage/restart probe, run only by the JUnit demo."""

import argparse
import json
import os
import socket
import sqlite3
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
from uuid import UUID


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--server-url", required=True)
    parser.add_argument("--database", type=Path, required=True)
    parser.add_argument("--isolated-demo", action="store_true")
    args = parser.parse_args()
    parsed = urlsplit(args.server_url)
    if not args.isolated_demo or parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or parsed.path not in ("", "/") or parsed.query or parsed.fragment or parsed.username or parsed.password:
        parser.error("requires an explicit JUnit-owned loopback URL and --isolated-demo")
    online = threading.Event()
    paths = []
    offline_attempts = []
    dropped = []
    duplicates = []
    armed = threading.Event()
    class Proxy(BaseHTTPRequestHandler):
        def log_message(self, *values):
            pass
        def do_POST(self):
            data = self.rfile.read(int(self.headers["Content-Length"]))
            if not online.is_set():
                offline_attempts.append(self.path)
                self.send_response(503)
                self.end_headers()
                return
            paths.append(self.path)
            request = Request(args.server_url + self.path, data=data, headers={"Content-Type": "application/json"}, method="POST")
            try:
                with urlopen(request, timeout=5) as response:
                    body, code = response.read(), response.status
            except HTTPError as error:
                body, code = error.read(), error.code
            if armed.is_set() and not dropped:
                answer = json.loads(body)
                if code != 200 or answer.get("accepted") != 1:
                    raise RuntimeError("expected a committed telemetry baseline before dropping its response")
                dropped.append((self.path, data))
                self.close_connection = True
                self.connection.shutdown(socket.SHUT_RDWR)
                self.connection.close()
                return
            if dropped and not duplicates:
                if (self.path, data) != dropped[0] or json.loads(body).get("duplicate") != 1:
                    raise RuntimeError("lost-response retry changed the request or lacked a duplicate receipt")
                duplicates.append(True)
            self.send_response(code)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    server = ThreadingHTTPServer(("127.0.0.1", 0), Proxy)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    proxy = f"http://127.0.0.1:{server.server_port}"
    env = dict(os.environ)
    source_path = str(Path(__file__).resolve().parents[1] / "device-agent/src")
    env["PYTHONPATH"] = source_path + os.pathsep + env.get("PYTHONPATH", "")
    sys.path.insert(0, source_path)
    from autopulse_agent import json_codec
    from autopulse_agent.models.diagnostics import snapshot_to_json
    from autopulse_agent.models.telemetry_json import sample_to_payload
    from autopulse_agent.sources.simulated import SimulatedTelemetrySource

    def expected(scenario, diagnostic, count):
        source = SimulatedTelemetrySource("sim-device-001", "venue-2021", datetime(2026, 10, 20, 12, tzinfo=timezone.utc),
                                          boot_id=UUID(int=0), scenario=scenario, diagnostic_scenario=diagnostic)
        output = []
        source.connect()
        try:
            diagnostic_done = False
            for _ in range(count):
                output.append({"kind": "telemetry", "payload": json.loads(json_codec.dumps(sample_to_payload(source.read())))})
                if not diagnostic_done:
                    try:
                        output.append({"kind": "diagnostic", "payload": json.loads(snapshot_to_json(source.read_diagnostics()))})
                    except StopIteration:
                        diagnostic_done = True
        finally:
            source.close()
        return output

    # Prepare expected content before any collection; each real process will supply
    # its own fresh boot, substituted only for comparison with the templates.
    baseline_expected = expected("snapshots", "baseline", 3)
    offline_expected = expected("normal-commute", "lifecycle", 5)

    def compare_template(actual, template, boot):
        wanted = [{"kind": item["kind"], "payload": dict(item["payload"], bootId=boot)} for item in template]
        if actual != wanted:
            raise RuntimeError("committed observations do not match the prepared manifest")

    common = [sys.executable, "-m", "autopulse_agent.outbox", "--outbox", str(args.database), "--server-url", proxy,
              "--scenario", "normal-commute", "--diagnostic-scenario", "lifecycle", "--start-at", "2026-10-20T12:00:00Z",
              "--max-readings", "5", "--pace", "0.05"]
    child = None
    try:
        baseline_db = args.database.with_name(args.database.stem + "-baseline.sqlite")
        baseline = [sys.executable, "-m", "autopulse_agent.outbox", "--outbox", str(baseline_db), "--server-url", proxy,
                    "--scenario", "snapshots", "--diagnostic-scenario", "baseline", "--start-at", "2026-10-20T12:00:00Z", "--pace", "0.01"]
        collected = subprocess.run(baseline + ["collect-only"], capture_output=True, text=True, timeout=15, env=env)
        if collected.returncode != 2:
            raise RuntimeError("baseline collection failed: " + collected.stderr + collected.stdout)
        with sqlite3.connect(baseline_db) as db:
            baseline_manifest = [{"kind": kind, "payload": json.loads(payload)} for kind, payload in db.execute("SELECT kind,payload FROM outbox ORDER BY enqueue_id")]
        if len(baseline_manifest) != 4:
            raise RuntimeError("unexpected baseline manifest")
        compare_template(baseline_manifest, baseline_expected, baseline_manifest[0]["payload"]["bootId"])
        online.set()
        baseline_drain = subprocess.run(baseline + ["--drain-seconds", "10", "drain-only"], capture_output=True, text=True, timeout=20, env=env)
        if baseline_drain.returncode != 0:
            raise RuntimeError("baseline drain failed: " + baseline_drain.stderr + baseline_drain.stdout)
        online.clear()
        paths.clear()
        child = subprocess.Popen(common + ["--drain-seconds", "60", "collect-upload"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
        deadline = time.monotonic() + 20
        count = 0
        while time.monotonic() < deadline and child.poll() is None:
            if args.database.exists():
                try:
                    with sqlite3.connect(args.database, timeout=1) as db:
                        count = db.execute("SELECT COUNT(*) FROM outbox").fetchone()[0]
                except sqlite3.Error:
                    pass
            if count == 10 and offline_attempts:
                break
            time.sleep(0.05)
        if count != 10 or not offline_attempts:
            raise RuntimeError("offline collector did not persist ten observations and attempt delivery")
        with sqlite3.connect(args.database, timeout=2) as db:
            first_rows = db.execute("SELECT kind,payload FROM outbox ORDER BY enqueue_id").fetchall()
        if len(first_rows) != 10:
            raise RuntimeError("unexpected first-process manifest")
        child.kill()
        child.communicate(timeout=10)
        child = None
        second = subprocess.run(common + ["collect-only"], capture_output=True, text=True, timeout=15, env=env)
        if second.returncode != 2:
            raise RuntimeError("second collector failed: " + second.stderr + second.stdout)
        with sqlite3.connect(args.database) as db:
            rows = db.execute("SELECT kind,payload FROM outbox ORDER BY enqueue_id").fetchall()
        if rows[:10] != first_rows:
            raise RuntimeError("restart changed the first process's stored content")
        manifest = [{"kind": kind, "payload": json.loads(payload)} for kind, payload in rows]
        if len(manifest) != 20:
            raise RuntimeError("unexpected local backlog")
        boots = [item["payload"]["bootId"] for item in manifest]
        if len(set(boots[:10])) != 1 or len(set(boots[10:])) != 1 or boots[0] == boots[10]:
            raise RuntimeError("restart did not preserve old boot and generate a new boot")
        compare_template(manifest[:10], offline_expected, boots[0])
        compare_template(manifest[10:], offline_expected, boots[10])
        armed.set()
        online.set()
        drained = subprocess.run(common + ["--drain-seconds", "40", "drain-only"], capture_output=True, text=True, timeout=50, env=env)
        if drained.returncode != 0:
            raise RuntimeError("drain failed: " + drained.stderr + drained.stdout)
        with sqlite3.connect(args.database) as db:
            remaining = db.execute("SELECT COUNT(*) FROM outbox").fetchone()[0]
        if remaining or len(paths) != 21 or len(duplicates) != 1:
            raise RuntimeError("outage drain did not process every ordered item")
        kinds = ["diagnostic" if path.endswith("diagnostic-snapshots") else "telemetry" for path in paths]
        if kinds[1:] != [item["kind"] for item in manifest]:
            raise RuntimeError("outage drain changed enqueue order")
        tiny_db = args.database.with_name(args.database.stem + "-tiny.sqlite")
        tiny = [sys.executable, "-m", "autopulse_agent.outbox", "--outbox", str(tiny_db), "--server-url", proxy,
                "--max-pending-rows", "1", "--min-free-bytes", "0", "--pace", "0.01", "collect-only"]
        tiny_result = subprocess.run(tiny, capture_output=True, text=True, timeout=15, env=env)
        if tiny_result.returncode != 2 or not json.loads(tiny_result.stdout)["capacity_paused"]:
            raise RuntimeError("separate tiny-capacity pause failed: " + tiny_result.stderr + tiny_result.stdout)
        with sqlite3.connect(tiny_db) as db:
            if db.execute("SELECT COUNT(*) FROM outbox").fetchone()[0] != 1:
                raise RuntimeError("tiny-capacity pause lost committed evidence")
        print(json.dumps({"manifest": manifest, "baseline_manifest": baseline_manifest, "request_kinds": kinds,
                          "remaining": remaining, "offline_attempts": len(offline_attempts), "lost_responses": len(dropped),
                          "duplicate_receipts": len(duplicates), "tiny_capacity_paused": True}), flush=True)
    finally:
        if child is not None and child.poll() is None:
            child.kill()
            child.communicate(timeout=10)
        server.shutdown()
        server.server_close()
        thread.join(5)


if __name__ == "__main__":
    main()
