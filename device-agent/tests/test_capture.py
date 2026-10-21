import io
from datetime import datetime, timezone

import pytest

from autopulse_agent import json_codec as json
from autopulse_agent.capture import FORMAT, bounded_batches, capture_batches, encode_batch
from autopulse_agent.models.telemetry_json import sample_to_payload
from autopulse_agent.send import replay_capture, send_source
from autopulse_agent.sources.simulated import SimulatedTelemetrySource
from autopulse_agent.transport.http import MAX_BYTES, BatchSender, PermanentDeliveryError
from test_delivery import ack


def test_full_commute_streams_three_bounded_batches_and_replays_original_events():
    calls, replayed = [], []
    class Client:
        def send(self, readings):
            calls.append(readings)
            return ack(["accepted"] * len(readings))
    class ReplayClient:
        def send(self, readings):
            replayed.append(readings)
            return ack(["duplicate"] * len(readings))
    stream = io.BytesIO(json.dumps({"format": FORMAT}).encode() + b"\n")
    stream.seek(0, 2)
    source = SimulatedTelemetrySource("sim-device-001", "venue-2021", datetime(2026, 10, 20, tzinfo=timezone.utc), scenario="normal-commute")
    summary = send_source(source, BatchSender(Client()), batch_size=100, capture=stream,
                          clock=lambda: 0, sleep=lambda _: None)
    assert summary.accepted == 251
    assert [len(batch) for batch in calls] == [100, 100, 51]
    assert all(len(encode_batch(batch)) <= MAX_BYTES for batch in calls)
    stream.seek(0)
    assert replay_capture(stream, BatchSender(ReplayClient())).duplicate == 251
    assert replayed == calls
    with pytest.raises(RuntimeError):
        source.read()


def test_legacy_capture_exact_decimal_and_offset_are_not_rewritten():
    source = SimulatedTelemetrySource("d", "v", datetime(2026, 10, 20, tzinfo=timezone.utc))
    source.connect()
    reading = sample_to_payload(source.read())
    reading["recordedAt"] = "2026-10-20T04:00:00+04:00"
    reading["rpm"] = json.loads("1900.0000000000000001")
    raw = json.dumps({"readings": [reading]}, indent=2).encode()
    assert list(capture_batches(io.BytesIO(raw))) == [[reading]]


def test_byte_limit_splits_before_item_limit_without_unbounded_history():
    readings = ({"deviceId": "x" * 140000, "sequenceNumber": n} for n in range(3))
    batches = list(bounded_batches(readings))
    assert [len(batch) for batch in batches] == [1, 1, 1]
    assert all(len(encode_batch(batch)) <= MAX_BYTES for batch in batches)
    with pytest.raises(PermanentDeliveryError):
        list(bounded_batches([{"oversize": "x" * MAX_BYTES}]))


@pytest.mark.parametrize("raw", [b"", b"{}", b'{"readings":[]}',
    json.dumps({"format": FORMAT}).encode() + b"\n",
    json.dumps({"format": FORMAT}).encode() + b"\n" + b"x" * (MAX_BYTES + 1),
    b"x" * (MAX_BYTES + 1)], ids=["empty", "missing", "empty-items", "header-only", "oversize-line", "oversize-legacy"])
def test_empty_malformed_and_oversize_captures_fail(raw):
    with pytest.raises(ValueError):
        list(capture_batches(io.BytesIO(raw)))
