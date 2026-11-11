package io.github.eswar7116.streamguard.availability;

import io.github.eswar7116.streamguard.model.WindowSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-endpoint HEALTHY → DEGRADED → DOWN → RECOVERING → HEALTHY, fed one closed window at a time.
 *
 * <p>A window is "failing" when its error rate is above 95%. A window with no requests changes
 * nothing: StreamGuard runs inside the application, so silence means no clients, not an outage.
 * <ul>
 *   <li>HEALTHY → DEGRADED on 1 failing window.</li>
 *   <li>DEGRADED → DOWN on the 3rd consecutive failing window; back to HEALTHY on a non-failing one.</li>
 *   <li>DOWN → RECOVERING once a window has traffic and errorRate &lt; 50%. A silent endpoint stays DOWN.</li>
 *   <li>RECOVERING → HEALTHY after 2 consecutive windows with traffic and errorRate &lt; 10%;
 *       back to DOWN on a failing window.</li>
 * </ul>
 * The spec doesn't cover DEGRADED or RECOVERING followed by a good window; the choices above
 * are this class's own.
 */
public class AvailabilityStateMachine {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityStateMachine.class);

    static final double FAILING_ERROR_RATE = 95.0;
    static final int WINDOWS_TO_DOWN = 3;
    static final double RECOVERING_ERROR_RATE = 50.0;
    static final double HEALTHY_ERROR_RATE = 10.0;
    static final int WINDOWS_TO_HEALTHY = 2;

    /** {@code streak} counts consecutive windows toward the next transition. */
    private record Status(EndpointState state, long lastTransitionMs, int streak) {
    }

    private final ConcurrentHashMap<String, Status> statuses = new ConcurrentHashMap<>();
    private final Clock clock;

    public AvailabilityStateMachine(Clock clock) {
        this.clock = clock;
    }

    /** Feeds the next closed window for {@code endpoint}; {@code windowEndMs} timestamps any transition. */
    public EndpointState evaluate(String endpoint, WindowSnapshot window, long windowEndMs) {
        Status updated = statuses.compute(endpoint, (k, current) -> {
            Status s = current != null ? current : new Status(EndpointState.HEALTHY, windowEndMs, 0);
            Status next = next(s, window, windowEndMs);
            if (next.state() != s.state()) {
                log.info("availability endpoint={} {} -> {} errorRate={} requests={}",
                        endpoint, s.state(), next.state(),
                        String.format("%.1f", window.errorRate()), window.totalRequests());
            }
            return next;
        });
        return updated.state();
    }

    private static Status next(Status s, WindowSnapshot w, long at) {
        if (w.totalRequests() == 0) {
            return s;
        }
        boolean failing = w.errorRate() > FAILING_ERROR_RATE;

        return switch (s.state()) {
            case HEALTHY -> failing ? new Status(EndpointState.DEGRADED, at, 1) : s;
            case DEGRADED -> {
                if (!failing) yield new Status(EndpointState.HEALTHY, at, 0);
                int streak = s.streak() + 1;
                yield streak >= WINDOWS_TO_DOWN
                        ? new Status(EndpointState.DOWN, at, 0)
                        : new Status(EndpointState.DEGRADED, s.lastTransitionMs(), streak);
            }
            case DOWN -> w.errorRate() < RECOVERING_ERROR_RATE
                    ? new Status(EndpointState.RECOVERING, at, 0)
                    : s;
            case RECOVERING -> {
                if (failing) yield new Status(EndpointState.DOWN, at, 0);
                if (w.errorRate() >= HEALTHY_ERROR_RATE) yield new Status(EndpointState.RECOVERING, s.lastTransitionMs(), 0);
                int streak = s.streak() + 1;
                yield streak >= WINDOWS_TO_HEALTHY
                        ? new Status(EndpointState.HEALTHY, at, 0)
                        : new Status(EndpointState.RECOVERING, s.lastTransitionMs(), streak);
            }
        };
    }

    public Optional<EndpointAvailability> availability(String endpoint) {
        Status s = statuses.get(endpoint);
        return s == null ? Optional.empty() : Optional.of(toAvailability(endpoint, s, clock.millis()));
    }

    /** Every evaluated endpoint, sorted by endpoint. */
    public List<EndpointAvailability> all() {
        long now = clock.millis();
        return statuses.entrySet().stream()
                .map(e -> toAvailability(e.getKey(), e.getValue(), now))
                .sorted(Comparator.comparing(EndpointAvailability::endpoint))
                .toList();
    }

    /** Drops state for endpoints not in {@code live}, so one-off URLs don't accumulate. */
    public void retainEndpoints(Set<String> live) {
        statuses.keySet().retainAll(live);
    }

    private static EndpointAvailability toAvailability(String endpoint, Status s, long now) {
        return new EndpointAvailability(endpoint, s.state(), Instant.ofEpochMilli(s.lastTransitionMs()),
                Math.max(0, now - s.lastTransitionMs()));
    }
}
