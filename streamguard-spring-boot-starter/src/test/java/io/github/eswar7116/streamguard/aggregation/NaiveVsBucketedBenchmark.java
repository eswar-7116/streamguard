package io.github.eswar7116.streamguard.aggregation;

import io.github.eswar7116.streamguard.model.WindowSnapshot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The headline before/after: the same query (a 5-minute snapshot) over a growing number of events,
 * on the bucketed {@link SlidingWindow} vs the {@link NaiveWindow} that rescans raw events.
 *
 * <p>Both hold the same events, all inside the query window. The bucketed window always scans the
 * same fixed number of buckets, so its query latency is flat as volume grows; the naive window
 * scans every event, so its latency grows roughly linearly. This turns "O(buckets) not O(events)"
 * from a claim into a measured contrast.
 *
 * <p>Run with {@code ./gradlew benchmark}.
 */
@Tag("benchmark")
class NaiveVsBucketedBenchmark {

    private static final long NOW = 1_700_000_000_000L;
    private static final Duration QUERY_WINDOW = Duration.ofMinutes(5);
    private static final long WINDOW_MS = QUERY_WINDOW.toMillis();
    private static final int[] EVENT_COUNTS = {10_000, 50_000, 200_000, 1_000_000, 2_000_000};
    private static final int QUERY_REPS = 200;

    @Test
    void queryLatencyVsEventVolume() {
        System.out.println();
        System.out.println("=== Naive (raw-event rescan) vs bucketed SlidingWindow ===");
        System.out.printf("query = snapshot(%s); both structures hold every event inside the window%n%n",
                QUERY_WINDOW);
        System.out.println("     events   bucketed p50(µs)   naive p50(µs)   speedup");
        System.out.println("  ---------   ----------------   -------------   -------");

        double firstBucketed = 0;
        double lastBucketed = 0;
        for (int count : EVENT_COUNTS) {
            SlidingWindow bucketed = new SlidingWindow("bench", 1000, 900, () -> NOW);
            NaiveWindow naive = new NaiveWindow(() -> NOW);
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            for (int i = 0; i < count; i++) {
                long ts = NOW - rnd.nextLong(WINDOW_MS);
                int status = rnd.nextInt(100) < 5 ? 500 : 200;
                long latency = rnd.nextInt(500);
                bucketed.record(ts, status, latency);
                naive.record(ts, status, latency);
            }

            WindowSnapshot b = bucketed.snapshot(QUERY_WINDOW);
            WindowSnapshot n = naive.snapshot(QUERY_WINDOW);
            assertThat(b.totalRequests()).isEqualTo(count);
            assertThat(n.totalRequests()).isEqualTo(count);

            double bucketedUs = medianQueryMicros(() -> bucketed.snapshot(QUERY_WINDOW));
            double naiveUs = medianQueryMicros(() -> naive.snapshot(QUERY_WINDOW));
            System.out.printf("  %,9d   %16.1f   %13.1f   %6.1fx%n",
                    count, bucketedUs, naiveUs, naiveUs / bucketedUs);

            if (firstBucketed == 0) {
                firstBucketed = bucketedUs;
            }
            lastBucketed = bucketedUs;
        }

        System.out.println();
        System.out.printf("bucketed query latency stayed flat: %.1fµs at %,d events -> %.1fµs at %,d events%n%n",
                firstBucketed, EVENT_COUNTS[0], lastBucketed, EVENT_COUNTS[EVENT_COUNTS.length - 1]);

        // The whole thesis: 200x more events must not make the bucketed query materially slower.
        assertThat(lastBucketed).isLessThan(firstBucketed * 3);
    }

    private static double medianQueryMicros(Runnable query) {
        for (int i = 0; i < 20; i++) {
            query.run(); // warmup
        }
        long[] nanos = new long[QUERY_REPS];
        for (int i = 0; i < QUERY_REPS; i++) {
            long t0 = System.nanoTime();
            query.run();
            nanos[i] = System.nanoTime() - t0;
        }
        java.util.Arrays.sort(nanos);
        return nanos[nanos.length / 2] / 1000.0;
    }
}
