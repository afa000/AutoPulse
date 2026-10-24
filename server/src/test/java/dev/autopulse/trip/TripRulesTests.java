package dev.autopulse.trip;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static dev.autopulse.trip.TripRules.*;
import static org.assertj.core.api.Assertions.*;

class TripRulesTests {
    static final Instant BASE = Instant.parse("2026-10-20T12:00:00Z");
    static final UUID DEVICE = UUID.fromString("20000000-0000-4000-8000-000000000001");
    static final UUID BOOT = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
    static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    static JsonNode file(String name) { return JSON.readTree(Path.of("../contracts/examples", name).toFile()); }

    static Observation event(int seconds, Integer rpm, Integer speed) {
        Map<String, BigDecimal> metrics = new HashMap<>();
        if (rpm != null) metrics.put("rpm", BigDecimal.valueOf(rpm));
        if (speed != null) metrics.put("speedKph", BigDecimal.valueOf(speed));
        return new Observation(BASE.plusSeconds(seconds), DEVICE, BOOT, BigInteger.valueOf(seconds), "SIMULATED", metrics);
    }
    static Observation from(JsonNode node, int index) {
        Map<String, BigDecimal> metrics = new HashMap<>();
        for (String field : METRICS) if (node.hasNonNull(field)) metrics.put(field, node.get(field).decimalValue());
        return new Observation(node.has("recordedAt") ? Instant.parse(node.get("recordedAt").stringValue()) : BASE.plusSeconds(node.get("offsetSeconds").longValue()),
                DEVICE, BOOT, node.has("sequenceNumber") ? node.get("sequenceNumber").bigIntegerValue() : BigInteger.valueOf(index), "SIMULATED", metrics);
    }
    static List<Observation> events(JsonNode nodes) {
        List<Observation> values = new ArrayList<>();
        for (JsonNode node : nodes) values.add(from(node, values.size()));
        return values;
    }
    static Result evaluateAt(int seconds, Observation... events) {
        return evaluate(List.of(events), DEVICE, BASE.plusSeconds(seconds), DEFAULT);
    }
    static void decimal(BigDecimal actual, JsonNode expected) {
        if (expected.isNull()) assertThat(actual).isNull();
        else assertThat(actual).isEqualByComparingTo(expected.asText());
    }
    static void expectedTrip(Result result, JsonNode expected) {
        assertThat(result.completed()).hasSize(1);
        Trip trip = result.completed().getFirst();
        assertThat(trip.boundary().first().at()).isEqualTo(BASE.plusSeconds(expected.get("startOffsetSeconds").longValue()));
        assertThat(trip.boundary().end().at()).isEqualTo(BASE.plusSeconds(expected.get("endOffsetSeconds").longValue()));
        assertThat(trip.boundary().confirmedAt()).isEqualTo(BASE.plusSeconds(expected.get("confirmedOffsetSeconds").longValue()));
        assertThat(trip.boundary().reason().name()).isEqualTo(expected.get("completionReason").stringValue());
        assertThat(trip.summary().sampleCount()).isEqualTo(expected.get("sampleCount").intValue());
        decimal(trip.summary().durationSeconds(), expected.get("durationSeconds"));
        decimal(trip.summary().speedAreaKphSeconds(), expected.get("speedAreaKphSeconds"));
        decimal(trip.summary().coveredSeconds(), expected.get("coveredSeconds"));
        for (String field : List.of("rpm", "speedKph", "coolantTempC", "engineLoadPct", "mafGps")) {
            var actual = trip.summary().metrics().get(field); var wanted = expected.get("metrics").get(field);
            assertThat(actual.count()).isEqualTo(wanted.get("count").longValue());
            decimal(actual.sum(), wanted.get("sum")); decimal(actual.min(), wanted.get("min")); decimal(actual.max(), wanted.get("max"));
        }
    }

