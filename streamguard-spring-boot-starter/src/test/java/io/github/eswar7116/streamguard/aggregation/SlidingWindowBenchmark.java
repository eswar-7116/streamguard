package io.github.eswar7116.streamguard.aggregation;

import io.github.eswar7116.streamguard.model.WindowSnapshot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N writer threads hammer one {@link SlidingWindow} for a fixed duration while a reader takes a
 * 15-minute snapshot every 100ms and times it.
 *
 * <p>This measures the aggregator's write path only (the per-bucket counter updates that
 * {@code recordEvent} performs), not end-to-end HTTP request handling. The "events/sec" figures
 * are therefore ingest/aggregation throughput, not requests served.
 *
 * <p>The window is prefilled so it already holds all 900 one-second buckets. Every query
 * therefore scans the same number of buckets, and the only thing that grows during the run is
 * the event count. If query cost depended on events (as a filter over a raw event list would),
 * latency would climb with the event count. With buckets it stays flat.
 *
 * <p>Run with {@code ./gradlew benchmark}; tune with {@code -Dbench.seconds=30 -Dbench.threads=8}.
 */
@Tag("benchmark")
class SlidingWindowBenchmark {

    private static final int MAX_BUCKETS = 900;
    private static final Duration QUERY_WINDOW = Duration.ofSeconds(MAX_BUCKETS);
    private static final long POLL_MS = 100;
    private static final int PREFILL_PER_BUCKET = 1_000;
    private static final int WARMUP_SAMPLES = 20; // first 2s: JIT, excluded from the flatness check

    private record Sample(long elapsedMs, long eventsSoFar, long queryNanos) {
    }

