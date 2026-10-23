"""Finite, bounded diagnostic delivery; no recollection on ambiguous responses.

This transport is independent of telemetry batch identity and is not a durable outbox.
"""

import re
import math
import time
from datetime import datetime
from http.client import HTTPException
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
from uuid import UUID

from simplejson import loads

from ..models.diagnostics import DiagnosticSnapshot, snapshot_to_json
from .http import PermanentDeliveryError, TransientDeliveryError

ACK_BYTES = 4096


def diagnostic_receipt(raw):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate receipt key")
            result[key] = value
        return result

    try:
        value = loads(raw.decode("utf-8"), allow_nan=False, object_pairs_hook=unique)
        if type(value) is not dict or set(value) != {"snapshotId", "result", "receivedAt"}:
            raise ValueError("receipt shape")
        identity, at = value["snapshotId"], value["receivedAt"]
        if type(identity) is not str or str(UUID(identity)) != identity:
            raise ValueError("receipt identity")
        if value["result"] not in ("ACCEPTED", "DUPLICATE"):
            raise ValueError("receipt result")
        if type(at) is not str or not re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?Z", at):
            raise ValueError("receipt time")
        datetime.fromisoformat(at)
        return value
    except (ValueError, TypeError, UnicodeError, AttributeError) as error:
        raise TransientDeliveryError("invalid diagnostic acknowledgment") from error


class HttpDiagnosticClient:
    def __init__(self, server_url, *, timeout=5, opener=urlopen):
        parsed = urlsplit(server_url)
        if parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("server_url must be an HTTP(S) base URL without credentials, query, or fragment")
        if type(timeout) not in (int, float) or not math.isfinite(timeout) or not 0 < timeout <= 30:
            raise ValueError("timeout must be finite and between 0 and 30 seconds")
        self.url = server_url.rstrip("/") + "/api/v1/device/diagnostic-snapshots"
        self.timeout, self.opener = timeout, opener

    def send(self, body: bytes):
        # Only the sender's already validated, frozen snapshot bytes cross this boundary.
        if type(body) is not bytes or not 1 <= len(body) <= 65536:
            raise PermanentDeliveryError("diagnostic body must be 1..65536 UTF-8 bytes")
        request = Request(self.url, data=body, headers={"Content-Type": "application/json"}, method="POST")
        try:
            with self.opener(request, timeout=self.timeout) as response:
                raw = response.read(ACK_BYTES + 1)
                if len(raw) > ACK_BYTES:
                    raise TransientDeliveryError("diagnostic acknowledgment exceeds size limit")
                return diagnostic_receipt(raw)
        except HTTPError as error:
            if error.code >= 500 or error.code in (408, 429):
                raise TransientDeliveryError(f"temporary HTTP failure ({error.code})") from error
            raise PermanentDeliveryError(f"diagnostic request rejected (HTTP {error.code})") from error
        except (URLError, TimeoutError, OSError, HTTPException) as error:
            raise TransientDeliveryError("diagnostic connection failed") from error


class DiagnosticSender:
    def __init__(self, client, *, max_attempts=3, sleep=time.sleep):
        if type(max_attempts) is not int or not 1 <= max_attempts <= 10:
            raise ValueError("max_attempts must be an integer between 1 and 10")
        self.client, self.max_attempts, self.sleep = client, max_attempts, sleep

    def deliver(self, snapshot: DiagnosticSnapshot):
        if not isinstance(snapshot, DiagnosticSnapshot):
            raise ValueError("immutable typed diagnostic snapshot required")
        body = snapshot_to_json(snapshot).encode("utf-8")
        for attempt in range(self.max_attempts):
            try:
                return self.client.send(body)
            except TransientDeliveryError:
                if attempt + 1 == self.max_attempts:
                    raise TransientDeliveryError(f"diagnostic delivery failed after {self.max_attempts} attempts") from None
                self.sleep(min(0.5 * 2 ** attempt, 8.0))
        raise AssertionError("unreachable")
