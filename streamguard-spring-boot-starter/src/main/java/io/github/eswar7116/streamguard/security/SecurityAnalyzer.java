package io.github.eswar7116.streamguard.security;

import io.github.eswar7116.streamguard.aggregation.RequestEventListener;
import io.github.eswar7116.streamguard.aggregation.WindowedAggregator;
import io.github.eswar7116.streamguard.model.RequestEvent;
import io.github.eswar7116.streamguard.model.WindowSnapshot;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * DDoS and brute-force checks read the aggregator's per-IP and per-endpoint|IP windows. Scanner
 * detection needs distinct endpoints per IP, which counters can't provide, so it keeps its own
 * per-IP sets of distinct targets in tumbling one-minute windows (see {@link #scanKey}).
 *
 * <p>Brute force is only checked on routes the application registered as auth routes.
 */
public class SecurityAnalyzer implements RequestEventListener {

    static final double DDOS_SHARE_PCT = 30.0;
    /** Below this much total traffic, "30% from one IP" is just a quiet server with one client. */
    static final int DDOS_MIN_TOTAL_REQUESTS = 100;
    static final int BRUTE_FORCE_THRESHOLD = 10;
    static final int SCANNER_THRESHOLD = 20;
    /** Stop growing an IP's set well past the threshold; the flag is already raised by then. */
    static final int SCANNER_SET_CAP = 1000;
    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);

    /** Distinct endpoints seen from one IP during {@code minute}, plus the previous minute's count. */
    private record EndpointSet(long minute, Set<String> endpoints, int previousMinuteCount) {
    }

    private final WindowedAggregator aggregator;
    private final Clock clock;
    private final Predicate<String> authRoute;
    private final ConcurrentHashMap<String, EndpointSet> endpointsByIp = new ConcurrentHashMap<>();

    public SecurityAnalyzer(WindowedAggregator aggregator, Clock clock, Predicate<String> authRoute) {
        this.aggregator = aggregator;
        this.clock = clock;
        this.authRoute = authRoute;
        aggregator.addListener(this);
    }

    @Override
    public void onEvent(RequestEvent event) {
        String ip = event.clientIp();
        if (ip == null) {
            return;
        }
        long minute = event.timestamp() / 60_000;
        EndpointSet set = endpointsByIp.get(ip);
        if (set == null || set.minute() < minute) {
            set = endpointsByIp.compute(ip, (k, old) -> {
                if (old != null && old.minute() >= minute) return old;
                int previous = old != null && old.minute() == minute - 1 ? old.endpoints().size() : 0;
                return new EndpointSet(minute, ConcurrentHashMap.newKeySet(), previous);
            });
        }
        // A late event for an older minute is folded into the current one.
        if (set.endpoints().size() < SCANNER_SET_CAP) {
            set.endpoints().add(scanKey(event));
        }
    }

    /** IPs sending more than 30% of all traffic in the window. */
    public List<SuspiciousIp> suspiciousIps(Duration window) {
        List<WindowSnapshot> perIp = aggregator.ipSnapshots(window);
        long total = perIp.stream().mapToLong(WindowSnapshot::totalRequests).sum();
        if (total < DDOS_MIN_TOTAL_REQUESTS) {
            return List.of();
        }
        return perIp.stream()
                .filter(s -> s.totalRequests() * 100.0 / total > DDOS_SHARE_PCT)
                .map(s -> new SuspiciousIp(s.endpoint(), s.totalRequests(),
                        Math.round(s.totalRequests() * 1000.0 / total) / 10.0, "POTENTIAL_DDOS"))
                .sorted(Comparator.comparingInt(SuspiciousIp::requestCount).reversed())
                .toList();
    }

    /** Brute-force and scanner flags for the last minute. */
    public List<Threat> activeThreats() {
        List<Threat> threats = new ArrayList<>();

        for (WindowSnapshot s : aggregator.authFailureSnapshots(ONE_MINUTE)) {
            String key = s.endpoint();
            int sep = key.lastIndexOf('|'); // IPs never contain '|', endpoints might
            String endpoint = key.substring(0, sep);
            if (s.totalRequests() > BRUTE_FORCE_THRESHOLD && authRoute.test(endpoint)) {
                threats.add(new Threat(Threat.ThreatType.BRUTE_FORCE, key.substring(sep + 1), endpoint, s.totalRequests()));
            }
        }

        long nowMinute = clock.millis() / 60_000;
        endpointsByIp.forEach((ip, set) -> {
            int distinct = scannerCount(set, nowMinute);
            if (distinct > SCANNER_THRESHOLD) {
                threats.add(new Threat(Threat.ThreatType.SCANNER, ip, null, distinct));
            }
        });

        threats.sort(Comparator.comparing(Threat::type).thenComparing(Comparator.comparingInt(Threat::count).reversed()));
        return threats;
    }

    /** Highest distinct-endpoint count across the current minute and the last complete one. */
    private static int scannerCount(EndpointSet set, long nowMinute) {
        if (set.minute() == nowMinute) {
            return Math.max(set.endpoints().size(), set.previousMinuteCount());
        }
        return set.minute() == nowMinute - 1 ? set.endpoints().size() : 0;
    }

    /**
     * Matched requests count by route, so a client browsing {@code /orders/1..500} is one target, not
     * 500. Requests no route matched (NOT_FOUND and friends) count by raw path: probing paths that
     * don't exist is exactly what a scanner does.
     */
    static String scanKey(RequestEvent event) {
        return event.endpoint().startsWith("/") ? event.endpoint() : event.path();
    }

    public void evictStaleEndpointSets() {
        long nowMinute = clock.millis() / 60_000;
        endpointsByIp.values().removeIf(set -> set.minute() < nowMinute - 1);
    }
}
