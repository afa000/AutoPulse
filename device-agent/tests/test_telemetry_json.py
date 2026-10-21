import json
from pathlib import Path

import pytest

from autopulse_agent.models.source import SampleSource
from autopulse_agent.models.telemetry_json import sample_from_payload, sample_to_payload


EXAMPLE_PATH = (
    Path(__file__).resolve().parents[2]
    / "contracts"
    / "examples"
    / "telemetry-sample.json"
)


def example_payload():
    return json.loads(EXAMPLE_PATH.read_text(encoding="utf-8"))


def test_shared_example_round_trips_as_equivalent_json():
    original = example_payload()

    sample = sample_from_payload(original)
    encoded = sample_to_payload(sample)

    assert sample.source is SampleSource.SIMULATED
    assert encoded == original
    assert json.loads(json.dumps(encoded)) == original


def test_missing_null_zero_and_false_survive_conversion():
    payload = example_payload()
    del payload["rpm"]
    payload["mafGps"] = None
    payload["speedKph"] = 0
    payload["milOn"] = False

    sample = sample_from_payload(payload)
    encoded = sample_to_payload(sample)

    assert sample.rpm is None
    assert sample.maf_gps is None
    assert sample.speed_kph == 0
    assert sample.mil_on is False
    assert encoded["rpm"] is None
    assert encoded["mafGps"] is None
    assert encoded["speedKph"] == 0
    assert encoded["milOn"] is False


def test_offset_timestamp_serializes_to_same_instant_in_utc():
    payload = example_payload()
    payload["recordedAt"] = "2026-09-10T08:30:00-04:00"

    encoded = sample_to_payload(sample_from_payload(payload))

    assert encoded["recordedAt"] == "2026-09-10T12:30:00Z"
    assert encoded["bootId"] == payload["bootId"]
    assert encoded["sequenceNumber"] == payload["sequenceNumber"]


@pytest.mark.parametrize(
    "missing_key",
    ("deviceId", "vehicleId", "bootId", "recordedAt", "sequenceNumber", "source"),
)
def test_missing_required_identity_is_rejected(missing_key):
    payload = example_payload()
    del payload[missing_key]

    with pytest.raises(ValueError, match=missing_key):
        sample_from_payload(payload)


@pytest.mark.parametrize(
    ("field", "invalid_value"),
    (
        ("bootId", "not-a-uuid"),
        ("recordedAt", "not-a-timestamp"),
        ("source", "UNKNOWN"),
        ("bootId", "123e4567e89b12d3a456426614174000"),
        ("recordedAt", "20260910T123000+0000"),
        ("recordedAt", "2026-09-10X12:30:00+00:00"),
    ),
)
def test_invalid_converted_fields_have_clear_errors(field, invalid_value):
    payload = example_payload()
    payload[field] = invalid_value

    with pytest.raises(ValueError, match=field):
        sample_from_payload(payload)


@pytest.mark.parametrize(
    ("json_field", "bad_value", "model_field"),
    (
        ("deviceId", "  ", "device_id"),
        ("sequenceNumber", True, "sequence_number"),
        ("recordedAt", "2026-09-10T12:30:00", "recordedAt"),
        ("rpm", float("nan"), "rpm"),
        ("engineLoadPct", 101, "engine_load_pct"),
        ("milOn", 0, "mil_on"),
    ),
)
def test_payload_uses_same_model_validation(json_field, bad_value, model_field):
    payload = example_payload()
    payload[json_field] = bad_value

    with pytest.raises(ValueError, match=model_field):
        sample_from_payload(payload)


@pytest.mark.parametrize("value", ("0001-01-01T00:00:00+01:00", "9999-12-31T23:59:59-01:00"))
def test_utc_overflow_is_a_field_specific_value_error(value):
    payload = example_payload()
    payload["recordedAt"] = value
    with pytest.raises(ValueError, match="recorded_at.*UTC"):
        sample_from_payload(payload)


def test_uuid_case_and_valid_offsets_are_preserved_semantically():
    payload = example_payload()
    payload["bootId"] = payload["bootId"].upper()
    payload["recordedAt"] = "2026-09-10t08:30:00-04:00"
    encoded = sample_to_payload(sample_from_payload(payload))
    assert encoded["bootId"] == payload["bootId"].lower()
    assert encoded["recordedAt"] == "2026-09-10T12:30:00Z"


def test_shared_invalid_fixtures_are_rejected():
    from autopulse_agent.json_codec import loads
    cases = loads((EXAMPLE_PATH.parent / "invalid-telemetry-cases.json").read_text())
    for case in cases:
        payload = example_payload()
        payload[case["field"]] = case["value"]
        with pytest.raises(ValueError):
            sample_from_payload(payload)


@pytest.mark.parametrize("timestamp,expected", [
    ("2026-09-10t12:30:00z", "2026-09-10T12:30:00Z"),
    ("2026-09-10T12:30:00+23:59", "2026-09-09T12:31:00Z"),
    ("2026-09-10T12:30:00-23:59", "2026-09-11T12:29:00Z"),
])
def test_full_rfc_offset_range(timestamp, expected):
    payload = example_payload()
    payload["recordedAt"] = timestamp
    assert sample_to_payload(sample_from_payload(payload))["recordedAt"] == expected


def test_six_digit_precision_and_exact_decimal_codec_round_trip():
    from autopulse_agent.json_codec import loads, dumps
    payload = loads(dumps(example_payload()).replace('1900', '1900.0000000000000001'))
    payload["recordedAt"] = "2026-09-10T08:30:00.123456-04:00"
    encoded = sample_to_payload(sample_from_payload(payload))
    assert encoded["recordedAt"] == "2026-09-10T12:30:00.123456Z"
    assert '1900.0000000000000001' in dumps(encoded)
    assert loads(dumps(encoded))["rpm"] == payload["rpm"]
