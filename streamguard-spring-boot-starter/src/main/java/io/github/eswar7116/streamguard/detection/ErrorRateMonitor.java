package io.github.eswar7116.streamguard.detection;

import io.github.eswar7116.streamguard.config.StreamGuardProperties;
import io.github.eswar7116.streamguard.model.WindowSnapshot;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Per-endpoint alert on the absolute error rate, for failures that ERROR_SPIKE stops seeing once they are the baseline. */
public class ErrorRateMonitor {

    /**
     * An open alert; {@code clearStreak} counts consecutive windows below the recovery threshold.
     */
    private record Open(long lastNotifiedMs, int clearStreak) {
    }

    private final int minRequests;
    private final double thresholdPct;
    private final double recoveryPct;
    private final int resolveWindows;
    private final long repeatIntervalMs;

    private final Map<String, Open> open = new ConcurrentHashMap<>();

    public ErrorRateMonitor(StreamGuardProperties.Detection detection) {
        this.minRequests = detection.minRequests();
        this.thresholdPct = detection.errorRateThresholdPct();
        this.recoveryPct = detection.errorRateRecoveryPct();
        this.resolveWindows = detection.errorRateResolveWindows();
        this.repeatIntervalMs = detection.errorRateRepeatIntervalSeconds() * 1000L;
    }

    /**
     * Feeds the window and the long window ending at {@code endMs}; returns the event to raise, if any.
     */
    public Optional<AnomalyEvent> observe(String endpoint, WindowSnapshot current, WindowSnapshot longWindow, long endMs) {
        // Silence means no clients, not recovery
        if (current.totalRequests() == 0) {
            return Optional.empty();
        }
        AnomalyEvent[] event = new AnomalyEvent[1];
        open.compute(endpoint, (k, alert) -> {
            if (alert == null) {
                // Multiwindow (Google SRE Workbook): the long window proves it is sustained, the current one that it still is
                boolean sustained = longWindow.totalRequests() >= minRequests
                        && longWindow.errorRate() >= thresholdPct
                        && current.errorRate() >= thresholdPct;
                if (!sustained) {
                    return null;
                }
                event[0] = high(endpoint, longWindow, endMs);
                return new Open(endMs, 0);
            }
            // Lower recovery threshold (Datadog) plus a run of good windows (Grafana keep firing for) stops flapping
            if (current.errorRate() < recoveryPct) {
                int streak = alert.clearStreak() + 1;
                if (streak >= resolveWindows) {
                    event[0] = new AnomalyEvent(
                            endMs, endpoint, AnomalyType.ERROR_RATE_RESOLVED, current.errorRate(), recoveryPct);
                    return null;
                }
                return new Open(alert.lastNotifiedMs(), streak);
            }
            // Reminder while open (Alertmanager repeat_interval)
            if (repeatIntervalMs > 0 && endMs - alert.lastNotifiedMs() >= repeatIntervalMs) {
                event[0] = high(endpoint, longWindow, endMs);
                return new Open(endMs, 0);
            }
            return new Open(alert.lastNotifiedMs(), 0);
        });
        return Optional.ofNullable(event[0]);
    }

    // Reports the long-window rate
    private AnomalyEvent high(String endpoint, WindowSnapshot longWindow, long endMs) {
        return new AnomalyEvent(endMs, endpoint, AnomalyType.ERROR_RATE_HIGH, longWindow.errorRate(), thresholdPct);
    }

    /**
     * Whether {@code endpoint} has an open ERROR_RATE_HIGH alert.
     */
    public boolean isOpen(String endpoint) {
        return open.containsKey(endpoint);
    }

    /**
     * Drops state for endpoints not in {@code live}, so one-off URLs don't accumulate.
     */
    public void retainEndpoints(Set<String> live) {
        open.keySet().retainAll(live);
    }
}
