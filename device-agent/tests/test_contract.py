import json
from datetime import datetime, timezone
from pathlib import Path

import yaml
from autopulse_agent import json_codec
from autopulse_agent.models.telemetry_json import sample_from_payload
import pytest
from openapi_schema_validator import OAS30Validator
from openapi_spec_validator import validate

from autopulse_agent.models.telemetry_json import sample_to_payload
from autopulse_agent.sources.simulated import SimulatedTelemetrySource


ROOT = Path(__file__).resolve().parents[2]


def test_sse_examples_match_event_data_contract():
    spec = yaml.safe_load((ROOT / "contracts/openapi.yaml").read_text())
    event = None
    for line in (ROOT / "contracts/examples/live-events.sse").read_text().splitlines():
        if line.startswith("event: "):
            event = line[7:]
        if line.startswith("data: "):
            schema_name = {"telemetry": "LatestTelemetry", "empty": "LiveEmpty"}[event]
            document = dict(spec, **{"$ref": f"#/components/schemas/{schema_name}"})
            OAS30Validator(document, format_checker=OAS30Validator.FORMAT_CHECKER).validate(json.loads(line[6:]))


def test_openapi_document_example_and_simulator_outputs():
    spec = yaml.safe_load((ROOT / "contracts/openapi.yaml").read_text(encoding="utf-8"))
    validate(spec)
    schema = spec["components"]["schemas"]["TelemetrySample"]
    validator = OAS30Validator(schema, format_checker=OAS30Validator.FORMAT_CHECKER)
    example = json.loads((ROOT / "contracts/examples/telemetry-sample.json").read_text())
    validator.validate(example)
    source = SimulatedTelemetrySource("sim-device-001", "venue-2021", datetime(2026, 10, 20, tzinfo=timezone.utc))
    source.connect()
    for _ in range(3):
        validator.validate(sample_to_payload(source.read()))
    source.close()
    commute = SimulatedTelemetrySource("sim-device-001", "venue-2021", datetime(2026, 10, 20, tzinfo=timezone.utc), scenario="normal-commute")
    commute.connect()
    try:
        while True:
            validator.validate(sample_to_payload(commute.read()))
    except StopIteration:
        pass
    finally:
        commute.close()


def test_api_examples_and_batch_result_accounting():
    spec = yaml.safe_load((ROOT / "contracts/openapi.yaml").read_text(encoding="utf-8"))
    examples = {
        "batch-request.json": "TelemetryBatch",
        "batch-result.json": "BatchResult",
        "latest-response.json": "LatestTelemetry",
        "request-error.json": "ApiError",
    }
    for filename, schema_name in examples.items():
        document = dict(spec, **{"$ref": f"#/components/schemas/{schema_name}"})
        example = json.loads((ROOT / "contracts/examples" / filename).read_text())
        OAS30Validator(document, format_checker=OAS30Validator.FORMAT_CHECKER).validate(example)
    result = json.loads((ROOT / "contracts/examples/batch-result.json").read_text())
    for status in ("accepted", "duplicate", "rejected"):
        assert result[status] == sum(item["status"] == status for item in result["results"])
    assert sum(result[name] for name in ("accepted", "duplicate", "rejected")) == len(result["results"])


def test_shared_exact_fixtures_against_model_and_format_aware_contract():
    spec = yaml.safe_load((ROOT / "contracts/openapi.yaml").read_text())
    validator = OAS30Validator(spec["components"]["schemas"]["TelemetrySample"],
                               format_checker=OAS30Validator.FORMAT_CHECKER)
    examples = ROOT / "contracts/examples"
    base = json_codec.loads((examples / "telemetry-sample.json").read_text())
    for case in json_codec.loads((examples / "valid-telemetry-cases.json").read_text()):
        payload = dict(base, **{case["field"]: case["value"]})
        validator.validate(payload)
        sample = sample_from_payload(payload)
        validator.validate(sample_to_payload(sample))
    for case in json_codec.loads((examples / "invalid-telemetry-cases.json").read_text()):
        payload = dict(base, **{case["field"]: case["value"]})
        with pytest.raises(ValueError):
            sample_from_payload(payload)
        # UTC overflow and database representability are explicit semantic rules,
        # not expressible using the portable OpenAPI 3.0 primitive constraints.
        errors = list(validator.iter_errors(payload))
        if not case.get("semanticOnly"):
            assert errors, case
