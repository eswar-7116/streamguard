package io.github.eswar7116.streamguard.security;

import io.github.eswar7116.streamguard.TestProperties;
import io.github.eswar7116.streamguard.aggregation.WindowedAggregator;
import io.github.eswar7116.streamguard.config.StreamGuardProperties;
import io.github.eswar7116.streamguard.model.RequestEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityAnalyzerTest {

    private static final long NOW = 1_700_000_040_000L; // 40s into a minute

    private final StreamGuardProperties props = TestProperties.defaults();
    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
    private final WindowedAggregator aggregator = new WindowedAggregator(props, clock);
    // Stands in for the registered auth routes
    private final SecurityAnalyzer analyzer = new SecurityAnalyzer(aggregator, clock,
            route -> route.equals("/api/auth/login") || route.equals("/oauth/token"));

    private void hit(String ip, String endpoint, int status) {
        aggregator.recordEvent(new RequestEvent(NOW - 1000, endpoint, "GET", status, 20, 0, ip));
    }

    @Test
    void flagsIpAboveThirtyPercentOfTraffic() {
        for (int i = 0; i < 70; i++) hit("10.0.0." + (i % 10), "/api/a", 200);
        for (int i = 0; i < 40; i++) hit("6.6.6.6", "/api/a", 200);

        assertThat(analyzer.suspiciousIps(Duration.ofMinutes(1)))
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.ip()).isEqualTo("6.6.6.6");
                    assertThat(s.requestCount()).isEqualTo(40);
                    assertThat(s.percentOfTotal()).isEqualTo(36.4);
                    assertThat(s.flag()).isEqualTo("POTENTIAL_DDOS");
                });
    }

    @Test
    void lowTrafficNeverLooksLikeDdos() {
        for (int i = 0; i < 50; i++) hit("127.0.0.1", "/api/a", 200);

        assertThat(analyzer.suspiciousIps(Duration.ofMinutes(1))).isEmpty();
    }

    @Test
    void flagsBruteForceOnRegisteredAuthRoutesOnly() {
        for (int i = 0; i < 11; i++) hit("1.2.3.4", "/api/auth/login", 401);
        for (int i = 0; i < 10; i++) hit("5.5.5.5", "/oauth/token", 403);  // at threshold, not above
        for (int i = 0; i < 30; i++) hit("1.2.3.4", "/api/admin", 403);    // not a registered auth route
        for (int i = 0; i < 30; i++) hit("7.7.7.7", "/oauth/token", 500);  // not an auth failure

        assertThat(analyzer.activeThreats())
                .containsExactly(new Threat(Threat.ThreatType.BRUTE_FORCE, "1.2.3.4", "/api/auth/login", 11));
    }

    @Test
    void flagsScannerHittingManyDistinctEndpoints() {
        for (int i = 0; i < 21; i++) hit("9.9.9.9", "/probe/" + i, 404);
        for (int i = 0; i < 50; i++) hit("8.8.8.8", "/probe/" + (i % 20), 404); // 20 distinct

        assertThat(analyzer.activeThreats())
                .containsExactly(new Threat(Threat.ThreatType.SCANNER, "9.9.9.9", null, 21));
    }

    @Test
    void scannerCountsRawPathsEvenWhenTheyShareARoute() {
        // Every miss is grouped under the NOT_FOUND route, but the raw paths still differ
        for (int i = 0; i < 21; i++) {
            aggregator.recordEvent(new RequestEvent(NOW - 1000, "NOT_FOUND", "/probe/" + i, "GET", 404, 5, 0, "9.9.9.9"));
        }

        assertThat(analyzer.activeThreats())
                .containsExactly(new Threat(Threat.ThreatType.SCANNER, "9.9.9.9", null, 21));
    }

    @Test
    void browsingManyIdsOfOneRouteIsNotScanning() {
        for (int i = 0; i < 200; i++) {
            aggregator.recordEvent(new RequestEvent(NOW - 1000, "/api/orders/{id}", "/api/orders/" + i, "GET", 200, 5, 0, "10.0.0.5"));
        }

        assertThat(analyzer.activeThreats()).isEmpty();
    }

    @Test
    void securityWindowsCoverOneMinute() {
        assertThatThrownBy(() -> analyzer.suspiciousIps(Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
