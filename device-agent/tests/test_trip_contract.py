"""Trip/history schemas and independent fixtures; HTTP behavior is tested in Java."""

from copy import deepcopy
from datetime import datetime, timedelta
from decimal import Decimal
from pathlib import Path
from uuid import UUID

import pytest
import yaml
from openapi_schema_validator import OAS30Validator
from openapi_spec_validator import validate

from autopulse_agent import json_codec
from autopulse_agent.models.telemetry_json import sample_to_payload
from autopulse_agent.sources.commute import CommuteConfig
from autopulse_agent.sources.simulated import SimulatedTelemetrySource


ROOT = Path(__file__).resolve().parents[2]
EXAMPLES = ROOT / "contracts/examples"


def read_json(name):
    return json_codec.loads((EXAMPLES / name).read_text(encoding="utf-8"))


def validator(name):
    spec = yaml.safe_load((ROOT / "contracts/openapi.yaml").read_text(encoding="utf-8"))
    return OAS30Validator(dict(spec, **{"$ref": f"#/components/schemas/{name}"}),
                          format_checker=OAS30Validator.FORMAT_CHECKER)


def test_proposed_document_and_examples():
    spec = yaml.safe_load((ROOT / "contracts/openapi.yaml").read_text(encoding="utf-8"))
    validate(spec)
    for path in ("/api/v1/vehicles/{vehicleId}/trips", "/api/v1/vehicles/{vehicleId}/trips/{tripId}"):
        assert spec["paths"][path]["get"]["x-implementation-status"] == "implemented"
    history = spec["paths"]["/api/v1/vehicles/{vehicleId}/telemetry/history"]["get"]
    assert history["x-implementation-status"] == "implemented"
    assert history["x-supported-resolutions"] == ["raw", "1m", "5m"]
    examples = read_json("trip-api-examples.json")
    schemas = {"detail": "Trip", "list": "TripPage", "emptyList": "TripPage",
               "historyRaw": "HistoryPage", "historyAggregate": "HistoryPage",
               "historyEmpty": "HistoryPage", "historyExpired": "HistoryPage",
               "pageChanged": "ApiError"}
    assert examples.keys() == schemas.keys()
    for name, schema in schemas.items():
        validator(schema).validate(examples[name])


@pytest.mark.parametrize("fixture_index", [0, 1])
def test_commute_inputs_still_match_real_source_and_hand_totals(fixture_index):
    fixtures = read_json("trip-rule-fixtures.json")
    case = fixtures["commutes"][fixture_index]
    readings = read_json(case["inputFile"])["readings"]
    base = datetime.fromisoformat(fixtures["baseTime"])
    source = SimulatedTelemetrySource("sim-device-001", "venue-2021", base,
        boot_id=UUID(fixtures["bootId"]), scenario="normal-commute",
        config=CommuteConfig(tuple(timedelta(seconds=s) for s in case["phaseSeconds"])))
    source.connect()
    generated = []
    try:
        while True:
            # Compare the decimal values actually sent on the JSON wire, not binary floats.
            generated.append(json_codec.loads(json_codec.dumps(sample_to_payload(source.read()))))
    except StopIteration:
        pass
    finally:
        source.close()
    assert generated == readings
    assert len(readings) == case["inputSampleCount"]
    schema = validator("TelemetrySample")
    for reading in readings:
        schema.validate(reading)
    expected = case["expected"]
    start = base + timedelta(seconds=expected["startOffsetSeconds"])
    end = base + timedelta(seconds=expected["endOffsetSeconds"])
    members = [r for r in readings if start <= datetime.fromisoformat(r["recordedAt"]) <= end]
    assert len(members) == expected["sampleCount"]
    for name, statistics in expected["metrics"].items():
        values = [r[name] for r in members if r[name] is not None]
        assert statistics == {"count": len(values), "sum": sum(values) if values else None,
                              "min": min(values) if values else None, "max": max(values) if values else None}
    # Independent hand phase sums, not outputs copied from the Java trip evaluator.
    for prefix, metric in (("rpm", "rpm"), ("speed", "speedKph"), ("load", "engineLoadPct")):
        assert sum(case["derivation"][prefix + "PhaseSums"]) == expected["metrics"][metric]["sum"]
    phases = case["phaseSeconds"]
    assert Decimal(50) * phases[2] / 2 + Decimal(85) * phases[3] * Decimal("0.8") == expected["speedAreaKphSeconds"]


def test_half_open_history_and_inclusive_trip_end_are_explicit():
    examples = read_json("trip-api-examples.json")
    detail, raw, aggregate = (examples[k] for k in ("detail", "historyRaw", "historyAggregate"))
    assert detail["sampleCount"] == 4
    assert len(raw["points"]) == 3
    lower, upper = (datetime.fromisoformat(raw[k]) for k in ("from", "to"))
    assert upper == datetime.fromisoformat(detail["endedAt"])
    for point in raw["points"]:
        assert lower <= datetime.fromisoformat(point["reading"]["recordedAt"]) < upper
    bucket = aggregate["points"][0]
    assert bucket["partial"] is True
    assert bucket["sampleCount"] == len(raw["points"])
    for name, statistics in bucket["metrics"].items():
        values = [p["reading"][name] for p in raw["points"] if p["reading"][name] is not None]
        assert statistics == {"count": len(values), "sum": sum(values) if values else None,
                              "min": min(values) if values else None, "max": max(values) if values else None}
    distance = detail["distance"]
    ratio = distance["kilometres"]
    assert Decimal(ratio["numerator"]) / Decimal(ratio["denominator"]) == Decimal("0.04")
    assert distance["coveredSeconds"] == distance["durationSeconds"] == 6
    assert examples["historyEmpty"]["availability"]["incomplete"] is False
    assert examples["historyExpired"]["availability"]["incomplete"] is True


def test_contract_rejects_unbounded_pages_and_invalid_distance_ratio():
    examples = read_json("trip-api-examples.json")
    oversized = deepcopy(examples["list"])
    oversized["items"] *= 101
    assert list(validator("TripPage").iter_errors(oversized))
    oversized = deepcopy(examples["historyRaw"])
    oversized["points"] = oversized["points"][:1] * 1001
    assert list(validator("HistoryPage").iter_errors(oversized))
    invalid = deepcopy(examples["detail"])
    invalid["distance"]["kilometres"]["denominator"] = 0
    assert list(validator("Trip").iter_errors(invalid))
    unavailable = deepcopy(examples["detail"])
    unavailable["distance"]["kilometres"] = None
    unavailable["distance"]["coveredSeconds"] = 0
    validator("Trip").validate(unavailable)


def test_large_analytical_tokens_are_valid_exact_json_numbers_not_sensor_limits():
    fixture = read_json("analytics-numeric-cases.json")
    schema = validator("MetricStatistics")
    for case in fixture["cases"]:
        value = case["sum"]
        schema.validate({"count": case["count"], "sum": value,
                         "min": None if value is None else Decimal("-1e308"),
                         "max": None if value is None else Decimal("1e308")})
    assert fixture["cases"][0]["sum"] == Decimal("2.7e309")
    assert fixture["cases"][1]["sum"] == Decimal("-2.7e309")
    assert fixture["cases"][2]["sum"] == 14 * Decimal("1e308") - 13 * Decimal("1e308")
    validator("NullableDistanceRatio").validate(fixture["distance"])
