"""Validate actual disposable PostgreSQL/Java HTTP output, not invented examples."""

from pathlib import Path
import json
import yaml
from openapi_schema_validator import OAS30Validator

ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = {
    'profile': 'ServiceProfileView',
    'entryList': 'MaintenanceEntryPage',
    'entryDetail': 'MaintenanceEntryDetail',
    'recommendationList': 'RecommendationPage',
    'recommendationDetail': 'RecommendationDetail',
    'receipt': 'MaintenanceReceipt',
}


def validate_examples(examples, spec):
    if set(examples) != set(SCHEMAS):
        raise ValueError('Missing actual maintenance HTTP evidence; run MaintenanceApiTests first')
    for name, schema in SCHEMAS.items():
        OAS30Validator(dict(spec, **{'$ref': f'#/components/schemas/{schema}'}),
                       format_checker=OAS30Validator.FORMAT_CHECKER).validate(examples[name])


if __name__ == '__main__':
    examples = json.loads((ROOT / 'server/target/maintenance-http-examples.json').read_text())
    spec = yaml.safe_load((ROOT / 'contracts/openapi.yaml').read_text())
    validate_examples(examples, spec)
    print('Actual maintenance HTTP responses match the shared OpenAPI schemas.')
