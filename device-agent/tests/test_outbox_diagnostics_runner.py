import io
import json
import sqlite3
import subprocess
import sys
import threading
import time
from datetime import datetime, timedelta, timezone
from urllib.error import URLError
from urllib.error import HTTPError

import pytest

from autopulse_agent.buffering import Association, Outbox
from autopulse_agent.buffering.diagnostic_upload import DiagnosticUploader
from autopulse_agent.buffering.runner import run
from autopulse_agent.sources.simulated import SimulatedTelemetrySource
from test_outbox_store import DIAGNOSTIC, TELEMETRY
from test_outbox_telemetry_upload import receipt

NOW = datetime(2026, 10, 27, tzinfo=timezone.utc)
ASSOC = Association("sim-device-001", "venue-2021", "http://127.0.0.1:8080")
DIAG_ACK = {"snapshotId": "10000000-0000-4000-8000-000000000010", "result": "DUPLICATE", "receivedAt": "2026-10-23T12:00:00.000001Z"}


def test_diagnostic_retry_preserves_bytes_and_original_server_receipt(tmp_path):
    path, requests = tmp_path / "queue.sqlite", []
    def offline(request, timeout):
        requests.append(request.data)
        raise URLError("offline")
    with Outbox(path, ASSOC) as store:
        store.enqueue("diagnostic", DIAGNOSTIC)
        store.enqueue("telemetry", TELEMETRY)
        worker = DiagnosticUploader(store, opener=offline, clock=lambda: NOW, monotonic=lambda: 0, jitter=lambda: 0.5)
        assert worker.cycle() == "waiting"
        assert [r.state for r in store.pending()] == ["waiting", "queued"]
    def recovered(request, timeout):
        requests.append(request.data)
        return io.BytesIO(json.dumps(DIAG_ACK).encode())
    with Outbox(path, ASSOC) as store:
        worker = DiagnosticUploader(store, opener=recovered, clock=lambda: NOW + timedelta(seconds=2), monotonic=lambda: 0)
        assert worker.cycle() == "settled"
        assert store.pending()[0].payload == TELEMETRY
        assert store.status()["last_diagnostic_receipt"] == DIAG_ACK
        assert requests == [DIAGNOSTIC, DIAGNOSTIC]


@pytest.mark.parametrize("bad", [b'{}', b'{"result":"ACCEPTED"}', b'not-json'])
def test_bad_diagnostic_receipt_never_settles_or_skips_head(tmp_path, bad):
    with Outbox(tmp_path / "queue.sqlite", ASSOC) as store:
        store.enqueue("diagnostic", DIAGNOSTIC)
        store.enqueue("telemetry", TELEMETRY)
        worker = DiagnosticUploader(store, opener=lambda request, timeout: io.BytesIO(bad), clock=lambda: NOW, monotonic=lambda: 0, jitter=lambda: 0.5)
        assert worker.cycle() == "waiting"
        assert [r.kind for r in store.pending()] == ["diagnostic", "telemetry"]
        assert store.status()["last_diagnostic_receipt"] is None


def test_collector_commits_while_upload_http_is_stalled(tmp_path):
    from autopulse_agent.buffering.telemetry_upload import TelemetryUploader
    started, release = threading.Event(), threading.Event()
    def stalled(request, timeout):
        started.set()
        assert release.wait(5)
        return io.BytesIO(json.dumps(receipt(["accepted"] * len(json.loads(request.data)["readings"]))).encode())
    with Outbox(tmp_path / "queue.sqlite", ASSOC) as store:
        class CoordinatedSource(SimulatedTelemetrySource):
            def read(self):
                if self._next_index == 1:
                    assert started.wait(5)
                return super().read()
        source = CoordinatedSource(ASSOC.device_id, ASSOC.vehicle_id, NOW, diagnostic_scenario="baseline")
        uploader = TelemetryUploader(store, opener=stalled)
        outcomes, errors = [], []
        def collect():
            try:
                outcomes.append(run(store, source=source, upload=True, pace=0.001, drain_seconds=0, telemetry_uploader=uploader))
            except Exception as error:
                errors.append(error)
        thread = threading.Thread(target=collect)
        thread.start()
        try:
            assert started.wait(5)
            deadline = time.monotonic() + 5
            while len(store.pending()) < 4 and time.monotonic() < deadline:
                time.sleep(0.01)
            assert [row.kind for row in store.pending()] == ["telemetry", "diagnostic", "telemetry", "telemetry"]
        finally:
            release.set()
            thread.join(10)
        assert not errors and outcomes[0]["scenario_completed"]
        assert (outcomes[0]["readings"], outcomes[0]["diagnostics"]) == (3, 1)


