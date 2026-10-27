"""Validated finite limits for one device-local outbox."""

from dataclasses import dataclass


@dataclass(frozen=True)
class CapacityPolicy:
    payload_bytes: int = 16 * 1024 * 1024
    rows: int = 10000
    oldest_seconds: int = 7 * 24 * 60 * 60
    database_bytes: int = 64 * 1024 * 1024
    control_reserve_bytes: int = 4 * 1024 * 1024
    disk_free_bytes: int = 80 * 1024 * 1024

    def __post_init__(self):
        fields = (self.payload_bytes, self.rows, self.oldest_seconds, self.database_bytes,
                  self.control_reserve_bytes, self.disk_free_bytes)
        if any(type(value) is not int for value in fields):
            raise ValueError("capacity limits must be integers")
        if not (1 <= self.payload_bytes <= 16 * 1024 * 1024 and 1 <= self.rows <= 10000 and
                1 <= self.oldest_seconds <= 365 * 24 * 60 * 60 and
                128 * 1024 <= self.database_bytes <= 64 * 1024 * 1024 and
                16 * 1024 <= self.control_reserve_bytes <= self.database_bytes // 2 and
                0 <= self.disk_free_bytes <= 2**40):
            raise ValueError("capacity limits outside supported finite bounds")
