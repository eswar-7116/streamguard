package io.github.eswar7116.streamguard.availability;

import io.github.eswar7116.streamguard.model.WindowSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the per-endpoint state machine one closed window at a time and asserts the
 * HEALTHY → DEGRADED → DOWN → RECOVERING → HEALTHY lifecycle, plus the availability snapshot.
 */
class AvailabilityStateMachineTest {

    private static final String EP = "/api/orders";
    private static final long NOW = 1_700_000_000_000L;

    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
    private final AvailabilityStateMachine machine = new AvailabilityStateMachine(clock);

    // A window with the given traffic and error rate; latency is irrelevant here.
    private static WindowSnapshot window(int requests, double errorRate) {
        return new WindowSnapshot(EP, 15_000, requests, requests / 15.0, errorRate, 10, 20, 30);
    }

    private static final WindowSnapshot HEALTHY = window(100, 1.0);
    private static final WindowSnapshot FAILING = window(100, 99.0);
    private static final WindowSnapshot SILENT = window(0, 0.0);

    private EndpointState feed(WindowSnapshot w) {
        return machine.evaluate(EP, w, NOW);
    }

    @Test
    void startsHealthyAndStaysHealthyWhileServing() {
        assertThat(feed(HEALTHY)).isEqualTo(EndpointState.HEALTHY);
        assertThat(feed(HEALTHY)).isEqualTo(EndpointState.HEALTHY);
    }

    @Test
    void oneFailingWindowDegrades() {
        feed(HEALTHY);
        assertThat(feed(FAILING)).isEqualTo(EndpointState.DEGRADED);
    }

    @Test
    void aSilentWindowChangesNothing() {
        // In-process, no requests means no clients, not an outage
        feed(HEALTHY);
        assertThat(feed(SILENT)).isEqualTo(EndpointState.HEALTHY);
        feed(FAILING);
        assertThat(feed(SILENT)).isEqualTo(EndpointState.DEGRADED);
    }

    @Test
    void silentWindowsDontBreakAFailingStreak() {
        feed(HEALTHY);
        feed(FAILING);
        feed(SILENT);
        feed(FAILING);
        assertThat(feed(FAILING)).isEqualTo(EndpointState.DOWN);
    }

    @Test
    void threeConsecutiveFailingWindowsGoDown() {
        feed(HEALTHY);
        assertThat(feed(FAILING)).isEqualTo(EndpointState.DEGRADED);
        assertThat(feed(FAILING)).isEqualTo(EndpointState.DEGRADED);
        assertThat(feed(FAILING)).isEqualTo(EndpointState.DOWN);
    }

    @Test
    void degradedRecoversStraightToHealthyOnAGoodWindow() {
        feed(HEALTHY);
        feed(FAILING); // DEGRADED
        assertThat(feed(HEALTHY)).isEqualTo(EndpointState.HEALTHY);
    }

    @Test
    void downStaysDownWhileSilent() {
        feed(HEALTHY);
        feed(FAILING);
        feed(FAILING);
        feed(FAILING); // DOWN
        assertThat(feed(SILENT)).isEqualTo(EndpointState.DOWN);
    }

    @Test
    void fullLifecycleBackToHealthy() {
        feed(HEALTHY);
        feed(FAILING);
        feed(FAILING);
        assertThat(feed(FAILING)).isEqualTo(EndpointState.DOWN);

        // Traffic with errorRate < 50% moves DOWN -> RECOVERING.
        assertThat(feed(window(100, 30.0))).isEqualTo(EndpointState.RECOVERING);
        // Two windows with errorRate < 10% promote RECOVERING -> HEALTHY.
        assertThat(feed(window(100, 2.0))).isEqualTo(EndpointState.RECOVERING);
        assertThat(feed(window(100, 2.0))).isEqualTo(EndpointState.HEALTHY);
    }

    @Test
    void recoveringFallsBackToDownOnAFailingWindow() {
        feed(HEALTHY);
        feed(FAILING);
        feed(FAILING);
        feed(FAILING); // DOWN
        feed(window(100, 30.0)); // RECOVERING
        assertThat(feed(FAILING)).isEqualTo(EndpointState.DOWN);
    }

    @Test
    void availabilityIsEmptyUntilAnEndpointIsSeen() {
        assertThat(machine.availability("/never")).isEmpty();

        feed(HEALTHY);
        assertThat(machine.availability(EP)).hasValueSatisfying(a -> {
            assertThat(a.endpoint()).isEqualTo(EP);
            assertThat(a.state()).isEqualTo(EndpointState.HEALTHY);
            assertThat(a.durationMs()).isGreaterThanOrEqualTo(0);
        });
    }
}
