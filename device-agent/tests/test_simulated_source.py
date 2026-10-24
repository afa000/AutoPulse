import json
from datetime import datetime, timedelta, timezone
from uuid import UUID

import pytest

from autopulse_agent.models.source import SampleSource
from autopulse_agent.models.telemetry_json import sample_from_payload, sample_to_payload
from autopulse_agent.sources.simulated import SimulatedTelemetrySource


START_AT = datetime(2026, 10, 20, 12, 0, tzinfo=timezone.utc)
BOOT_ID = UUID("123e4567-e89b-42d3-a456-426614174000")


def make_source(**overrides):
    values = {
        "device_id": "sim-device-001",
        "vehicle_id": "venue-2021",
        "start_at": START_AT,
        "interval": timedelta(seconds=2),
        "boot_id": BOOT_ID,
    }
    values.update(overrides)
    return SimulatedTelemetrySource(**values)


def test_engine_off_then_cold_idle_then_acceleration():
    source = make_source()
    source.connect()

    off, idle, acceleration = (source.read() for _ in range(3))

    assert [sample.sequence_number for sample in (off, idle, acceleration)] == [0, 1, 2]
    assert [sample.recorded_at for sample in (off, idle, acceleration)] == [
        START_AT,
        START_AT + timedelta(seconds=2),
        START_AT + timedelta(seconds=4),
    ]
    assert all(sample.boot_id == BOOT_ID for sample in (off, idle, acceleration))
    assert all(sample.source is SampleSource.SIMULATED for sample in (off, idle, acceleration))
    assert off.rpm == 0 and off.speed_kph == 0 and off.mil_on is None
    assert idle.rpm > 0 and idle.speed_kph == 0 and idle.mil_on is False
    assert acceleration.rpm > idle.rpm
    assert acceleration.speed_kph > idle.speed_kph
    assert acceleration.engine_load_pct > idle.engine_load_pct
    assert acceleration.throttle_position_pct > idle.throttle_position_pct
    assert "rpm" in source.capabilities()
    assert "maf_gps" not in source.capabilities()
    with pytest.raises(StopIteration):
        source.read()
    source.close()


def test_same_clock_and_identity_produce_identical_serialized_readings():
    first = make_source()
    second = make_source()
    first.connect()
    second.connect()

    first_payloads = [sample_to_payload(first.read()) for _ in range(3)]
    second_payloads = [sample_to_payload(second.read()) for _ in range(3)]

    assert first_payloads == second_payloads
    assert [
        sample_to_payload(sample_from_payload(payload)) for payload in first_payloads
    ] == first_payloads
    assert [json.loads(json.dumps(payload)) for payload in first_payloads] == first_payloads
    first.close()
    second.close()


def test_new_agent_instances_get_new_boot_ids_by_default(monkeypatch):
    generated = iter(
        (
            UUID("123e4567-e89b-42d3-a456-426614174000"),
            UUID("123e4567-e89b-42d3-a456-426614174001"),
        )
    )
    monkeypatch.setattr("autopulse_agent.sources.simulated.uuid4", lambda: next(generated))

    first = make_source(boot_id=None)
    second = make_source(boot_id=None)

    assert first.boot_id != second.boot_id


def test_source_requires_connection_and_positive_interval():
    source = make_source()
    with pytest.raises(RuntimeError, match="not connected"):
        source.read()
    with pytest.raises(RuntimeError, match="not connected"):
        source.capabilities()
    with pytest.raises(ValueError, match="interval"):
        make_source(interval=timedelta(0))

    source.connect()
    source.close()
    with pytest.raises(RuntimeError, match="not connected"):
        source.read()


@pytest.mark.parametrize("value", ("0001-01-01T00:00:00+01:00", "9999-12-31T23:59:59-01:00"))
def test_start_time_overflow_has_an_understandable_error(value):
    with pytest.raises(ValueError, match="start_at.*UTC"):
        make_source(start_at=datetime.fromisoformat(value))


def collect_commute(**overrides):
    source = make_source(scenario="normal-commute", **overrides)
    source.connect()
    samples = []
    try:
        while True:
            samples.append(source.read())
    except StopIteration:
        pass
    finally:
        source.close()
    return samples


def test_complete_commute_is_deterministic_coherent_and_larger_than_one_batch():
    samples = collect_commute()
    assert samples == collect_commute()
    assert len(samples) == 251
    assert [s.sequence_number for s in samples] == list(range(251))
    assert all(s.boot_id == BOOT_ID and s.source is SampleSource.SIMULATED for s in samples)
    assert all(b.recorded_at - a.recorded_at == timedelta(seconds=2) for a, b in zip(samples, samples[1:]))
    assert samples[0].rpm == samples[-1].rpm == 0
    assert samples[0].mil_on is samples[-1].mil_on is None
    assert all(s.maf_gps is None and s.fuel_level_pct is None for s in samples)
    assert [s.coolant_temp_c for s in samples] == sorted(s.coolant_temp_c for s in samples)
    assert samples[-1].coolant_temp_c == 90
    assert max(s.speed_kph for s in samples) == 85
    assert samples[25].speed_kph < samples[30].speed_kph
    assert samples[25].rpm < samples[30].rpm
    assert samples[25].engine_load_pct < samples[30].engine_load_pct
    assert samples[25].throttle_position_pct < samples[30].throttle_position_pct
    assert samples[65].speed_kph > samples[70].speed_kph
    assert samples[230].speed_kph == 0 and samples[230].rpm > 0  # hot idle


def test_phase_boundaries_configuration_completion_and_reconnect():
    from autopulse_agent.sources.commute import CommuteConfig
    config = CommuteConfig(tuple(timedelta(seconds=s) for s in (2, 4, 8, 8, 4, 2)))
    for seconds, phase in [(0, 0), (2, 1), (6, 2), (14, 3), (22, 4), (26, 5), (28, 5)]:
        assert config.phase_at(timedelta(seconds=seconds))[0] == phase
    assert len(collect_commute(config=config)) == 15
    source = make_source(scenario="normal-commute", config=config)
    source.connect()
    assert source.read().sequence_number == 0
    source.close()
    with pytest.raises(RuntimeError):
        source.read()
    source.connect()
    assert source.read().sequence_number == 1  # reconnect does not restart identity
    assert "maf_gps" not in source.capabilities()
    source.close()
    with pytest.raises(ValueError):
        CommuteConfig((timedelta(seconds=1),))
    with pytest.raises(ValueError, match="shortest"):
        make_source(scenario="normal-commute", config=config, interval=timedelta(seconds=3))
