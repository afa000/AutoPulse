package dev.autopulse.ingestion;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.autopulse.shared.FieldError;
import java.util.List;
import java.util.UUID;

public record BatchResult(int accepted, int duplicate, int rejected, List<ItemResult> results, UUID correlationId) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ItemResult(int index, String status, String code, List<FieldError> errors) {
        static ItemResult success(int index, String status) { return new ItemResult(index, status, null, null); }
        static ItemResult rejected(int index, String code, String field, String message) {
            return new ItemResult(index, "rejected", code, List.of(new FieldError(field, message)));
        }
    }
}
