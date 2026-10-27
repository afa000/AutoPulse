import sqlite3
import io
import json
import subprocess
import sys
import threading
import time
from datetime import datetime, timedelta, timezone

import pytest

from autopulse_agent.buffering import Association, CapacityError, CapacityPolicy, Outbox, OutboxError
from autopulse_agent.buffering.runner import run
from autopulse_agent.sources.simulated import SimulatedTelemetrySource
from test_outbox_store import reading

NOW = datetime(2026, 10, 27, tzinfo=timezone.utc)
ASSOC = Association("sim-device-001", "venue-2021", "http://127.0.0.1:8080")


def policy(**options):
    return CapacityPolicy(**dict({"payload_bytes": 16384, "rows": 4, "oldest_seconds": 3600,
                                  "database_bytes": 128 * 1024, "control_reserve_bytes": 32 * 1024,
                                  "disk_free_bytes": 0}, **options))


def settle(store, count):
    rows = store.pending(limit=count)
    store._change_delivery(rows, "settle", outcomes=[("accepted", None)] * len(rows), now=NOW.isoformat())


def test_pause_persists_and_resumes_only_below_low_water(tmp_path):
    path = tmp_path / "outbox.sqlite"
    with Outbox(path, ASSOC, policy=policy()) as store:
        for n in range(4):
            store.enqueue("telemetry", reading(sequenceNumber=n), now=NOW)
        assert not store.collection_room(100, now=NOW)
        health = store.status(now=NOW)
        assert health["collection_state"] == "paused" and health["pause_reason"] == "logical_capacity"
        assert health["pause_count"] == 1 and health["oldest_pending_age_seconds"] == 0
    with Outbox(path, ASSOC) as store:
        assert store.policy == policy() and store.status(now=NOW)["collection_state"] == "paused"
        settle(store, 1)
        assert store.collection_room(100, now=NOW + timedelta(seconds=8))
        health = store.status(now=NOW + timedelta(seconds=8))
        assert health["collection_state"] == "collecting"
        assert health["last_pause_seconds"] == 8 and health["pause_count"] == 1
        assert len(store.pending()) == 3


def test_blocked_rows_count_against_atomic_limits_and_are_preserved(tmp_path):
    with Outbox(tmp_path / "outbox.sqlite", ASSOC, policy=policy(rows=2)) as store:
        store.enqueue("telemetry", reading(sequenceNumber=1), now=NOW)
        store.enqueue("telemetry", reading(sequenceNumber=2), now=NOW)
        rows = store.pending()
        store._change_delivery(rows[:1], "block", reason="review")
        assert not store.collection_room(1, now=NOW)
        with pytest.raises(CapacityError):
            store.enqueue("telemetry", reading(sequenceNumber=3), now=NOW)
        assert [row.payload for row in store.pending()] == [row.payload for row in rows]
        assert store.status(now=NOW)["blocked"]["reason"] == "review"


def test_old_age_pauses_without_deletion_and_drain_can_resume(tmp_path):
    with Outbox(tmp_path / "outbox.sqlite", ASSOC, policy=policy(oldest_seconds=10)) as store:
        store.enqueue("telemetry", reading(sequenceNumber=1), now=NOW)
        assert not store.collection_room(1, now=NOW + timedelta(seconds=10))
        assert store.status(now=NOW + timedelta(seconds=10))["pause_reason"] == "oldest_pending_age"
        with pytest.raises(CapacityError):
            store.enqueue("telemetry", reading(sequenceNumber=2), now=NOW + timedelta(seconds=11))
        assert len(store.pending()) == 1
        settle(store, 1)
        assert store.collection_room(1, now=NOW + timedelta(seconds=12))
        assert store.status(now=NOW + timedelta(seconds=12))["last_pause_seconds"] == 2


def test_clock_jump_is_visible_and_does_not_invent_pause_duration(tmp_path):
    with Outbox(tmp_path / "outbox.sqlite", ASSOC, policy=policy(rows=1)) as store:
        store.enqueue("telemetry", reading(sequenceNumber=1), now=NOW)
        assert not store.collection_room(1, now=NOW + timedelta(seconds=20))
        earlier = NOW - timedelta(days=1)
        status = store.status(now=earlier)
        assert status["clock_warning"] and status["oldest_pending_age_seconds"] == 0
        settle(store, 1)
        assert store.collection_room(1, now=earlier)
        assert store.status(now=earlier)["last_pause_seconds"] is None


