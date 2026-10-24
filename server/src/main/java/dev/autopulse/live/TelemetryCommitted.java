package dev.autopulse.live;

import java.util.Set;
import java.util.UUID;

public record TelemetryCommitted(Set<UUID> vehicles) {}