def test_collect_restart_and_drain_both_identity_streams_with_stub_receipts(tmp_path):
    calls = []
    def endpoint(request, timeout):
        value = json.loads(request.data)
        if request.full_url.endswith("/batches"):
            calls.extend(("telemetry", item) for item in value["readings"])
            ack = receipt(["accepted"] * len(value["readings"]))
        else:
            calls.append(("diagnostic", value))
            ack = dict(DIAG_ACK, result="ACCEPTED")
        return io.BytesIO(json.dumps(ack).encode())
    path = tmp_path / "offline.sqlite"
    url = "http://127.0.0.1:8080"
    common = [sys.executable, "-m", "autopulse_agent.outbox", "--outbox", str(path), "--server-url", url]
    for _ in range(2):
        result = subprocess.run(common + ["--pace", "0.001", "collect-only"], capture_output=True, text=True, timeout=15)
        assert result.returncode == 2, result.stderr
        assert json.loads(result.stdout)["scenario_completed"]
    association = Association("sim-device-001", "venue-2021", url)
    with Outbox(path, association) as store:
        before = [(r.kind, r.payload) for r in store.pending()]
        assert [k for k, _ in before] == ["telemetry", "diagnostic", "telemetry", "telemetry"] * 2
        boots = [json.loads(payload)["bootId"] for _, payload in before]
        assert len(set(boots[:4])) == len(set(boots[4:])) == 1 and boots[0] != boots[4]
        from autopulse_agent.buffering.telemetry_upload import TelemetryUploader
        tick = iter(range(100))
        clock = lambda: next(tick)
        telemetry = TelemetryUploader(store, opener=endpoint, monotonic=clock)
        diagnostic = DiagnosticUploader(store, opener=endpoint, monotonic=clock)
        while store.pending():
            state = telemetry.cycle()
            if state == "diagnostic_head":
                state = diagnostic.cycle()
            assert state == "settled"
        assert [kind for kind, _ in calls] == [kind for kind, _ in before]
        assert [payload for _, payload in calls] == [json.loads(payload) for _, payload in before]
        assert store.status()["last_diagnostic_receipt"]["receivedAt"] == DIAG_ACK["receivedAt"]


def test_status_requires_existing_file(tmp_path):
    path = tmp_path / "missing.sqlite"
    result = subprocess.run([sys.executable, "-m", "autopulse_agent.outbox", "status", "--outbox", str(path)], capture_output=True, timeout=10)
    assert result.returncode == 1 and not path.exists()


def test_read_only_status_works_while_runner_owns_store(tmp_path):
    path = tmp_path / "queue.sqlite"
    with Outbox(path, ASSOC) as store:
        store.enqueue("telemetry", TELEMETRY)
        result = subprocess.run([sys.executable, "-m", "autopulse_agent.outbox", "status", "--outbox", str(path)], capture_output=True, text=True, timeout=10)
        assert result.returncode == 0, result.stderr
        status = json.loads(result.stdout)
        assert status["groups"][0]["count"] == 1
        assert status["collection_state"] == "stopped" and status["owner_active"]


def test_diagnostic_permanent_failure_blocks_later_telemetry(tmp_path):
    def reject(request, timeout):
        raise HTTPError(request.full_url, 409, "conflict", {}, io.BytesIO())
    with Outbox(tmp_path / "queue.sqlite", ASSOC) as store:
        store.enqueue("diagnostic", DIAGNOSTIC)
        store.enqueue("telemetry", TELEMETRY)
        worker = DiagnosticUploader(store, opener=reject, clock=lambda: NOW, monotonic=lambda: 0)
        assert worker.cycle() == "blocked"
        assert store.blocked()[0].payload == DIAGNOSTIC
        assert store.pending()[1].payload == TELEMETRY


def test_version_two_store_upgrades_without_losing_pending_data(tmp_path):
    path = tmp_path / "queue.sqlite"
    with Outbox(path, ASSOC) as store:
        store.enqueue("diagnostic", DIAGNOSTIC)
    with sqlite3.connect(path) as db:
        db.execute("DROP TABLE capacity")
        db.execute("ALTER TABLE health DROP COLUMN pause_reason")
        db.execute("ALTER TABLE health DROP COLUMN last_pause_seconds")
        db.execute("ALTER TABLE health DROP COLUMN pause_count")
        db.execute("DROP TABLE diagnostic_receipt")
        db.execute("PRAGMA user_version=2")
    with Outbox(path, ASSOC) as store:
        assert store.pending()[0].payload == DIAGNOSTIC
        assert store.status()["last_diagnostic_receipt"] is None
        with sqlite3.connect(path) as db:
            assert db.execute("PRAGMA user_version").fetchone()[0] == 4


def test_partial_failed_and_timeout_reads_are_stored_explicitly(tmp_path):
    with Outbox(tmp_path / "queue.sqlite", ASSOC) as store:
        source = SimulatedTelemetrySource(ASSOC.device_id, ASSOC.vehicle_id, NOW, scenario="normal-commute", diagnostic_scenario="lifecycle")
        result = run(store, source=source, pace=0.001, max_readings=8)
        snapshots = [json.loads(row.payload) for row in store.pending() if row.kind == "diagnostic"]
        assert result["diagnostics"] == 8
        assert [snapshot["ecus"][0]["outcome"] for snapshot in snapshots[4:8]] == ["PARTIAL", "UNAVAILABLE", "FAILED", "TIMEOUT"]
        assert snapshots[4]["ecus"][0]["mil"]["illuminated"] is None


def test_collector_pauses_before_read_when_disk_headroom_unavailable(tmp_path, monkeypatch):
    from autopulse_agent.buffering import CapacityPolicy
    with Outbox(tmp_path / "queue.sqlite", ASSOC, policy=CapacityPolicy(payload_bytes=100)) as store:
        monkeypatch.setattr(store, "_free_space", lambda _: 0)
        source = SimulatedTelemetrySource(ASSOC.device_id, ASSOC.vehicle_id, NOW, diagnostic_scenario="baseline")
        result = run(store, source=source, pace=0.001)
        assert result["capacity_paused"] and result["readings"] == 0
        assert source._next_index == 0 and store.pending() == []
