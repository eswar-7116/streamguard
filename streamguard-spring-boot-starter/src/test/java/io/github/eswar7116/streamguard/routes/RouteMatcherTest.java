package io.github.eswar7116.streamguard.routes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteMatcherTest {

    @Test
    void monitorsEverythingWhenNoRoutesAreRegistered() {
        RouteMatcher m = new RouteMatcher("/streamguard", new RouteRegistry());

        assertThat(m.shouldMonitor("/api/orders")).isTrue();
        assertThat(m.shouldMonitor("/")).isTrue();
    }

    @Test
    void neverMonitorsItsOwnApiAndDashboard() {
        RouteMatcher m = new RouteMatcher("/streamguard", new RouteRegistry().monitor("/**"));

        assertThat(m.shouldMonitor("/streamguard")).isFalse();
        assertThat(m.shouldMonitor("/streamguard/")).isFalse();
        assertThat(m.shouldMonitor("/streamguard/api/routes")).isFalse();
        assertThat(m.shouldMonitor("/streamguardian")).as("only a path-segment prefix").isTrue();
    }

    @Test
    void monitorPatternsLimitAndExcludesWin() {
        RouteMatcher m = new RouteMatcher("/streamguard", new RouteRegistry()
                .monitor("/api/**")
                .exclude("/api/health", "/api/internal/**"));

        assertThat(m.shouldMonitor("/api/orders/42")).isTrue();
        assertThat(m.shouldMonitor("/api/health")).isFalse();
        assertThat(m.shouldMonitor("/api/internal/cache")).isFalse();
        assertThat(m.shouldMonitor("/assets/app.js")).isFalse();
    }

    @Test
    void authRoutesMatchTemplatesAndPaths() {
        RouteMatcher m = new RouteMatcher("/streamguard", new RouteRegistry().authRoutes("/api/auth/**", "/login"));

        assertThat(m.isAuthRoute("/login")).isTrue();
        assertThat(m.isAuthRoute("/api/auth/token")).isTrue();
        assertThat(m.isAuthRoute("/api/orders/{id}")).isFalse();
        assertThat(m.isAuthRoute("NOT_FOUND")).isFalse();
    }

    @Test
    void noAuthRoutesMeansNoBruteForceChecks() {
        assertThat(new RouteMatcher("/streamguard", new RouteRegistry()).isAuthRoute("/login")).isFalse();
    }

    @Test
    void rejectsInvalidPatternsWithTheOffendingPattern() {
        assertThatThrownBy(() -> new RouteMatcher("/streamguard", new RouteRegistry().monitor("/api/{unclosed")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/api/{unclosed");
    }
}
