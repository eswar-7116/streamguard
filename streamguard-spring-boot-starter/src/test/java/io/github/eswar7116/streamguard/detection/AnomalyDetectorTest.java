package io.github.eswar7116.streamguard.detection;

import io.github.eswar7116.streamguard.TestProperties;
import io.github.eswar7116.streamguard.model.WindowSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

/**
 * Tests the stateless window-delta rules: a current window is compared against the previous one,
 * with minimum-sample and floor guards so small or lucky windows don't false-alarm.
 */
class AnomalyDetectorTest {

    private final AnomalyDetector detector = new AnomalyDetector(TestProperties.defaults().detection());

    private static AnomalyDetector detector(String minRequests, String floorPct) {
        return new AnomalyDetector(TestProperties.of(Map.of(
                "streamguard.detection.min-requests", minRequests,
                "streamguard.detection.error-rate-floor-pct", floorPct)).detection());
    }

    // endpoint/duration/rps are irrelevant to the rules; only counts, errorRate and p99 matter.
    private static WindowSnapshot snap(int requests, double errorRate, long p99Ms) {
        return new WindowSnapshot("/api/x", 15_000, requests, requests / 15.0, errorRate, 10, 50, p99Ms);
    }

    @Test
    void ignoresWindowsWithTooFewPriorRequests() {
        // Previous window below min-requests (20) => no rule fires, however bad the current one looks.
        assertThat(detector.detect(snap(500, 100.0, 5000), snap(5, 0.0, 10))).isNull();
    }

    @Test
    void flagsErrorSpike() {
        // current 60% vs previous 5% (floored); 60 > 3 * 5
        assertThat(detector.detect(snap(100, 60.0, 50), snap(100, 5.0, 50)))
                .isEqualTo(AnomalyType.ERROR_SPIKE);
    }

    @Test
    void errorRateFloorStopsSpikeOnQuietBaseline() {
        // previous 0% would make any error look infinite; the 5% floor caps it. 12% is not > 3*5.
        assertThat(detector.detect(snap(100, 12.0, 50), snap(100, 0.0, 50))).isNull();
    }

    @Test
    void quietRouteWithOneErrorIsNotASpike() {
        // 0/10 -> 1/10 errors: an unguarded ratio would call 10% vs 0% an infinite spike
        assertThat(detector.detect(snap(10, 10.0, 50), snap(10, 0.0, 50))).isNull();
    }

    @Test
    void quietRouteWithOneErrorIsNotASpikeEvenWithLowMinRequests() {
        // Min requests lowered to 10 so the windows are compared; the 5% floor still holds (10% is not > 15%)
        assertThat(detector("10", "5").detect(snap(10, 10.0, 50), snap(10, 0.0, 50))).isNull();
    }

    @Test
    void errorRateFloorIsConfigurable() {
        // 0% -> 12%: not > 3 * 5% by default, but > 3 * 2% with a lower floor
        assertThat(detector("20", "2").detect(snap(100, 12.0, 50), snap(100, 0.0, 50)))
                .isEqualTo(AnomalyType.ERROR_SPIKE);
    }

    @Test
    void minRequestsIsConfigurable() {
        // 0/30 -> 30/30 fires with the default 20, but not when 50 requests are required
        assertThat(detector.detect(snap(30, 100.0, 50), snap(30, 0.0, 50))).isEqualTo(AnomalyType.ERROR_SPIKE);
        assertThat(detector("50", "5").detect(snap(30, 100.0, 50), snap(30, 0.0, 50))).isNull();
    }

    @Test
    void persistentFailureIsInvisibleToTheRelativeRule() {
        // Flat 80% across windows: no change, so no ERROR_SPIKE; ErrorRateMonitor covers this case
        WindowSnapshot w1 = snap(100, 80.0, 50);
        WindowSnapshot w2 = snap(100, 80.0, 50);
        WindowSnapshot w3 = snap(100, 80.0, 50);
        assertThat(detector.detect(w2, w1)).isNull();
        assertThat(detector.detect(w3, w2)).isNull();
    }

    @Test
    void skippedErrorRuleFallsThroughToOtherRules() {
        // Error and traffic spike both hold; with errors skipped the traffic spike is still reported
        assertThat(detector.detect(snap(500, 90.0, 50), snap(30, 1.0, 50), true))
                .isEqualTo(AnomalyType.TRAFFIC_SPIKE);
    }