def test_disk_headroom_pause_and_recovery_without_eviction(tmp_path):
    free = [200]
    with Outbox(tmp_path / "outbox.sqlite", ASSOC, policy=policy(disk_free_bytes=100), free_space=lambda _: free[0]) as store:
        store.enqueue("telemetry", reading(sequenceNumber=1), now=NOW)
        free[0] = 99
        assert not store.collection_room(1, now=NOW)
        assert store.status(now=NOW)["pause_reason"] == "disk_headroom"
        assert store.pending()[0].payload == reading(sequenceNumber=1)
        free[0] = 200
        assert store.collection_room(1, now=NOW + timedelta(seconds=3))


def test_storage_failure_is_reported_and_recorded_when_possible(tmp_path, monkeypatch):
    with Outbox(tmp_path / "outbox.sqlite", ASSOC, policy=policy()) as store:
        source = SimulatedTelemetrySource(ASSOC.device_id, ASSOC.vehicle_id, NOW, diagnostic_scenario="baseline")
        def broken(_):
            raise OSError("simulated disk failure")
        monkeypatch.setattr(store, "_free_space", broken)
        with pytest.raises(OutboxError, match="disk space"):
            run(store, source=source, pace=0.001)
        monkeypatch.setattr(store, "_free_space", lambda _: 1000)
        assert store.status()["collection_state"] == "storage_failure"
        assert store.pending() == []


def test_storage_failure_does_not_claim_unwritten_health_record(tmp_path, monkeypatch):
    with Outbox(tmp_path / "outbox.sqlite", ASSOC, policy=policy()) as store:
        source = SimulatedTelemetrySource(ASSOC.device_id, ASSOC.vehicle_id, NOW, diagnostic_scenario="baseline")
        original = store._connect
        def broken():
            raise OutboxError("simulated write failure")
        monkeypatch.setattr(store, "_connect", broken)
        with pytest.raises(OutboxError):
            run(store, source=source, pace=0.001)
        monkeypatch.setattr(store, "_connect", original)
        assert store.status()["collection_state"] == "stopped"
        assert store.pending() == []


def test_running_collector_waits_for_drain_and_resumes(tmp_path):
    limits = CapacityPolicy(payload_bytes=1024 * 1024, rows=2, oldest_seconds=3600,
                            database_bytes=2 * 1024 * 1024, control_reserve_bytes=128 * 1024, disk_free_bytes=0)
    gate = threading.Event()
    with Outbox(tmp_path / "outbox.sqlite", ASSOC, policy=limits) as store:
        source = SimulatedTelemetrySource(ASSOC.device_id, ASSOC.vehicle_id, NOW, diagnostic_scenario="baseline")
        class Uploader:
            def __init__(self, kind):
                self.kind = kind
            def cycle(self):
                rows = store.pending(limit=1)
                if not rows:
                    return "empty"
                if rows[0].kind != self.kind:
                    return "diagnostic_head" if self.kind == "telemetry" else "telemetry_head"
                if not gate.is_set():
                    return "waiting"
                store._change_delivery(rows, "settle", outcomes=[("accepted", None)], now=NOW.isoformat())
                return "settled"
        outcomes, failures = [], []
        def collect():
            try:
                outcomes.append(run(store, source=source, upload=True, pace=0.001, drain_seconds=2,
                                    telemetry_uploader=Uploader("telemetry"), diagnostic_uploader=Uploader("diagnostic")))
            except Exception as error:
                failures.append(error)
        thread = threading.Thread(target=collect)
        thread.start()
        try:
            deadline = time.monotonic() + 5
            while store.status()["collection_state"] != "paused" and time.monotonic() < deadline:
                time.sleep(0.01)
            assert store.status()["collection_state"] == "paused"
            assert len(store.pending()) == 2 and source._next_index == 1
        finally:
            gate.set()
            thread.join(10)
        assert not thread.is_alive() and not failures
        assert outcomes[0]["scenario_completed"] and outcomes[0]["readings"] == 3
        status = store.status()
        assert status["pause_count"] >= 1 and status["pause_ended_at"] is not None
        assert status["collection_state"] == "stopped" and not store.pending()


def test_repeated_fill_drain_reuses_pages_and_bounds_files(tmp_path):
    path = tmp_path / "outbox.sqlite"
    limits = policy(rows=8)
    high_water = 0
    with Outbox(path, ASSOC, policy=limits) as store:
        for cycle in range(20):
            for n in range(8):
                payload = reading(sequenceNumber=cycle * 8 + n) + b" " * 1500
                store.enqueue("telemetry", payload, now=NOW)
            status = store.status(now=NOW)
            high_water = max(high_water, status["physical"]["database_bytes"] + status["physical"]["journal_bytes"])
            assert status["physical"]["database_bytes"] <= limits.database_bytes - limits.control_reserve_bytes
            settle(store, 8)
            assert not store.pending()
        with sqlite3.connect(path) as db:
            assert db.execute("PRAGMA freelist_count").fetchone()[0] > 0
    assert high_water <= limits.database_bytes


