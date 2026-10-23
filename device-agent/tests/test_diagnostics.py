"""Checkpoint-1 contracts/source evidence, NOT a diagnostic event/recommendation engine."""

from copy import deepcopy
from dataclasses import FrozenInstanceError, replace
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import UUID

import pytest
import yaml
from openapi_schema_validator import OAS30Validator
from openapi_spec_validator import validate

from autopulse_agent import json_codec
from autopulse_agent.models.diagnostics import (CategoryRead, CodeCategory, DiagnosticSnapshot,
                                               EcuRead, MAX_BYTES, MAX_SEQUENCE, MilRead,
                                               ReadOutcome, snapshot_from_json,
                                               snapshot_from_payload, snapshot_to_json)
from autopulse_agent.models.source import SampleSource
from autopulse_agent.models.telemetry_json import sample_to_payload
from autopulse_agent.sources.protocol import (DiagnosticSource, ReadOperation,
                                              SourceCapabilities, TelemetrySource, require_read_operation)
from autopulse_agent.sources.simulated import SimulatedTelemetrySource

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = json_codec.loads((ROOT / 'contracts/examples/diagnostic-contract-fixtures.json').read_text())
BASE = datetime.fromisoformat(FIXTURE['baseTime'])
BOOT = UUID(FIXTURE['bootId'])


def source(**options):
    return SimulatedTelemetrySource('sim-device-001', 'venue-2021', BASE, boot_id=BOOT, **options)


def schema(name):
    spec = yaml.safe_load((ROOT / 'contracts/openapi.yaml').read_text())
    return OAS30Validator(dict(spec, **{'$ref': f'#/components/schemas/{name}'}),
                          format_checker=OAS30Validator.FORMAT_CHECKER)


def test_openapi_is_valid_and_diagnostic_routes_are_explicitly_implemented_at_checkpoint_two():
    spec = yaml.safe_load((ROOT / 'contracts/openapi.yaml').read_text())
    validate(spec)
    for path, verb in [('/api/v1/device/diagnostic-snapshots', 'post'),
                       ('/api/v1/vehicles/{vehicleId}/diagnostics', 'get'),
                       ('/api/v1/vehicles/{vehicleId}/diagnostics/{eventId}', 'get')]:
        assert spec['paths'][path][verb]['x-implementation-status'] == 'implemented'
    assert spec['paths']['/api/v1/device/diagnostic-snapshots']['post']['x-max-request-bytes'] == MAX_BYTES
    assert spec['components']['schemas']['DiagnosticSnapshot']['properties']['diagnosticSequence']['maximum'] == MAX_SEQUENCE


def test_shared_planned_api_examples_have_explicit_source_provenance_and_unknown_definition():
    examples = json_codec.loads((ROOT / 'contracts/examples/diagnostic-api-examples.json').read_text())
    for name, definition in [('snapshot', 'DiagnosticSnapshot'), ('acceptedReceipt', 'DiagnosticSnapshotReceipt'),
                             ('duplicateReceipt', 'DiagnosticSnapshotReceipt'), ('eventDetail', 'DiagnosticEvent'),
                             ('eventList', 'DiagnosticEventPage'), ('emptyList', 'DiagnosticEventPage')]:
        schema(definition).validate(examples[name])
    assert examples['acceptedReceipt']['snapshotId'] == examples['duplicateReceipt']['snapshotId']
    assert examples['acceptedReceipt']['receivedAt'] == examples['duplicateReceipt']['receivedAt']
    assert examples['eventDetail']['source'] == 'SIMULATED'
    assert examples['eventDetail']['definition']['verification'] == 'UNKNOWN'
    assert examples['eventDetail']['positiveObservationCount'] == 2


@pytest.mark.parametrize('case', FIXTURE['validExtra'], ids=lambda c: c['name'])
def test_additional_ecu_partial_positive_and_future_obd_contract_cases(case):
    schema('DiagnosticSnapshot').validate(case['payload'])
    value = snapshot_from_payload(case['payload'])
    assert snapshot_from_json(snapshot_to_json(value)).to_payload() == case['payload']
    for ecu in value.ecus:
        for c in ecu.categories:
            if c.outcome is ReadOutcome.PARTIAL:
                assert c.codes and not value.absence_eligible(c, value.captured_at)


