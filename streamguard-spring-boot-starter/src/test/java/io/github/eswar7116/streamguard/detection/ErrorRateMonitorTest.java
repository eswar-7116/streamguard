package io.github.eswar7116.streamguard.detection;

import io.github.eswar7116.streamguard.TestProperties;
import io.github.eswar7116.streamguard.model.WindowSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests the absolute error-rate alert: opening, staying quiet, repeating and resolving. */
class ErrorRateMonitorTest {

    private static final String EP = "/api/x";
    private static final long W = 15_000;

    // Defaults: min-requests 20, threshold 50%, long window 180s, recovery 25%, resolve after 2 windows, repeat 4h
    private final ErrorRateMonitor monitor = new ErrorRateMonitor(TestProperties.defaults().detection());

    private static WindowSnapshot snap(int requests, double errorRate, long durationMs) {
        return new WindowSnapshot(EP, durationMs, requests, requests * 1000.0 / durationMs, errorRate, 10, 50, 50);
    }

    /** Current window and long window with their own volumes and rates. */
    private static AnomalyType observe(ErrorRateMonitor m, int cur, double curRate, int lng, double lngRate, long endMs) {
        return m.observe(EP, snap(cur, curRate, W), snap(lng, lngRate, 12 * W), endMs)
                .map(AnomalyEvent::type).orElse(null);
    }

    /** A steady rate: the long window holds 12 windows of the same traffic. */
    private static AnomalyType steady(ErrorRateMonitor m, double rate, long endMs) {
        return observe(m, 100, rate, 1200, rate, endMs);
    }

