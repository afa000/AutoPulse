"""Small structural read-only protocols; no physical command/adapter implementation."""

from dataclasses import dataclass
from enum import StrEnum
from typing import Protocol, runtime_checkable

from autopulse_agent.models.diagnostics import CodeCategory, DiagnosticSnapshot
from autopulse_agent.models.telemetry import TelemetrySample
from autopulse_agent.models.source import SampleSource


class ReadOperation(StrEnum):
    CAPABILITIES = "READ_CAPABILITIES"
    TELEMETRY = "READ_TELEMETRY"
    DIAGNOSTICS = "READ_DIAGNOSTICS"


def require_read_operation(operation: str) -> ReadOperation:
    if not isinstance(operation, str):
        raise ValueError("only allowlisted logical read operations are permitted")
    try:
        return ReadOperation(operation)
    except ValueError as error:
        raise ValueError("unknown/control operations are prohibited") from error


@dataclass(frozen=True)
class SourceCapabilities:
    source: SampleSource
    telemetry_fields: frozenset[str]
    diagnostics_supported: bool
    ecu_ids: tuple[str, ...]
    diagnostic_categories: frozenset[CodeCategory]

    def __post_init__(self):
        if not isinstance(self.source, SampleSource) or type(self.diagnostics_supported) is not bool:
            raise ValueError("typed source/support status required")
        if type(self.telemetry_fields) is not frozenset or any(type(v) is not str or not v for v in self.telemetry_fields):
            raise ValueError("immutable telemetry field capability set required")
        if type(self.ecu_ids) is not tuple or any(type(v) is not str or not v for v in self.ecu_ids) or tuple(sorted(set(self.ecu_ids))) != self.ecu_ids:
            raise ValueError("immutable sorted unique ECU capability IDs required")
        if type(self.diagnostic_categories) is not frozenset or any(not isinstance(v, CodeCategory) for v in self.diagnostic_categories):
            raise ValueError("immutable typed diagnostic category capability set required")
        if self.diagnostics_supported != bool(self.ecu_ids and self.diagnostic_categories):
            raise ValueError("diagnostic support and advertised coverage disagree")
        if not self.diagnostics_supported and (self.ecu_ids or self.diagnostic_categories):
            raise ValueError("unsupported source cannot advertise diagnostic coverage")


@runtime_checkable
class TelemetrySource(Protocol):
    def connect(self) -> None: ...
    def capabilities(self) -> set[str]: ...
    def read(self) -> TelemetrySample: ...
    def close(self) -> None: ...


@runtime_checkable
class DiagnosticSource(TelemetrySource, Protocol):
    def source_capabilities(self) -> SourceCapabilities: ...
    def read_diagnostics(self) -> DiagnosticSnapshot: ...
