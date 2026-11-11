package io.github.eswar7116.streamguard.model;

/**
 * One observed HTTP request.
 *
 * @param timestamp        epoch millis when the request completed
 * @param endpoint         route template the request matched, e.g. {@code /api/orders/{id}}
 * @param path             raw request path, e.g. {@code /api/orders/42}; feeds scanner detection
 * @param method           HTTP method
 * @param statusCode       response status
 * @param latencyMs        time spent processing the request
 * @param requestSizeBytes request body size (0 when unknown)
 * @param clientIp         remote address; feeds the per-IP security windows
 */
public record RequestEvent(
        long timestamp,
        String endpoint,
        String path,
        String method,
        int statusCode,
        long latencyMs,
        long requestSizeBytes,
        String clientIp
) {
    /** An event whose raw path is its endpoint, for literal routes. */
    public RequestEvent(long timestamp, String endpoint, String method, int statusCode, long latencyMs,
                        long requestSizeBytes, String clientIp) {
        this(timestamp, endpoint, endpoint, method, statusCode, latencyMs, requestSizeBytes, clientIp);
    }
}
