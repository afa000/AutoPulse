package dev.autopulse.trip;

import java.time.Duration;
import java.time.Instant;

/** Pure proposed eligibility rules; persistence/work scheduling is checkpoint 2. */
public final class TripReconciliation {
    private TripReconciliation() {}
    public enum Action { RECONCILE, QUARANTINE_FUTURE, REVIEW_FINALIZED, REVIEW_TOO_WIDE }
    public static final Duration QUIET_PERIOD = Duration.ofMinutes(5);
    public static final Duration MAX_AUTOMATIC_SPAN = Duration.ofHours(48);

    public static Action action(Instant recordedAt, Instant asOf, boolean touchesFinalized,
                                Duration affectedSpan, TripRules.Config config) {
        if (affectedSpan.isNegative()) throw new IllegalArgumentException("affected span must be nonnegative");
        if (recordedAt.isAfter(asOf.plus(config.futureTolerance()))) return Action.QUARANTINE_FUTURE;
        if (touchesFinalized) return Action.REVIEW_FINALIZED;
        if (affectedSpan.compareTo(MAX_AUTOMATIC_SPAN) > 0) return Action.REVIEW_TOO_WIDE;
        return Action.RECONCILE;
    }

    /** Timestamp is last NEW eligible accepted receipt, never duplicate/future receipt. */
    public static boolean canFinalize(Instant asOf, Instant lastEligibleReceipt, boolean completed,
                                      boolean pendingOrFailedWork, boolean unresolvedAffectedLateData) {
        return completed && !pendingOrFailedWork && !unresolvedAffectedLateData
                && Duration.between(lastEligibleReceipt, asOf).compareTo(QUIET_PERIOD) >= 0;
    }
}