    @Test void tinyHandCalculatedFixturePrecedesImplementation() {
        var tiny = file("trip-rule-fixtures.json").get("tiny");
        expectedTrip(evaluate(events(tiny.get("readings")), DEVICE, BASE.plusSeconds(20), DEFAULT), tiny.get("expected"));
    }
    @Test void fullNormalCommuteMatchesIndependentPhaseSumsAndArea() {
        var fixture = file("trip-rule-fixtures.json").get("commutes").get(0);
        var input = events(file(fixture.get("inputFile").stringValue()).get("readings"));
        assertThat(input).hasSize(fixture.get("inputSampleCount").intValue());
        expectedTrip(evaluate(input, DEVICE, BASE.plusSeconds(501), DEFAULT), fixture.get("expected"));
    }
    @Test void shortCommuteMatchesIndependentPhaseSumsAndRepeatingDistance() {
        var fixture = file("trip-rule-fixtures.json").get("commutes").get(1);
        expectedTrip(evaluate(events(file(fixture.get("inputFile").stringValue()).get("readings")), DEVICE, BASE.plusSeconds(61), DEFAULT), fixture.get("expected"));
    }
    @Test void startRequiresConsecutiveDistinctTimeRunningEvidence() {
        assertThat(evaluateAt(8, event(0, 1000, 0), event(2, null, 0), event(4, 1000, 0)).remaining().phase()).isEqualTo(Phase.START_CANDIDATE);
        assertThat(evaluateAt(7, event(0, 1000, 0), event(6, 1000, 0)).remaining().phase()).isEqualTo(Phase.START_CANDIDATE);
        var state = step(State.idle(), event(0, 1000, 0), DEFAULT).state();
        assertThatThrownBy(() -> step(state, event(0, 2000, 0), DEFAULT)).isInstanceOf(IllegalArgumentException.class);
        assertThat(evaluateAt(2, event(0, 1000, 0), event(2, 1000, 0)).remaining().phase()).isEqualTo(Phase.ACTIVE);
    }
    @Test void stationaryAndShortUnknownDoNotEndTripsOrConfirmOff() {
        var result = evaluateAt(10, event(0, 1000, 0), event(2, 1000, 0), event(4, 0, 0), event(6, null, 0), event(8, 1000, 0));
        assertThat(result.completed()).isEmpty(); assertThat(result.remaining().phase()).isEqualTo(Phase.ACTIVE);
        assertThat(result.remaining().quality()).contains("UNKNOWN_RPM");
    }
    @Test void offConfirmationRequiresTimeSpanAndRestartsAfterExcessiveGap() {
        var result = evaluateAt(14, event(0, 1000, 0), event(2, 1000, 0), event(4, 0, 0), event(10, 0, 0), event(12, 0, 0));
        assertThat(result.completed().getFirst().boundary().end().at()).isEqualTo(BASE.plusSeconds(10));
        var state = evaluateAt(5, event(0, 1000, 0), event(2, 1000, 0), event(4, 0, 0), event(5, 0, 0)).remaining();
        assertThat(state.phase()).isEqualTo(Phase.END_CANDIDATE);
    }
    @Test void silenceExpiresAtLastRunningTimeNotGraceDeadline() {
        var observations = List.of(event(0, 1000, 36), event(2, 1000, 36), event(10, null, null));
        assertThat(evaluate(observations, DEVICE, BASE.plusSeconds(31), DEFAULT).completed()).isEmpty();
        var trip = evaluate(observations, DEVICE, BASE.plusSeconds(32), DEFAULT).completed().getFirst();
        assertThat(trip.boundary().reason()).isEqualTo(Reason.SIGNAL_TIMEOUT);
        assertThat(trip.boundary().end().at()).isEqualTo(BASE.plusSeconds(2));
        assertThat(trip.summary().durationSeconds()).isEqualByComparingTo("2");
    }
    @Test void longGapCreatesHonestIncompleteTripAndNewCandidate() {
        var result = evaluateAt(42, event(0, 1000, 36), event(2, 1000, 36), event(40, 1000, 36), event(42, 1000, 36));
        assertThat(result.completed()).hasSize(1);
        assertThat(result.completed().getFirst().boundary().reason()).isEqualTo(Reason.SIGNAL_TIMEOUT);
        assertThat(result.remaining().first().at()).isEqualTo(BASE.plusSeconds(40));
    }
    @Test void bootChangeContinuesButDistanceDoesNotBridgeRestart() {
        var original = event(4, 1000, 36);
        var restarted = new Observation(original.at(), DEVICE, UUID.randomUUID(), BigInteger.ZERO, original.source(), original.metrics());
        var result = evaluateAt(8, event(0, 1000, 36), event(2, 1000, 36), restarted, event(6, 0, 0), event(8, 0, 0));
        var trip = result.completed().getFirst();
        assertThat(trip.boundary().quality()).contains("AGENT_RESTART");
        assertThat(trip.summary().coveredSeconds()).isEqualByComparingTo("2"); // both boot boundaries break intervals
    }
    @Test void sourceSwitchClosesWithoutMixingSourcesAndSecondaryDeviceIsExcluded() {
        var raw = event(4, 1000, 36);
        var obd = new Observation(raw.at(), DEVICE, BOOT, raw.sequence(), "OBD", raw.metrics());
        var secondary = new Observation(BASE.plusSeconds(3), UUID.randomUUID(), BOOT, BigInteger.ZERO, "OBD", Map.of("rpm", BigDecimal.ZERO));
        var result = evaluateAt(5, event(0, 1000, 36), event(2, 1000, 36), secondary, obd);
        assertThat(result.excludedSecondary()).isEqualTo(1);
        assertThat(result.completed().getFirst().boundary().reason()).isEqualTo(Reason.SOURCE_CHANGED);
        assertThat(result.completed().getFirst().summary().sampleCount()).isEqualTo(2);
        assertThat(result.remaining().phase()).isEqualTo(Phase.START_CANDIDATE);
    }
    @Test void futureOutlierCannotAdvanceTimeoutAndHistoricalBatchNeedsNoSleep() {
        var result = evaluateAt(2, event(0, 1000, 0), event(2, 1000, 0), event(999999, 1000, 0));
        assertThat(result.quarantinedFuture()).isEqualTo(1); assertThat(result.completed()).isEmpty();
        var tiny = file("trip-rule-fixtures.json").get("tiny");
        assertThat(evaluate(events(tiny.get("readings")), DEVICE, BASE.plus(Duration.ofDays(30)), DEFAULT).completed()).hasSize(1);
    }
    @Test void canonicalTieUsesUnsignedUuidOrderAndNeverIntegratesEqualTime() {
        var low = new Observation(BASE, DEVICE, UUID.fromString("00000000-0000-4000-8000-000000000001"), BigInteger.TEN, "SIMULATED", Map.of("rpm", BigDecimal.ZERO));
        var high = new Observation(BASE, DEVICE, UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff"), BigInteger.ZERO, "SIMULATED", Map.of("rpm", BigDecimal.valueOf(1000)));
        var result = evaluateAt(2, low, high, new Observation(BASE.plusSeconds(2), DEVICE, high.boot(), BigInteger.ONE, "SIMULATED", high.metrics()));
        assertThat(result.equalTimeCollapsed()).isEqualTo(1); assertThat(result.remaining().phase()).isEqualTo(Phase.ACTIVE);
        assertThat(result.remaining().first().boot()).isEqualTo(high.boot());
    }
    @Test void exactReplayAndArrivalPermutationPreserveBoundariesAndSums() {
        var tiny = events(file("trip-rule-fixtures.json").get("tiny").get("readings"));
        var shuffled = new ArrayList<>(tiny); shuffled.addAll(tiny); Collections.reverse(shuffled);
        assertThat(evaluate(shuffled, DEVICE, BASE.plusSeconds(20), DEFAULT)).isEqualTo(evaluate(tiny, DEVICE, BASE.plusSeconds(20), DEFAULT));
        var changed = event(0, 999, 0);
        assertThatThrownBy(() -> evaluateAt(20, event(0, 1000, 0), changed)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void streamingPartitionsUseTheSamePureStateAndTimer() {
        var input = events(file("trip-rule-fixtures.json").get("tiny").get("readings"));
        var expected = evaluate(input, DEVICE, BASE.plusSeconds(20), DEFAULT).completed().getFirst().boundary();
        for (int split = 1; split < input.size(); split++) {
            State state = State.idle(); Boundary actual = null;
            for (var page : List.of(input.subList(0, split), input.subList(split, input.size()))) for (var event : page) {
                var result = step(state, event, DEFAULT); state = result.state();
                if (result.completed() != null) actual = result.completed();
            }
            assertThat(actual).isEqualTo(expected);
        }
    }
    @Test void missingAndLongGapSpeedIntervalsDoNotBecomeInventedDistance() {
        var summary = summarize(List.of(event(0, 1000, null), event(2, 1000, 36), event(20, 1000, 36)), DEFAULT);
        assertThat(summary.distanceAvailable()).isFalse(); assertThat(summary.coveredSeconds()).isEqualByComparingTo("0");
        assertThat(summary.metrics().get("mafGps")).isEqualTo(new Metric(0, null, null, null));
        var stopped = summarize(List.of(event(0, 1000, 0), event(2, 1000, 0)), DEFAULT);
        assertThat(stopped.distanceAvailable()).isTrue(); assertThat(stopped.speedAreaKphSeconds()).isEqualByComparingTo("0");
    }
    @Test void subsecondDistancePreservesExactDecimalValuesWithoutEarlyRounding() {
        var left = new Observation(BASE, DEVICE, BOOT, BigInteger.ZERO, "SIMULATED",
                Map.of("speedKph", new BigDecimal("0.1234567890123456789")));
        var right = new Observation(BASE.plusNanos(1000), DEVICE, BOOT, BigInteger.ONE, "SIMULATED",
                Map.of("speedKph", new BigDecimal("0.1234567890123456791")));
        var summary = summarize(List.of(left, right), DEFAULT);
        assertThat(summary.speedAreaKphSeconds()).isEqualByComparingTo("0.000000123456789012345679");
        assertThat(summary.coveredSeconds()).isEqualByComparingTo("0.000001");
        assertThat(summary.metrics().get("speedKph").sum()).isEqualByComparingTo("0.2469135780246913580");
        assertThat(summary.metrics().get("rpm").count()).isZero();
    }

    @Test void referenceWindowAndConfigurationAreBounded() {
        assertThatThrownBy(() -> evaluate(Collections.nCopies(10001, event(0, 1000, 0)), DEVICE, BASE, DEFAULT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Config(1, 2, Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ZERO, Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
        var shorter = new Config(2, 2, Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ZERO, Duration.ofSeconds(40));
        var result = evaluate(List.of(event(0, 1000, 36), event(2, 1000, 36), event(20, 1000, 36), event(39, 1000, 36), event(40, 1000, 36)), DEVICE, BASE.plusSeconds(40), shorter);
        assertThat(result.completed().getFirst().boundary().reason()).isEqualTo(Reason.DURATION_LIMIT);
    }

    @Test void lateHistoricalInputCanReconcileButSealedAndTooWideCorrectionsNeedReview() {
        assertThat(TripReconciliation.action(BASE, BASE.plus(Duration.ofDays(30)), false, Duration.ofHours(1), DEFAULT))
                .isEqualTo(TripReconciliation.Action.RECONCILE);
        assertThat(TripReconciliation.action(BASE, BASE.plusSeconds(10), true, Duration.ofMinutes(1), DEFAULT))
                .isEqualTo(TripReconciliation.Action.REVIEW_FINALIZED);
        assertThat(TripReconciliation.action(BASE, BASE.plusSeconds(10), false, Duration.ofHours(48).plusNanos(1000), DEFAULT))
                .isEqualTo(TripReconciliation.Action.REVIEW_TOO_WIDE);
        assertThat(TripReconciliation.action(BASE.plusSeconds(13), BASE.plusSeconds(10), false, Duration.ZERO, DEFAULT))
                .isEqualTo(TripReconciliation.Action.QUARANTINE_FUTURE);
    }

    @Test void receiptQuietPeriodDoesNotSealOldFirstBatchImmediatelyOrIgnorePendingWork() {
        assertThat(TripReconciliation.canFinalize(BASE.plusSeconds(299), BASE, true, false, false)).isFalse();
        assertThat(TripReconciliation.canFinalize(BASE.plusSeconds(300), BASE, true, false, false)).isTrue();
        assertThat(TripReconciliation.canFinalize(BASE.plusSeconds(300), BASE, true, true, false)).isFalse();
        assertThat(TripReconciliation.canFinalize(BASE.plusSeconds(300), BASE, true, false, true)).isFalse();
        assertThat(TripReconciliation.canFinalize(BASE.plusSeconds(300), BASE, false, false, false)).isFalse();
        assertThat(TripReconciliation.canFinalize(BASE, BASE.plusSeconds(1), true, false, false)).isFalse();
    }
}
