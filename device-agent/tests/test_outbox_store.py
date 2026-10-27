import hashlib
import json
import sqlite3
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pytest

from autopulse_agent.buffering import Association, IdentityConflict, Outbox, OutboxError, OwnershipError

ROOT = Path(__file__).resolve().parents[2]
TELEMETRY = (ROOT / "contracts/examples/telemetry-sample.json").read_bytes()
DIAGNOSTIC = json.dumps(json.loads((ROOT / "contracts/examples/diagnostic-api-examples.json").read_text())["snapshot"], separators=(",", ":")).encode()
ASSOC = Association("sim-device-001", "venue-2021", "http://127.0.0.1:8080/")


def reading(**changes):
    value = json.loads(TELEMETRY)
    value.update(changes)
    return json.dumps(value, separators=(",", ":")).encode()


def child(code, *args):
    return subprocess.Popen([sys.executable, "-u", "-c", code, *map(str, args)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)


def wait_ready(process):
    line = process.stdout.readline().strip()
    if line != "ready":
        raise AssertionError(f"child did not become ready: {line!r}, {process.stderr.read()}")


def kill(process):
    process.kill()
    process.communicate(timeout=10)


def test_enqueue_reopen_and_fifo_across_kinds_and_boots(tmp_path):
    path = tmp_path / "outbox.sqlite"
    third = reading(bootId="00000000-0000-0000-0000-000000000001", sequenceNumber=0, rpm=0, milOn=False)
    with Outbox(path, ASSOC) as store:
        assert store.enqueue("telemetry", TELEMETRY) == (1, True)
        assert store.enqueue("diagnostic", DIAGNOSTIC) == (2, True)
        assert store.enqueue("telemetry", third) == (3, True)
    with Outbox(path, ASSOC) as store:
        rows = store.pending()
        assert [(r.enqueue_id, r.kind, r.payload) for r in rows] == [(1, "telemetry", TELEMETRY), (2, "diagnostic", DIAGNOSTIC), (3, "telemetry", third)]
        assert rows[0].digest == hashlib.sha256(TELEMETRY).hexdigest()
        assert all(r.state == "queued" for r in rows)
        assert len(store.pending(limit=1)) == 1


def test_local_identity_duplicate_and_conflict(tmp_path):
    with Outbox(tmp_path / "outbox.sqlite", ASSOC) as store:
        assert store.enqueue("telemetry", TELEMETRY) == (1, True)
        assert store.enqueue("telemetry", TELEMETRY) == (1, False)
        with pytest.raises(IdentityConflict):
            store.enqueue("telemetry", reading(rpm=42))
        assert store.pending()[0].payload == TELEMETRY


def test_separate_kind_namespaces_and_thread_owned_connections(tmp_path):
    diagnostic = json.loads(DIAGNOSTIC)
    diagnostic.update(bootId="123e4567-e89b-12d3-a456-426614174000", diagnosticSequence=1)
    with Outbox(tmp_path / "outbox.sqlite", ASSOC) as store:
        store.enqueue("telemetry", TELEMETRY)
        store.enqueue("diagnostic", json.dumps(diagnostic).encode())
        with ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(lambda n: store.enqueue("telemetry", reading(sequenceNumber=n)), range(2, 10)))
        assert len({row_id for row_id, inserted in results if inserted}) == 8
        rows = store.pending()
        assert len(rows) == 10
        assert [row.enqueue_id for row in rows] == sorted(row.enqueue_id for row in rows)


def test_rejects_invalid_payload_and_association_without_insert(tmp_path):
    with Outbox(tmp_path / "outbox.sqlite", ASSOC) as store:
        for payload in (reading(vehicleId="other"), reading(sequenceNumber=2**63), reading(extra=True), b'{"deviceId":1,"deviceId":2}'):
            with pytest.raises(ValueError):
                store.enqueue("telemetry", payload)
        assert store.pending() == []
    with pytest.raises(ValueError):
        Association("device", "vehicle", "http://user:secret@localhost")


