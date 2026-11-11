package io.github.eswar7116.streamguard.model;

/**
 * Immutable aggregate of a {@code SlidingWindow} over a time range.
 *
 * @param endpoint          the window's key (an endpoint, or an IP for the per-IP windows)
 * @param windowDurationMs  length of the range the snapshot covers
 * @param totalRequests     requests in the range
 * @param requestsPerSecond totalRequests / window length in seconds
 * @param errorRate         percentage (0–100) of responses with status >= 400
 * @param p50Ms             latency percentiles, estimated from the merged histogram
 * @param p95Ms
 * @param p99Ms
 */
public record WindowSnapshot(
        String endpoint,
        long windowDurationMs,
        int totalRequests,
        double requestsPerSecond,
        double errorRate,
        long p50Ms,
        long p95Ms,
        long p99Ms
) {
}
