"""Pinned primary-document facts, strict manual schemas and independent rule cases."""

from pathlib import Path
import json
import yaml
import pytest
from openapi_schema_validator import OAS30Validator
from jsonschema.exceptions import ValidationError

ROOT = Path(__file__).resolve().parents[2]


def test_oil_rule_is_pinned_to_primary_us_manual_and_unknown_schedule_is_not_inferred():
    rule = json.loads((ROOT / 'server/src/main/resources/maintenance/oil-filter-v1.json').read_text())
    assert rule['applicability'] == 'US_2021_VENUE_G16'
    assert rule['normal'] == {'MI': '8000', 'KM': '13000', 'months': 12}
    assert rule['severe'] == {'MI': '5000', 'KM': '8000', 'months': 6}
    assert 'Hyundai Motor America' == rule['manufacturer']
    assert 'p8-11' in rule['sourceTitle'] and 'mirror' in rule['sourceHosting']
    assert 'not redistributed' in rule['rights']
    assert 'UNKNOWN' in rule['normalCondition']
    cases = json.loads((ROOT / 'contracts/examples/maintenance-rule-cases.json').read_text())
    assert len(cases['oilCases']) == 7
    assert cases['lifecycle']['diagnosticAdvisories'] == 3
    assert cases['lifecycle']['thermalRecoveryOffsetSeconds'] == 70


def test_manual_contract_uses_decimal_strings_revision_retry_identity_and_no_control_fields():
    spec = yaml.safe_load((ROOT / 'contracts/openapi.yaml').read_text())
    validator = OAS30Validator(dict(spec, **{'$ref': '#/components/schemas/MaintenanceEntryCommand'}),
                               format_checker=OAS30Validator.FORMAT_CHECKER)
    good = {'requestId': '123e4567-e89b-12d3-a456-426614174000', 'expectedRevision': 0,
            'kind': 'SERVICE', 'serviceType': 'OIL_FILTER', 'observedAt': '2026-01-01T00:00:00.123456Z',
            'odometerValue': '12345.001', 'odometerUnit': 'MI', 'notes': None, 'correctionReason': None}
    validator.validate(good)
    for bad in [dict(good, odometerValue=12345.001), dict(good, clearDtc=True),
                dict(good, odometerValue='-1'), dict(good, expectedRevision=-1)]:
        with pytest.raises(ValidationError):
            validator.validate(bad)
    routes = {p for p in spec['paths'] if 'maintenance-records' in p or 'recommendations' in p or 'service-profile' in p}
    assert len(routes) == 5
    assert all('{vehicleId}' in p for p in routes)
    assert all('delete' not in spec['paths'][p] for p in routes)
