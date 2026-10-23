package dev.autopulse.maintenance;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/vehicles/{vehicleId}")
public class MaintenanceController {
    private final MaintenanceService service;
    private final MaintenanceInput input;
    public MaintenanceController(MaintenanceService service,MaintenanceInput input) { this.service=service;this.input=input; }
    private ResponseEntity<JsonNode> ok(JsonNode n) { return ResponseEntity.ok().header("Cache-Control","no-store").body(n); }
    private void noQuery(HttpServletRequest request) { if(request.getQueryString()!=null) throw MaintenanceInput.invalid(); }
    @GetMapping("/service-profile") public ResponseEntity<JsonNode> profile(@PathVariable String vehicleId,HttpServletRequest request) { noQuery(request);return ok(service.profile(vehicleId)); }
    @PutMapping(value="/service-profile",consumes="application/json") public ResponseEntity<JsonNode> profile(@PathVariable String vehicleId,HttpServletRequest request,@RequestParam MultiValueMap<String,String> query) throws IOException {
        noQuery(request);return ok(service.saveProfile(vehicleId,input.decode(request.getInputStream().readNBytes(8193))));
    }
    @PostMapping(value="/maintenance-records",consumes="application/json") public ResponseEntity<JsonNode> create(@PathVariable String vehicleId,HttpServletRequest request) throws IOException {
        noQuery(request);return ok(service.saveEntry(vehicleId,null,input.decode(request.getInputStream().readNBytes(8193))));
    }
    @PatchMapping(value="/maintenance-records/{entryId}",consumes="application/json") public ResponseEntity<JsonNode> edit(@PathVariable String vehicleId,@PathVariable String entryId,HttpServletRequest request) throws IOException {
        noQuery(request);return ok(service.saveEntry(vehicleId,input.uuid(entryId),input.decode(request.getInputStream().readNBytes(8193))));
    }
    @GetMapping("/maintenance-records/{entryId}") public ResponseEntity<JsonNode> entry(@PathVariable String vehicleId,@PathVariable String entryId,HttpServletRequest request) { noQuery(request);return ok(service.detail(vehicleId,input.uuid(entryId),false)); }
    @GetMapping("/recommendations/{recommendationId}") public ResponseEntity<JsonNode> recommendation(@PathVariable String vehicleId,@PathVariable String recommendationId,HttpServletRequest request) { noQuery(request);return ok(service.detail(vehicleId,input.uuid(recommendationId),true)); }
    @PatchMapping(value="/recommendations/{recommendationId}",consumes="application/json") public ResponseEntity<JsonNode> action(@PathVariable String vehicleId,@PathVariable String recommendationId,HttpServletRequest request) throws IOException {
        noQuery(request);return ok(service.action(vehicleId,input.uuid(recommendationId),input.decode(request.getInputStream().readNBytes(8193))));
    }
    @GetMapping({"/maintenance-records","/recommendations"}) public ResponseEntity<JsonNode> list(@PathVariable String vehicleId,@RequestParam MultiValueMap<String,String> query,HttpServletRequest request) {
        if(query.entrySet().stream().anyMatch(e->!Set.of("limit","cursor").contains(e.getKey())||e.getValue().size()!=1)) throw MaintenanceInput.invalid();
        String limit=query.getFirst("limit");if(limit!=null&&!limit.matches("[0-9]{1,3}")) throw MaintenanceInput.invalid();
        return ok(service.list(vehicleId,request.getRequestURI().endsWith("/recommendations"),limit==null?20:Integer.parseInt(limit),query.getFirst("cursor")));
    }
}
