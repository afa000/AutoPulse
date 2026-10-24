"""Deterministic snapshots and normal-commute vehicle sources."""

from datetime import datetime, timedelta, timezone
from uuid import UUID, uuid4

from autopulse_agent.models.source import SampleSource
from autopulse_agent.models.telemetry import TelemetrySample
from .commute import CommuteConfig
from .diagnostics import SCENARIOS, diagnostic_frame, synthetic_temperature_at
from .protocol import ReadOperation, SourceCapabilities, require_read_operation
from autopulse_agent.models.diagnostics import CodeCategory, DiagnosticSnapshot


_PHASE_READINGS = (
    # Engine off: RPM and speed are known zero; engine-only values are unavailable.
    {"rpm": 0.0, "speed_kph": 0.0, "coolant_temp_c": 20.0},
    # Cold idle: the engine is running while the vehicle remains stopped.
    {
        "rpm": 850.0,
        "speed_kph": 0.0,
        "coolant_temp_c": 30.0,
        "engine_load_pct": 22.0,
        "throttle_position_pct": 8.0,
        "intake_temp_c": 20.0,
        "control_module_voltage_v": 14.1,
        "mil_on": False,
    },
    # Acceleration: RPM, speed, load, and throttle rise together.
    {
        "rpm": 2000.0,
        "speed_kph": 30.0,
        "coolant_temp_c": 45.0,
        "engine_load_pct": 48.0,
        "throttle_position_pct": 31.0,
        "intake_temp_c": 22.0,
        "control_module_voltage_v": 14.2,
        "mil_on": False,
    },
)

_SUPPORTED_FIELDS = frozenset(
    (
        "rpm",
        "speed_kph",
        "coolant_temp_c",
        "engine_load_pct",
        "throttle_position_pct",
        "intake_temp_c",
        "control_module_voltage_v",
        "mil_on",
    )
)


class SimulatedTelemetrySource:
    """Emit snapshots or a finite commute using an index-driven simulation clock."""

    def __init__(
        self,
        device_id: str,
        vehicle_id: str,
        start_at: datetime,
        interval: timedelta = timedelta(seconds=2),
        boot_id: UUID | None = None,
        *,
        scenario: str = "snapshots",
        config: CommuteConfig | None = None,
        diagnostic_scenario: str | None = None,
    ) -> None:
        if not isinstance(start_at, datetime) or start_at.utcoffset() is None:
            raise ValueError("start_at must include time-zone information")
        if not isinstance(interval, timedelta) or interval <= timedelta(0):
            raise ValueError("interval must be a positive timedelta")
        if scenario not in ("snapshots", "normal-commute", "synthetic-temperature"):
            raise ValueError("unknown scenario")
        if diagnostic_scenario is not None and diagnostic_scenario not in SCENARIOS:
            raise ValueError("unknown diagnostic scenario")
        if scenario == "synthetic-temperature" and interval != timedelta(seconds=2):
            raise ValueError("synthetic temperature fixture requires its fixed 2-second measurement interval")
        self.diagnostic_scenario = diagnostic_scenario
        self.scenario = scenario
        self.config = config if config is not None else CommuteConfig()
        if scenario == "normal-commute" and interval > min(self.config.durations):
            raise ValueError("interval must not exceed the shortest phase duration")

        self.device_id = device_id
        self.vehicle_id = vehicle_id
        self.boot_id = boot_id if boot_id is not None else uuid4()
        try:
            self.start_at = start_at.astimezone(timezone.utc)
        except (OverflowError, ValueError) as exc:
            raise ValueError("start_at cannot be represented in UTC") from exc
        self.interval = interval
        self._connected = False
        self._next_index = 0
        self._next_diagnostic_index = 0

    def connect(self) -> None:
        self._connected = True

    def capabilities(self) -> set[str]:
        require_read_operation(ReadOperation.CAPABILITIES)
        if not self._connected:
            raise RuntimeError("simulated source is not connected")
        return set(("rpm", "speed_kph", "coolant_temp_c", "mil_on")) if self.scenario == "synthetic-temperature" else set(_SUPPORTED_FIELDS)

    def source_capabilities(self) -> SourceCapabilities:
        fields = frozenset(self.capabilities())
        supported = self.diagnostic_scenario is not None
        return SourceCapabilities(SampleSource.SIMULATED, fields, supported,
                                  ("SIMULATOR",) if supported else (),
                                  frozenset(CodeCategory) if supported else frozenset())

    def read_diagnostics(self) -> DiagnosticSnapshot:
        require_read_operation(ReadOperation.DIAGNOSTICS)
        if not self._connected:
            raise RuntimeError("simulated source is not connected")
        snapshot = diagnostic_frame(self.device_id, self.vehicle_id, self.boot_id,
                                    self.start_at, self._next_diagnostic_index, self.diagnostic_scenario)
        self._next_diagnostic_index += 1
        return snapshot

    def read(self) -> TelemetrySample:
        require_read_operation(ReadOperation.TELEMETRY)
        if not self._connected:
            raise RuntimeError("simulated source is not connected")
        elapsed = self.interval * self._next_index
        if (self.scenario == "snapshots" and self._next_index >= len(_PHASE_READINGS)) or (
                self.scenario == "normal-commute" and elapsed > self.config.duration):
            raise StopIteration("simulated scenario is complete")

        index = self._next_index
        sample = TelemetrySample(
            device_id=self.device_id,
            vehicle_id=self.vehicle_id,
            boot_id=self.boot_id,
            recorded_at=self.start_at + self.interval * index,
            sequence_number=index,
            source=SampleSource.SIMULATED,
            **(_PHASE_READINGS[index] if self.scenario == "snapshots" else
               synthetic_temperature_at(elapsed) if self.scenario == "synthetic-temperature" else self.config.readings_at(elapsed)),
        )
        self._next_index += 1
        return sample

    def close(self) -> None:
        self._connected = False