@pytest.mark.parametrize('index', range(len(FIXTURE['lifecycle'])))
def test_shared_valid_snapshots_round_trip_schema_and_immutable_models(index):
    payload = FIXTURE['lifecycle'][index]
    schema('DiagnosticSnapshot').validate(payload)
    value = snapshot_from_payload(payload)
    assert value.to_payload() == payload
    assert snapshot_from_json(snapshot_to_json(value)) == value
    assert isinstance(value.source, SampleSource)
    with pytest.raises(FrozenInstanceError):
        value.ecus[0].categories[0].codes = ('P1999',)


@pytest.mark.parametrize('case', FIXTURE['invalid'], ids=lambda c: c['name'])
def test_shared_invalid_snapshots_agree_on_schema_vs_semantic_validation(case):
    errors = list(schema('DiagnosticSnapshot').iter_errors(case['payload']))
    assert bool(errors) == case['schemaInvalid']
    with pytest.raises(ValueError):
        snapshot_from_json(json_codec.dumps(case['payload']))


def test_source_matches_predeclared_lifecycle_without_reading_shared_files_or_consuming_telemetry():
    s = source(diagnostic_scenario='lifecycle')
    assert isinstance(s, TelemetrySource) and isinstance(s, DiagnosticSource)
    with pytest.raises(RuntimeError):
        s.read_diagnostics()
    s.connect()
    assert s.source_capabilities().diagnostics_supported is True
    assert s.source_capabilities().diagnostic_categories == frozenset(CodeCategory)
    assert s.source_capabilities().ecu_ids == ('SIMULATOR',)
    observed = [s.read_diagnostics().to_payload() for _ in range(12)]
    assert observed == FIXTURE['lifecycle'][:12]
    with pytest.raises(StopIteration):
        s.read_diagnostics()
    assert s.read().sequence_number == 0
    s.close()
    restarted = SimulatedTelemetrySource('sim-device-001', 'venue-2021', BASE + timedelta(seconds=22),
                                         boot_id=UUID(FIXTURE['restartBootId']), diagnostic_scenario='restart')
    restarted.connect()
    assert restarted.read_diagnostics().to_payload() == FIXTURE['lifecycle'][12]
    with pytest.raises(StopIteration):
        restarted.read_diagnostics()
    restarted.close()


def test_unsupported_is_not_empty_success_and_diagnostic_reconnect_does_not_reset_identity():
    s = source()
    s.connect()
    cap = s.source_capabilities()
    assert cap.diagnostics_supported is False and cap.ecu_ids == () and cap.diagnostic_categories == frozenset()
    first = s.read_diagnostics()
    assert first.ecus[0].outcome is ReadOutcome.UNSUPPORTED
    assert all(c.outcome is ReadOutcome.UNSUPPORTED and not c.codes for c in first.ecus[0].categories)
    assert first.ecus[0].mil.illuminated is None
    assert not first.absence_eligible(first.ecus[0].categories[0], BASE)
    s.close()
    with pytest.raises(RuntimeError):
        s.source_capabilities()
    s.connect()
    assert s.read_diagnostics().diagnostic_sequence == 1
    assert s.read().sequence_number == 0
    s.close()


