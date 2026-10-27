"""Deliver the diagnostic queue head without changing capture identity or time."""

import math
import random
import time
from datetime import datetime, timedelta, timezone
from urllib.request import urlopen

from ..transport.diagnostics import HttpDiagnosticClient
from ..transport.http import PermanentDeliveryError, TransientDeliveryError
from .store import OutboxError
from .telemetry_upload import MAX_DELAY, _text, _timestamp


class DiagnosticUploader:
    def __init__(self, store, *, opener=urlopen, clock=lambda: datetime.now(timezone.utc),
                 monotonic=time.monotonic, jitter=random.random):
        self.store = store
        self.client = HttpDiagnosticClient(store.association.server_url, timeout=5, opener=opener)
        self.clock, self.monotonic, self.jitter = clock, monotonic, jitter

    def cycle(self):
        with self.store._upload_lock:
            now = _timestamp(self.clock())
            if self.store.has_blocked():
                return "blocked"
            rows = self.store.pending(limit=1)
            if not rows:
                return "empty"
            head = rows[0]
            if head.kind != "diagnostic":
                return "telemetry_head"
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
            self.store._next_upload = tick + 1.0
            try:
                receipt = self.client.send(head.payload)
            except TransientDeliveryError as error:
                sample = self.jitter()
                if type(sample) not in (int, float) or not math.isfinite(sample) or not 0 <= sample <= 1:
                    raise ValueError("jitter must be between zero and one")
                delay = min(MAX_DELAY, 2 ** min(head.attempts, 9) * (0.5 + sample))
                self.store._change_delivery(rows, "retry", retry_at=_text(_timestamp(self.clock()) + timedelta(seconds=delay)), reason=str(error)[:1024])
                return "waiting"
            except PermanentDeliveryError as error:
                self.store._change_delivery(rows, "block", reason=str(error)[:1024])
                return "blocked"
            outcome = receipt["result"].lower()
            self.store._change_delivery(rows, "settle", outcomes=[(outcome, None)],
                                        now=_text(_timestamp(self.clock())), receipt=receipt)
            return "settled"
