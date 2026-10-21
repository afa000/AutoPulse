package dev.autopulse.shared;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {
    public record ErrorBody(String code, String message, UUID correlationId, List<FieldError> fieldErrors) {}

    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<ErrorBody> failure(ApiFailure error, HttpServletRequest request) {
        return ResponseEntity.status(error.status).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(new ErrorBody(error.code, error.getMessage(),
                (UUID) request.getAttribute(CorrelationFilter.ATTRIBUTE), error.errors));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ErrorBody> database(RuntimeException error, HttpServletRequest request) {
        return failure(new ApiFailure(503, "SERVICE_UNAVAILABLE", "Database unavailable; retry unchanged readings", List.of()), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorBody> media(HttpMediaTypeNotSupportedException error, HttpServletRequest request) {
        return failure(new ApiFailure(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json", List.of()), request);
    }
}