def test_future_stale_unknown_and_watermark_boundaries_are_explicit_not_sleep_based():
    baseline = snapshot_from_payload(FIXTURE['lifecycle'][0])
    category = baseline.ecus[0].categories[0]
    assert baseline.absence_eligible(category, BASE)
    assert baseline.absence_eligible(category, BASE + timedelta(seconds=300))
    assert not baseline.absence_eligible(category, BASE + timedelta(seconds=300, microseconds=1))
    assert not baseline.absence_eligible(category, BASE - timedelta(seconds=120))
    with pytest.raises(ValueError):
        baseline.validate_receipt(BASE - timedelta(seconds=120, microseconds=1))
    assert not baseline.absence_eligible(category, BASE, baseline.order_key)
    newer_unknown = snapshot_from_payload(FIXTURE['lifecycle'][7])
    assert not baseline.absence_eligible(category, BASE + timedelta(seconds=20), newer_unknown.order_key)
    assert not newer_unknown.absence_eligible(newer_unknown.ecus[0].categories[0], BASE + timedelta(seconds=20))
    with pytest.raises(ValueError):
        baseline.absence_eligible(CategoryRead(CodeCategory.PENDING, ReadOutcome.COMPLETE), BASE)


def test_ties_microseconds_independent_sequences_replay_and_hand_counts_are_not_derived_results():
    values = [snapshot_from_payload(p) for p in FIXTURE['lifecycle']]
    keys = [(v.device_id, v.boot_id, v.diagnostic_sequence) for v in values]
    deliveries = [keys[i] for i in FIXTURE['deliveryOrder']]
    expected = FIXTURE['expected']
    assert len(deliveries) == expected['deliveries'] == 15
    assert len(set(deliveries)) == expected['uniqueSnapshots'] == 13
    assert len(deliveries) - len(set(deliveries)) == expected['duplicates'] == 2
    assert values[11].captured_at.microsecond == 1 and values[11].order_key < values[2].order_key
    assert values[12].diagnostic_sequence == 0 and values[12].order_key > values[10].order_key
    tied = replace(values[0], boot_id=UUID(int=BOOT.int + 1))
    assert tied.order_key > values[0].order_key
    assert replace(values[0], diagnostic_sequence=1).order_key > values[0].order_key
    assert len(expected['cycles']) == expected['eventCycles'] == 5
    assert sum(c['category'] == 'CONFIRMED' for c in expected['cycles']) == expected['confirmedCodeAdvisoryCycles'] == 3
    assert [c['positiveObservationCount'] for c in expected['cycles']] == [3, 2, 3, 2, 2]
    # This is an explicit expectation manifest, not an unimplemented projector test.
    assert expected['implementationStatus'].startswith('EXPECTED ONLY')


@pytest.mark.parametrize('operation', ['CLEAR_DTC', 'RESET_ECU', 'CODING', 'ACTUATION', 'SECURITY_ACCESS',
                                       'RAW_CAN', 'WRITE', 'execute', 'UNKNOWN', '', 'read_diagnostics'])
def test_read_only_logical_guard_rejects_every_control_unknown_or_unmapped_name(operation):
    with pytest.raises(ValueError):
        require_read_operation(operation)


def test_allowlist_and_source_surface_have_no_generic_command_passthrough():
    assert tuple(ReadOperation) == tuple(require_read_operation(o.value) for o in ReadOperation)
    for name in ('execute', 'command', 'clear_dtc', 'reset', 'actuate', 'send_raw', 'security_access'):
        assert not hasattr(source(), name)
    for invalid in (None, 1, ['READ_TELEMETRY']):
        with pytest.raises(ValueError):
            require_read_operation(invalid)


def test_decoder_byte_identity_version_duplicate_and_precision_boundaries():
    payload = FIXTURE['lifecycle'][0]
    raw = json_codec.dumps(payload)
    assert snapshot_from_json(raw.encode()).to_payload() == payload
    assert snapshot_from_json(raw + ' ' * (MAX_BYTES - len(raw))).to_payload() == payload
    with pytest.raises(ValueError):
        snapshot_from_json(raw + ' ' * (MAX_BYTES - len(raw) + 1))
    with pytest.raises(ValueError):
        snapshot_from_json(raw.replace('"snapshotVersion": 1', '"snapshotVersion": 1, "snapshotVersion": 1'))
    for field, invalid in [('diagnosticSequence', MAX_SEQUENCE + 1), ('diagnosticSequence', 1.0),
                           ('snapshotVersion', True), ('capturedAt', '2026-02-30T00:00:00Z'),
                           ('capturedAt', '2026-10-22T12:00:00+00:00'), ('bootId', str(BOOT).upper())]:
        changed = deepcopy(payload)
        changed[field] = invalid
        with pytest.raises(ValueError):
            snapshot_from_json(json_codec.dumps(changed))
    maximum = snapshot_from_payload(dict(payload, diagnosticSequence=MAX_SEQUENCE))
    assert snapshot_from_json(snapshot_to_json(maximum)).diagnostic_sequence == MAX_SEQUENCE


