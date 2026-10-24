import importlib.util
import io
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError

import pytest

from autopulse_agent import json_codec as json
from autopulse_agent.models.diagnostics import snapshot_from_payload, snapshot_to_json
from autopulse_agent.transport.diagnostics import DiagnosticSender, HttpDiagnosticClient, diagnostic_receipt
from autopulse_agent.transport.http import PermanentDeliveryError, TransientDeliveryError

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = json.loads((ROOT / 'contracts/examples/diagnostic-contract-fixtures.json').read_text())
SNAPSHOT = snapshot_from_payload(FIXTURE['lifecycle'][2])
RECEIPT = dict(snapshotId='10000000-0000-4000-8000-000000000010', result='DUPLICATE', receivedAt='2026-10-23T12:00:00.000001Z')


def test_lost_ack_retries_the_identical_encoded_snapshot_with_bounded_timeout():
    bodies, sleeps = [], []

    def opener(request, timeout):
        assert timeout == 5 and request.method == 'POST'
        assert request.full_url.endswith('/api/v1/device/diagnostic-snapshots')
        bodies.append(request.data)
        if len(bodies) == 1:
            raise TimeoutError('lost after commit')
        return io.BytesIO(json.dumps(RECEIPT).encode())

    receipt = DiagnosticSender(HttpDiagnosticClient('http://127.0.0.1:1234', opener=opener), sleep=sleeps.append).deliver(SNAPSHOT)
    assert receipt == RECEIPT
    assert bodies == [snapshot_to_json(SNAPSHOT).encode()] * 2
    assert sleeps == [0.5]


@pytest.mark.parametrize('raw', [b'{}', b'null', b'[]', b'{', b'\xff',
    json.dumps(dict(RECEIPT, receivedAt='2026-02-30T12:00:00Z')).encode(),
    json.dumps(dict(RECEIPT, receivedAt='2026-10-23T12:00:00+00:00')).encode(),
    json.dumps(dict(RECEIPT, result='REJECTED')).encode(),
    json.dumps(dict(RECEIPT, snapshotId='not-an-id')).encode(),
    json.dumps(dict(RECEIPT, unexpected=True)).encode(),
    json.dumps(RECEIPT).replace('"result": "DUPLICATE"', '"result":"ACCEPTED","result":"DUPLICATE"').encode()])
def test_invalid_receipt_cannot_acknowledge_delivery(raw):
    with pytest.raises(TransientDeliveryError):
        diagnostic_receipt(raw)


@pytest.mark.parametrize('status, attempts, error', [(400, 1, PermanentDeliveryError), (409, 1, PermanentDeliveryError),
                                                  (503, 3, TransientDeliveryError), (429, 3, TransientDeliveryError)])
def test_status_classification_and_retry_budget(status, attempts, error):
    calls, sleeps = [], []

    def opener(request, timeout):
        calls.append(request.data)
        raise HTTPError(request.full_url, status, 'controlled', {}, None)

    sender = DiagnosticSender(HttpDiagnosticClient('http://127.0.0.1:1234', opener=opener), sleep=sleeps.append)
    with pytest.raises(error):
        sender.deliver(SNAPSHOT)
    assert len(calls) == attempts and len(set(calls)) == 1
    assert sleeps == ([] if attempts == 1 else [0.5, 1.0])


def test_response_size_bound_and_typed_input():
    calls = []

    def opener(request, timeout):
        calls.append(True)
        return io.BytesIO(b' ' * 4097)

    with pytest.raises(TransientDeliveryError, match='2 attempts'):
        DiagnosticSender(HttpDiagnosticClient('http://127.0.0.1:1234', opener=opener), max_attempts=2, sleep=lambda _: None).deliver(SNAPSHOT)
    assert len(calls) == 2
    with pytest.raises(ValueError):
        DiagnosticSender(None).deliver(SNAPSHOT.to_payload())
    for attempts in (0, 11, True, 1.5):
        with pytest.raises(ValueError):
            DiagnosticSender(None, max_attempts=attempts)
    for timeout in (None, 0, 31, True, float('inf')):
        with pytest.raises(ValueError):
            HttpDiagnosticClient('http://127.0.0.1:1234', timeout=timeout)


def test_demo_collects_actual_simulator_frames_matching_independent_hand_fixtures():
    spec = importlib.util.spec_from_file_location('demo_care', ROOT / 'scripts/demo_care.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    base = datetime(2026, 10, 22, 12, tzinfo=timezone.utc)
    capture = module.collect(base)
    assert capture['diagnostics'] == FIXTURE['lifecycle']
    thermal = json.loads((ROOT / 'contracts/examples/synthetic-temperature-fixture.json').read_text())
    assert len(capture['thermal']) == thermal['expected']['inputSamples']
    assert [v['coolantTempC'] for v in capture['thermal']] == [v['coolantTempC'] for v in thermal['readings']]
    assert capture['stale']['sequenceNumber'] == 1000
    assert capture['stale']['recordedAt'].startswith('2026-10-22T11:50:00')


@pytest.mark.parametrize('url', ['https://127.0.0.1:1234', 'http://example.com:1234', 'http://user@127.0.0.1:1234', 'http://127.0.0.1:1234?x=1'])
def test_demo_refuses_nonisolated_url_before_network_or_capture_creation(tmp_path, url):
    capture = tmp_path / 'not-created.json'
    result = subprocess.run([sys.executable, str(ROOT / 'scripts/demo_care.py'), '--server-url', url, '--capture', str(capture),
                             '--mode', 'baseline', '--isolated-demo'], capture_output=True, timeout=10)
    assert result.returncode != 0 and not capture.exists()
    assert b'JUnit-owned loopback' in result.stderr
