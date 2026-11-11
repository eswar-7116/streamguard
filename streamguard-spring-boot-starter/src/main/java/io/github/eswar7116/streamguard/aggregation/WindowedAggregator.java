package io.github.eswar7116.streamguard.aggregation;

import io.github.eswar7116.streamguard.config.StreamGuardProperties;
import io.github.eswar7116.streamguard.model.RequestEvent;
import io.github.eswar7116.streamguard.model.WindowSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fans each request out to three families of {@link SlidingWindow}s:
 * <ol>
 *   <li>per endpoint, for metrics, anomaly detection and availability</li>
 *   <li>per client IP, for DDoS detection</li>
 *   <li>per {@code endpoint|IP}, for brute-force detection. Only 401/403 responses are recorded
 *       here, so a window's request count is its auth-failure count. That also keeps this map's
 *       size proportional to failing clients rather than endpoints × IPs.</li>
 * </ol>
 * Windows are created lazily on first use and dropped once idle for the maximum window. The
 * per-IP families only keep {@link #SECURITY_WINDOW}, the longest window the security checks
 * read, so a flood of distinct clients costs a minute of buckets each, not fifteen.
 */
public class WindowedAggregator {

    public static final Duration SECURITY_WINDOW = Duration.ofMinutes(1);


    private final ConcurrentHashMap<String, SlidingWindow> endpointWindows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SlidingWindow> ipWindows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SlidingWindow> endpointIpWindows = new ConcurrentHashMap<>();
    private final List<RequestEventListener> listeners = new CopyOnWriteArrayList<>();

    private final Clock clock;
    private final long bucketMillis;
    private final int maxBuckets;
    private final int securityBuckets;
    private final Duration maxWindow;

    public WindowedAggregator(StreamGuardProperties properties, Clock clock) {
        StreamGuardProperties.Windows windows = properties.windows();
        this.clock = clock;
        this.bucketMillis = windows.bucketSizeSeconds() * 1000L;
        this.maxBuckets = Math.ceilDiv(windows.maxWindowSeconds(), windows.bucketSizeSeconds());
        this.maxWindow = Duration.ofSeconds(windows.maxWindowSeconds());
        this.securityBuckets = (int) Math.min(maxBuckets, Math.ceilDiv(SECURITY_WINDOW.toMillis(), bucketMillis));
    }

    public void recordEvent(RequestEvent event) {
        window(endpointWindows, event.endpoint(), maxBuckets).record(event.timestamp(), event.statusCode(), event.latencyMs());

        String ip = event.clientIp();
        if (ip != null) {
            window(ipWindows, ip, securityBuckets).record(event.timestamp(), event.statusCode(), event.latencyMs());
            if (event.statusCode() == 401 || event.statusCode() == 403) {
                window(endpointIpWindows, endpointIpKey(event.endpoint(), ip), securityBuckets)
                        .record(event.timestamp(), event.statusCode(), event.latencyMs());
            }
        }

        for (RequestEventListener listener : listeners) {
            listener.onEvent(event);
        }
    }

    private SlidingWindow window(ConcurrentHashMap<String, SlidingWindow> map, String key, int buckets) {
        SlidingWindow w = map.get(key); // avoid computeIfAbsent's bin lock on the hot path
        return w != null ? w : map.computeIfAbsent(key,
                k -> new SlidingWindow(k, bucketMillis, buckets, clock::millis));
    }

    /** Endpoints that currently have a window, i.e. saw traffic within the maximum window. */
    public Set<String> endpoints() {
        return Set.copyOf(endpointWindows.keySet());
    }

    public void addListener(RequestEventListener listener) {
        listeners.add(listener);
    }

    public Optional<WindowSnapshot> snapshot(String endpoint, Duration window) {
        validate(window);
        SlidingWindow w = endpointWindows.get(endpoint);
        return w == null ? Optional.empty() : Optional.of(w.snapshot(window));
    }

    public List<WindowSnapshot> snapshotAll(Duration window) {
        validate(window);
        return endpointWindows.values().stream()
                .map(w -> w.snapshot(window))
                .sorted(Comparator.comparing(WindowSnapshot::endpoint))
                .toList();
    }

    /** Endpoints by request count, busiest first, as an insertion-ordered map. */
    public Map<String, Integer> topEndpoints(Duration window, int limit) {
        validate(window);
        Map<String, Integer> top = new LinkedHashMap<>();
        endpointWindows.values().stream()
                .map(w -> w.snapshot(window))
                .filter(s -> s.totalRequests() > 0)
                .sorted(Comparator.comparingInt(WindowSnapshot::totalRequests).reversed()
                        .thenComparing(WindowSnapshot::endpoint))
                .limit(limit)
                .forEach(s -> top.put(s.endpoint(), s.totalRequests()));
        return top;
    }

    /**
     * The closed window of the given length ending at {@code endMs}, for each endpoint that had
     * already been seen before {@code endMs}. An endpoint that first appears later wasn't
     * silent in that window; it didn't exist yet.
     */
    public List<WindowSnapshot> closedSnapshots(long endMs, Duration window) {
        validate(window);
        return endpointWindows.values().stream()
                .filter(w -> w.firstEventMs() < endMs)
                .map(w -> w.snapshotEndingAt(endMs, window))
                .toList();
    }

    /** Start of the bucket holding the endpoint's first event, or Long.MAX_VALUE if it has no window. */
    public long firstEventMs(String endpoint) {
        SlidingWindow w = endpointWindows.get(endpoint);
        return w == null ? Long.MAX_VALUE : w.firstEventMs();
    }

    public Optional<WindowSnapshot> closedSnapshot(String endpoint, long endMs, Duration window) {
        validate(window);
        SlidingWindow w = endpointWindows.get(endpoint);
        return w == null ? Optional.empty() : Optional.of(w.snapshotEndingAt(endMs, window));
    }

    /** Snapshot per client IP; each snapshot's {@code endpoint} field holds the IP. */
    public List<WindowSnapshot> ipSnapshots(Duration window) {
        validateSecurity(window);
        return ipWindows.values().stream().map(w -> w.snapshot(window)).toList();
    }

    /** Auth failures (401/403) per {@code endpoint|IP} key; snapshot {@code endpoint} holds the key. */
    public List<WindowSnapshot> authFailureSnapshots(Duration window) {
        validateSecurity(window);
        return endpointIpWindows.values().stream().map(w -> w.snapshot(window)).toList();
    }

    public static String endpointIpKey(String endpoint, String ip) {
        return endpoint + "|" + ip;
    }

    public Duration maxWindow() {
        return maxWindow;
    }

    public Duration bucketSize() {
        return Duration.ofMillis(bucketMillis);
    }

    private void validateSecurity(Duration window) {
        if (window.isNegative() || window.isZero() || window.compareTo(SECURITY_WINDOW) > 0) {
            throw new IllegalArgumentException("security window must be > 0 and <= " + SECURITY_WINDOW.toSeconds() + "s");
        }
    }

    private void validate(Duration window) {
        if (window.isNegative() || window.isZero() || window.compareTo(maxWindow) > 0) {
            throw new IllegalArgumentException("window must be > 0 and <= " + maxWindow.toSeconds() + "s");
        }
    }

    /**
     * Drops windows with no traffic inside the maximum window, so one-off keys (scanned URLs,
     * transient IPs) don't accumulate. A writer racing with removal can lose one event to the
     * discarded window, which is harmless for a key that was idle for 15 minutes.
     */
    public void evictIdleWindows() {
        for (ConcurrentHashMap<String, SlidingWindow> map : List.of(endpointWindows, ipWindows, endpointIpWindows)) {
            map.values().removeIf(SlidingWindow::isIdle);
            map.values().forEach(SlidingWindow::evictExpired);
        }
    }
}