def test_synthetic_temperature_readings_match_hand_fixture_without_claiming_a_rule_engine():
    fixture = json_codec.loads((ROOT / 'contracts/examples/synthetic-temperature-fixture.json').read_text())
    s = source(scenario='synthetic-temperature')
    s.connect()
    samples = [s.read() for _ in range(fixture['expected']['inputSamples'])]
    with pytest.raises(StopIteration):
        s.read()
    assert [dict(offsetSeconds=int((v.recorded_at - BASE).total_seconds()), coolantTempC=v.coolant_temp_c) for v in samples] == fixture['readings']
    for value in samples:
        schema('TelemetrySample').validate(sample_to_payload(value))
    assert all(v.source is SampleSource.SIMULATED and v.mil_on is False for v in samples)
    assert samples[17].coolant_temp_c is None
    assert samples[16].recorded_at - samples[1].recorded_at == timedelta(seconds=30)
    assert samples[-1].recorded_at - samples[-2].recorded_at == timedelta(seconds=2)
    assert fixture['expected']['opensAtOffsetSeconds'] == 32
    assert fixture['expected']['recoversAtOffsetSeconds'] == 70
    assert fixture['expected']['dtcInferred'] is False
    assert fixture['expected']['implementationStatus'].startswith('EXPECTED ONLY')
    s.close()
    with pytest.raises(ValueError):
        source(scenario='synthetic-temperature', interval=timedelta(seconds=1))


def test_baseline_is_complete_success_and_invalid_scenario_is_rejected():
    s = source(diagnostic_scenario='baseline')
    s.connect()
    assert s.read_diagnostics().to_payload() == FIXTURE['lifecycle'][0]
    with pytest.raises(StopIteration):
        s.read_diagnostics()
    s.close()
    with pytest.raises(ValueError):
        source(diagnostic_scenario='unknown')


def test_immutable_capability_and_maximum_ecu_code_limits_are_truthful():
    s = source(scenario='synthetic-temperature', diagnostic_scenario='baseline')
    s.connect()
    cap = s.source_capabilities()
    assert cap.telemetry_fields == frozenset(s.capabilities()) == frozenset(('rpm', 'speed_kph', 'coolant_temp_c', 'mil_on'))
    with pytest.raises(FrozenInstanceError):
        cap.diagnostics_supported = False
    with pytest.raises(ValueError):
        SourceCapabilities(SampleSource.SIMULATED, frozenset(), False, ('ENGINE',), frozenset(CodeCategory))
    codes = tuple(f'P00{i:02X}' for i in range(32))
    categories = tuple(CategoryRead(c, ReadOutcome.COMPLETE, codes) for c in CodeCategory)
    ecus = tuple(EcuRead(f'ECU{i:02}', ReadOutcome.COMPLETE, MilRead(ReadOutcome.COMPLETE, True), categories) for i in range(8))
    value = DiagnosticSnapshot('sim-device-001', 'venue-2021', BOOT, MAX_SEQUENCE, BASE, SampleSource.SIMULATED, ecus)
    schema('DiagnosticSnapshot').validate(value.to_payload())
    assert snapshot_from_json(snapshot_to_json(value)) == value
    with pytest.raises(ValueError):
        CategoryRead(CodeCategory.CONFIRMED, ReadOutcome.COMPLETE, codes + ('P0100',))
    with pytest.raises(ValueError):
        replace(value, ecus=ecus + (EcuRead('ECU09', ReadOutcome.COMPLETE, MilRead(ReadOutcome.COMPLETE, True), categories),))
    s.close()
