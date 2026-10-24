"""Catalog provenance/unknown fallback and the actual HTTP contract gate's validators."""

import importlib.util
from pathlib import Path

import pytest
import yaml
from jsonschema.exceptions import ValidationError
from autopulse_agent import json_codec

ROOT = Path(__file__).resolve().parents[2]


def test_tiny_catalog_uses_original_licensed_summaries_and_explicit_primary_provenance():
    catalog = json_codec.loads((ROOT / 'server/src/main/resources/diagnostic/catalog-v1.json').read_text())
    assert catalog['version'] == 'autopulse-generic-v1'
    assert catalog['license'].startswith('MIT (original AutoPulse summaries only')
    assert {e['code'] for e in catalog['entries']} == {'P0117', 'P0300'}
    assert all(e['sourceUrl'].startswith('https://static.nhtsa.gov/odi/tsbs/') for e in catalog['entries'])
    assert all(e['sourceTitle'] and len(e['description']) <= 500 for e in catalog['entries'])
    assert 'not Hyundai-specific' in catalog['scope']
    notice = (ROOT / 'server/src/main/resources/diagnostic/catalog-license.txt').read_text()
    assert 'not the' in notice and 'referenced Audi/FCA documents' in notice


def test_actual_http_gate_accepts_shared_shapes_and_rejects_unknown_or_invalid_fields():
    module_spec = importlib.util.spec_from_file_location('diagnostic_http_gate', ROOT / 'scripts/verify_diagnostic_http.py')
    module = importlib.util.module_from_spec(module_spec)
    module_spec.loader.exec_module(module)
    shared = json_codec.loads((ROOT / 'contracts/examples/diagnostic-api-examples.json').read_text())
    spec = yaml.safe_load((ROOT / 'contracts/openapi.yaml').read_text())
    examples = {name: shared[name] for name in ('eventList', 'eventDetail')}
    module.validate_examples(examples, spec)
    with pytest.raises(ValueError):
        module.validate_examples({}, spec)
    examples['eventDetail']['unboundedAdapterLog'] = 'must not be accepted'
    with pytest.raises(ValidationError):
        module.validate_examples(examples, spec)
