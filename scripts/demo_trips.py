"""Finite checkpoint-6 sender for JUnit-owned disposable infrastructure only.

Uses production simulator/capture/HTTP transport, never database access. Generated
captures are demonstration artifacts, NOT the deferred durable device outbox.
"""

import argparse
from dataclasses import asdict
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import urlopen
from uuid import UUID

from autopulse_agent import json_codec as json
from autopulse_agent.capture import FORMAT, bounded_batches, encode_batch
from autopulse_agent.send import replay_capture, send_source
from autopulse_agent.sources.commute import CommuteConfig
from autopulse_agent.sources.simulated import SimulatedTelemetrySource
from autopulse_agent.transport.http import BatchSender, HttpBatchClient

ROOT = Path(__file__).resolve().parents[1]
BASE = datetime(2026, 10, 20, 12, tzinfo=timezone.utc)
BOOT = UUID("123e4567-e89b-42d3-a456-426614174000")
VEHICLES = ["venue-2021", "partition-vehicle", "short-vehicle", "tiny-vehicle",
            "edge-vehicle", "gap-vehicle", "tie-vehicle"]


def device(vehicle):
    return "sim-device-001" if vehicle == "venue-2021" else "sim-" + vehicle


def finite_rows(vehicle, entries):
    template = json.loads((ROOT / "contracts/examples/telemetry-sample.json").read_text())
    for sequence, entry in enumerate(entries):
        offset, rpm, speed, *boot = entry
        row = dict(template, vehicleId=vehicle, deviceId=device(vehicle), bootId=str(boot[0] if boot else BOOT),
                   sequenceNumber=sequence, recordedAt=(BASE + timedelta(seconds=offset)).isoformat(),
                   rpm=rpm, speedKph=speed, coolantTempC=None, engineLoadPct=None,
                   throttlePositionPct=None, intakeTempC=None, mafGps=None, fuelLevelPct=None,
                   controlModuleVoltageV=None, milOn=None)
        yield row


def seed(server, directory):
    for vehicle in VEHICLES:
        with urlopen(server + f"/api/v1/vehicles/{vehicle}/telemetry/latest", timeout=5) as response:
            assert response.status == 204, "Refusing seed: disposable target must be empty"
    directory.mkdir(exist_ok=False)
    sender = BatchSender(HttpBatchClient(server), sleep=lambda _: None)
    results = {}
    for vehicle, phases, batch in [("venue-2021", None, 100), ("partition-vehicle", None, 7),
                                    ("short-vehicle", (4, 8, 16, 20, 8, 4), 3)]:
        config = CommuteConfig() if phases is None else CommuteConfig(tuple(timedelta(seconds=s) for s in phases))
        source = SimulatedTelemetrySource(device(vehicle), vehicle, BASE, boot_id=BOOT,
                                         scenario="normal-commute", config=config)
        with (directory / f"{vehicle}.jsonl").open("xb") as capture:
            capture.write(json.dumps({"format": FORMAT}).encode() + b"\n")
            result = send_source(source, sender, interval=0.01, batch_size=batch, capture=capture,
                                 clock=lambda: 0, sleep=lambda _: None)
        results[vehicle] = asdict(result)
    fixture = json.loads((ROOT / "contracts/examples/trip-rule-fixtures.json").read_text())
    tiny = list(finite_rows("tiny-vehicle", [(r["offsetSeconds"], r["rpm"], r["speedKph"]) for r in fixture["tiny"]["readings"]]))
    # Preserve the independently hand-calculated nullable tiny sensor values.
    for row, expected in zip(tiny, fixture["tiny"]["readings"]):
        for name in ("coolantTempC", "engineLoadPct"):
            row[name] = expected.get(name)
    other_boot = UUID("aaaaaaaa-0000-4000-8000-000000000001")
    cases = {
        "tiny-vehicle": tiny,
        "edge-vehicle": list(finite_rows("edge-vehicle", [(0, 0, 0), (2, 1000, 0), (4, 2000, 36),
                            (6, None, None), (8, 1000, 36, other_boot), (10, 0, 0, other_boot), (12, 0, 0, other_boot)])),
        "gap-vehicle": list(finite_rows("gap-vehicle", [(0, 1000, 36), (2, 1000, 36),
                            (40, 1000, 36), (42, 1000, 36), (44, 0, 0), (46, 0, 0)])),
        "tie-vehicle": list(finite_rows("tie-vehicle", [(0, 0, 0, UUID("00000000-0000-4000-8000-000000000001")),
                            (0, 1000, 36, UUID("ffffffff-ffff-4fff-8fff-ffffffffffff")),
                            (2, 1000, 36, UUID("ffffffff-ffff-4fff-8fff-ffffffffffff")),
                            (4, 0, 0, UUID("ffffffff-ffff-4fff-8fff-ffffffffffff")),
                            (6, 0, 0, UUID("ffffffff-ffff-4fff-8fff-ffffffffffff"))])),
    }
    for vehicle, readings in cases.items():
        # Reverse delivery and duplicate it BEFORE any projection exists. Canonical
        # work ordering, not request order or sequence numbers, must determine trips.
        with (directory / f"{vehicle}.jsonl").open("xb") as capture:
            capture.write(json.dumps({"format": FORMAT}).encode() + b"\n")
            for batch in bounded_batches(reversed(readings), 3):
                capture.write(encode_batch(batch) + b"\n")
        with (directory / f"{vehicle}.jsonl").open("rb") as capture:
            result = replay_capture(capture, sender)
        with (directory / f"{vehicle}.jsonl").open("rb") as capture:
            duplicate = replay_capture(capture, sender)
        assert duplicate.accepted == 0 and duplicate.duplicate == len(readings)
        results[vehicle] = asdict(result)
    print(json.dumps({"seed": results, "transport": "production BatchSender/HttpBatchClient", "capture": "not a durable outbox"}))


def replay(server, directory):
    sender = BatchSender(HttpBatchClient(server), sleep=lambda _: None)
    results = {}
    for vehicle in VEHICLES:
        with (directory / f"{vehicle}.jsonl").open("rb") as capture:
            result = replay_capture(capture, sender)
        assert result.accepted == result.rejected == 0
        results[vehicle] = asdict(result)
    print(json.dumps({"replay": results}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-url", required=True)
    parser.add_argument("--captures", required=True, type=Path)
    parser.add_argument("--mode", choices=("seed", "replay"), required=True)
    parser.add_argument("--isolated-demo", action="store_true", required=True)
    args = parser.parse_args()
    target = urlparse(args.server_url)
    if target.scheme != "http" or target.hostname != "127.0.0.1" or not target.port or target.path:
        parser.error("Requires the explicit JUnit-owned loopback URL")
    (seed if args.mode == "seed" else replay)(args.server_url, args.captures)
