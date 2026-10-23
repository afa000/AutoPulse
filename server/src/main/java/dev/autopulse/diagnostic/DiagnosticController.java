package dev.autopulse.diagnostic;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
public class DiagnosticController {
    private final DiagnosticValidator validator;
    private final DiagnosticStore store;
    private final DiagnosticQueries queries;
    public DiagnosticController(DiagnosticValidator validator,DiagnosticStore store,DiagnosticQueries queries) { this.validator=validator;this.store=store;this.queries=queries; }
    @PostMapping(value="/api/v1/device/diagnostic-snapshots",consumes="application/json")
    public ResponseEntity<DiagnosticStore.Receipt> accept(HttpServletRequest request) throws IOException {
        var receipt=store.accept(validator.decode(request.getInputStream().readNBytes(DiagnosticValidator.MAX_BYTES+1)));
        return ResponseEntity.ok().header("Cache-Control","no-store").body(receipt);
    }
    @GetMapping("/api/v1/vehicles/{vehicleId}/diagnostics")
    public ResponseEntity<DiagnosticQueries.Page> list(@PathVariable String vehicleId,@RequestParam MultiValueMap<String,String> parameters) {
        validate(parameters,Set.of("limit","cursor","category","observationState"));String raw=parameters.getFirst("limit");
        if(raw!=null && !raw.matches("[0-9]{1,3}")) throw DiagnosticQueries.invalid();
        var result=queries.list(vehicleId,raw==null?20:Integer.parseInt(raw),parameters.getFirst("category"),parameters.getFirst("observationState"),parameters.getFirst("cursor"));
        return ResponseEntity.ok().header("Cache-Control","no-store").body(result);
    }
    @GetMapping("/api/v1/vehicles/{vehicleId}/diagnostics/{eventId}")
    public ResponseEntity<DiagnosticQueries.Event> detail(@PathVariable String vehicleId,@PathVariable String eventId,@RequestParam MultiValueMap<String,String> parameters) {
        validate(parameters,Set.of());
        if(!eventId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw DiagnosticQueries.invalid();
        var result=queries.detail(vehicleId,UUID.fromString(eventId));
        return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Diagnostic-Processing-State",result.processingState()).body(result.event());
    }
    private static void validate(MultiValueMap<String,String> parameters,Set<String> allowed) {
        if(parameters.entrySet().stream().anyMatch(e->!allowed.contains(e.getKey()) || e.getValue().size()!=1)) throw DiagnosticQueries.invalid();
    }
}
