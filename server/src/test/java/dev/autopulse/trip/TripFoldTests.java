package dev.autopulse.trip;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static dev.autopulse.trip.TripRules.*;
import static dev.autopulse.trip.TripRulesTests.*;
import static org.assertj.core.api.Assertions.*;

class TripFoldTests {
    void equivalent(List<Observation> events, Instant asOf) {
        var expected = evaluate(events, DEVICE, asOf, DEFAULT).completed();
        var actual = new ArrayList<Trip>();
        var checkpoint = TripFold.Checkpoint.empty();
        var mapper = JsonMapper.builder().build();
        for (var event : events) {
            // Simulate a persisted restart after every distinct-time input.
            checkpoint = mapper.readValue(mapper.writeValueAsString(checkpoint), TripFold.Checkpoint.class);
            var change = TripFold.step(checkpoint, event, DEFAULT);
            if (change.completed() != null) actual.add(change.completed());
            checkpoint = change.next();
        }
        var tail = TripFold.tick(checkpoint, asOf, DEFAULT);
        if (tail.completed() != null) actual.add(tail.completed());
        assertThat(actual).usingRecursiveComparison().withComparatorForType(BigDecimal::compareTo, BigDecimal.class).isEqualTo(expected);
    }

    @Test void tinyNormalAndShortMatchReferenceAcrossEveryRestart() {
        equivalent(events(file("trip-rule-fixtures.json").get("tiny").get("readings")), BASE.plusSeconds(20));
        equivalent(events(file("trip-normal-input.json").get("readings")), BASE.plusSeconds(501));
        equivalent(events(file("trip-short-input.json").get("readings")), BASE.plusSeconds(61));
    }
    @Test void cancelledOffAndUnknownIntervalsUseCorrectBoundarySnapshots() {
        equivalent(List.of(event(0, 1000, 0), event(2, 1000, 36), event(4, 0, 0), event(6, null, null),
                event(8, 1000, 36), event(10, 0, 0), event(12, 0, 0)), BASE.plusSeconds(20));
        equivalent(List.of(event(0, 1000, 36), event(2, 1000, 36), event(4, null, null), event(6, 0, 0)), BASE.plusSeconds(40));
    }
    @Test void longGapAndFailedCandidateNeverContaminateNewTrip() {
        equivalent(List.of(event(0, 1000, 0), event(2, null, 0), event(10, 1000, 36), event(12, 1000, 36),
                event(50, 1000, 36), event(52, 1000, 36), event(54, 0, 0), event(56, 0, 0)), BASE.plusSeconds(60));
    }
    @Test void restartAndSourceSwitchKeepReferenceSemantics() {
        var original = event(4, 1000, 36);
        var restarted = new Observation(original.at(), DEVICE, java.util.UUID.randomUUID(), original.sequence(), "SIMULATED", original.metrics());
        var obd = new Observation(BASE.plusSeconds(6), DEVICE, BOOT, java.math.BigInteger.TEN, "OBD", original.metrics());
        equivalent(List.of(event(0, 1000, 36), event(2, 1000, 36), restarted, obd,
                new Observation(BASE.plusSeconds(8), DEVICE, BOOT, java.math.BigInteger.valueOf(11), "OBD", original.metrics())), BASE.plusSeconds(40));
    }
    @Test void denseStreamDoesNotNeedAnEverGrowingObservationList() {
        var checkpoint = TripFold.Checkpoint.empty();
        for (int index = 0; index < 25_000; index++) checkpoint = TripFold.step(checkpoint, event(index * 2, 1000, 36), DEFAULT).next();
        assertThat(checkpoint.current().summary().sampleCount()).isEqualTo(25_000);
        assertThat(checkpoint.current().summary().coveredSeconds()).isEqualByComparingTo("49998");
        assertThat(JsonMapper.builder().build().writeValueAsString(checkpoint).length()).isLessThan(10_000);
    }
}
