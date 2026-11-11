package io.github.eswar7116.streamguard.aggregation;

import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.LongAdder;

public class Bucket {
    private long id;
    private final LongAdder requests = new LongAdder();
    private final LongAdder errors = new LongAdder();
    // Exponential latency bins (see SlidingWindow.BIN_UPPER_MS); atomic so concurrent writers never lose increments
    private final AtomicIntegerArray latencyHistogram = new AtomicIntegerArray(SlidingWindow.HISTOGRAM_BINS);

    public Bucket(long id) {
        this.id = id;
    }

    void reset(long newId) {
        this.id = newId;
        requests.reset();
        errors.reset();
        for (int i = 0; i < latencyHistogram.length(); i++) {
            latencyHistogram.set(i, 0);
        }
    }

    void update(int statusCode, long latencyMs) {
        requests.increment();
        if (statusCode >= 400) errors.increment();
        latencyHistogram.incrementAndGet(SlidingWindow.binFor(latencyMs));
    }

    public long getId() { return id; }
    public long getRequests() { return requests.sum(); }
    public long getErrors() { return errors.sum(); }
    public AtomicIntegerArray getLatencyHistogram() { return latencyHistogram; }
}
