package io.github.eswar7116.streamguard.aggregation;

import io.github.eswar7116.streamguard.model.WindowSnapshot;

import java.time.Duration;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.LongSupplier;

/**
 * Bucketed window of per-time-slice counters for one key (an endpoint, an IP, ...),
 * held as a time-ordered deque of 1-second buckets with the oldest evicted on roll-over.
 *
 * <p>Time is cut into fixed buckets (1s by default). The deque holds only buckets that saw
 * traffic, oldest first, and never more than {@code maxBuckets} of them, so memory is bounded
 * no matter how many events arrive.
 *
 * <ul>
 *   <li><b>Writes are O(1).</b> The common case reads one volatile field ({@link #head}) and
 *       bumps that bucket's {@code LongAdder}s. The lock is taken once per bucket boundary, to
 *       append a new bucket, never per event.</li>
 *   <li><b>Queries are O(buckets in range)</b>, independent of how many events those buckets
 *       absorbed. Counters are summed and latency histograms merged across the range, and
 *       percentiles come from the merged histogram instead of raw values.</li>
 * </ul>
 *
 * <p>The histogram trades exactness for bounded memory, the same tradeoff Prometheus
 * histograms make. Bins grow exponentially, each about 25% wider than the last from 1ms to 10s,
 * so the error is relative to the latency instead of a fixed width: a percentile is known to lie
 * within its bin and is linearly interpolated inside it. Anything at or above 10s lands in the
 * last (overflow) bin and reads as 10s.
 */
public class SlidingWindow {

    /** Exclusive upper bound of each latency bin in ms; the extra last bin holds everything above. */
    static final long[] BIN_UPPER_MS = {
            1, 2, 3, 4, 5, 6, 8, 10, 12, 15, 20, 25, 30, 40, 50, 60, 80, 100, 120, 150, 200, 250, 300, 400,
            500, 600, 800, 1_000, 1_200, 1_500, 2_000, 2_500, 3_000, 4_000, 5_000, 6_000, 8_000, 10_000};
    static final int HISTOGRAM_BINS = BIN_UPPER_MS.length + 1;
    static final long OVERFLOW_MS = BIN_UPPER_MS[BIN_UPPER_MS.length - 1];

    /** Index of the bin holding {@code latencyMs}: a binary search over ~40 bounds, about 6 comparisons. */
    static int binFor(long latencyMs) {
        int i = Arrays.binarySearch(BIN_UPPER_MS, latencyMs);
        // An exact hit on an upper bound belongs to the next bin, since bounds are exclusive
        return i >= 0 ? i + 1 : -i - 1;
    }

    private final String key;
    private final long bucketMillis;
    private final int maxBuckets;
    private final LongSupplier clock;

    /** Oldest bucket first. Appended under {@link #rotateLock}, iterated lock-free by readers. */
    private final ConcurrentLinkedDeque<Bucket> buckets = new ConcurrentLinkedDeque<>();
    /** Newest bucket, the writers' fast path. Buckets are never reset, so an id never changes. */
    private volatile Bucket head;
    /** Start of the first bucket ever recorded, or Long.MAX_VALUE before any event. */
    private volatile long firstEventMs = Long.MAX_VALUE;
    private final Object rotateLock = new Object();

    public SlidingWindow(String key, long bucketMillis, int maxBuckets, LongSupplier clock) {
        if (bucketMillis <= 0 || maxBuckets <= 0) {
            throw new IllegalArgumentException("bucketMillis and maxBuckets must be positive");
        }
        this.key = key;
        this.bucketMillis = bucketMillis;
        this.maxBuckets = maxBuckets;
        this.clock = clock;
    }

    public String getKey() {
        return key;
    }

    public void record(long timestampMs, int statusCode, long latencyMs) {
        long id = timestampMs / bucketMillis;
        long latency = Math.max(0, latencyMs);

        Bucket h = head;
        if (h != null && h.getId() == id) {
            h.update(statusCode, latency);
            return;
        }
        Bucket target = (h == null || id > h.getId()) ? rotateTo(id) : findLate(id);
        if (target != null) {
            target.update(statusCode, latency);
        }
    }

    /** Appends a bucket for {@code id} (unless another writer just did) and evicts expired ones. */
    private Bucket rotateTo(long id) {
        synchronized (rotateLock) {
            Bucket h = head;
            if (h != null && h.getId() >= id) {
                return h.getId() == id ? h : findLate(id);
            }
            Bucket fresh = new Bucket(id);
            if (h == null) {
                firstEventMs = id * bucketMillis;
            }
            buckets.addLast(fresh);
            head = fresh;
            evictBefore(id - maxBuckets + 1);
            return fresh;
        }
    }

    /**
     * Finds the bucket for an event older than {@link #head}, e.g. one whose timestamp was
     * taken just before a second boundary but recorded just after it. Such events are almost
     * always one bucket late, so the descending scan stops after a step or two.
     *
     * <p>If no bucket exists for that slice (no other traffic in it), the event is counted in the
     * next newer bucket. The deque can't insert in the middle, and the skew is under one bucket.
     * Events older than the window are dropped.
     */
    private Bucket findLate(long id) {
        Bucket h = head;
        if (h == null || id <= h.getId() - maxBuckets) {
            return null;
        }
        Bucket newer = h;
        for (Iterator<Bucket> it = buckets.descendingIterator(); it.hasNext(); ) {
            Bucket b = it.next();
            if (b.getId() == id) {
                return b;
            }
            if (b.getId() < id) {
                break;
            }
            newer = b;
        }
        return newer;
    }

