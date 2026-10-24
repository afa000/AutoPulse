import math
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal
from uuid import UUID

from .source import SampleSource

SensorNumber = int | float | Decimal

@dataclass(frozen=True)
class TelemetrySample:
    """One collected reading; unavailable sensors are represented by None."""

    device_id: str
    vehicle_id: str
    boot_id: UUID
    recorded_at: datetime
    sequence_number: int
    source: SampleSource
    rpm: SensorNumber | None = None
    speed_kph: SensorNumber | None = None
    coolant_temp_c: SensorNumber | None = None
    engine_load_pct: SensorNumber | None = None
    throttle_position_pct: SensorNumber | None = None
    intake_temp_c: SensorNumber | None = None
    maf_gps: SensorNumber | None = None
    fuel_level_pct: SensorNumber | None = None
    control_module_voltage_v: SensorNumber | None = None
    mil_on: bool | None = None

    def __post_init__(self) -> None:
        for name in ("device_id", "vehicle_id"):
            value = getattr(self, name)
            if not isinstance(value, str) or not value.strip():
                raise ValueError(f"{name} must be a nonempty string")

        if not isinstance(self.boot_id, UUID):
            raise ValueError("boot_id must be a UUID")
        if type(self.sequence_number) is not int or self.sequence_number < 0:
            raise ValueError("sequence_number must be an integer greater than or equal to zero")
        if not isinstance(self.source, SampleSource):
            raise ValueError("source must be a SampleSource value")
        if not isinstance(self.recorded_at, datetime):
            raise ValueError("recorded_at must be a datetime with time-zone information")
        if self.recorded_at.tzinfo is None or self.recorded_at.utcoffset() is None:
            raise ValueError("recorded_at must include time-zone information")
        try:
            utc_time = self.recorded_at.astimezone(timezone.utc)
        except (OverflowError, ValueError) as exc:
            raise ValueError("recorded_at cannot be represented in UTC") from exc
        object.__setattr__(self, "recorded_at", utc_time)

        numeric_fields = (
            "rpm",
            "speed_kph",
            "coolant_temp_c",
            "engine_load_pct",
            "throttle_position_pct",
            "intake_temp_c",
            "maf_gps",
            "fuel_level_pct",
            "control_module_voltage_v",
        )
        for name in numeric_fields:
            value = getattr(self, name)
            if value is None:
                continue
            if isinstance(value, bool) or not isinstance(value, (int, float, Decimal)):
                raise ValueError(f"{name} must be a finite number or None")
            if isinstance(value, float) and not math.isfinite(value):
                raise ValueError(f"{name} must be a finite number or None")
            if isinstance(value, Decimal) and not value.is_finite():
                raise ValueError(f"{name} must be a finite number or None")
            try:
                approximate = float(value)
            except (OverflowError, ValueError) as exc:
                raise ValueError(f"{name} cannot be represented in storage") from exc
            if not math.isfinite(approximate) or (value != 0 and approximate == 0):
                raise ValueError(f"{name} cannot be represented in storage")
            if isinstance(value, Decimal):
                exponent = value.as_tuple().exponent
                if -exponent > 16383 or len(value.as_tuple().digits) + exponent > 131072:
                    raise ValueError(f"{name} exceeds JSONB numeric storage limits")

        for name in ("rpm", "speed_kph", "maf_gps", "control_module_voltage_v"):
            value = getattr(self, name)
            if value is not None and value < 0:
                raise ValueError(f"{name} must be greater than or equal to zero")

        for name in ("engine_load_pct", "throttle_position_pct", "fuel_level_pct"):
            value = getattr(self, name)
            if value is not None and not 0 <= value <= 100:
                raise ValueError(f"{name} must be between 0 and 100")

        if self.mil_on is not None and not isinstance(self.mil_on, bool):
            raise ValueError("mil_on must be a boolean or None")
