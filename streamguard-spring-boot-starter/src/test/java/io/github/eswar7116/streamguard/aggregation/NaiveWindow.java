package io.github.eswar7116.streamguard.aggregation;

import io.github.eswar7116.streamguard.model.WindowSnapshot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * The deliberately naive baseline the bucketed {@link SlidingWindow} is meant to beat: it keeps
 * every raw event and recomputes the whole snapshot by scanning them on each query. Correct, simple,
 * and O(events) per query — so query latency grows with volume instead of staying flat. Used only by
 * the comparison benchmark to make the before/after a measured result rather than a claim.
 */
class NaiveWindow {

    private record Event(long timestampMs, int statusCode, long latencyMs) {
    }

    private final List<Event> events = Collections.synchronizedList(new ArrayList<>());
    private final LongSupplier clock;

    NaiveWindow(LongSupplier clock) {
        this.clock = clock;
    }

    void record(long timestampMs, int statusCode, long latencyMs) {
        events.add(new Event(timestampMs, statusCode, Math.max(0, latencyMs)));
    }

    int size() {
        return events.size();
    }

    /** Scans every stored event, filters to the window, then sorts latencies for exact percentiles. */
    WindowSnapshot snapshot(Duration window) {
        long now = clock.getAsLong();
        long from = now - window.toMillis();

        long requests = 0;
        long errors = 0;
        List<Long> latencies = new ArrayList<>();
        Event[] snapshot;
        synchronized (events) {
            snapshot = events.toArray(new Event[0]);
        }
        for (Event e : snapshot) {
            if (e.timestampMs() < from || e.timestampMs() > now) {
                continue;
            }
            requests++;
            if (e.statusCode() >= 400) {
                errors++;
            }
            latencies.add(e.latencyMs());
        }
        Collections.sort(latencies);

        long durationMs = window.toMillis();
        return new WindowSnapshot(
                "naive",
                durationMs,
                (int) Math.min(requests, Integer.MAX_VALUE),
                requests * 1000.0 / durationMs,
                requests == 0 ? 0.0 : errors * 100.0 / requests,
                percentile(latencies, 0.50),
                percentile(latencies, 0.95),
                percentile(latencies, 0.99));
    }

    private static long percentile(List<Long> sorted, double q) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = Math.min(sorted.size() - 1, (int) Math.ceil(q * sorted.size()) - 1);
        return sorted.get(Math.max(0, idx));
    }
}