def test_database_reserve_allows_pause_and_drain_at_data_limit(tmp_path):
    limits = policy(payload_bytes=1024 * 1024, rows=100)
    with Outbox(tmp_path / "outbox.sqlite", ASSOC, policy=limits) as store:
        for n in range(50):
            if not store.collection_room(8192, now=NOW):
                break
            store.enqueue("telemetry", reading(sequenceNumber=n) + b" " * 6000, now=NOW)
        else:
            pytest.fail("database limit did not pause the collector")
        status = store.status(now=NOW)
        assert status["pause_reason"] == "database_capacity"
        assert status["physical"]["database_bytes"] <= limits.database_bytes - limits.control_reserve_bytes
        assert store.pending()
        settle(store, len(store.pending()))
        assert store.collection_room(8192, now=NOW + timedelta(seconds=1))
        assert not store.pending()


def test_live_rollback_journal_is_accounted_and_bounded(tmp_path):
    path = tmp_path / "outbox.sqlite"
    limits = policy(rows=8)
    with Outbox(path, ASSOC, policy=limits) as store:
        for n in range(8):
            store.enqueue("telemetry", reading(sequenceNumber=n) + b" " * 1500, now=NOW)
        with sqlite3.connect(path, isolation_level=None) as db:
            db.execute("PRAGMA synchronous=FULL")
            db.execute("BEGIN IMMEDIATE")
            db.execute("UPDATE outbox SET attempts=attempts+1")
            journal = path.with_name(path.name + "-journal")
            assert journal.exists() and journal.stat().st_size > 0
            assert journal.stat().st_size <= path.stat().st_size + 8192
            assert path.stat().st_size + journal.stat().st_size <= 2 * limits.database_bytes + 8192
            db.execute("ROLLBACK")
        assert all(row.attempts == 0 for row in store.pending())


def test_persisted_policy_mismatch_does_not_modify_file(tmp_path):
    path = tmp_path / "outbox.sqlite"
    with Outbox(path, ASSOC, policy=policy()) as store:
        store.enqueue("telemetry", reading(sequenceNumber=1), now=NOW)
    before = path.read_bytes()
    with pytest.raises(OutboxError, match="policy mismatch"):
        Outbox(path, ASSOC, policy=policy(rows=3))
    assert path.read_bytes() == before
    with pytest.raises(OutboxError, match="policy mismatch"):
        Outbox(path, ASSOC, policy=policy(database_bytes=256 * 1024))
    assert path.read_bytes() == before


def test_cli_reports_pause_and_reuses_persisted_limits_after_restart(tmp_path):
    path = tmp_path / "outbox.sqlite"
    command = [sys.executable, "-m", "autopulse_agent.outbox", "--outbox", str(path)]
    result = subprocess.run(command + ["--max-pending-rows", "1", "--min-free-bytes", "0", "--pace", "0.001", "collect-only"], capture_output=True, text=True, timeout=10)
    assert result.returncode == 2, result.stderr
    output = json.loads(result.stdout)
    assert output["capacity_paused"] and output["readings"] == 1
    result = subprocess.run(command + ["status"], capture_output=True, text=True, timeout=10)
    assert result.returncode == 0, result.stderr
    status = json.loads(result.stdout)
    assert status["collection_state"] == "paused" and status["pause_reason"] == "logical_capacity"
    assert status["capacity"]["rows"] == 1 and status["groups"][0]["count"] == 1


def test_reservation_larger_than_entire_buffer_records_a_pause(tmp_path):
    with Outbox(tmp_path / "outbox.sqlite", ASSOC, policy=policy(payload_bytes=100)) as store:
        assert not store.collection_room(200, now=NOW)
        assert store.status(now=NOW)["pause_reason"] == "logical_capacity"
        assert not store.pending()


@pytest.mark.parametrize("mode", ["collect-only", "collect-upload"])
def test_cli_oversized_observation_pauses_without_retry_loop(tmp_path, mode):
    path = tmp_path / "outbox.sqlite"
    result = subprocess.run([sys.executable, "-m", "autopulse_agent.outbox", mode,
                            "--outbox", str(path), "--server-url", "http://127.0.0.1:1",
                            "--max-payload-bytes", "100", "--min-free-bytes", "0", "--pace", "0.001"],
                            capture_output=True, text=True, timeout=5)
    assert result.returncode == 2, result.stderr
    output = json.loads(result.stdout)
    assert output["capacity_paused"] and output["readings"] == output["diagnostics"] == 0
    assert output["status"]["collection_state"] == "paused"
    assert output["status"]["pause_reason"] == "logical_capacity"
    assert output["status"]["groups"] == []


