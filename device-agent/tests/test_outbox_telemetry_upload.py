import io
import json
import sqlite3
import threading
from datetime import datetime, timedelta, timezone
from urllib.error import HTTPError, URLError

import pytest

from autopulse_agent.buffering import Association, Outbox
from autopulse_agent.buffering.telemetry_upload import TelemetryUploader
from autopulse_agent.transport.http import MAX_BYTES
from test_outbox_store import DIAGNOSTIC, TELEMETRY, reading

NOW = datetime(2026, 10, 27, tzinfo=timezone.utc)
ASSOC = Association("sim-device-001", "venue-2021", "http://127.0.0.1:8080")


def receipt(statuses):
    results = []
    for index, status in enumerate(statuses):
        result = {"index": index, "status": status}
        if status == "rejected":
            result.update(code="IDENTITY_CONFLICT", errors=[{"field": "bootId", "message": "changed"}])
        results.append(result)
    return {**{s: statuses.count(s) for s in ("accepted", "duplicate", "rejected")},
            "results": results, "correlationId": "123e4567-e89b-42d3-a456-426614174000"}


def opener(response, calls):
    def open_request(request, timeout):
        calls.append(request.data)
        assert timeout == 5
        return io.BytesIO(json.dumps(response).encode())
    return open_request


def uploader(store, response, calls, clock=lambda: NOW, monotonic=lambda: 0.0):
    return TelemetryUploader(store, opener=opener(response, calls), clock=clock, monotonic=monotonic, jitter=lambda: 0.5)


def test_mixed_ack_maps_indexes_and_blocks_following_delivery(tmp_path):
    calls = []
    result = receipt(["accepted", "duplicate", "rejected"])
    result["results"].reverse()
    with Outbox(tmp_path / "queue.sqlite", ASSOC) as store:
        for n in range(1, 4):
            store.enqueue("telemetry", reading(sequenceNumber=n))
        worker = uploader(store, result, calls)
        assert worker.cycle() == "blocked"
        pending = store.pending()
        assert len(pending) == 1 and pending[0].enqueue_id == 3
        assert pending[0].state == "blocked" and "IDENTITY_CONFLICT" in pending[0].reason
        assert store.blocked(limit=1)[0].payload == reading(sequenceNumber=3)
        assert store.blocked(after_id=3) == []
        store.enqueue("telemetry", reading(sequenceNumber=4))
        assert worker.cycle() == "blocked" and len(calls) == 1
        store.review_action(3, "discard", now=NOW)
        assert store.pending()[0].enqueue_id == 4
        with sqlite3.connect(store.path) as db:
            assert db.execute("SELECT acknowledged_count, discarded_count FROM health").fetchone() == (2, 1)
            assert db.execute("SELECT last_action, last_enqueue_id FROM review_audit").fetchone() == ("discard", 3)


@pytest.mark.parametrize("change", ["missing", "duplicate_index", "counts", "status", "reason", "correlation", "oversized_reason"])
def test_ambiguous_ack_never_settles_any_item(tmp_path, change):
    result = receipt(["accepted", "rejected"])
    if change == "missing": result["results"].pop()
    elif change == "duplicate_index": result["results"][1]["index"] = 0
    elif change == "counts": result["accepted"] = 2
    elif change == "status": result["results"][0]["status"] = "other"
    elif change == "reason": result["results"][1]["errors"] = [{"field": "rpm"}]
    elif change == "correlation": result.pop("correlationId")
    else: result["results"][1]["errors"][0]["message"] = "x" * 2000
    with Outbox(tmp_path / "queue.sqlite", ASSOC) as store:
        store.enqueue("telemetry", TELEMETRY)
        store.enqueue("telemetry", reading(sequenceNumber=2))
        assert uploader(store, result, []).cycle() == "waiting"
        rows = store.pending()
        assert len(rows) == 2 and [r.state for r in rows] == ["waiting", "queued"]
        assert rows[0].payload == TELEMETRY


def test_retry_persists_across_restart_and_clamps_backward_clock(tmp_path):
    path, calls = tmp_path / "queue.sqlite", []
    def offline(request, timeout):
        calls.append(request.data)
        raise URLError("offline")
    with Outbox(path, ASSOC) as store:
        store.enqueue("telemetry", TELEMETRY)
        worker = TelemetryUploader(store, opener=offline, clock=lambda: NOW, monotonic=lambda: 0, jitter=lambda: 0.5)
        assert worker.cycle() == "waiting"
        assert store.pending()[0].attempts == 1
    earlier = NOW - timedelta(days=10)
    with Outbox(path, ASSOC) as store:
        worker = uploader(store, receipt(["duplicate"]), calls, clock=lambda: earlier)
        assert worker.cycle() == "waiting"
        assert datetime.fromisoformat(store.pending()[0].retry_at) == earlier + timedelta(seconds=300)
    with Outbox(path, ASSOC) as store:
        worker = uploader(store, receipt(["duplicate"]), calls, clock=lambda: earlier + timedelta(seconds=301))
        assert worker.cycle() == "settled"
        assert store.pending() == []
    assert calls[0] == calls[1]


