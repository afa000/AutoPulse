package dev.autopulse.ingestion;

import dev.autopulse.ingestion.BatchResult.ItemResult;
import dev.autopulse.telemetry.InvalidReading;
import dev.autopulse.telemetry.TelemetryRepository;
import dev.autopulse.telemetry.TelemetryValidator;
import dev.autopulse.telemetry.ValidatedReading;
import dev.autopulse.vehicle.VehicleRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.HashSet;
import dev.autopulse.live.TelemetryCommitted;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;

@Service
public class IngestionService {
    private final TelemetryValidator validator;
    private final VehicleRegistry registry;
    private final TelemetryRepository repository;
    private final ApplicationEventPublisher events;
    public IngestionService(TelemetryValidator validator, VehicleRegistry registry, TelemetryRepository repository, ApplicationEventPublisher events) {
        this.validator = validator; this.registry = registry; this.repository = repository;
        this.events = events;
    }

    @Transactional
    public BatchResult ingest(ArrayNode items, UUID correlationId) {
        List<ItemResult> results = new ArrayList<>();
        var changed = new HashSet<UUID>();
        for (int index = 0; index < items.size(); index++) {
            try {
                ValidatedReading sample = validator.validate(items.get(index));
                var vehicle = registry.vehicle(sample.vehicleId());
                var device = registry.device(sample.deviceId());
                if (vehicle.isEmpty()) {
                    results.add(ItemResult.rejected(index, "UNKNOWN_VEHICLE", "vehicleId", "vehicle is not registered"));
                } else if (device.isEmpty()) {
                    results.add(ItemResult.rejected(index, "UNKNOWN_DEVICE", "deviceId", "device is not registered"));
                } else if (!device.get().vehicleId().equals(vehicle.get())) {
                    results.add(ItemResult.rejected(index, "DEVICE_VEHICLE_MISMATCH", "deviceId", "device does not belong to this vehicle"));
                } else {
                    String status = repository.store(vehicle.get(), device.get().id(), sample);
                    if (status.equals("accepted")) changed.add(vehicle.get());
                    results.add(status.equals("conflict")
                            ? ItemResult.rejected(index, "IDENTITY_CONFLICT", "sequenceNumber", "identity already has different content")
                            : ItemResult.success(index, status));
                }
            } catch (InvalidReading error) {
                results.add(ItemResult.rejected(index, "INVALID_READING", error.field, error.getMessage()));
            }
        }
        int accepted = (int) results.stream().filter(item -> item.status().equals("accepted")).count();
        if (!changed.isEmpty()) events.publishEvent(new TelemetryCommitted(java.util.Set.copyOf(changed)));
        int duplicate = (int) results.stream().filter(item -> item.status().equals("duplicate")).count();
        return new BatchResult(accepted, duplicate, results.size() - accepted - duplicate, results, correlationId);
    }
}
