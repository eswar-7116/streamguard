package io.github.eswar7116.streamguard.aggregation;

import io.github.eswar7116.streamguard.model.WindowSnapshot;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JMH microbenchmarks for the hot paths of the bucketed {@link SlidingWindow}: the per-event
 * write and the windowed snapshot query. Replaces trust in the hand-rolled loop benchmark with
 * proper warmup, forks and @State so JIT and dead-code effects are controlled for.
 *
 * <p>Run with {@code ./gradlew jmh}. Config (warmup/iterations/forks) lives in build.gradle.kts.
 */
public class SlidingWindowBenchmark {

    private static final Duration QUERY_WINDOW = Duration.ofMinutes(5);
    private static final long WINDOW_MS = QUERY_WINDOW.toMillis();

    /** A window whose clock advances on every write, so writes keep rolling buckets as in production. */
    @State(Scope.Thread)
    public static class WriteState {
        SlidingWindow window;
        AtomicLong clock;

        @Setup(Level.Iteration)
        public void setup() {
            clock = new AtomicLong(1_700_000_000_000L);
            window = new SlidingWindow("bench", 1000, 900, clock::get);
        }
    }

    /** The same, but shared across a group's threads to measure contended writes to one window. */
    @State(Scope.Group)
    public static class SharedWriteState {
        SlidingWindow window;
        AtomicLong clock;

        @Setup(Level.Iteration)
        public void setup() {
            clock = new AtomicLong(1_700_000_000_000L);
            window = new SlidingWindow("bench", 1000, 900, clock::get);
        }
    }

    /** A window pre-filled with events spread across the query range, frozen for repeatable queries. */
    @State(Scope.Benchmark)
    public static class QueryState {
        static final long NOW = 1_700_000_000_000L;
        SlidingWindow window;

        @Setup(Level.Trial)
        public void setup() {
            window = new SlidingWindow("bench", 1000, 900, () -> NOW);
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            for (int i = 0; i < 1_000_000; i++) {
                long ts = NOW - rnd.nextLong(WINDOW_MS);
                int status = rnd.nextInt(100) < 5 ? 500 : 200;
                window.record(ts, status, rnd.nextInt(500));
            }
        }
    }

    /** Single-thread write throughput: the uncontended O(1) fast path. */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(java.util.concurrent.TimeUnit.SECONDS)
    public void recordSingle(WriteState s) {
        long ts = s.clock.incrementAndGet();
        s.window.record(ts, ts % 20 == 0 ? 500 : 200, ts % 500);
    }

    /** Contended write throughput: four writers sharing one window, as a servlet filter would drive it. */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(java.util.concurrent.TimeUnit.SECONDS)
    @Group("recordContended")
    @GroupThreads(4)
    public void recordContended(SharedWriteState s) {
        long ts = s.clock.incrementAndGet();
        s.window.record(ts, ts % 20 == 0 ? 500 : 200, ts % 500);
    }

    /** Snapshot latency over a million-event window: should track the fixed bucket count, not volume. */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(java.util.concurrent.TimeUnit.MICROSECONDS)
    public void snapshot(QueryState s, Blackhole bh) {
        WindowSnapshot snap = s.window.snapshot(QUERY_WINDOW);
        bh.consume(snap);
    }
}
