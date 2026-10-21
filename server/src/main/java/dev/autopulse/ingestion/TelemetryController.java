package dev.autopulse.ingestion;

import dev.autopulse.shared.ApiFailure;
import dev.autopulse.shared.CorrelationFilter;
import dev.autopulse.shared.FieldError;
import dev.autopulse.telemetry.TelemetryRepository;
import dev.autopulse.vehicle.VehicleRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

@RestController
public class TelemetryController {
    public static final int MAX_BYTES = 262144;
    private final ObjectMapper mapper;
    private final IngestionService service;
    private final VehicleRegistry registry;
    private final TelemetryRepository repository;
    public TelemetryController(ObjectMapper mapper, IngestionService service, VehicleRegistry registry, TelemetryRepository repository) {
        this.mapper = mapper; this.service = service; this.registry = registry; this.repository = repository;
    }

    @PostMapping(value = "/api/v1/device/telemetry/batches", consumes = "application/json")
    public BatchResult ingest(HttpServletRequest request) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new ApiFailure(413, "REQUEST_TOO_LARGE", "Request exceeds 262144 bytes", List.of());
        JsonNode envelope;
        try {
            envelope = mapper.readerFor(JsonNode.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readValue(bytes);
        } catch (RuntimeException error) {
            throw new ApiFailure(400, "INVALID_REQUEST", "Malformed JSON", List.of());
        }
        if (envelope == null || !envelope.isObject() || envelope.size() != 1 || !envelope.has("readings")
                || !envelope.get("readings").isArray() || envelope.get("readings").isEmpty() || envelope.get("readings").size() > 100)
            throw new ApiFailure(400, "INVALID_REQUEST", "readings must contain between 1 and 100 items",
                    List.of(new FieldError("readings", "must contain between 1 and 100 items")));
        return service.ingest((ArrayNode) envelope.get("readings"), (UUID) request.getAttribute(CorrelationFilter.ATTRIBUTE));
    }

    @GetMapping("/api/v1/vehicles/{vehicleId}/telemetry/latest")
    public ResponseEntity<TelemetryRepository.Latest> latest(@PathVariable String vehicleId) {
        UUID internalId = registry.vehicle(vehicleId).orElseThrow(() ->
                new ApiFailure(404, "UNKNOWN_VEHICLE", "Vehicle is not registered", List.of()));
        return repository.latest(internalId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }
}
