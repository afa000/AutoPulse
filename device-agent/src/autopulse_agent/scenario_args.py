"""Shared explicit offline/network simulation selection."""

from datetime import datetime, timedelta, timezone
from uuid import UUID

from .sources.commute import CommuteConfig
from .sources.simulated import SimulatedTelemetrySource
from .models.telemetry_json import sample_from_payload


def add_scenario_arguments(parser):
    parser.add_argument("--scenario", choices=("snapshots", "normal-commute"), default="snapshots")
    parser.add_argument("--sample-interval", type=float, default=2.0, help="Simulated seconds between measurements")
    parser.add_argument("--phase-seconds", nargs=6, type=float, default=(10, 30, 120, 300, 30, 10),
                        metavar=("OFF", "COLD", "CITY", "HIGHWAY", "HOT", "FINAL"))
    parser.add_argument("--start-at", help="Fixed RFC timestamp for reproducible simulation")
    parser.add_argument("--boot-id", type=UUID, help="Fixed identity for reproducible simulation; do not reuse for new events")
    parser.add_argument("--device-id", default="sim-device-001")
    parser.add_argument("--vehicle-id", default="venue-2021")


def source_from_args(args):
    start = datetime.now(timezone.utc)
    if args.start_at:
        # Reuse the production timestamp rules rather than a looser CLI parser.
        start = sample_from_payload(dict(deviceId=args.device_id, vehicleId=args.vehicle_id,
            bootId=str(args.boot_id or UUID(int=0)), recordedAt=args.start_at,
            sequenceNumber=0, source="SIMULATED")).recorded_at
    return SimulatedTelemetrySource(args.device_id, args.vehicle_id, start,
        timedelta(seconds=args.sample_interval), args.boot_id, scenario=args.scenario,
        config=CommuteConfig(tuple(timedelta(seconds=s) for s in args.phase_seconds)))
