package io.github.eswar7116.streamguard.filter;

import io.github.eswar7116.streamguard.aggregation.WindowedAggregator;
import io.github.eswar7116.streamguard.model.RequestEvent;
import io.github.eswar7116.streamguard.routes.RouteMatcher;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.TimeUnit;

/**
 * Times every monitored request and records it straight into the aggregator: same process, no queue.
 * Runs near the front of the chain so the latency covers the other filters too.
 *
 * <p>Requests are keyed by their Spring MVC route template ({@code /orders/{id}}, not
 * {@code /orders/42}), so per-ID paths don't each become an endpoint. Requests no handler matched
 * are grouped as {@code NOT_FOUND}, {@code REDIRECTION} or {@code UNKNOWN}, except registered auth
 * routes, which keep their path so logins handled by a security filter are still attributed.
 */
public class RequestMetricsFilter extends OncePerRequestFilter {

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    static final String NOT_FOUND = "NOT_FOUND";
    static final String REDIRECTION = "REDIRECTION";
    static final String UNKNOWN = "UNKNOWN";

    private final WindowedAggregator aggregator;
    private final RouteMatcher routes;
    private final Clock clock;
    private final boolean trustForwardedFor;

    public RequestMetricsFilter(WindowedAggregator aggregator, RouteMatcher routes, Clock clock,
                                boolean trustForwardedFor) {
        this.aggregator = aggregator;
        this.routes = routes;
        this.clock = clock;
        this.trustForwardedFor = trustForwardedFor;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = pathWithinApplication(request);
        if (!routes.shouldMonitor(path)) {
            chain.doFilter(request, response);
            return;
        }

        long start = System.nanoTime();
        boolean failed = true;
        try {
            chain.doFilter(request, response);
            failed = false;
        } finally {
            if (!failed && request.isAsyncStarted()) {
                // The handler is still running; record when the async request completes
                request.getAsyncContext().addListener(new CompletionListener(request, response, path, start));
            } else {
                record(request, response, path, start, failed);
            }
        }
    }

    private void record(HttpServletRequest request, HttpServletResponse response, String path, long startNanos,
                        boolean failed) {
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        // An exception escaping the chain becomes a 500, but the status isn't set yet
        int status = failed ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus();
        aggregator.recordEvent(new RequestEvent(
                clock.millis(),
                route(request, path, status),
                path,
                request.getMethod(),
                status,
                latencyMs,
                Math.max(0, request.getContentLengthLong()),
                clientIp(request)));
    }

    String route(HttpServletRequest request, String path, int status) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String p && !p.isEmpty() && !p.equals("/**")) {
            return p;
        }
        if (routes.isAuthRoute(path)) {
            return path;
        }
        if (status == HttpServletResponse.SC_NOT_FOUND) {
            return NOT_FOUND;
        }
        if (status >= 300 && status < 400) {
            return REDIRECTION;
        }
        // "/**" is the static resource handler's catch-all
        return pattern instanceof String p && !p.isEmpty() ? p : UNKNOWN;
    }

    private String clientIp(HttpServletRequest request) {
        // Trust the first X-Forwarded-For hop only when behind a known proxy; the header is spoofable
        if (trustForwardedFor) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",", 2)[0].strip();
            }
        }
        return request.getRemoteAddr();
    }

    static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length())
                : uri;
        return path.isEmpty() ? "/" : path;
    }

    private final class CompletionListener implements AsyncListener {

        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final String path;
        private final long startNanos;

        CompletionListener(HttpServletRequest request, HttpServletResponse response, String path, long startNanos) {
            this.request = request;
            this.response = response;
            this.path = path;
            this.startNanos = startNanos;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            record(request, response, path, startNanos, false);
        }

        @Override
        public void onTimeout(AsyncEvent event) {
        }

        @Override
        public void onError(AsyncEvent event) {
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            // A re-dispatch that starts async again replaces the listeners; re-register
            event.getAsyncContext().addListener(this);
        }
    }
}
