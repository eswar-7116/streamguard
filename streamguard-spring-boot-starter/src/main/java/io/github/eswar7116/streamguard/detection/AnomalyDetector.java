package io.github.eswar7116.streamguard.detection;

import io.github.eswar7116.streamguard.config.StreamGuardProperties;
import io.github.eswar7116.streamguard.model.WindowSnapshot;

/** Stateless comparison of a window against the one before it. */
public class AnomalyDetector {

    // A p99 over fewer samples is just the maximum, so one slow request would "double" it
    static final int MIN_LATENCY_SAMPLES = 100;
    static final long P99_FLOOR_MS = 100;

    private final int minRequests;
    private final double errorRateFloorPct;
    private final double errorSpikeMinZ;

    public AnomalyDetector(StreamGuardProperties.Detection detection) {
        this.minRequests = detection.minRequests();
        this.errorRateFloorPct = detection.errorRateFloorPct();
        this.errorSpikeMinZ = detection.errorSpikeMinZ();
    }

    /**
     * Returns the most severe anomaly, or null. Checked in order: errors, latency, traffic.
     */
    public AnomalyType detect(WindowSnapshot current, WindowSnapshot previous) {
        return detect(current, previous, false);
    }

    /**
     * @param skipErrors skip the error rule while an ERROR_RATE_HIGH alert already covers the route
     */
    public AnomalyType detect(WindowSnapshot current, WindowSnapshot previous, boolean skipErrors) {
        // Ratios on tiny samples are noise
        if (previous.totalRequests() < minRequests) {
            return null;
        }
        // The 3x ratio decides if a rise is big enough to matter, the z-test that it isn't chance
        if (!skipErrors && current.totalRequests() >= minRequests
                && current.errorRate() > 3 * Math.max(previous.errorRate(), errorRateFloorPct)
                && errorRiseZ(current, previous) >= errorSpikeMinZ) {
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

    /**
     * One-sided pooled two-proportion z-score for the current error rate exceeding the previous one.
     */
    static double errorRiseZ(WindowSnapshot current, WindowSnapshot previous) {
        double n = current.totalRequests();
        double m = previous.totalRequests();
        double p1 = current.errorRate() / 100;
        double p2 = previous.errorRate() / 100;
        double pooled = (p1 * n + p2 * m) / (n + m);
        double variance = pooled * (1 - pooled) * (1 / n + 1 / m);
        // Pooled 0% or 100% means both windows match, so there is no rise
        return variance > 0 ? (p1 - p2) / Math.sqrt(variance) : 0;
    }
}
