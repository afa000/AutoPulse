package dev.autopulse.trip;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Pure checkpoint-1 reference rules. Not registered with Spring or ingestion. */
public final class TripRules {
    private TripRules() {}
    public static final String VERSION = "trip-v1-draft";
    public enum Phase { IDLE, START_CANDIDATE, ACTIVE, END_CANDIDATE }
    public enum Reason { ENGINE_OFF, SIGNAL_TIMEOUT, SOURCE_CHANGED, DURATION_LIMIT }
    public record Config(int startCount, int offCount, Duration confirmationGap, Duration offSpan,
                         Duration signalGrace, Duration distanceGap, Duration futureTolerance, Duration maxDuration) {
        public Config {
            if (startCount < 2 || offCount < 2 || confirmationGap.isNegative() || confirmationGap.isZero()
                    || offSpan.isNegative() || offSpan.isZero() || signalGrace.isNegative() || signalGrace.isZero()
                    || distanceGap.isNegative() || distanceGap.isZero() || futureTolerance.isNegative()
                    || maxDuration.compareTo(signalGrace) <= 0) throw new IllegalArgumentException("invalid trip settings");
        }
    }
    public static final Config DEFAULT = new Config(2, 2, Duration.ofSeconds(5), Duration.ofSeconds(2),
            Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofHours(24));

    /** Validated committed numeric data: absent map entries mean unavailable. */
    public record Observation(Instant at, UUID device, UUID boot, BigInteger sequence, String source,
                              Map<String, BigDecimal> metrics) {
        public Observation {
            if (at == null || device == null || boot == null || sequence == null || sequence.signum() < 0
                    || !Set.of("SIMULATED", "OBD").contains(source)) throw new IllegalArgumentException("invalid identity/source");
            var normalized = new HashMap<String, BigDecimal>();
            metrics.forEach((key, value) -> { if (value != null) normalized.put(key, value.stripTrailingZeros()); });
            metrics = Map.copyOf(normalized);
        }
        public BigDecimal rpm() { return metrics.get("rpm"); }
        public boolean running() { return rpm() != null && rpm().signum() > 0; }
        public boolean off() { return rpm() != null && rpm().signum() == 0; }
    }
    private record Key(UUID device, UUID boot, BigInteger sequence) {}
    // PostgreSQL UUID byte order is unsigned, unlike UUID.compareTo's signed longs.
    public static final Comparator<Observation> ORDER = Comparator.comparing(Observation::at)
            .thenComparing(value -> value.device().toString()).thenComparing(value -> value.boot().toString())
            .thenComparing(Observation::sequence);

    public record State(Phase phase, Observation first, Observation last, Observation lastRunning,
                        int startCount, Observation offFirst, int offCount, Set<String> quality) {
        public State { quality = Set.copyOf(quality); }
        public static State idle() { return new State(Phase.IDLE, null, null, null, 0, null, 0, Set.of()); }
        public boolean active() { return phase == Phase.ACTIVE || phase == Phase.END_CANDIDATE; }
    }
    public record Boundary(Observation first, Observation end, Instant confirmedAt, Reason reason, Set<String> quality) {
        public Boundary { quality = Set.copyOf(quality); }
    }
    public record Transition(State state, Boundary completed) {}

    /** Caller supplies strictly increasing, primary-device, distinct-time observations. */
    public static Transition step(State previous, Observation event, Config config) {
        if (previous.last() != null && !event.at().isAfter(previous.last().at()))
            throw new IllegalArgumentException("step requires canonical distinct-time order");
        State state = previous;
        Boundary completed = null;
        if (state.active()) {
            Reason close = expiry(state, event.at(), config);
            if (close == null && !state.first().source().equals(event.source())) close = Reason.SOURCE_CHANGED;
            if (close != null) {
                completed = boundary(state, state.lastRunning(), event.at(), close);
                state = State.idle();
            }
        }
        if (!state.active()) {
            if (!event.running()) return new Transition(State.idle(), completed);
            boolean continues = state.phase() == Phase.START_CANDIDATE
                    && state.first().source().equals(event.source()) && state.last().boot().equals(event.boot())
                    && Duration.between(state.last().at(), event.at()).compareTo(config.confirmationGap()) <= 0;
            int count = continues ? state.startCount() + 1 : 1;
            return new Transition(new State(count >= config.startCount() ? Phase.ACTIVE : Phase.START_CANDIDATE,
                    continues ? state.first() : event, event, event, count, null, 0, Set.of()), completed);
        }
        Set<String> quality = new HashSet<>(state.quality());
        if (!state.last().boot().equals(event.boot())) quality.add("AGENT_RESTART");
        if (event.rpm() == null) quality.add("UNKNOWN_RPM");
        Observation offFirst = null;
        int offCount = 0;
        if (event.off()) {
            boolean consecutive = state.phase() == Phase.END_CANDIDATE
                    && Duration.between(state.last().at(), event.at()).compareTo(config.confirmationGap()) <= 0;
            offFirst = consecutive ? state.offFirst() : event;
            offCount = consecutive ? state.offCount() + 1 : 1;
            if (offCount >= config.offCount() && Duration.between(offFirst.at(), event.at()).compareTo(config.offSpan()) >= 0) {
                var closing = new State(state.phase(), state.first(), event, state.lastRunning(),
                        state.startCount(), offFirst, offCount, quality);
                return new Transition(State.idle(), boundary(closing, offFirst, event.at(), Reason.ENGINE_OFF));
            }
        }
        return new Transition(new State(event.off() ? Phase.END_CANDIDATE : Phase.ACTIVE,
                state.first(), event, event.running() ? event : state.lastRunning(), state.startCount(),
                offFirst, offCount, quality), completed);
    }