    /** Must hold {@link #rotateLock}, so two evictors can't both pop the same head-of-queue. */
    private void evictBefore(long minId) {
        Bucket oldest;
        while ((oldest = buckets.peekFirst()) != null && oldest.getId() < minId) {
            buckets.pollFirst();
        }
    }

    /** Drops buckets that have fallen out of the largest supported window. */
    public void evictExpired() {
        long minId = clock.getAsLong() / bucketMillis - maxBuckets + 1;
        synchronized (rotateLock) {
            evictBefore(minId);
        }
    }

    /** Start of the bucket holding this window's first event; Long.MAX_VALUE if none yet. */
    public long firstEventMs() {
        return firstEventMs;
    }

    /** True when nothing has been recorded within the largest supported window. */
    public boolean isIdle() {
        Bucket h = head;
        return h == null || h.getId() <= clock.getAsLong() / bucketMillis - maxBuckets;
    }

    /** Live window: the last {@code window} of buckets, including the current, still-filling one. */
    public WindowSnapshot snapshot(Duration window) {
        evictExpired();
        long n = bucketsIn(window);
        long toExclusive = clock.getAsLong() / bucketMillis + 1;
        return aggregate(toExclusive - n, toExclusive);
    }

    /**
     * Closed window: the {@code window} of buckets ending (exclusive) at {@code endMs}, which
     * should be bucket-aligned. Used to compare complete, non-overlapping windows.
     */
    public WindowSnapshot snapshotEndingAt(long endMs, Duration window) {
        evictExpired();
        long toExclusive = endMs / bucketMillis;
        return aggregate(toExclusive - bucketsIn(window), toExclusive);
    }

    private long bucketsIn(Duration window) {
        long n = Math.ceilDiv(window.toMillis(), bucketMillis);
        if (n <= 0 || n > maxBuckets) {
            throw new IllegalArgumentException(
                    "window must be between 1 bucket and " + maxBuckets * bucketMillis / 1000 + "s");
        }
        return n;
    }

    /** Sums buckets with fromInclusive <= id < toExclusive, walking newest to oldest. */
    private WindowSnapshot aggregate(long fromInclusive, long toExclusive) {
        long requests = 0;
        long errors = 0;
        long[] histogram = new long[HISTOGRAM_BINS];

        for (Iterator<Bucket> it = buckets.descendingIterator(); it.hasNext(); ) {
            Bucket b = it.next();
            long id = b.getId();
            if (id < fromInclusive) {
                break;
            }
            if (id >= toExclusive) {
                continue;
            }
            requests += b.getRequests();
            errors += b.getErrors();
            AtomicIntegerArray bins = b.getLatencyHistogram();
            for (int i = 0; i < HISTOGRAM_BINS; i++) {
                histogram[i] += bins.get(i);
            }
        }

        long durationMs = (toExclusive - fromInclusive) * bucketMillis;
        return new WindowSnapshot(
                key,
                durationMs,
                (int) Math.min(requests, Integer.MAX_VALUE),
                requests * 1000.0 / durationMs,
                requests == 0 ? 0.0 : errors * 100.0 / requests,
                percentile(histogram, 0.50),
                percentile(histogram, 0.95),
                percentile(histogram, 0.99));
    }

    /**
     * Estimates a quantile from the histogram: find the bin holding the q-th sample, then
     * interpolate linearly within it (as Prometheus' histogram_quantile does). The rank is taken
     * against the histogram's own total, not the request counter, so the two needn't agree.
     */
    static long percentile(long[] histogram, double q) {
        long total = 0;
        for (long c : histogram) {
            total += c;
        }
        if (total == 0) {
            return 0;
        }
        double rank = q * total;
        long cumulative = 0;
        for (int i = 0; i < histogram.length; i++) {
            long count = histogram[i];
            if (count > 0 && cumulative + count >= rank) {
                if (i == BIN_UPPER_MS.length) {
                    return OVERFLOW_MS; // The overflow bin has no upper bound to interpolate toward
                }
                long lower = i == 0 ? 0 : BIN_UPPER_MS[i - 1];
                double fraction = (rank - cumulative) / count;
                return Math.round(lower + fraction * (BIN_UPPER_MS[i] - lower));
            }
            cumulative += count;
        }
        return OVERFLOW_MS;
    }

    /** Sum of the latency histogram across the whole window. Lets tests check it against the request counters. */
    long histogramSampleCount() {
        long total = 0;
        for (Bucket b : buckets) {
            AtomicIntegerArray bins = b.getLatencyHistogram();
            for (int i = 0; i < bins.length(); i++) {
                total += bins.get(i);
            }
        }
        return total;
    }

    /** Requests across every bucket held, as a long (WindowSnapshot.totalRequests is an int). */
    long requestCount() {
        long total = 0;
        for (Bucket b : buckets) {
            total += b.getRequests();
        }
        return total;
    }

    int bucketCount() {
        return buckets.size();
    }
}
