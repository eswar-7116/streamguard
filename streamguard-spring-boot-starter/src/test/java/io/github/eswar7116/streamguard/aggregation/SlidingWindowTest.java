package io.github.eswar7116.streamguard.aggregation;

import io.github.eswar7116.streamguard.model.WindowSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the single-key bucketed window: counting, error rate, histogram percentiles,
 * bucket eviction, late-event handling and window-bound validation. Time is driven by a mutable
 * clock so eviction and roll-over are exercised deterministically.
 */
class SlidingWindowTest {

    // 1s buckets, at most 10 of them (a 10s window)
    private static final long BUCKET_MS = 1000;
    private static final int MAX_BUCKETS = 10;

    private final AtomicLong now = new AtomicLong(100_000);
    private final SlidingWindow window = new SlidingWindow("/api/orders", BUCKET_MS, MAX_BUCKETS, now::get);

    @Test
    void countsRequestsAndErrorRateWithinWindow() {
        for (int i = 0; i < 100; i++) {
            window.record(now.get(), i < 20 ? 500 : 200, 40);
        }
        WindowSnapshot snap = window.snapshot(Duration.ofSeconds(MAX_BUCKETS));

        assertThat(snap.endpoint()).isEqualTo("/api/orders");
        assertThat(snap.totalRequests()).isEqualTo(100);
        assertThat(snap.errorRate()).isEqualTo(20.0);
    }

    @Test
    void estimatesPercentilesFromHistogram() {
        // 90 fast (~50ms) and 10 slow (~800ms) requests; p99 lands in the slow bin
        for (int i = 0; i < 90; i++) window.record(now.get(), 200, 50);
        for (int i = 0; i < 10; i++) window.record(now.get(), 200, 800);

        WindowSnapshot snap = window.snapshot(Duration.ofSeconds(MAX_BUCKETS));

        assertThat(snap.p50Ms()).isBetween(0L, 100L);
        assertThat(snap.p99Ms()).isGreaterThanOrEqualTo(800L);
    }

    @Test
    void evictsBucketsOlderThanMaxWindow() {
        window.record(now.get(), 200, 10); // bucket at t=100s
        now.addAndGet(MAX_BUCKETS * BUCKET_MS); // advance past the window
        window.record(now.get(), 200, 10); // bucket at t=110s

        WindowSnapshot snap = window.snapshot(Duration.ofSeconds(MAX_BUCKETS));

        // The first request has aged out; only the newest bucket remains.
        assertThat(snap.totalRequests()).isEqualTo(1);
        assertThat(window.bucketCount()).isEqualTo(1);
    }

    @Test
    void recordsAnEventLandingJustBeforeABucketBoundary() {
        window.record(now.get(), 200, 10); // head bucket at t=100s
        now.addAndGet(BUCKET_MS); // move into the next bucket
        window.record(now.get(), 200, 10); // head bucket at t=101s
        window.record(now.get() - BUCKET_MS, 500, 10); // late event for t=100s

        WindowSnapshot snap = window.snapshot(Duration.ofSeconds(MAX_BUCKETS));

        assertThat(snap.totalRequests()).isEqualTo(3);
        assertThat(window.histogramSampleCount()).isEqualTo(window.requestCount());
    }

    @Test
    void dropsEventsOlderThanTheWholeWindow() {
        now.addAndGet(MAX_BUCKETS * BUCKET_MS);
        window.record(now.get(), 200, 10); // establish head far ahead
        window.record(now.get() - (MAX_BUCKETS + 5) * BUCKET_MS, 200, 10); // ancient event

        WindowSnapshot snap = window.snapshot(Duration.ofSeconds(MAX_BUCKETS));
        assertThat(snap.totalRequests()).isEqualTo(1);
    }

    @Test
    void reportsIdleUntilSomethingIsRecorded() {
        assertThat(window.isIdle()).isTrue();
        window.record(now.get(), 200, 10);
        assertThat(window.isIdle()).isFalse();
    }

    @Test
    void rejectsWindowsOutsideTheSupportedRange() {
        assertThatThrownBy(() -> window.snapshot(Duration.ofSeconds(MAX_BUCKETS + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> window.snapshot(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveConstructorArgs() {
        assertThatThrownBy(() -> new SlidingWindow("k", 0, MAX_BUCKETS, now::get))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlidingWindow("k", BUCKET_MS, 0, now::get))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void percentilesStayAccurateForFastRequests() {
        // A 100ms-wide first bin would report p50 = 50ms here; exponential bins keep it near 20ms
        long[] histogram = new long[SlidingWindow.HISTOGRAM_BINS];
        histogram[SlidingWindow.binFor(20)] = 1000;

        assertThat(SlidingWindow.percentile(histogram, 0.50)).isBetween(20L, 25L);
        assertThat(SlidingWindow.percentile(histogram, 0.99)).isBetween(20L, 25L);
    }

    @Test
    void binsAreExclusiveOfTheirUpperBound() {
        assertThat(SlidingWindow.binFor(0)).isZero();
        assertThat(SlidingWindow.binFor(1)).isEqualTo(1);
        assertThat(SlidingWindow.binFor(99)).isEqualTo(SlidingWindow.binFor(80));
        assertThat(SlidingWindow.binFor(100)).isEqualTo(SlidingWindow.binFor(119));
        assertThat(SlidingWindow.binFor(10_000)).isEqualTo(SlidingWindow.HISTOGRAM_BINS - 1);
        assertThat(SlidingWindow.binFor(Long.MAX_VALUE)).isEqualTo(SlidingWindow.HISTOGRAM_BINS - 1);
    }

    @Test
    void overflowReadsAsTheTenSecondFloor() {
        long[] histogram = new long[SlidingWindow.HISTOGRAM_BINS];
        histogram[SlidingWindow.HISTOGRAM_BINS - 1] = 10;

        assertThat(SlidingWindow.percentile(histogram, 0.99)).isEqualTo(10_000L);
    }
}
