package dev.autopulse.telemetry;

public class InvalidReading extends RuntimeException {
    public final String field;

    public InvalidReading(String field, String message) {
        super(message);
        this.field = field;
    }
}
