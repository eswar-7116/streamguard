package io.github.eswar7116.streamguard.routes;

import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;

/**
 * Compiled form of the registered routes. StreamGuard's own API and dashboard (everything under
 * the base path) are never monitored, so viewing them doesn't pollute the stats they show.
 * The base path must already be canonical: starting with '/', with no trailing '/'.
 */
public final class RouteMatcher {

    private final String basePath;
    private final List<PathPattern> monitored;
    private final List<PathPattern> excluded;
    private final List<PathPattern> auth;

    public RouteMatcher(String basePath, RouteRegistry registry) {
        this.basePath = basePath;
        this.monitored = parse(registry.monitored());
        this.excluded = parse(registry.excluded());
        this.auth = parse(registry.auth());
    }

    /** True if a request to {@code path} (relative to the context path) should be recorded. */
    public boolean shouldMonitor(String path) {
        if (path.equals(basePath) || path.startsWith(basePath + "/")) {
            return false;
        }
        PathContainer container = PathContainer.parsePath(path);
        if (matchesAny(excluded, container)) {
            return false;
        }
        return monitored.isEmpty() || matchesAny(monitored, container);
    }

    /** True if {@code route} (a route template or raw path) is a registered login/token route. */
    public boolean isAuthRoute(String route) {
        return !auth.isEmpty() && route.startsWith("/") && matchesAny(auth, PathContainer.parsePath(route));
    }

    private static boolean matchesAny(List<PathPattern> patterns, PathContainer path) {
        for (PathPattern p : patterns) {
            if (p.matches(path)) {
                return true;
            }
        }
        return false;
    }

    private static List<PathPattern> parse(List<String> patterns) {
        return patterns.stream()
                .filter(p -> p != null && !p.isBlank())
                .map(p -> {
                    try {
                        return PathPatternParser.defaultInstance.parse(p.strip());
                    } catch (RuntimeException e) {
                        throw new IllegalArgumentException("Invalid StreamGuard route pattern '" + p + "': " + e.getMessage(), e);
                    }
                })
                .toList();
    }
}
