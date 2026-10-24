import copy
import io
import json
from http.client import IncompleteRead
from datetime import datetime, timezone
from urllib.error import HTTPError

import pytest

from autopulse_agent.send import send_source
from autopulse_agent.sources.simulated import SimulatedTelemetrySource
from autopulse_agent.transport.http import (
    BatchSender, HttpBatchClient, PermanentDeliveryError, TransientDeliveryError,
    acknowledgment, MAX_BYTES,
)


def ack(statuses):
    response = {name: statuses.count(name) for name in ("accepted", "duplicate", "rejected")}
    response["results"] = []
    for index, status in enumerate(statuses):
        result = {"index": index, "status": status}
        if status == "rejected":
            result.update(code="INVALID_READING", errors=[{"field": "rpm", "message": "invalid"}])
        response["results"].append(result)
    return response


def test_retry_preserves_collected_event_and_acknowledges_duplicate():
    calls, sleeps = [], []
    class Client:
        def send(self, readings):
            calls.append(copy.deepcopy(readings))
            if len(calls) == 1:
                raise TransientDeliveryError("response lost after server accepted")
            return ack(["duplicate"])
    reading = {"bootId": "original", "sequenceNumber": 1, "recordedAt": "original-time", "rpm": 0}
    summary = BatchSender(Client(), sleep=sleeps.append).deliver([reading])
    assert calls == [[reading], [reading]]
    assert sleeps == [0.5]
    assert summary.duplicate == 1


def test_accepted_duplicate_and_permanent_rejection_are_not_resent():
    calls = []
    class Client:
        def send(self, readings):
            calls.append(readings)
            return ack(["accepted", "duplicate", "rejected"])
    result = BatchSender(Client()).deliver([{}, {}, {}])
    assert (result.accepted, result.duplicate, result.rejected) == (1, 1, 1)
    assert len(calls) == 1


def test_transient_retry_budget_is_bounded():
    sleeps, calls = [], []
    class Client:
        def send(self, readings):
            calls.append(readings)
            raise TransientDeliveryError("offline")
    with pytest.raises(TransientDeliveryError, match="3 attempts"):
        BatchSender(Client(), sleep=sleeps.append).deliver([{}])
    assert len(calls) == 3
    assert sleeps == [0.5, 1.0]


def test_invalid_acknowledgment_cannot_silently_drop_items():
    invalid = ack(["accepted", "duplicate"])
    invalid["results"][1]["index"] = 0
    with pytest.raises(TransientDeliveryError):
        acknowledgment(invalid, 2)
    invalid = ack(["accepted"])
    invalid["accepted"] = 2
    with pytest.raises(TransientDeliveryError):
        acknowledgment(invalid, 1)


@pytest.mark.parametrize("code,exception", [(400, PermanentDeliveryError), (503, TransientDeliveryError), (429, TransientDeliveryError)])
def test_http_failure_classification(code, exception):
    def opener(request, timeout):
        raise HTTPError(request.full_url, code, "test", {}, io.BytesIO())
    with pytest.raises(exception):
        HttpBatchClient("http://localhost:8080", opener=opener).send([{}])


def test_http_input_and_response_sizes_are_bounded():
    client = HttpBatchClient("http://localhost:8080", opener=lambda request, timeout: io.BytesIO(b"x" * (MAX_BYTES + 1)))
    with pytest.raises(TransientDeliveryError, match="size limit"):
        client.send([{}])
    with pytest.raises(PermanentDeliveryError, match="exceeds"):
        client.send([{"text": "x" * MAX_BYTES}])


def test_simulator_pacing_and_batching_use_injected_clock_without_sleeping():
    now, waits, calls = [0.0], [], []
    def sleep(seconds):
        waits.append(seconds)
        now[0] += seconds
    class Client:
        def send(self, readings):
            calls.append(copy.deepcopy(readings))
            return ack(["accepted"] * len(readings))
    source = SimulatedTelemetrySource("sim-device-001", "venue-2021", datetime(2026, 10, 20, tzinfo=timezone.utc))
    result = send_source(source, BatchSender(Client()), interval=2, batch_size=2, clock=lambda: now[0], sleep=sleep)
    readings = [item for batch in calls for item in batch]
    assert result.accepted == 3
    assert [len(batch) for batch in calls] == [2, 1]
    assert [item["sequenceNumber"] for item in readings] == [0, 1, 2]
    assert waits == [2.0, 2.0, 2.0]
    assert len({item["bootId"] for item in readings}) == 1


@pytest.mark.parametrize("recovery", ["accepted", "duplicate", None])
def test_interrupted_http_response_retries_identical_bytes_and_closes_response(recovery):
    requests, closed, waits = [], [], []

    class Response:
        def __enter__(self):
            return self

        def __exit__(self, *args):
            closed.append(True)

        def read(self, limit):
            assert limit == MAX_BYTES + 1
            if len(requests) == 1 or recovery is None:
                raise IncompleteRead(b'{"accepted":', 40)
            return json.dumps(ack([recovery])).encode()

    def opener(request, timeout):
        requests.append(request.data)
        assert timeout == 5
        return Response()

    sender = BatchSender(HttpBatchClient("http://localhost:8080", opener=opener),
                         max_attempts=2, sleep=waits.append)
    reading = {"bootId": "original", "sequenceNumber": 0, "recordedAt": "original", "rpm": 0}
    if recovery is None:
        with pytest.raises(TransientDeliveryError, match="2 attempts"):
            sender.deliver([reading])
    else:
        assert getattr(sender.deliver([reading]), recovery) == 1
    assert len(requests) == 2 and requests[0] == requests[1]
    assert closed == [True, True]
    assert waits == [0.5]
