"""Convert between the shared JSON payload shape and the Python telemetry model."""

import re
from datetime import datetime, timezone
from typing import Any, Mapping
from uuid import UUID

from .source import SampleSource
from .telemetry import TelemetrySample


_UUID_TEXT = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
_TIMESTAMP_TEXT = re.compile(
    r"[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt](?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]"
    r"(?:\.[0-9]{1,6})?(?:[Zz]|[+-](?:[01][0-9]|2[0-3]):[0-5][0-9])"
)


_REQUIRED_KEYS = (
    "deviceId",
    "vehicleId",
    "bootId",
    "recordedAt",
    "sequenceNumber",
    "source",
)

_SENSOR_KEYS = (
    ("rpm", "rpm"),
    ("speedKph", "speed_kph"),
    ("coolantTempC", "coolant_temp_c"),
    ("engineLoadPct", "engine_load_pct"),
    ("throttlePositionPct", "throttle_position_pct"),
    ("intakeTempC", "intake_temp_c"),
    ("mafGps", "maf_gps"),
    ("fuelLevelPct", "fuel_level_pct"),
    ("controlModuleVoltageV", "control_module_voltage_v"),
    ("milOn", "mil_on"),
)


def sample_from_payload(payload: Mapping[str, Any]) -> TelemetrySample:
    """Parse one telemetry payload; model validation handles value ranges."""
    if not isinstance(payload, Mapping):
        raise ValueError("telemetry payload must be an object")

    for key in _REQUIRED_KEYS:
        if key not in payload:
            raise ValueError(f"{key} is required")

    boot_id_text = payload["bootId"]
    if not isinstance(boot_id_text, str) or not _UUID_TEXT.fullmatch(boot_id_text):
        raise ValueError("bootId must be a UUID string")
    try:
        boot_id = UUID(boot_id_text)
    except ValueError as exc:
        raise ValueError("bootId must be a UUID string") from exc

    recorded_at_text = payload["recordedAt"]
    if not isinstance(recorded_at_text, str) or not _TIMESTAMP_TEXT.fullmatch(recorded_at_text):
        raise ValueError("recordedAt must be an RFC 3339 timestamp with a time-zone offset")
    try:
        recorded_at = datetime.fromisoformat(recorded_at_text.replace("z", "Z"))
    except ValueError as exc:
        raise ValueError("recordedAt must be an RFC 3339 timestamp with a time-zone offset") from exc

    try:
        source = SampleSource(payload["source"])
    except ValueError as exc:
        raise ValueError("source must be SIMULATED or OBD") from exc

    readings = {
        python_name: payload.get(json_name)
        for json_name, python_name in _SENSOR_KEYS
    }
    return TelemetrySample(
        device_id=payload["deviceId"],
        vehicle_id=payload["vehicleId"],
        boot_id=boot_id,
        recorded_at=recorded_at,
        sequence_number=payload["sequenceNumber"],
        source=source,
        **readings,
    )


def sample_to_payload(sample: TelemetrySample) -> dict[str, Any]:
    """Produce the complete JSON-ready payload, including null sensor values."""
    if sample.recorded_at.tzinfo is None or sample.recorded_at.utcoffset() is None:
        raise ValueError("recordedAt must include time-zone information")

    recorded_at_utc = sample.recorded_at.astimezone(timezone.utc)
    payload = {
        "deviceId": sample.device_id,
        "vehicleId": sample.vehicle_id,
        "bootId": str(sample.boot_id),
        "recordedAt": recorded_at_utc.isoformat().replace("+00:00", "Z"),
        "sequenceNumber": sample.sequence_number,
        "source": sample.source.value,
    }
    payload.update(
        (json_name, getattr(sample, python_name))
        for json_name, python_name in _SENSOR_KEYS
    )
    return payload
