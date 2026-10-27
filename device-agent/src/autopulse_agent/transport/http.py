"""Bounded HTTP batches with explicit acknowledgment and retry handling."""

import time
from http.client import HTTPException
from dataclasses import dataclass
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
from uuid import UUID

from simplejson import loads as strict_loads

from .. import json_codec as json


MAX_BYTES = 262144
MAX_ITEMS = 100


class DeliveryError(RuntimeError):
    pass


class TransientDeliveryError(DeliveryError):
    pass


class PermanentDeliveryError(DeliveryError):
    pass


class HttpBatchClient:
    def __init__(self, server_url, *, timeout=5, opener=urlopen):
        parsed = urlsplit(server_url)
        if parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("server_url must be an HTTP(S) base URL without credentials, query, or fragment")
        self.url = server_url.rstrip("/") + "/api/v1/device/telemetry/batches"
        self.timeout = timeout
        self.opener = opener

    def send(self, readings):
        if not 1 <= len(readings) <= MAX_ITEMS:
            raise PermanentDeliveryError("a batch must contain 1-100 readings")
        body = json.dumps({"readings": readings}).encode("utf-8")
        if len(body) > MAX_BYTES:
            raise PermanentDeliveryError("batch exceeds 262144 bytes")
        return self.send_encoded(body)

    def send_encoded(self, body):
        """Send an already bounded envelope without rewriting stored number tokens."""
        if type(body) is not bytes or not 1 <= len(body) <= MAX_BYTES:
            raise PermanentDeliveryError("batch exceeds 262144 bytes or is empty")
        request = Request(self.url, data=body, headers={"Content-Type": "application/json"}, method="POST")
        try:
            with self.opener(request, timeout=self.timeout) as response:
                raw = response.read(MAX_BYTES + 1)
                if len(raw) > MAX_BYTES:
                    raise TransientDeliveryError("acknowledgment exceeds response size limit")
                try:
                    def unique(pairs):
                        result = {}
                        for key, value in pairs:
                            if key in result:
                                raise ValueError("duplicate acknowledgment key")
                            result[key] = value
                        return result
                    return strict_loads(raw.decode("utf-8"), use_decimal=True, allow_nan=False, object_pairs_hook=unique)
                except (ValueError, UnicodeError) as exc:
                    raise TransientDeliveryError("invalid JSON acknowledgment") from exc
        except HTTPError as exc:
            if exc.code >= 500 or exc.code in (408, 429):
                raise TransientDeliveryError(f"temporary HTTP failure ({exc.code})") from exc
            raise PermanentDeliveryError(f"request rejected by server (HTTP {exc.code})") from exc
        except (URLError, TimeoutError, OSError, HTTPException) as exc:
            raise TransientDeliveryError("server connection failed") from exc


@dataclass(frozen=True)
class DeliverySummary:
    accepted: int = 0
    duplicate: int = 0
    rejected: int = 0

    def __add__(self, other):
        return DeliverySummary(self.accepted + other.accepted, self.duplicate + other.duplicate, self.rejected + other.rejected)


def acknowledgment(response, item_count):
    if not isinstance(response, dict) or not isinstance(response.get("results"), list):
        raise TransientDeliveryError("invalid batch acknowledgment")
    results = response["results"]
    if len(results) != item_count:
        raise TransientDeliveryError("acknowledgment does not cover every item")
    counts = {"accepted": 0, "duplicate": 0, "rejected": 0}
    indexes = set()
    for result in results:
        if not isinstance(result, dict):
            raise TransientDeliveryError("invalid item acknowledgment")
        index, status = result.get("index"), result.get("status")
        if type(index) is not int or not 0 <= index < item_count or index in indexes or not isinstance(status, str) or status not in counts:
            raise TransientDeliveryError("invalid item acknowledgment index or status")
        if status == "rejected" and (not isinstance(result.get("code"), str) or not isinstance(result.get("errors"), list) or not result["errors"]):
            raise TransientDeliveryError("rejected acknowledgment has no field errors")
        indexes.add(index)
        counts[status] += 1
    if any(type(response.get(name)) is not int or response[name] != count for name, count in counts.items()):
        raise TransientDeliveryError("acknowledgment counts do not match results")
    return DeliverySummary(**counts)


def indexed_acknowledgment(response, item_count):
    """Validate the complete receipt before exposing any row settlement."""
    summary = acknowledgment(response, item_count)
    try:
        correlation = response["correlationId"]
        if type(correlation) is not str or str(UUID(correlation)) != correlation.lower():
            raise ValueError("correlationId")
        ordered = [None] * item_count
        codes = {"INVALID_READING", "UNKNOWN_DEVICE", "UNKNOWN_VEHICLE", "DEVICE_VEHICLE_MISMATCH", "IDENTITY_CONFLICT"}
        for item in response["results"]:
            status = item["status"]
            reason = None
            if status == "rejected":
                errors = item["errors"]
                if item["code"] not in codes or not 1 <= len(errors) <= 32:
                    raise ValueError("rejection details")
                parts = []
                for error in errors:
                    if (type(error) is not dict or type(error.get("field")) is not str or
                            type(error.get("message")) is not str or not error["field"] or not error["message"]):
                        raise ValueError("rejection field error")
                    parts.append(error["field"] + ": " + error["message"])
                reason = item["code"] + ": " + "; ".join(parts)
                if len(reason.encode("utf-8")) > 1024:
                    raise ValueError("rejection details too large")
            ordered[item["index"]] = (status, reason)
        return summary, ordered
    except (KeyError, ValueError, TypeError, AttributeError) as error:
        raise TransientDeliveryError("invalid indexed acknowledgment") from error


class BatchSender:
    def __init__(self, client, *, max_attempts=3, sleep=time.sleep):
        if not 1 <= max_attempts <= 10:
            raise ValueError("max_attempts must be between 1 and 10")
        self.client, self.max_attempts, self.sleep = client, max_attempts, sleep

    def deliver(self, readings):
        # Detach once; retries never recollect or rewrite event identity/time/content.
        original = json.loads(json.dumps(readings))
        for attempt in range(self.max_attempts):
            try:
                return acknowledgment(self.client.send(original), len(original))
            except TransientDeliveryError:
                if attempt + 1 == self.max_attempts:
                    raise TransientDeliveryError(f"delivery failed after {self.max_attempts} attempts") from None
                self.sleep(min(0.5 * 2 ** attempt, 8.0))
        raise AssertionError("unreachable")
