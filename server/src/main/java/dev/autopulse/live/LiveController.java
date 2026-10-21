package dev.autopulse.live;

import dev.autopulse.shared.ApiFailure;
import dev.autopulse.vehicle.VehicleRegistry;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class LiveController {
    private final VehicleRegistry registry;
    private final LiveStreams streams;
    public LiveController(VehicleRegistry registry, LiveStreams streams) { this.registry = registry; this.streams = streams; }

    @GetMapping("/api/v1/vehicles/{vehicleId}/live")
    public ResponseEntity<SseEmitter> subscribe(@PathVariable String vehicleId) {
        var id = registry.vehicle(vehicleId).orElseThrow(() -> new ApiFailure(404, "UNKNOWN_VEHICLE", "Vehicle is not registered", List.of()));
        try {
            return ResponseEntity.ok().header("Cache-Control", "no-cache").header("X-Accel-Buffering", "no")
                    .contentType(org.springframework.http.MediaType.TEXT_EVENT_STREAM).body(streams.subscribe(id, vehicleId));
        } catch (RejectedExecutionException full) {
            throw new ApiFailure(503, "SERVICE_UNAVAILABLE", "Live stream capacity reached; reconnect later", List.of());
        }
    }
}
