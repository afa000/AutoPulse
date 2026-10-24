package dev.autopulse.telemetry;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

public record ValidatedReading(String deviceId, String vehicleId, UUID bootId,
        BigInteger sequenceNumber, Instant recordedAt, ObjectNode payload) {}
