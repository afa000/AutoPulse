from datetime import datetime, timedelta, timezone
from uuid import UUID

import pytest

from autopulse_agent.models.source import SampleSource
from autopulse_agent.models.telemetry import TelemetrySample


def make_sample(**overrides):
    values = {
        "device_id": "sim-device-001",
        "vehicle_id": "venue-2021",
        "boot_id": UUID("123e4567-e89b-12d3-a456-426614174000"),
        "recorded_at": datetime(2026, 9, 10, 12, 30, tzinfo=timezone.utc),
        "sequence_number": 1,
        "source": SampleSource.SIMULATED,
    }
    values.update(overrides)
    return TelemetrySample(**values)


def test_optional_sensors_default_to_unavailable():
    sample = make_sample()

    sensor_fields = (
        "rpm",
        "speed_kph",
        "coolant_temp_c",
        "engine_load_pct",
        "throttle_position_pct",
        "intake_temp_c",
        "maf_gps",
        "fuel_level_pct",
        "control_module_voltage_v",
        "mil_on",
    )
    assert all(getattr(sample, field) is None for field in sensor_fields)


def test_zero_and_false_remain_observed_values():
    sample = make_sample(rpm=0.0, speed_kph=0.0, engine_load_pct=0.0, mil_on=False)

    assert sample.rpm == 0.0
    assert sample.speed_kph == 0.0
    assert sample.engine_load_pct == 0.0
    assert sample.mil_on is False
    assert sample.fuel_level_pct is None


def test_aware_offset_is_normalized_to_utc_without_changing_instant():
    eastern = timezone(timedelta(hours=-4))
    sample = make_sample(recorded_at=datetime(2026, 9, 10, 8, 30, tzinfo=eastern))

    assert sample.recorded_at == datetime(2026, 9, 10, 12, 30, tzinfo=timezone.utc)
    assert sample.recorded_at.tzinfo is timezone.utc


def test_valid_numeric_boundaries_and_negative_temperatures():
    sample = make_sample(
        rpm=0.0,
        speed_kph=0.0,
        maf_gps=0.0,
        control_module_voltage_v=0.0,
        engine_load_pct=100.0,
        throttle_position_pct=0.0,
        fuel_level_pct=100.0,
        coolant_temp_c=-40.0,
        intake_temp_c=-20.0,
        mil_on=False,
    )

    assert sample.coolant_temp_c == -40.0
    assert sample.intake_temp_c == -20.0
    assert sample.engine_load_pct == 100.0
    assert sample.mil_on is False


@pytest.mark.parametrize(
    ("field", "bad_value"),
    (
        ("device_id", ""),
        ("device_id", "   "),
        ("vehicle_id", None),
        ("boot_id", "not-a-uuid"),
        ("sequence_number", -1),
        ("sequence_number", True),
        ("sequence_number", 1.0),
        ("source", "SIMULATED"),
        ("recorded_at", datetime(2026, 9, 10, 12, 30)),
        ("recorded_at", "2026-09-10T12:30:00Z"),
        ("rpm", -0.1),
        ("speed_kph", -1.0),
        ("maf_gps", -1.0),
        ("control_module_voltage_v", -1.0),
        ("engine_load_pct", -0.1),
        ("throttle_position_pct", 100.1),
        ("fuel_level_pct", 101.0),
        ("intake_temp_c", True),
        ("coolant_temp_c", float("nan")),
        ("rpm", float("inf")),
        ("mil_on", 0),
    ),
)
def test_invalid_model_values_identify_the_field(field, bad_value):
    with pytest.raises(ValueError, match=field):
        make_sample(**{field: bad_value})
