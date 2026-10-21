package dev.autopulse.shared;

import java.util.List;

public class ApiFailure extends RuntimeException {
    public final int status;
    public final String code;
    public final List<FieldError> errors;

    public ApiFailure(int status, String code, String message, List<FieldError> errors) {
        super(message);
        this.status = status;
        this.code = code;
        this.errors = errors;
    }
}
