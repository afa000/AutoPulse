"""Validate actual disposable Java HTTP responses against the shared public schemas."""

from pathlib import Path
import json
import yaml
from openapi_schema_validator import OAS30Validator

ROOT = Path(__file__).resolve().parents[1]


def validate_examples(examples, spec):
    names = {'eventList': 'DiagnosticEventPage', 'eventDetail': 'DiagnosticEvent'}
    if set(examples) != set(names):
        raise ValueError('Missing actual diagnostic HTTP evidence; run DiagnosticApiTests first')
    for name, schema in names.items():
        OAS30Validator(dict(spec, **{'$ref': f'#/components/schemas/{schema}'}),
                       format_checker=OAS30Validator.FORMAT_CHECKER).validate(examples[name])


if __name__ == '__main__':
    examples = json.loads((ROOT / 'server/target/diagnostic-http-examples.json').read_text())
    spec = yaml.safe_load((ROOT / 'contracts/openapi.yaml').read_text())
    validate_examples(examples, spec)
    print('Actual diagnostic HTTP list/detail responses match the shared OpenAPI schemas.')
