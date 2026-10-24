"""Deterministic synthetic signals, not verified vehicle characteristics."""

from dataclasses import dataclass
from datetime import timedelta

PHASES = ("engine-off", "cold-idle", "city", "highway", "hot-idle", "engine-off-complete")


@dataclass(frozen=True)
class CommuteConfig:
    durations: tuple[timedelta, ...] = tuple(timedelta(seconds=s) for s in (10, 30, 120, 300, 30, 10))

    def __post_init__(self):
        if len(self.durations) != 6 or any(not isinstance(d, timedelta) or d <= timedelta(0) for d in self.durations):
            raise ValueError("phase durations must contain six positive timedeltas")

    @property
    def duration(self):
        return sum(self.durations, timedelta())

    def phase_at(self, elapsed):
        if elapsed < timedelta(0) or elapsed > self.duration:
            raise ValueError("elapsed time is outside the commute")
        start = timedelta()
        for index, duration in enumerate(self.durations):
            if elapsed < start + duration or index == 5:
                return index, (elapsed - start) / duration
            start += duration
        raise AssertionError("unreachable")

    def readings_at(self, elapsed):
        index, progress = self.phase_at(elapsed)
        warm_duration = sum(self.durations[1:4], timedelta())
        warm = max(0.0, min(1.0, (elapsed - self.durations[0]) / warm_duration))
        coolant = round(20 + 70 * warm, 3)
        if index in (0, 5):
            return {"rpm": 0.0, "speed_kph": 0.0, "coolant_temp_c": coolant}
        speed = 0.0
        rpm, load, throttle = 800.0, 20.0, 6.0
        if index == 1:
            rpm = 1200 - 350 * progress
            load, throttle = 24.0, 9.0
        elif index == 2:
            speed = 50 * (1 - abs(2 * progress - 1))
            rpm = 850 + 35 * speed
            accelerating = progress < 0.5
            load = 20 + speed * (0.7 if accelerating else 0.2)
            throttle = 8 + speed * (0.6 if accelerating else 0.1)
        elif index == 3:
            speed = 85 * min(1.0, progress / 0.2, (1 - progress) / 0.2)
            rpm, load, throttle = 800 + 25 * speed, 20 + 0.4 * speed, 6 + 0.3 * speed
        return {
            "rpm": round(rpm, 3), "speed_kph": round(speed, 3),
            "coolant_temp_c": coolant, "engine_load_pct": round(load, 3),
            "throttle_position_pct": round(throttle, 3), "intake_temp_c": 20.0,
            "control_module_voltage_v": 14.1, "mil_on": False,
        }
