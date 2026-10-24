package dev.autopulse.telemetry;

import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class TelemetryValidator {
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern TIME_TEXT = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt](?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\\.[0-9]{1,6})?(?:[Zz]|[+-](?:[01][0-9]|2[0-3]):[0-5][0-9])");
    public static final List<String> NUMBERS = List.of("rpm", "speedKph", "coolantTempC", "engineLoadPct", "throttlePositionPct", "intakeTempC", "mafGps", "fuelLevelPct", "controlModuleVoltageV");
    private final ObjectMapper mapper;

    public TelemetryValidator(ObjectMapper mapper) { this.mapper = mapper; }

    public ValidatedReading validate(JsonNode input) {
        if (input == null || !input.isObject()) throw new InvalidReading("reading", "must be an object");
        String device = text(input, "deviceId");
        String vehicle = text(input, "vehicleId");
        String bootText = text(input, "bootId");
        if (!UUID_TEXT.matcher(bootText).matches()) throw new InvalidReading("bootId", "must be a hyphenated UUID");
        UUID boot = UUID.fromString(bootText);
        String timeText = text(input, "recordedAt");
        if (!TIME_TEXT.matcher(timeText).matches()) throw new InvalidReading("recordedAt", "must be an RFC 3339 timestamp with an offset");
        OffsetDateTime time;
        try {
            // RFC 3339 permits offsets through 23:59; ZoneOffset stops at 18:00.
            boolean utc = timeText.endsWith("Z") || timeText.endsWith("z");
            int end = timeText.length() - (utc ? 1 : 6);
            int offsetSeconds = utc ? 0 : (Integer.parseInt(timeText.substring(end + 1, end + 3)) * 60
                    + Integer.parseInt(timeText.substring(end + 4))) * 60 * (timeText.charAt(end) == '-' ? -1 : 1);
            time = LocalDateTime.parse(timeText.substring(0, end).toUpperCase(java.util.Locale.ROOT),
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC)
                    .minusSeconds(offsetSeconds).atOffset(ZoneOffset.UTC);
            if (time.getYear() < 1 || time.getYear() > 9999) throw new IllegalArgumentException();
        } catch (RuntimeException error) {
            throw new InvalidReading("recordedAt", "cannot be represented as a UTC timestamp");
        }
        JsonNode sequence = input.get("sequenceNumber");
        if (sequence == null || !sequence.isIntegralNumber() || sequence.bigIntegerValue().signum() < 0)
            throw new InvalidReading("sequenceNumber", "must be a nonnegative integer");
        String source = text(input, "source");
        if (!List.of("SIMULATED", "OBD").contains(source)) throw new InvalidReading("source", "must be SIMULATED or OBD");
        ObjectNode canonical = mapper.createObjectNode();
        canonical.put("deviceId", device).put("vehicleId", vehicle).put("bootId", boot.toString())
                .put("recordedAt", time.toInstant().toString()).put("sequenceNumber", sequence.bigIntegerValue()).put("source", source);
        for (String field : NUMBERS) {
            JsonNode value = input.get(field);
            if (value == null || value.isNull()) { canonical.putNull(field); continue; }
            if (!value.isNumber()) throw new InvalidReading(field, "must be a finite number or null");
            BigDecimal exact = value.decimalValue();
            if (List.of("rpm", "speedKph", "mafGps", "controlModuleVoltageV").contains(field) && exact.signum() < 0)
                throw new InvalidReading(field, "must be greater than or equal to zero");
            if (List.of("engineLoadPct", "throttlePositionPct", "fuelLevelPct").contains(field) && (exact.signum() < 0 || exact.compareTo(BigDecimal.valueOf(100)) > 0))
                throw new InvalidReading(field, "must be between 0 and 100");
            double approximate = exact.doubleValue();
            if (!Double.isFinite(approximate) || (exact.signum() != 0 && approximate == 0)
                    || exact.scale() > 16383 || (long) exact.precision() - exact.scale() > 131072)
                throw new InvalidReading(field, "cannot be represented in storage");
            canonical.set(field, value);
        }
        JsonNode mil = input.get("milOn");
        if (mil == null || mil.isNull()) canonical.putNull("milOn");
        else if (!mil.isBoolean()) throw new InvalidReading("milOn", "must be a boolean or null");
        else canonical.put("milOn", mil.booleanValue());
        return new ValidatedReading(device, vehicle, boot, sequence.bigIntegerValue(), time.toInstant(), canonical);
    }

    private String text(JsonNode input, String field) {
        JsonNode value = input.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) throw new InvalidReading(field, "must be a nonempty string");
        return value.stringValue();
    }
}