    @Test
    void chanceRiseOnLowTrafficRouteIsNotASpike() {
        // 1/20 -> 4/20: 20% > 3 * 5%, but well within chance at 5% background (z = 1.4)
        assertThat(detector.detect(snap(20, 20.0, 50), snap(20, 5.0, 50))).isNull();
        // With the test disabled, the ratio alone fires on it
        AnomalyDetector ratioOnly = new AnomalyDetector(TestProperties.of(Map.of(
                "streamguard.detection.error-spike-min-z", "0")).detection());
        assertThat(ratioOnly.detect(snap(20, 20.0, 50), snap(20, 5.0, 50))).isEqualTo(AnomalyType.ERROR_SPIKE);
    }

    @Test
    void sameRiseIsASpikeWithEnoughTraffic() {
        // 5% -> 20% again, now over 200 requests a window: z = 4.5
        assertThat(detector.detect(snap(200, 20.0, 50), snap(200, 5.0, 50))).isEqualTo(AnomalyType.ERROR_SPIKE);
    }

    @Test
    void largeJumpOnLowTrafficRouteIsStillASpike() {
        // 0/20 -> 16/20 is far beyond chance (z = 5.2)
        assertThat(detector.detect(snap(20, 80.0, 50), snap(20, 0.0, 50))).isEqualTo(AnomalyType.ERROR_SPIKE);
    }

    @Test
    void significantButSmallRiseIsNotASpike() {
        // 1% -> 1.3% over 100k requests is significant (z = 6.3) but far below 3x; the ratio rejects it
        assertThat(AnomalyDetector.errorRiseZ(snap(100_000, 1.3, 50), snap(100_000, 1.0, 50))).isGreaterThan(6);
        assertThat(detector.detect(snap(100_000, 1.3, 50), snap(100_000, 1.0, 50))).isNull();
    }

    @Test
    void zScoreMatchesTheTwoProportionTest() {
        // pooled 32.5%, se = sqrt(0.325 * 0.675 * (1/100 + 1/100)) = 0.0662; (0.60 - 0.05) / 0.0662 = 8.30
        assertThat(AnomalyDetector.errorRiseZ(snap(100, 60.0, 50), snap(100, 5.0, 50)))
                .isCloseTo(8.30, offset(0.01));
        // Identical all-success windows have no variance and no rise
        assertThat(AnomalyDetector.errorRiseZ(snap(100, 0.0, 50), snap(100, 0.0, 50))).isZero();
    }

    @Test
    void rejectsNegativeMinZ() {
        assertThatThrownBy(() -> TestProperties.of(Map.of(
                        "streamguard.detection.error-spike-min-z", "-1")))
                .hasRootCauseMessage("error-spike-min-z must be >= 0, got -1.0");
    }

    @Test
    void flagsLatencySpike() {
        // both windows have >= 100 samples; current p99 400ms > 2 * max(150, 100)
        assertThat(detector.detect(snap(200, 1.0, 400), snap(200, 1.0, 150)))
                .isEqualTo(AnomalyType.LATENCY_SPIKE);
    }

    @Test
    void latencySpikeNeedsEnoughSamplesInBothWindows() {
        // current has only 50 samples (< MIN_LATENCY_SAMPLES 100), so the latency rule is skipped.
        assertThat(detector.detect(snap(50, 1.0, 5000), snap(200, 1.0, 100))).isNull();
    }

    @Test
    void flagsTrafficSpike() {
        // current > 5 * previous
        assertThat(detector.detect(snap(200, 1.0, 50), snap(30, 1.0, 50)))
                .isEqualTo(AnomalyType.TRAFFIC_SPIKE);
    }

    @Test
    void flagsTrafficDrop() {
        // current < 0.2 * previous
        assertThat(detector.detect(snap(10, 1.0, 50), snap(100, 1.0, 50)))
                .isEqualTo(AnomalyType.TRAFFIC_DROP);
    }

    @Test
    void returnsNullWhenNothingIsAnomalous() {
        assertThat(detector.detect(snap(100, 2.0, 60), snap(90, 2.0, 55))).isNull();
    }

    @Test
    void errorSpikeTakesPrecedenceOverTraffic() {
        // Both an error spike and a traffic spike hold; errors are checked first.
        assertThat(detector.detect(snap(500, 90.0, 50), snap(30, 1.0, 50)))
                .isEqualTo(AnomalyType.ERROR_SPIKE);
    }
}
