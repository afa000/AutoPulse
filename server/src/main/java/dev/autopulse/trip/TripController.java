package dev.autopulse.trip;

import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TripController {
    private final TripQueries queries;
    public TripController(TripQueries queries) { this.queries=queries; }
    @GetMapping("/api/v1/vehicles/{vehicleId}/trips")
    public ResponseEntity<TripQueries.TripPage> list(@PathVariable String vehicleId,@RequestParam MultiValueMap<String,String> parameters) {
        validate(parameters,Set.of("limit","cursor"));
        String limit=parameters.getFirst("limit");
        if (limit!=null && !limit.matches("[0-9]{1,3}")) throw TripQueries.invalid("limit must be an integer from 1 to 100");
        return response(queries.list(vehicleId,limit==null ? 20 : Integer.parseInt(limit),parameters.getFirst("cursor")));
    }
    @GetMapping("/api/v1/vehicles/{vehicleId}/trips/{tripId}")
    public ResponseEntity<TripQueries.Trip> detail(@PathVariable String vehicleId,@PathVariable String tripId,@RequestParam MultiValueMap<String,String> parameters) {
        validate(parameters,Set.of());
        if (!tripId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw TripQueries.invalid("tripId must be a UUID");
        return response(queries.detail(vehicleId,UUID.fromString(tripId)));
    }
    private static void validate(MultiValueMap<String,String> parameters,Set<String> allowed) {
        if (parameters.entrySet().stream().anyMatch(entry -> !allowed.contains(entry.getKey()) || entry.getValue().size()!=1))
            throw TripQueries.invalid("Unknown or repeated query parameter");
    }
    private static <T> ResponseEntity<T> response(TripQueries.Result<T> result) {
        return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Trip-Processing-State",result.processingState())
                .header("X-Trip-Data-Generation",result.generation()).body(result.body());
    }
}