    private static ErrorRateMonitor with(String... kv) {
        Map<String, String> overrides = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            overrides.put("streamguard.detection." + kv[i], kv[i + 1]);
        }
        return new ErrorRateMonitor(TestProperties.of(overrides).detection());
    }

    @Test
    void persistentFailureAlertsOnceNotEveryWindow() {
        // Flat 80% across windows: one ERROR_RATE_HIGH, then silence while it stays open
        assertThat(steady(monitor, 80.0, W)).isEqualTo(AnomalyType.ERROR_RATE_HIGH);
        for (int i = 2; i <= 10; i++) {
            assertThat(steady(monitor, 80.0, i * W)).isNull();
        }
        assertThat(monitor.isOpen(EP)).isTrue();
    }

    @Test
    void eventCarriesLongWindowRateAndThreshold() {
        AnomalyEvent e = monitor.observe(EP, snap(100, 90.0, W), snap(1200, 60.0, 12 * W), W).orElseThrow();
        assertThat(e.currentValue()).isEqualTo(60.0);
        assertThat(e.previousValue()).isEqualTo(50.0);
        assertThat(e.timestamp()).isEqualTo(W);
    }

    @Test
    void briefBurstDoesNotOpenAlert() {
        // One window at 100% inside an otherwise healthy 3 minutes: the long window is ~10%
        assertThat(observe(monitor, 100, 100.0, 1200, 10.0, W)).isNull();
        assertThat(monitor.isOpen(EP)).isFalse();
    }

    @Test
    void failureThatAlreadyEndedDoesNotOpenAlert() {
        // The long window is still above threshold from earlier failures, but the current window is healthy
        assertThat(observe(monitor, 100, 2.0, 1200, 60.0, W)).isNull();
    }

    @Test
    void lowTrafficRouteIsJudgedOnTheLongWindow() {
        // 5 requests per window is under min-requests, but 60 over the long window is enough
        assertThat(observe(monitor, 5, 80.0, 60, 80.0, W)).isEqualTo(AnomalyType.ERROR_RATE_HIGH);
    }

    @Test
    void longWindowNeedsMinRequests() {
        // A route with only 15 requests in 3 minutes is too small a sample, however bad
        assertThat(observe(monitor, 5, 100.0, 15, 100.0, W)).isNull();
    }

    @Test
    void resolvesAfterConsecutiveWindowsBelowRecovery() {
        steady(monitor, 80.0, W);
        // 40% is below the 50% threshold but not below 25% recovery: still open
        assertThat(observe(monitor, 100, 40.0, 1200, 70.0, 2 * W)).isNull();
        // One good window isn't enough (keep firing) ...
        assertThat(observe(monitor, 100, 10.0, 1200, 60.0, 3 * W)).isNull();
        assertThat(monitor.isOpen(EP)).isTrue();
        // ... the second in a row resolves it
        assertThat(observe(monitor, 100, 10.0, 1200, 50.0, 4 * W)).isEqualTo(AnomalyType.ERROR_RATE_RESOLVED);
        assertThat(monitor.isOpen(EP)).isFalse();
    }

    @Test
    void badWindowResetsTheResolveCount() {
        steady(monitor, 80.0, W);
        assertThat(steady(monitor, 10.0, 2 * W)).isNull();
        assertThat(steady(monitor, 80.0, 3 * W)).isNull();
        assertThat(steady(monitor, 10.0, 4 * W)).isNull();
        assertThat(monitor.isOpen(EP)).isTrue();
        assertThat(steady(monitor, 10.0, 5 * W)).isEqualTo(AnomalyType.ERROR_RATE_RESOLVED);
    }

    @Test
    void rateHoveringAroundThresholdDoesNotFlap() {
        // 49/51/49/51...: one alert, never resolved, since 49% is above 25% recovery
        assertThat(steady(monitor, 51.0, W)).isEqualTo(AnomalyType.ERROR_RATE_HIGH);
        for (int i = 2; i <= 8; i++) {
            assertThat(steady(monitor, i % 2 == 0 ? 49.0 : 51.0, i * W)).isNull();
        }
    }

    @Test
    void reopensAfterResolving() {
        steady(monitor, 80.0, W);
        steady(monitor, 10.0, 2 * W);
        assertThat(steady(monitor, 10.0, 3 * W)).isEqualTo(AnomalyType.ERROR_RATE_RESOLVED);
        assertThat(steady(monitor, 60.0, 4 * W)).isEqualTo(AnomalyType.ERROR_RATE_HIGH);
    }

    @Test
    void openAlertRepeatsAfterInterval() {
        ErrorRateMonitor m = with("error-rate-repeat-interval-seconds", "60");
        assertThat(steady(m, 80.0, W)).isEqualTo(AnomalyType.ERROR_RATE_HIGH);
        assertThat(steady(m, 80.0, 2 * W)).isNull();
        assertThat(steady(m, 80.0, 4 * W)).isNull();
        // 60s after the first notification
        assertThat(steady(m, 80.0, 5 * W)).isEqualTo(AnomalyType.ERROR_RATE_HIGH);
        assertThat(steady(m, 80.0, 6 * W)).isNull();
    }

    @Test
    void zeroRepeatIntervalNeverRepeats() {
        ErrorRateMonitor m = with("error-rate-repeat-interval-seconds", "0");
        assertThat(steady(m, 80.0, W)).isEqualTo(AnomalyType.ERROR_RATE_HIGH);
        assertThat(steady(m, 80.0, 1_000 * W)).isNull();
    }

    @Test
    void persistentFailureBelowThresholdIsNotReported() {
        // Flat 30% is under the default 50%, but a stricter configured threshold reports it
        assertThat(steady(monitor, 30.0, W)).isNull();
        ErrorRateMonitor strict = with("error-rate-threshold-pct", "25", "error-rate-recovery-pct", "10");
        assertThat(steady(strict, 30.0, W)).isEqualTo(AnomalyType.ERROR_RATE_HIGH);
    }

    @Test
    void silentWindowsChangeNothing() {
        steady(monitor, 80.0, W);
        for (int i = 2; i <= 5; i++) {
            assertThat(observe(monitor, 0, 0.0, 400, 80.0, i * W)).isNull();
        }
        assertThat(monitor.isOpen(EP)).isTrue();
    }

    @Test
    void forgetsEvictedEndpoints() {
        steady(monitor, 80.0, W);
        monitor.retainEndpoints(Set.of());
        assertThat(monitor.isOpen(EP)).isFalse();
    }

    @Test
    void rejectsInvalidSettings() {
        assertThatThrownBy(() -> with("error-rate-threshold-pct", "30", "error-rate-recovery-pct", "40"))
                .hasRootCauseMessage("error-rate-recovery-pct (40.0) must not exceed error-rate-threshold-pct (30.0)");
        assertThatThrownBy(() -> with("error-rate-long-window-seconds", "10"))
                .hasRootCauseMessage("error-rate-long-window-seconds (10) must be at least evaluation-window-seconds (15)");
        assertThatThrownBy(() -> with("error-rate-resolve-windows", "0"))
                .hasRootCauseMessage("error-rate-resolve-windows must be >= 1, got 0");
    }
}
