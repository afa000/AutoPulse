package dev.autopulse;

import dev.autopulse.telemetry.InvalidReading;
import dev.autopulse.telemetry.TelemetryValidator;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class TelemetryValidatorTests {
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private final TelemetryValidator validator = new TelemetryValidator(mapper);

    @Test
    void sharedFixturesAgreeWithPython() throws Exception {
        var base = mapper.readTree(Path.of("../contracts/examples/telemetry-sample.json").toFile());
        assertThat(validator.validate(base).payload().get("milOn").booleanValue()).isFalse();
        var valid = mapper.readTree(Path.of("../contracts/examples/valid-telemetry-cases.json").toFile());
        for (var testCase : valid) {
            ObjectNode input = (ObjectNode) base.deepCopy();
            input.set(testCase.get("field").stringValue(), testCase.get("value"));
            assertThatCode(() -> validator.validate(input)).doesNotThrowAnyException();
        }
        var cases = mapper.readTree(Path.of("../contracts/examples/invalid-telemetry-cases.json").toFile());
        for (var testCase : cases) {
            ObjectNode input = (ObjectNode) base.deepCopy();
            input.set(testCase.get("field").stringValue(), testCase.get("value"));
            assertThatThrownBy(() -> validator.validate(input)).isInstanceOf(InvalidReading.class);
        }
    }

    @Test
    void fullRfcOffsetRangeAndLowercaseAreAccepted() throws Exception {
        ObjectNode input = (ObjectNode) mapper.readTree(Path.of("../contracts/examples/telemetry-sample.json").toFile());
        input.put("recordedAt", "2026-09-10T12:30:00+23:59");
        assertThat(validator.validate(input).recordedAt().toString()).isEqualTo("2026-09-09T12:31:00Z");
        input.put("recordedAt", "2026-09-10t12:30:00z");
        assertThat(validator.validate(input).recordedAt().toString()).isEqualTo("2026-09-10T12:30:00Z");
    }
}
