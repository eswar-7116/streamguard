package io.github.eswar7116.streamguard.routes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Collects the path patterns StreamGuard monitors. Patterns use Spring MVC syntax, e.g.
 * {@code /api/**} or {@code /orders/{id}}.
 *
 * <ul>
 *   <li>{@link #monitor} limits monitoring to matching requests. With no monitor patterns, every
 *       request is monitored.</li>
 *   <li>{@link #exclude} skips matching requests, even ones a monitor pattern covers.</li>
 *   <li>{@link #authRoutes} marks login and token routes, where repeated 401/403 responses from one
 *       client are reported as brute force. Brute-force detection is off until one is registered.</li>
 * </ul>
 */
public final class RouteRegistry {

    private final List<String> monitored = new ArrayList<>();
    private final List<String> excluded = new ArrayList<>();
    private final List<String> auth = new ArrayList<>();

    public RouteRegistry monitor(String... patterns) {
        monitored.addAll(Arrays.asList(patterns));
        return this;
    }

    public RouteRegistry exclude(String... patterns) {
        excluded.addAll(Arrays.asList(patterns));
        return this;
    }

    public RouteRegistry authRoutes(String... patterns) {
        auth.addAll(Arrays.asList(patterns));
        return this;
    }

    public List<String> monitored() {
        return List.copyOf(monitored);
    }

    public List<String> excluded() {
        return List.copyOf(excluded);
    }

    public List<String> auth() {
        return List.copyOf(auth);
    }
}
