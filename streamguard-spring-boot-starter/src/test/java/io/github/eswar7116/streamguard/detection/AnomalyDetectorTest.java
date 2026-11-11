package io.github.eswar7116.streamguard.detection;

import io.github.eswar7116.streamguard.model.WindowSnapshot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the stateless window-delta rules: a current window is compared against the previous one,
 * with minimum-sample and floor guards so small or lucky windows don't false-alarm.
 */
class AnomalyDetectorTest {

    private final AnomalyDetector detector = new AnomalyDetector();

    // endpoint/duration/rps are irrelevant to the rules; only counts, errorRate and p99 matter.
    private static WindowSnapshot snap(int requests, double errorRate, long p99Ms) {
        return new WindowSnapshot("/api/x", 15_000, requests, requests / 15.0, errorRate, 10, 50, p99Ms);
    }

    @Test
    void ignoresWindowsWithTooFewPriorRequests() {
        // Previous window below MIN_REQUESTS (20) => no rule fires, however bad the current one looks.
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