def test_whole_request_rejection_requires_explicit_review(tmp_path):
    def reject(request, timeout):
        raise HTTPError(request.full_url, 400, "bad request", {}, io.BytesIO())
    with Outbox(tmp_path / "queue.sqlite", ASSOC) as store:
        store.enqueue("telemetry", TELEMETRY)
        store.enqueue("telemetry", reading(sequenceNumber=2))
        worker = TelemetryUploader(store, opener=reject, clock=lambda: NOW, monotonic=lambda: 0)
        assert worker.cycle() == "blocked"
        assert [r.state for r in store.pending()] == ["blocked", "blocked"]
        store.review_action(1, "release", now=NOW)
        assert worker.cycle() == "blocked"
        store.review_action(2, "release", now=NOW)
        with sqlite3.connect(store.path) as db:
            assert db.execute("SELECT release_count FROM review_audit").fetchone() == (2,)


def test_diagnostic_head_and_request_byte_limits(tmp_path):
    calls = []
    with Outbox(tmp_path / "queue.sqlite", ASSOC) as store:
        store.enqueue("telemetry", TELEMETRY)
        store.enqueue("diagnostic", DIAGNOSTIC)
        store.enqueue("telemetry", reading(sequenceNumber=2))
        worker = uploader(store, receipt(["accepted"]), calls)
        assert worker.cycle() == "settled"
        assert len(calls[0]) <= MAX_BYTES
        assert worker.cycle() == "diagnostic_head"
        assert len(calls) == 1 and [row.kind for row in store.pending()] == ["diagnostic", "telemetry"]


def test_http_does_not_hold_store_transaction(tmp_path):
    started, proceed = threading.Event(), threading.Event()
    def blocking(request, timeout):
        started.set()
        assert proceed.wait(5)
        return io.BytesIO(json.dumps(receipt(["accepted"])).encode())
    with Outbox(tmp_path / "queue.sqlite", ASSOC) as store:
        store.enqueue("telemetry", TELEMETRY)
        worker = TelemetryUploader(store, opener=blocking, clock=lambda: NOW, monotonic=lambda: 0)
        outcome = []
        thread = threading.Thread(target=lambda: outcome.append(worker.cycle()))
        thread.start()
        try:
            assert started.wait(5)
            store.enqueue("telemetry", reading(sequenceNumber=2))
        finally:
            proceed.set()
            thread.join(10)
        assert outcome == ["settled"] and store.pending()[0].enqueue_id == 2


def test_duplicate_json_receipt_keys_are_ambiguous(tmp_path):
    def duplicate_keys(request, timeout):
        raw = json.dumps(receipt(["accepted"]))
        return io.BytesIO(raw.replace('"accepted": 1', '"accepted": 1, "accepted": 0').encode())
    with Outbox(tmp_path / "queue.sqlite", ASSOC) as store:
        store.enqueue("telemetry", TELEMETRY)
        worker = TelemetryUploader(store, opener=duplicate_keys, clock=lambda: NOW, monotonic=lambda: 0, jitter=lambda: 0.5)
        assert worker.cycle() == "waiting"
        assert store.pending()[0].payload == TELEMETRY


def test_rate_limit_is_shared_between_uploader_instances(tmp_path):
    calls, tick = [], [0.0]
    with Outbox(tmp_path / "queue.sqlite", ASSOC) as store:
        store.enqueue("telemetry", TELEMETRY)
        first = uploader(store, receipt(["accepted"]), calls, monotonic=lambda: tick[0])
        second = uploader(store, receipt(["accepted"]), calls, monotonic=lambda: tick[0])
        assert first.cycle() == "settled"
        store.enqueue("telemetry", reading(sequenceNumber=2))
        assert second.cycle() == "rate_limited"
        tick[0] += 1
        assert second.cycle() == "settled" and len(calls) == 2


def test_known_version_one_store_upgrades_without_losing_payload(tmp_path):
    path = tmp_path / "queue.sqlite"
    with Outbox(path, ASSOC) as store:
        store.enqueue("telemetry", TELEMETRY)
    with sqlite3.connect(path) as db:
        db.execute("DROP TABLE capacity")
        db.execute("ALTER TABLE health DROP COLUMN pause_reason")
        db.execute("ALTER TABLE health DROP COLUMN last_pause_seconds")
        db.execute("ALTER TABLE health DROP COLUMN pause_count")
        db.execute("DROP TABLE diagnostic_receipt")
        db.execute("DROP TABLE review_audit")
        db.execute("PRAGMA user_version=1")
    with Outbox(path, ASSOC) as store:
        assert store.pending()[0].payload == TELEMETRY
        with sqlite3.connect(path) as db:
            assert db.execute("PRAGMA user_version").fetchone()[0] == 4
            assert db.execute("SELECT release_count FROM review_audit").fetchone()[0] == 0


def test_requests_obey_bytes_and_item_limits_without_loading_backlog(tmp_path):
    requests = []
    def respond(request, timeout):
        requests.append(request.data)
        count = len(json.loads(request.data)["readings"])
        return io.BytesIO(json.dumps(receipt(["accepted"] * count)).encode())
    with Outbox(tmp_path / "queue.sqlite", ASSOC) as store:
        for number in range(1, 111):
            store.enqueue("telemetry", reading(sequenceNumber=number) + b" " * 6000)
        worker = TelemetryUploader(store, opener=respond, clock=lambda: NOW, monotonic=lambda: 0)
        assert worker.cycle() == "settled"
        count = len(json.loads(requests[0])["readings"])
        assert 1 <= count < 100 and len(requests[0]) <= MAX_BYTES
        assert store.pending(limit=100)[0].enqueue_id == count + 1
