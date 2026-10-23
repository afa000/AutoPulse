package dev.autopulse.trip;

import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
public class HistoryController {
    private final HistoryQueries queries;
    private final PublicOwnedHistory owned;
    private final boolean ownedEnabled;
    public HistoryController(HistoryQueries queries,PublicOwnedHistory owned,@Value("${autopulse.history.owned-aggregates-enabled:false}") boolean ownedEnabled) {
        this.queries=queries; this.owned=owned; this.ownedEnabled=ownedEnabled;
    }
    @GetMapping("/api/v1/vehicles/{vehicleId}/telemetry/history")
    public ResponseEntity<HistoryQueries.Page> history(@PathVariable String vehicleId,@RequestParam MultiValueMap<String,String> parameters) {
        var query=HistoryRequest.parse(parameters);
        var result=ownedEnabled && query.tripId()==null && !query.resolution().equals("raw")
                ? owned.history(vehicleId,query) : queries.history(vehicleId,query);
        return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Trip-Processing-State",result.processingState()).body(result.body());
    }
}
