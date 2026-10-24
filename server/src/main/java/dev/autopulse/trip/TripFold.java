package dev.autopulse.trip;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static dev.autopulse.trip.TripRules.*;

/** Constant-space fold. Snapshots avoid counting unconfirmed shutdown wait time. */
public final class TripFold {
    private TripFold() {}
    public record Evidence(Observation first, Observation last, Summary summary) {}
    public record Checkpoint(State state, Evidence current, Evidence running, Evidence off) {
        public static Checkpoint empty() { return new Checkpoint(State.idle(), null, null, null); }
    }
    public record Change(Checkpoint next, Trip completed) {}

    public static Change step(Checkpoint prior, Observation event, Config config) {
        var transition = TripRules.step(prior.state(), event, config);
        Trip completed = completed(prior, transition.completed());
        State state = transition.state();
        if (state.phase() == Phase.IDLE) return new Change(Checkpoint.empty(), completed);
        boolean fresh = prior.state().first() == null || !state.first().equals(prior.state().first()) || completed != null;
        Evidence current = append(fresh ? null : prior.current(), event, config);
        Evidence running = event.running() ? current : prior.running();
        Evidence off = state.offFirst() == null ? null
                : state.offFirst().equals(event) ? current : prior.off();
        return new Change(new Checkpoint(state, current, running, off), completed);
    }

    public static Change tick(Checkpoint prior, Instant asOf, Config config) {
        var transition = TripRules.tick(prior.state(), asOf, config);
        return new Change(transition.state().phase() == Phase.IDLE ? Checkpoint.empty() : prior,
                completed(prior, transition.completed()));
    }

    private static Trip completed(Checkpoint prior, Boundary boundary) {
        if (boundary == null) return null;
        Evidence evidence = boundary.reason() == Reason.ENGINE_OFF ? prior.off() : prior.running();
        if (evidence == null || !evidence.last().equals(boundary.end())) throw new IllegalStateException("missing boundary snapshot");
        return new Trip(boundary, evidence.summary());
    }

    private static Evidence append(Evidence prior, Observation event, Config config) {
        if (prior == null) return new Evidence(event, event, summarize(List.of(event), config));
        Summary single = summarize(List.of(event), config);
        Summary edge = summarize(List.of(prior.last(), event), config);
        Map<String, Metric> metrics = new HashMap<>();
        for (String field : METRICS) {
            Metric a = prior.summary().metrics().get(field), b = single.metrics().get(field);
            metrics.put(field, a.count() == 0 ? b : b.count() == 0 ? a
                    : new Metric(Math.addExact(a.count(), b.count()), a.sum().add(b.sum()),
                    a.min().min(b.min()), a.max().max(b.max())));
        }
        return new Evidence(prior.first(), event, new Summary(Math.addExact(prior.summary().sampleCount(), 1),
                seconds(java.time.Duration.between(prior.first().at(), event.at())), metrics,
                prior.summary().speedAreaKphSeconds().add(edge.speedAreaKphSeconds()),
                prior.summary().coveredSeconds().add(edge.coveredSeconds())));
    }
}
