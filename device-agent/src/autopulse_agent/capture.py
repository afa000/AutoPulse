"""Bounded streaming demonstration captures; not a restart-safe outbox."""

from . import json_codec as json
from .models.telemetry_json import sample_from_payload
from .transport.http import MAX_BYTES, MAX_ITEMS, PermanentDeliveryError

FORMAT = "autopulse-batches-v1"


def encode_batch(readings):
    return json.dumps({"readings": readings}).encode("utf-8")


def bounded_batches(readings, batch_size=100):
    if type(batch_size) is not int or not 1 <= batch_size <= MAX_ITEMS:
        raise ValueError("batch_size must be 1-100")
    batch = []
    for reading in readings:
        if len(encode_batch([reading])) > MAX_BYTES:
            raise PermanentDeliveryError("one reading exceeds request size limit")
        if batch and len(encode_batch(batch + [reading])) > MAX_BYTES:
            yield batch
            batch = []
        batch.append(reading)
        if len(batch) == batch_size:
            yield batch
            batch = []
    if batch:
        yield batch


def validate_envelope(payload):
    if not isinstance(payload, dict) or set(payload) != {"readings"}:
        raise ValueError("capture batch must be a readings envelope")
    readings = payload["readings"]
    if not isinstance(readings, list) or not 1 <= len(readings) <= MAX_ITEMS:
        raise ValueError("capture batch must contain 1-100 readings")
    for reading in readings:
        sample_from_payload(reading)
    # Validate without rewriting captured decimals, timestamps or event identities.
    return readings


def capture_batches(stream):
    first = stream.readline(MAX_BYTES + 2)
    if len(first.rstrip(b"\r\n")) > MAX_BYTES:
        raise ValueError("capture line exceeds byte limit")
    try:
        header = json.loads(first)
    except ValueError:
        header = None
    if header != {"format": FORMAT}:
        # Backward-compatible single, possibly indented, envelope; still bounded.
        stream.seek(0)
        raw = stream.read(MAX_BYTES + 1)
        if len(raw) > MAX_BYTES:
            raise ValueError("legacy capture exceeds byte limit")
        yield validate_envelope(json.loads(raw))
        return
    count = 0
    while True:
        line = stream.readline(MAX_BYTES + 2)
        if not line:
            break
        if len(line.rstrip(b"\r\n")) > MAX_BYTES:
            raise ValueError("capture line exceeds byte limit")
        yield validate_envelope(json.loads(line))
        count += 1
    if not count:
        raise ValueError("capture contains no batches")
