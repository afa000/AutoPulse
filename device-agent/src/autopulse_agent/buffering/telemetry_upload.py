"""One bounded telemetry attempt against the outbox's pinned destination."""

import math
import random
import time
from datetime import datetime, timedelta, timezone
from urllib.request import urlopen

from ..transport.http import (HttpBatchClient, MAX_BYTES, MAX_ITEMS,
                              PermanentDeliveryError, TransientDeliveryError,
                              indexed_acknowledgment)
from .store import OutboxError

MAX_DELAY = 300.0


def _timestamp(value):
    if not isinstance(value, datetime) or value.utcoffset() is None:
        raise ValueError("clock must return an aware datetime")
    return value.astimezone(timezone.utc)


def _text(value):
    return value.isoformat().replace("+00:00", "Z")


class TelemetryUploader:
    """A single-attempt cycle; scheduling and all network I/O stay outside SQLite."""

    def __init__(self, store, *, opener=urlopen, clock=lambda: datetime.now(timezone.utc),
                 monotonic=time.monotonic, jitter=random.random):
        self.store = store
        self.client = HttpBatchClient(store.association.server_url, timeout=5, opener=opener)
        self.clock, self.monotonic, self.jitter = clock, monotonic, jitter

    def cycle(self):
        # Only one in-flight request per outbox, even with multiple uploader objects.
        with self.store._upload_lock:
            now = _timestamp(self.clock())
            if self.store.has_blocked():
                return "blocked"
            rows = self.store.pending(limit=MAX_ITEMS)
            if not rows:
                return "empty"
            head = rows[0]
            if head.kind != "telemetry":
                return "diagnostic_head"
            if head.state == "waiting":
                try:
                    due = _timestamp(datetime.fromisoformat(head.retry_at))
                except (ValueError, TypeError) as error:
                    raise OutboxError("invalid persisted retry deadline; file preserved") from error
                if due > now + timedelta(seconds=MAX_DELAY):
                    due = now + timedelta(seconds=MAX_DELAY)
                    self.store.clamp_retry(head, _text(due))
                if due > now:
                    return "waiting"
            tick = self.monotonic()
            if not math.isfinite(tick):
                raise ValueError("monotonic clock must be finite")
            if tick < self.store._next_upload:
                return "rate_limited"
            prefix = []
            length = len(b'{"readings":[]}')
            for row in rows:
                if row.kind != "telemetry" or row.state == "blocked" or (prefix and row.state != "queued"):
                    break
                extra = len(row.payload) + (1 if prefix else 0)
                if length + extra > MAX_BYTES:
                    break
                prefix.append(row)
                length += extra
            if not prefix:
                raise OutboxError("queue head cannot fit in a telemetry request")
            body = b'{"readings":[' + b','.join(row.payload for row in prefix) + b']}'
            self.store._next_upload = tick + 1.0
            try:
                response = self.client.send_encoded(body)
                _, outcomes = indexed_acknowledgment(response, len(prefix))
            except TransientDeliveryError as error:
                sample = self.jitter()
                if type(sample) not in (int, float) or not math.isfinite(sample) or not 0 <= sample <= 1:
                    raise ValueError("jitter must be between zero and one")
                delay = min(MAX_DELAY, 2 ** min(head.attempts, 9) * (0.5 + sample))
                failure_at = _timestamp(self.clock())
                self.store._change_delivery(prefix, "retry", retry_at=_text(failure_at + timedelta(seconds=delay)), reason=str(error)[:1024])
                return "waiting"
            except PermanentDeliveryError as error:
                self.store._change_delivery(prefix, "block", reason=str(error)[:1024])
                return "blocked"
            self.store._change_delivery(prefix, "settle", outcomes=outcomes, now=_text(_timestamp(self.clock())))
            return "blocked" if any(status == "rejected" for status, _ in outcomes) else "settled"
