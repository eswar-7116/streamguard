package io.github.eswar7116.streamguard.forecast;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrafficPredictorTest {

    @Test
    void rejectsAlphaOutsideOpenUnitInterval() {
        assertThatThrownBy(() -> new TrafficPredictor(0.0, 3.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrafficPredictor(1.0, 3.0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void firstObservationSeedsTheEwmaAndIsNeverASpike() {
        TrafficPredictor p = new TrafficPredictor(0.3, 3.0);
        assertThat(p.initialized()).isFalse();
        assertThat(p.isSpike(10_000)).isFalse(); // uninitialized never spikes
        p.update(100);
        assertThat(p.initialized()).isTrue();
        assertThat(p.predict()).isEqualTo(100);
    }

    @Test
    void noSpikeUntilWarmedUpEvenIfTheFirstWindowWasPartial() {
        // A predictor created mid-window sees a low first rate, then the real one; the zero-width
        // band after one observation must not turn that jump into a spike
        TrafficPredictor p = new TrafficPredictor(0.3, 3.0);
        p.update(2);
        assertThat(p.isWarm()).isFalse();
        assertThat(p.isSpike(15)).isFalse();
        p.update(15);
        assertThat(p.isSpike(15)).isFalse();
        p.update(15);
        assertThat(p.isWarm()).isTrue();
        assertThat(p.isSpike(16)).as("normal jitter after warm-up").isFalse();
    }

    @Test
    void steadyTrafficStaysNearTheEwmaAndDoesNotSpike() {
        TrafficPredictor p = new TrafficPredictor(0.3, 3.0);
        for (int i = 0; i < 50; i++) {
            double count = 100 + (i % 2 == 0 ? 2 : -2); // small jitter around 100
            assertThat(p.isSpike(count)).as("steady jitter is not a spike").isFalse();
            p.update(count);
        }
        assertThat(p.predict()).isCloseTo(100, org.assertj.core.data.Offset.offset(5.0));
    }

    @Test
    void largeOvershootIsFlaggedAsSpike() {
        TrafficPredictor p = new TrafficPredictor(0.3, 3.0);
        for (int i = 0; i < 30; i++) {
            p.update(100 + (i % 2 == 0 ? 2 : -2)); // build a steady level + small deviation band
        }
        assertThat(p.isSpike(500)).as("5x the steady rate overshoots the band").isTrue();
    }

    @Test
    void alphaAdaptsSoTheForecastChasesASustainedShift() {
        TrafficPredictor p = new TrafficPredictor(0.3, 3.0);
        p.update(100);
        p.update(1000); // big jump: adaptive alpha should move the EWMA well past a fixed-0.3 step
        assertThat(p.predict()).isGreaterThan(100 + 0.3 * (1000 - 100));
    }

    @Test
    void poissonNoiseDoesNotSpikeButARealSurgeDoes() {
        // 13 req/s over 15s windows: counts vary by about sqrt(195) = 14 requests, i.e. ~0.9 req/s
        TrafficPredictor p = new TrafficPredictor(0.3, 3.0, 15);
        java.util.Random rnd = new java.util.Random(42);
        for (int i = 0; i < 200; i++) {
            double rate = poisson(rnd, 13 * 15) / 15.0;
            assertThat(p.isSpike(rate)).as("window %d at %.2f req/s", i, rate).isFalse();
            p.update(rate);
        }
        assertThat(p.isSpike(26)).as("double the usual rate").isTrue();
    }

    @Test
    void rejectsNonPositiveWindow() {
        assertThatThrownBy(() -> new TrafficPredictor(0.3, 3.0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    // Knuth's method, in log space so large means don't underflow
    private static int poisson(java.util.Random rnd, double mean) {
        double l = -mean, sum = 0;
        int k = 0;
        while ((sum += Math.log(rnd.nextDouble())) > l) {
            k++;
        }
        return k;
    }
}