def test_exact_decimal_token_is_stored_without_reencoding(tmp_path):
    payload = TELEMETRY.replace(b'"rpm" : 1900', b'"rpm" : 1900.123456789012345')
    assert payload != TELEMETRY
    with Outbox(tmp_path / "outbox.sqlite", ASSOC) as store:
        store.enqueue("telemetry", payload)
        assert store.pending()[0].payload == payload


def test_wrong_association_unknown_version_and_corruption_preserve_file(tmp_path):
    path = tmp_path / "outbox.sqlite"
    with Outbox(path, ASSOC) as store:
        store.enqueue("telemetry", TELEMETRY)
    original = path.read_bytes()
    with pytest.raises(OutboxError, match="association"):
        Outbox(path, Association("other", "venue-2021", ASSOC.server_url))
    assert path.read_bytes() == original
    with sqlite3.connect(path) as db:
        db.execute("PRAGMA user_version=99")
    versioned = path.read_bytes()
    with pytest.raises(OutboxError, match="version"):
        Outbox(path, ASSOC)
    assert path.read_bytes() == versioned
    corrupt = tmp_path / "corrupt.sqlite"
    corrupt.write_bytes(b"not a database")
    with pytest.raises(OutboxError):
        Outbox(corrupt, ASSOC)
    assert corrupt.read_bytes() == b"not a database"


def test_digest_detects_altered_payload(tmp_path):
    path = tmp_path / "outbox.sqlite"
    with Outbox(path, ASSOC) as store:
        store.enqueue("telemetry", TELEMETRY)
        with sqlite3.connect(path) as db:
            db.execute("UPDATE outbox SET payload=?", (b"altered",))
        with pytest.raises(OutboxError, match="integrity"):
            store.pending()


def test_single_owner_released_on_process_death(tmp_path):
    path = tmp_path / "outbox.sqlite"
    process = child("""
import sys, time
from autopulse_agent.buffering import Association, Outbox
box = Outbox(sys.argv[1], Association('sim-device-001','venue-2021','http://127.0.0.1:8080'))
print('ready', flush=True)
time.sleep(60)
""", path)
    try:
        wait_ready(process)
        with pytest.raises(OwnershipError):
            Outbox(path, ASSOC)
    finally:
        kill(process)
    with Outbox(path, ASSOC) as store:
        assert store.pending() == []


@pytest.mark.parametrize("committed", [False, True])
def test_terminated_writer_before_and_after_commit(tmp_path, committed):
    path = tmp_path / "outbox.sqlite"
    fixture = tmp_path / "reading.json"
    fixture.write_bytes(TELEMETRY)
    process = child("""
import hashlib, sqlite3, sys, time
from autopulse_agent.buffering import Association, Outbox
path, fixture, committed = sys.argv[1:]
box = Outbox(path, Association('sim-device-001','venue-2021','http://127.0.0.1:8080'))
payload = open(fixture,'rb').read()
if committed == 'True':
    box.enqueue('telemetry', payload)
else:
    db = sqlite3.connect(path, isolation_level=None)
    db.execute('PRAGMA synchronous=FULL')
    db.execute('BEGIN IMMEDIATE')
    db.execute(\"INSERT INTO outbox(kind,device_id,boot_id,sequence_number,wire_version,payload,payload_bytes,digest,enqueued_at,state) VALUES('telemetry','sim-device-001','123e4567-e89b-12d3-a456-426614174000',1,1,?,?,?,'2026-01-01T00:00:00Z','queued')\", (payload,len(payload),hashlib.sha256(payload).hexdigest()))
print('ready', flush=True)
time.sleep(60)
""", path, fixture, committed)
    try:
        wait_ready(process)
    finally:
        kill(process)
    with Outbox(path, ASSOC) as store:
        rows = store.pending()
        assert [r.payload for r in rows] == ([TELEMETRY] if committed else [])