    /** No real clock or network required; backend restart will use this same rule. */
    public static Transition tick(State state, Instant asOf, Config config) {
        if (state.last() != null && asOf.isBefore(state.last().at())) return new Transition(state, null);
        Reason close = state.active() ? expiry(state, asOf, config) : null;
        if (close != null) return new Transition(State.idle(), boundary(state, state.lastRunning(), asOf, close));
        if (state.phase() == Phase.START_CANDIDATE
                && Duration.between(state.last().at(), asOf).compareTo(config.confirmationGap()) > 0)
            return new Transition(State.idle(), null);
        return new Transition(state, null);
    }
    private static Reason expiry(State state, Instant now, Config config) {
        if (Duration.between(state.lastRunning().at(), now).compareTo(config.signalGrace()) >= 0) return Reason.SIGNAL_TIMEOUT;
        if (Duration.between(state.first().at(), now).compareTo(config.maxDuration()) >= 0) return Reason.DURATION_LIMIT;
        return null;
    }
    private static Boundary boundary(State state, Observation end, Instant confirmed, Reason reason) {
        var quality = new HashSet<>(state.quality());
        if (reason != Reason.ENGINE_OFF) quality.add(reason.name());
        return new Boundary(state.first(), end, confirmed, reason, quality);
    }

    public record Metric(long count, BigDecimal sum, BigDecimal min, BigDecimal max) {}
    public record Summary(int sampleCount, BigDecimal durationSeconds, Map<String, Metric> metrics,
                          BigDecimal speedAreaKphSeconds, BigDecimal coveredSeconds) {
        public Summary { metrics = Map.copyOf(metrics); }
        public boolean distanceAvailable() { return coveredSeconds.signum() > 0; }
    }
    public record Trip(Boundary boundary, Summary summary) {}
    public record Result(List<Trip> completed, State remaining, int excludedSecondary, int quarantinedFuture, int equalTimeCollapsed) {
        public Result { completed = List.copyOf(completed); }
    }
    public static final List<String> METRICS = List.of("rpm", "speedKph", "coolantTempC", "engineLoadPct",
            "throttlePositionPct", "intakeTempC", "mafGps", "fuelLevelPct", "controlModuleVoltageV");

    /** Bounded reference evaluator only; a production worker must page/checkpoint. */
    public static Result evaluate(List<Observation> input, UUID primary, Instant asOf, Config config) {
        if (input.size() > 10_000) throw new IllegalArgumentException("reference window exceeds 10000 events");
        Map<Key, Observation> unique = new HashMap<>();
        for (Observation event : input) {
            Observation prior = unique.putIfAbsent(new Key(event.device(), event.boot(), event.sequence()), event);
            if (prior != null && !prior.equals(event)) throw new IllegalArgumentException("conflicting event identity");
        }
        int secondary = 0, future = 0, collapsed = 0;
        TreeMap<Instant, Observation> byTime = new TreeMap<>();
        for (Observation event : unique.values()) {
            if (!event.device().equals(primary)) { secondary++; continue; }
            if (event.at().isAfter(asOf.plus(config.futureTolerance()))) { future++; continue; }
            if (byTime.containsKey(event.at())) collapsed++;
            byTime.merge(event.at(), event, (a, b) -> ORDER.compare(a, b) >= 0 ? a : b);
        }
        List<Observation> events = List.copyOf(byTime.values());
        List<Boundary> boundaries = new ArrayList<>();
        State state = State.idle();
        for (Observation event : events) {
            Transition transition = step(state, event, config);
            if (transition.completed() != null) boundaries.add(transition.completed());
            state = transition.state();
        }
        Transition tail = tick(state, asOf, config);
        if (tail.completed() != null) boundaries.add(tail.completed());
        var trips = boundaries.stream().map(boundary -> new Trip(boundary, summarize(events.stream()
                .filter(event -> !event.at().isBefore(boundary.first().at()) && !event.at().isAfter(boundary.end().at())
                        && event.source().equals(boundary.first().source())).toList(), config))).toList();
        return new Result(trips, tail.state(), secondary, future, collapsed);
    }

    public static Summary summarize(List<Observation> events, Config config) {
        if (events.isEmpty()) throw new IllegalArgumentException("trip evidence is empty");
        Map<String, Metric> metrics = new HashMap<>();
        for (String field : METRICS) {
            var values = events.stream().map(event -> event.metrics().get(field)).filter(java.util.Objects::nonNull).toList();
            metrics.put(field, values.isEmpty() ? new Metric(0, null, null, null) : new Metric(values.size(),
                    values.stream().reduce(BigDecimal.ZERO, BigDecimal::add), values.stream().min(BigDecimal::compareTo).orElseThrow(),
                    values.stream().max(BigDecimal::compareTo).orElseThrow()));
        }
        BigDecimal area = BigDecimal.ZERO, covered = BigDecimal.ZERO;
        for (int i = 1; i < events.size(); i++) {
            Observation left = events.get(i - 1), right = events.get(i);
            Duration gap = Duration.between(left.at(), right.at());
            var a = left.metrics().get("speedKph"); var b = right.metrics().get("speedKph");
            if (gap.isNegative()) throw new IllegalArgumentException("summary evidence must be ordered");
            if (!gap.isZero() && gap.compareTo(config.distanceGap()) <= 0 && a != null && b != null
                    && left.device().equals(right.device()) && left.boot().equals(right.boot()) && left.source().equals(right.source())) {
                var duration = seconds(gap);
                covered = covered.add(duration);
                area = area.add(a.add(b).multiply(duration).divide(BigDecimal.valueOf(2)));
            }
        }
        return new Summary(events.size(), seconds(Duration.between(events.getFirst().at(), events.getLast().at())), metrics, area, covered);
    }
    public static BigDecimal seconds(Duration duration) {
        return BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano(), 9));
    }
}
