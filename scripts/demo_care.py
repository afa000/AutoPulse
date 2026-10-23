"""Finite simulator/real HTTP rehearsal for CareMilestoneDemo's disposable database.

Captures are ignored demonstration evidence, not the future durable edge outbox.
There is no hardware interface or ECU-command transport here.
"""

import argparse
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import urlopen
from uuid import UUID

from autopulse_agent import json_codec as json
from autopulse_agent.models.diagnostics import snapshot_from_payload
from autopulse_agent.models.telemetry_json import sample_to_payload
from autopulse_agent.sources.simulated import SimulatedTelemetrySource
from autopulse_agent.transport.diagnostics import DiagnosticSender, HttpDiagnosticClient
from autopulse_agent.transport.http import BatchSender, HttpBatchClient

BOOT = UUID('123e4567-e89b-42d3-a456-426614174000')
RESTART = UUID('223e4567-e89b-42d3-a456-426614174000')
MODES = ('baseline', 'seed', 'replay-seed', 'uncertain', 'absence', 'recurrence', 'first-low', 'recovery', 'replay')


def collect(base):
    source = SimulatedTelemetrySource('sim-device-001', 'venue-2021', base, boot_id=BOOT,
                                     scenario='synthetic-temperature', diagnostic_scenario='lifecycle')
    source.connect()
    try:
        diagnostics = [source.read_diagnostics().to_payload() for _ in range(12)]
        thermal = [source.read() for _ in range(36)]
    finally:
        source.close()
    restart = SimulatedTelemetrySource('sim-device-001', 'venue-2021', base + timedelta(seconds=22),
                                      boot_id=RESTART, diagnostic_scenario='restart')
    restart.connect()
    try:
        diagnostics.append(restart.read_diagnostics().to_payload())
    finally:
        restart.close()
    # Explicit additional stale/older low control: cannot recover the active scope.
    stale = replace(thermal[0], sequence_number=1000, recorded_at=base - timedelta(seconds=600))
    return dict(format='autopulse-care-demo-v1', base=base.isoformat(), diagnostics=diagnostics,
                thermal=[sample_to_payload(v) for v in thermal], stale=sample_to_payload(stale))


def deliver(server, capture, mode):
    dropped = []

    def lose_first_ack(request, **options):
        response = urlopen(request, **options)
        if not dropped:
            with response:
                response.read()
            dropped.append(True)
            raise TimeoutError('controlled loss after real diagnostic commit')
        return response

    client = HttpDiagnosticClient(server, opener=lose_first_ack) if mode == 'seed' else HttpDiagnosticClient(server)
    diagnostic_sender = DiagnosticSender(client, sleep=lambda _: None)
    telemetry_sender = BatchSender(HttpBatchClient(server), sleep=lambda _: None)
    indices = {'baseline': [0], 'seed': [1, 2, 3], 'replay-seed': list(range(4)),
               'uncertain': [4, 5, 6, 7, 11], 'absence': [8, 9], 'recurrence': [10, 12],
               'first-low': [], 'recovery': [], 'replay': list(range(13)) + [3, 12]}[mode]
    thermal_indices = {'baseline': [0], 'seed': list(range(1, 17)), 'replay-seed': list(range(17)),
                       'uncertain': [17], 'absence': [], 'recurrence': [], 'first-low': [34],
                       'recovery': list(range(18, 34)) + [35], 'replay': list(range(36))}[mode]
    receipts = [diagnostic_sender.deliver(snapshot_from_payload(capture['diagnostics'][i])) for i in indices]
    rows = [capture['thermal'][i] for i in thermal_indices]
    if mode in ('uncertain', 'replay'):
        rows.append(capture['stale'])
    summary = telemetry_sender.deliver(rows) if rows else None
    if mode.startswith('replay'):
        assert all(r['result'] == 'DUPLICATE' for r in receipts)
        assert summary is not None and summary.accepted == summary.rejected == 0 and summary.duplicate == len(rows)
    print(json.dumps(dict(mode=mode, diagnostics=receipts, telemetry=None if summary is None else
                          dict(accepted=summary.accepted, duplicate=summary.duplicate, rejected=summary.rejected),
                          diagnosticAckLostAfterCommit=bool(dropped), capture='not a durable outbox')))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server-url', required=True)
    parser.add_argument('--capture', required=True, type=Path)
    parser.add_argument('--mode', choices=MODES, required=True)
    parser.add_argument('--isolated-demo', action='store_true', required=True)
    parser.add_argument('--base')
    args = parser.parse_args()
    target = urlsplit(args.server_url)
    if target.scheme != 'http' or target.hostname != '127.0.0.1' or not target.port or target.path or target.query or target.fragment or target.username or target.password:
        parser.error('Requires the explicit JUnit-owned loopback URL without credentials or suffix')
    if args.mode == 'baseline':
        if not args.base:
            parser.error('--base required for initial capture')
        with urlopen(args.server_url + '/api/v1/vehicles/venue-2021/diagnostics', timeout=5) as response:
            initial = json.loads(response.read())
            assert initial['items'] == [] and initial['latestSnapshot'] is None, 'Refusing non-empty diagnostic target'
        with urlopen(args.server_url + '/api/v1/vehicles/venue-2021/telemetry/latest', timeout=5) as response:
            assert response.status == 204, 'Refusing non-empty telemetry target'
        base = datetime.fromisoformat(args.base)
        if base.utcoffset() is None or not timedelta(seconds=75) <= datetime.now(timezone.utc) - base <= timedelta(seconds=240):
            parser.error('Demo base must be an aware recent past time, 75..240 seconds ago')
        capture = collect(base)
        with args.capture.open('x', encoding='utf-8') as output:
            output.write(json.dumps(capture))
    else:
        capture = json.loads(args.capture.read_text(encoding='utf-8'))
        if capture['format'] != 'autopulse-care-demo-v1':
            raise ValueError('unsupported demo capture')
    deliver(args.server_url, capture, args.mode)


if __name__ == '__main__':
    main()
