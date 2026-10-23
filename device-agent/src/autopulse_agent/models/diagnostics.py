"""Immutable diagnostic evidence, independent from telemetry identity and receipts."""

import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from enum import StrEnum
from uuid import UUID

from autopulse_agent import json_codec
from .source import SampleSource

MAX_BYTES = 65536
MAX_SEQUENCE = 9223372036854775807
_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,79}\Z")
_ECU = re.compile(r"[A-Z][A-Z0-9_-]{0,31}\Z")
_CODE = re.compile(r"[PBCU][0-3][0-9A-F]{3}\Z")
_TIME = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,6})?Z\Z")
_UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\Z")


class ReadOutcome(StrEnum):
    COMPLETE = "COMPLETE"
    PARTIAL = "PARTIAL"
    UNSUPPORTED = "UNSUPPORTED"
    UNAVAILABLE = "UNAVAILABLE"
    TIMEOUT = "TIMEOUT"
    FAILED = "FAILED"


class CodeCategory(StrEnum):
    PENDING = "PENDING"
    CONFIRMED = "CONFIRMED"
    PERMANENT = "PERMANENT"


@dataclass(frozen=True)
class CategoryRead:
    category: CodeCategory
    outcome: ReadOutcome
    codes: tuple[str, ...] = ()

    def __post_init__(self):
        if not isinstance(self.category, CodeCategory) or not isinstance(self.outcome, ReadOutcome):
            raise ValueError("typed category/outcome required")
        if type(self.codes) is not tuple or len(self.codes) > 32 or any(type(c) is not str or not _CODE.fullmatch(c) for c in self.codes):
            raise ValueError("at most 32 bounded uppercase code identifiers required")
        if tuple(sorted(set(self.codes))) != self.codes:
            raise ValueError("codes must be sorted and unique")
        if self.outcome not in (ReadOutcome.COMPLETE, ReadOutcome.PARTIAL) and self.codes:
            raise ValueError("failed/unsupported reads cannot carry positive code evidence")


@dataclass(frozen=True)
class MilRead:
    outcome: ReadOutcome
    illuminated: bool | None

    def __post_init__(self):
        if not isinstance(self.outcome, ReadOutcome) or self.outcome is ReadOutcome.PARTIAL:
            raise ValueError("MIL requires a complete boolean or explicit unknown outcome")
        if self.outcome is ReadOutcome.COMPLETE:
            if type(self.illuminated) is not bool:
                raise ValueError("complete MIL requires a boolean")
        elif self.illuminated is not None:
            raise ValueError("unknown MIL must be null, not false")


@dataclass(frozen=True)
class EcuRead:
    ecu_id: str
    outcome: ReadOutcome
    mil: MilRead
    categories: tuple[CategoryRead, ...]

    def __post_init__(self):
        if type(self.ecu_id) is not str or not _ECU.fullmatch(self.ecu_id) or not isinstance(self.outcome, ReadOutcome):
            raise ValueError("bounded ECU identity and typed outcome required")
        if not isinstance(self.mil, MilRead) or type(self.categories) is not tuple or any(not isinstance(c, CategoryRead) for c in self.categories):
            raise ValueError("immutable typed ECU reads required")
        if tuple(c.category for c in self.categories) != tuple(CodeCategory):
            raise ValueError("exactly PENDING, CONFIRMED, PERMANENT in canonical order required")
        outcomes = (self.mil.outcome, *(c.outcome for c in self.categories))
        complete = all(o is ReadOutcome.COMPLETE for o in outcomes)
        if (self.outcome is ReadOutcome.COMPLETE) != complete:
            raise ValueError("ECU completeness disagrees with constituent reads")
        if self.outcome not in (ReadOutcome.COMPLETE, ReadOutcome.PARTIAL) and any(o is not self.outcome for o in outcomes):
            raise ValueError("whole-ECU unavailable outcome must match its reads")


def _utc(value: datetime) -> datetime:
    if not isinstance(value, datetime) or value.utcoffset() is None:
        raise ValueError("timezone-aware time required")
    try:
        return value.astimezone(timezone.utc)
    except (ValueError, OverflowError) as error:
        raise ValueError("time cannot be represented in UTC") from error


