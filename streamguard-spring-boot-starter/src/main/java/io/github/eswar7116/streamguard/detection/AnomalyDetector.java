package io.github.eswar7116.streamguard.detection;

import io.github.eswar7116.streamguard.model.WindowSnapshot;

/**
 * Stateless comparison of a window against the one before it.
 *
 * <p>Ratios on tiny samples are noise (1 error in 3 requests vs 0 in 2 is not a spike), so
 * every rule needs {@link #MIN_REQUESTS} in the previous window, and the error rule also needs
 * them in the current one. The latency rule needs {@link #MIN_LATENCY_SAMPLES} in both windows,
 * because a p99 over fewer than 100 samples is just the maximum, and one slow request would
 * "double" it. The previous error rate and p99 are floored, so a lucky window with 0% errors
 * doesn't make ordinary background errors look like a spike.
 */
public class AnomalyDetector {

    static final int MIN_REQUESTS = 20;
    static final int MIN_LATENCY_SAMPLES = 100;
    static final double ERROR_RATE_FLOOR_PCT = 5.0;
    static final long P99_FLOOR_MS = 100;

    /** Returns the most severe anomaly, or null. Checked in order: errors, latency, traffic. */
    public AnomalyType detect(WindowSnapshot current, WindowSnapshot previous) {
        if (previous.totalRequests() < MIN_REQUESTS) {
            return null;
        }
        if (current.totalRequests() >= MIN_REQUESTS
                && current.errorRate() > 3 * Math.max(previous.errorRate(), ERROR_RATE_FLOOR_PCT)) {
            return AnomalyType.ERROR_SPIKE;
        }
        if (current.totalRequests() >= MIN_LATENCY_SAMPLES && previous.totalRequests() >= MIN_LATENCY_SAMPLES
                && current.p99Ms() > 2 * Math.max(previous.p99Ms(), P99_FLOOR_MS)) {
            return AnomalyType.LATENCY_SPIKE;
        }
        if (current.totalRequests() > 5L * previous.totalRequests()) {
            return AnomalyType.TRAFFIC_SPIKE;
        }
        if (current.totalRequests() < 0.2 * previous.totalRequests()) {
            return AnomalyType.TRAFFIC_DROP;
        }
        return null;
    }
}