    @Test
    void writeThroughputAndFlatQueryLatency() throws Exception {
        int seconds = Integer.getInteger("bench.seconds", 30);
        int threads = Integer.getInteger("bench.threads", Math.max(2, Runtime.getRuntime().availableProcessors() - 1));

        SlidingWindow window = new SlidingWindow("bench", 1000, MAX_BUCKETS, System::currentTimeMillis);

        long now = System.currentTimeMillis();
        long prefillStart = now - (MAX_BUCKETS - 1) * 1000L;
        for (long ts = prefillStart; ts < now - 1000; ts += 1000) {
            for (int i = 0; i < PREFILL_PER_BUCKET; i++) {
                window.record(ts, i % 20 == 0 ? 500 : 200, i % 400);
            }
        }
        long prefilled = window.requestCount();

        LongAdder written = new LongAdder();
        CountDownLatch start = new CountDownLatch(1);
        long[] deadline = new long[1];
        List<Thread> writers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            writers.add(Thread.ofPlatform().name("writer-" + t).start(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                while (System.nanoTime() < deadline[0]) {
                    for (int i = 0; i < 1024; i++) {
                        int r = rnd.nextInt(100);
                        window.record(System.currentTimeMillis(), r < 5 ? 500 : 200, r < 2 ? 800 : r * 2L);
                    }
                    written.add(1024);
                }
            }));
        }

        List<Sample> samples = new ArrayList<>();
        long begin = System.nanoTime();
        deadline[0] = begin + Duration.ofSeconds(seconds).toNanos();
        start.countDown();
        long nextPoll = begin;
        while (System.nanoTime() < deadline[0]) {
            long q0 = System.nanoTime();
            WindowSnapshot s = window.snapshot(QUERY_WINDOW);
            long q1 = System.nanoTime();
            assertThat(s.totalRequests()).isPositive();
            samples.add(new Sample((q0 - begin) / 1_000_000, written.sum(), q1 - q0));

            nextPoll += POLL_MS * 1_000_000;
            long sleepNanos = nextPoll - System.nanoTime();
            if (sleepNanos > 0) {
                Thread.sleep(sleepNanos / 1_000_000, (int) (sleepNanos % 1_000_000));
            }
        }
        for (Thread w : writers) w.join();
        double elapsedSec = (System.nanoTime() - begin) / 1e9;
        long totalWritten = written.sum();

        report(samples, threads, seconds, elapsedSec, totalWritten, prefilled, window);

        // Correctness: LongAdder counters lose nothing under contention.
        // (Write-time eviction may have dropped prefill buckets older than 15m, so count them back.)
        assertThat(window.requestCount()).isBetween(totalWritten, totalWritten + prefilled);

        // Correctness: the AtomicIntegerArray histogram records every request exactly, with no
        // increments lost to concurrent writers (a plain int[] would drop a large fraction here).
        assertThat(window.histogramSampleCount()).isEqualTo(window.requestCount());

        // Flatness: once the JIT has warmed up, the last third of queries is no slower than the first.
        List<Sample> steady = samples.subList(Math.min(WARMUP_SAMPLES, samples.size() / 2), samples.size());
        int third = steady.size() / 3;
        double early = medianMicros(steady.subList(0, third));
        double late = medianMicros(steady.subList(steady.size() - third, steady.size()));
        assertThat(late).as("late-run median query latency vs early-run").isLessThan(early * 3);
    }

    private static void report(List<Sample> samples, int threads, int seconds, double elapsedSec,
                               long totalWritten, long prefilled, SlidingWindow window) {
        System.out.println();
        System.out.println("=== SlidingWindow benchmark ===");
        System.out.printf("writers=%d  duration=%ds  query=snapshot(%ds) every %dms  buckets scanned=%d%n",
                threads, seconds, QUERY_WINDOW.toSeconds(), POLL_MS, window.bucketCount());
        System.out.printf("prefilled %,d events across %d buckets%n%n", prefilled, MAX_BUCKETS - 1);

        System.out.println("  t(s)   events written     events/s   query p50(µs)   query max(µs)");
        System.out.println("  ----   --------------   ----------   -------------   -------------");
        long prevEvents = 0;
        for (int sec = 0; sec * 1000L <= samples.getLast().elapsedMs(); sec++) {
            long from = sec * 1000L, to = from + 1000;
            List<Sample> inSec = samples.stream().filter(s -> s.elapsedMs() >= from && s.elapsedMs() < to).toList();
            if (inSec.isEmpty()) continue;
            long events = inSec.getLast().eventsSoFar();
            long maxNanos = inSec.stream().mapToLong(Sample::queryNanos).max().orElse(0);
            System.out.printf("  %4d   %,14d   %,10d   %13.1f   %13.1f%n",
                    sec + 1, events, events - prevEvents, medianMicros(inSec), maxNanos / 1000.0);
            prevEvents = events;
        }

        long[] nanos = samples.stream().mapToLong(Sample::queryNanos).sorted().toArray();
        System.out.println();
        System.out.printf("events written      : %,d%n", totalWritten);
        System.out.printf("aggregator write rate: %,.0f events/sec (counter path, not HTTP)%n", totalWritten / elapsedSec);
        System.out.printf("query latency (n=%d): p50=%.1fµs  p95=%.1fµs  p99=%.1fµs  max=%.1fµs%n",
                nanos.length, pct(nanos, 0.50) / 1000.0, pct(nanos, 0.95) / 1000.0,
                pct(nanos, 0.99) / 1000.0, nanos[nanos.length - 1] / 1000.0);

        List<Sample> steady = samples.subList(Math.min(WARMUP_SAMPLES, samples.size() / 2), samples.size());
        int third = steady.size() / 3;
        Sample firstThirdEnd = steady.get(third - 1);
        double early = medianMicros(steady.subList(0, third));
        double late = medianMicros(steady.subList(steady.size() - third, steady.size()));
        System.out.printf("flatness            : median %.1fµs at ≤%,d events  vs  %.1fµs at ≥%,d events  (ratio %.2f)%n",
                early, firstThirdEnd.eventsSoFar(), late, steady.get(steady.size() - third).eventsSoFar(), late / early);

        // Correctness check: the histogram uses AtomicIntegerArray, so it must match the LongAdder
        // request counters exactly (0% lost) even under heavy concurrent writes to the same bucket.
        long requests = window.requestCount();
        long histogram = window.histogramSampleCount();
        System.out.printf("histogram samples   : %,d of %,d requests (%.4f%% lost — expect 0%% with AtomicIntegerArray)%n%n",
                histogram, requests, (requests - histogram) * 100.0 / requests);
    }

    private static double medianMicros(List<Sample> samples) {
        long[] nanos = samples.stream().mapToLong(Sample::queryNanos).sorted().toArray();
        return pct(nanos, 0.50) / 1000.0;
    }

    private static long pct(long[] sorted, double q) {
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(q * sorted.length) - 1)];
    }
}