@dataclass(frozen=True)
class DiagnosticSnapshot:
    device_id: str
    vehicle_id: str
    boot_id: UUID
    diagnostic_sequence: int
    captured_at: datetime
    source: SampleSource
    ecus: tuple[EcuRead, ...]

    def __post_init__(self):
        if any(type(v) is not str or not _ID.fullmatch(v) for v in (self.device_id, self.vehicle_id)):
            raise ValueError("bounded device/vehicle identifiers required")
        if not isinstance(self.boot_id, UUID) or type(self.diagnostic_sequence) is not int or not 0 <= self.diagnostic_sequence <= MAX_SEQUENCE:
            raise ValueError("UUID and independent nonnegative int64 diagnostic sequence required")
        if not isinstance(self.source, SampleSource):
            raise ValueError("typed source required")
        object.__setattr__(self, "captured_at", _utc(self.captured_at))
        if type(self.ecus) is not tuple or not 1 <= len(self.ecus) <= 8 or any(not isinstance(e, EcuRead) for e in self.ecus):
            raise ValueError("1..8 immutable typed ECU reads required")
        ids = tuple(e.ecu_id for e in self.ecus)
        if tuple(sorted(set(ids))) != ids:
            raise ValueError("ECU IDs must be sorted and unique")

    @property
    def order_key(self) -> tuple[datetime, int, int]:
        return self.captured_at, self.boot_id.int, self.diagnostic_sequence

    def validate_receipt(self, received_at: datetime) -> None:
        if self.captured_at > _utc(received_at) + timedelta(seconds=120):
            raise ValueError("capture exceeds 120-second future tolerance")

    def absence_eligible(self, category: CategoryRead, received_at: datetime,
                         previous_key: tuple[datetime, int, int] | None = None) -> bool:
        """Category eligibility only. Caller must also check that the target code is
        absent and that the previous watermark belongs to the same scope. This is
        NOT an event projector, a code-resolution decision or an assertion of health.
        """
        received_at = _utc(received_at)
        self.validate_receipt(received_at)
        if not any(category is c for e in self.ecus for c in e.categories):
            raise ValueError("category must belong to this snapshot/scope")
        return (category.outcome is ReadOutcome.COMPLETE
                and timedelta(0) <= received_at - self.captured_at <= timedelta(seconds=300)
                and (previous_key is None or self.order_key > previous_key))

    def to_payload(self) -> dict:
        return dict(snapshotVersion=1, deviceId=self.device_id, vehicleId=self.vehicle_id,
                    bootId=str(self.boot_id), diagnosticSequence=self.diagnostic_sequence,
                    capturedAt=self.captured_at.isoformat().replace("+00:00", "Z"), source=self.source.value,
                    ecus=[dict(ecuId=e.ecu_id, outcome=e.outcome.value,
                               mil=dict(outcome=e.mil.outcome.value, illuminated=e.mil.illuminated),
                               categories=[dict(category=c.category.value, outcome=c.outcome.value, codes=list(c.codes))
                                           for c in e.categories]) for e in self.ecus])


def _shape(value, keys):
    if type(value) is not dict or set(value) != set(keys.split()):
        raise ValueError("diagnostic object has missing or unknown fields")


def snapshot_from_payload(payload: dict) -> DiagnosticSnapshot:
    _shape(payload, "snapshotVersion deviceId vehicleId bootId diagnosticSequence capturedAt source ecus")
    if type(payload["snapshotVersion"]) is not int or payload["snapshotVersion"] != 1:
        raise ValueError("only integer snapshotVersion 1 is supported")
    if type(payload["bootId"]) is not str or not _UUID.fullmatch(payload["bootId"]):
        raise ValueError("canonical lowercase boot UUID required")
    if type(payload["capturedAt"]) is not str or not _TIME.fullmatch(payload["capturedAt"]):
        raise ValueError("UTC capturedAt with 0..6 fractional digits required")
    if type(payload["ecus"]) is not list or not 1 <= len(payload["ecus"]) <= 8:
        raise ValueError("1..8 ECU reads required")
    ecus = []
    for e in payload["ecus"]:
        _shape(e, "ecuId outcome mil categories")
        _shape(e["mil"], "outcome illuminated")
        if type(e["categories"]) is not list or len(e["categories"]) != 3:
            raise ValueError("exactly three category reads required")
        categories = []
        for c in e["categories"]:
            _shape(c, "category outcome codes")
            if type(c["codes"]) is not list or len(c["codes"]) > 32:
                raise ValueError("at most 32 code identifiers required")
            categories.append(CategoryRead(CodeCategory(c["category"]), ReadOutcome(c["outcome"]), tuple(c["codes"])))
        ecus.append(EcuRead(e["ecuId"], ReadOutcome(e["outcome"]), MilRead(ReadOutcome(e["mil"]["outcome"]), e["mil"]["illuminated"]), tuple(categories)))
    return DiagnosticSnapshot(payload["deviceId"], payload["vehicleId"], UUID(payload["bootId"]),
                              payload["diagnosticSequence"], datetime.fromisoformat(payload["capturedAt"]),
                              SampleSource(payload["source"]), tuple(ecus))


def snapshot_from_json(raw: bytes | str) -> DiagnosticSnapshot:
    # Decoder rejects duplicate keys before a dictionary can silently overwrite evidence.
    from simplejson import loads

    if type(raw) not in (bytes, str):
        raise ValueError("UTF-8 JSON required")
    encoded = raw.encode("utf-8") if type(raw) is str else raw
    if len(encoded) > MAX_BYTES:
        raise ValueError("diagnostic snapshot exceeds 65536 UTF-8 bytes")

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate diagnostic JSON key")
            result[key] = value
        return result

    try:
        return snapshot_from_payload(loads(encoded.decode("utf-8"), use_decimal=True, allow_nan=False, object_pairs_hook=unique))
    except (TypeError, KeyError, UnicodeError) as error:
        raise ValueError("invalid diagnostic JSON structure") from error


def snapshot_to_json(snapshot: DiagnosticSnapshot) -> str:
    encoded = json_codec.dumps(snapshot.to_payload(), separators=(",", ":"))
    if len(encoded.encode("utf-8")) > MAX_BYTES:
        raise ValueError("diagnostic snapshot exceeds byte limit")
    return encoded
