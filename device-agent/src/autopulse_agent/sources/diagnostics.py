"""Finite synthetic diagnostic stories; identifiers have no verified catalog meanings."""

from datetime import timedelta

from autopulse_agent.models.diagnostics import (CategoryRead, CodeCategory, DiagnosticSnapshot,
                                               EcuRead, MilRead, ReadOutcome)
from autopulse_agent.models.source import SampleSource

SCENARIOS = frozenset(("baseline", "lifecycle", "restart"))


def diagnostic_frame(device, vehicle, boot, start, index, scenario):
    """Boot changes are new source instances; diagnostic counters never consume telemetry."""
    if scenario is not None and scenario not in SCENARIOS:
        raise ValueError("unknown diagnostic scenario")
    pending, confirmed, permanent = (), (), ()
    outcome, mil, offset = ReadOutcome.COMPLETE, False, timedelta(seconds=index * 2)
    categories = None
    if scenario is None:
        outcome, mil = ReadOutcome.UNSUPPORTED, None
    elif scenario == "baseline":
        if index != 0:
            raise StopIteration("diagnostic baseline complete")
    elif scenario == "restart":
        if index != 0:
            raise StopIteration("diagnostic restart fixture complete")
        confirmed, mil = ("P0117", "P1999"), True
    else:
        if index > 11:
            raise StopIteration("diagnostic lifecycle complete")
        if index == 1:
            pending, mil = ("P0117",), True
        elif index in (2, 3):
            pending = confirmed = permanent = ("P0117",)
            mil = True
        elif index == 4:
            outcome, mil = ReadOutcome.PARTIAL, None
            categories = (CategoryRead(CodeCategory.PENDING, ReadOutcome.UNSUPPORTED),
                          CategoryRead(CodeCategory.CONFIRMED, ReadOutcome.PARTIAL),
                          CategoryRead(CodeCategory.PERMANENT, ReadOutcome.TIMEOUT))
        elif index in (5, 6, 7):
            outcome = {5: ReadOutcome.UNAVAILABLE, 6: ReadOutcome.FAILED, 7: ReadOutcome.TIMEOUT}[index]
            mil = None
        elif index == 8:
            permanent = ("P0117",)
        elif index == 10:
            confirmed, mil = ("P0117", "P1999"), True
        elif index == 11:
            # Intentionally later delivery with an older measurement, not a new current fault.
            offset, confirmed, mil = timedelta(seconds=3, microseconds=1), ("P0117",), True
    if categories is None:
        categories = tuple(CategoryRead(category, outcome, codes)
                           for category, codes in zip(CodeCategory, (pending, confirmed, permanent)))
    mil_outcome = ReadOutcome.UNAVAILABLE if outcome is ReadOutcome.PARTIAL else outcome
    ecu = EcuRead("UNIDENTIFIED" if scenario is None else "SIMULATOR", outcome, MilRead(mil_outcome, mil), categories)
    return DiagnosticSnapshot(device, vehicle, boot, index, start + offset, SampleSource.SIMULATED, (ecu,))


def synthetic_temperature_at(elapsed):
    """Synthetic >=110C/30s, recovery <=95C/2s fixture, NOT manufacturer limits."""
    if elapsed > timedelta(seconds=70):
        raise StopIteration("synthetic temperature fixture complete")
    seconds = elapsed.total_seconds()
    coolant = None if seconds == 34 else 90 if seconds == 0 or seconds >= 68 else 120
    return dict(rpm=1000, speed_kph=0, coolant_temp_c=coolant, mil_on=False)