@pytest.mark.parametrize("review", ["discard", "release"])
def test_control_reserve_growth_can_resume_using_freed_pages(tmp_path, review):
    from autopulse_agent.buffering.telemetry_upload import TelemetryUploader
    from test_outbox_telemetry_upload import receipt

    path = tmp_path / "outbox.sqlite"
    limits = policy(payload_bytes=1024 * 1024, rows=100, control_reserve_bytes=16 * 1024)
    data_limit = limits.database_bytes - limits.control_reserve_bytes
    calls = []

    def endpoint(request, timeout):
        count = len(json.loads(request.data)["readings"])
        response = receipt(["rejected" if not calls else "accepted"] * count)
        for item in response["results"]:
            if item["status"] == "rejected":
                item["errors"] = [{"field": "rpm", "message": "x" * 990}]
        calls.append(request.data)
        return io.BytesIO(json.dumps(response).encode())

    with Outbox(path, ASSOC, policy=limits) as store:
        for number in range(100):
            if not store.collection_room(8192, now=NOW):
                break
            payload = json.dumps(json.loads(reading(sequenceNumber=number))).encode() + b" " * 4000
            store.enqueue("telemetry", payload, now=NOW)
        else:
            pytest.fail("database budget did not pause collection")
        original = [row.payload for row in store.pending()]
        assert original and store.status(now=NOW)["pause_reason"] == "database_capacity"
        worker = TelemetryUploader(store, opener=endpoint, clock=lambda: NOW, monotonic=lambda: len(calls) * 2)
        assert worker.cycle() == "blocked"
        assert [row.payload for row in store.blocked()] == original
        allocated = path.stat().st_size
        assert data_limit < allocated <= limits.database_bytes
        for row in store.blocked():
            store.review_action(row.enqueue_id, review, now=NOW)
        if review == "release":
            assert worker.cycle() == "settled"
        assert not store.pending()

    # Reopen exercises the persisted file size, not just a connection-local limit.
    with Outbox(path, ASSOC) as store:
        assert store.collection_room(8192, now=NOW + timedelta(seconds=1))
        payload = reading(sequenceNumber=1000) + b" " * 4000
        store.enqueue("telemetry", payload, now=NOW + timedelta(seconds=1))
        assert store.pending()[0].payload == payload
        assert path.stat().st_size == allocated
        # Direct enqueues must also preserve the active-page control reserve.
        for number in range(1001, 1101):
            try:
                store.enqueue("telemetry", reading(sequenceNumber=number) + b" " * 4000,
                              now=NOW + timedelta(seconds=1))
            except CapacityError:
                break
        else:
            pytest.fail("direct enqueue exceeded the database budget")
        with sqlite3.connect(path) as db:
            used_pages = db.execute("PRAGMA page_count").fetchone()[0] - db.execute("PRAGMA freelist_count").fetchone()[0]
            assert used_pages * db.execute("PRAGMA page_size").fetchone()[0] <= data_limit
        assert store.pending()[0].payload == payload


def test_cli_blocked_inspect_export_release_and_explicit_discard(tmp_path):
    path, export = tmp_path / "outbox.sqlite", tmp_path / "evidence.json"
    with Outbox(path, ASSOC, policy=policy()) as store:
        store.enqueue("telemetry", reading(sequenceNumber=1), now=NOW)
        store._change_delivery(store.pending(), "block", reason="review")
    base = [sys.executable, "-m", "autopulse_agent.outbox", "--outbox", str(path)]
    inspected = subprocess.run(base + ["inspect"], capture_output=True, text=True, timeout=10)
    assert inspected.returncode == 0 and json.loads(inspected.stdout)[0]["reason"] == "review"
    exported = subprocess.run(base + ["--enqueue-id", "1", "--output", str(export), "export-blocked"], capture_output=True, text=True, timeout=10)
    assert exported.returncode == 0 and export.read_bytes() == reading(sequenceNumber=1)
    assert subprocess.run(base + ["--enqueue-id", "1", "--output", str(export), "export-blocked"], capture_output=True, timeout=10).returncode == 1
    assert subprocess.run(base + ["--enqueue-id", "1", "discard"], capture_output=True, timeout=10).returncode == 1
    assert subprocess.run(base + ["--enqueue-id", "1", "release"], capture_output=True, timeout=10).returncode == 0
    with Outbox(path, ASSOC) as store:
        store._change_delivery(store.pending(), "block", reason="review again")
    assert subprocess.run(base + ["--enqueue-id", "1", "--confirm-discard", "discard"], capture_output=True, timeout=10).returncode == 0
    with Outbox(path, ASSOC) as store:
        assert store.pending() == [] and store.status()["discarded_count"] == 1
