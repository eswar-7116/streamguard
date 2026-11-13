package io.github.eswar7116.streamguard.detection;

import io.github.eswar7116.streamguard.TestProperties;
import io.github.eswar7116.streamguard.aggregation.WindowedAggregator;
import io.github.eswar7116.streamguard.alerting.AlertPublisher;
import io.github.eswar7116.streamguard.availability.AvailabilityStateMachine;
import io.github.eswar7116.streamguard.availability.EndpointState;
import io.github.eswar7116.streamguard.config.StreamGuardProperties;
import io.github.eswar7116.streamguard.forecast.TrafficForecaster;
import io.github.eswar7116.streamguard.model.RequestEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WindowEvaluatorTest {

    private static final long T0 = 1_700_000_010_000L; // aligned to 15s windows

    private final StreamGuardProperties props = TestProperties.defaults();
    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(T0 + 31_000), ZoneOffset.UTC);
    private final WindowedAggregator aggregator = new WindowedAggregator(props, clock);
    private final AnomalyLog log = new AnomalyLog();
    private final AvailabilityStateMachine availability = new AvailabilityStateMachine(clock);
    private final TrafficForecaster forecaster = new TrafficForecaster(props);
    private final WindowEvaluator evaluator = new WindowEvaluator(
            aggregator, new AnomalyDetector(props.detection()), new ErrorRateMonitor(props.detection()), log, new AlertPublisher(List.of()), availability,
            forecaster, clock, props);

    private void traffic(long windowStart, int requests, double errorShare) {
        for (int i = 0; i < requests; i++) {
            int status = i < requests * errorShare ? 500 : 200;
            aggregator.recordEvent(new RequestEvent(windowStart + (i % 15) * 1000L, "/api/orders", "GET", status, 50, 0, "10.0.0.1"));
        }
    }

    @Test
    void comparesConsecutiveClosedWindowsAndLogsSpike() {
        // 2% -> 20%: a relative spike that stays under the 50% alert threshold
        traffic(T0, 100, 0.02);
        traffic(T0 + 15_000, 100, 0.20);

        evaluator.evaluate(T0 + 30_000);

        assertThat(log.recent()).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo(AnomalyType.ERROR_SPIKE);
            assertThat(e.endpoint()).isEqualTo("/api/orders");
            assertThat(e.currentValue()).isEqualTo(20.0);
            assertThat(e.previousValue()).isEqualTo(2.0);
            assertThat(e.timestamp()).isEqualTo(T0 + 30_000);
        });
        assertThat(availability.availability("/api/orders").orElseThrow().state()).isEqualTo(EndpointState.HEALTHY);
    }

    @Test
    void sustainedFailureIsReportedOnceThenResolved() {
        // Windows: 2%, then 80% x4, then 5% x2, all within the 180s long window
        int[] errorsPerWindow = {2, 80, 80, 80, 80, 5, 5};
        Clock late = Clock.fixed(Instant.ofEpochMilli(T0 + 121_000), ZoneOffset.UTC);
        WindowedAggregator agg = new WindowedAggregator(props, late);
        WindowEvaluator ev = new WindowEvaluator(
                agg, new AnomalyDetector(props.detection()), new ErrorRateMonitor(props.detection()), log,
                new AlertPublisher(List.of()), availability, forecaster, late, props);
        for (int w = 0; w < errorsPerWindow.length; w++) {
            for (int i = 0; i < 100; i++) {
                int status = i < errorsPerWindow[w] ? 500 : 200;
                agg.recordEvent(new RequestEvent(T0 + w * 15_000L + (i % 15) * 1000L, "/api/orders", "GET", status, 50, 0, "10.0.0.1"));
            }
        }

        for (int w = 1; w <= errorsPerWindow.length; w++) {
            ev.evaluate(T0 + w * 15_000L);
        }

        // Jump is an ERROR_SPIKE, the next 80% window lifts the long window to 54% and opens the alert, two 5% windows resolve it
        assertThat(log.recent().reversed()).extracting(AnomalyEvent::type).containsExactly(
                AnomalyType.ERROR_SPIKE, AnomalyType.ERROR_RATE_HIGH, AnomalyType.ERROR_RATE_RESOLVED);
    }

    @Test
    void rejectsLongWindowBeyondRetention() {
        StreamGuardProperties tooLong = TestProperties.of(Map.of(
                "streamguard.windows.max-window-seconds", "120"));
        WindowedAggregator agg = new WindowedAggregator(tooLong, clock);
        assertThatThrownBy(() -> new WindowEvaluator(
                agg, new AnomalyDetector(tooLong.detection()), new ErrorRateMonitor(tooLong.detection()), log,
                new AlertPublisher(List.of()), availability, forecaster, clock, tooLong))
                .hasMessageContaining("must not exceed streamguard.windows.max-window-seconds (120)");
    }

    @Test
    void endpointFirstSeenAfterTheWindowIsNotEvaluated() {
        aggregator.recordEvent(new RequestEvent(T0 + 30_500, "/api/new", "GET", 200, 10, 0, "10.0.0.1"));

        evaluator.evaluate(T0 + 30_000);

        assertThat(availability.availability("/api/new")).isEmpty();
    }

    @Test
    void dropsForecastAndAvailabilityStateForEvictedEndpoints() {
        traffic(T0, 100, 0.02);
        traffic(T0 + 15_000, 100, 0.02);
        evaluator.evaluate(T0 + 30_000);
        assertThat(availability.availability("/api/orders")).isPresent();
        assertThat(forecaster.forecast("/api/orders")).isPresent();

        // 16 minutes later the idle window is evicted, and the next evaluation forgets the endpoint
        Clock later = Clock.offset(clock, Duration.ofMinutes(16));
        WindowedAggregator agg = new WindowedAggregator(props, later);
        WindowEvaluator lateEvaluator = new WindowEvaluator(
                agg, new AnomalyDetector(props.detection()), new ErrorRateMonitor(props.detection()), log, new AlertPublisher(List.of()), availability, forecaster, later, props);
        lateEvaluator.evaluate(T0 + 30_000 + 16 * 60_000);

        assertThat(availability.availability("/api/orders")).isEmpty();
        assertThat(forecaster.forecast("/api/orders")).isEmpty();
    }

    @Test
    void doesNotCompareAgainstAPartiallyObservedWindow() {
        // First seen halfway through the previous window, as after a restart: 7 requests, then 100
        for (int i = 0; i < 7; i++) {
            aggregator.recordEvent(new RequestEvent(T0 + 8_000 + i * 1000L, "/api/orders", "GET", 200, 50, 0, "10.0.0.1"));
        }
        traffic(T0 + 15_000, 100, 0.0);

        evaluator.evaluate(T0 + 30_000);

        assertThat(log.recent()).isEmpty();
    }

    @Test
    void skipsGroupsOfUnmatchedRequests() {
        // A scan ending looks like a traffic drop on NOT_FOUND; that's not an incident
        for (int i = 0; i < 30; i++) {
            aggregator.recordEvent(new RequestEvent(T0 + (i % 15) * 1000L, "NOT_FOUND", "/probe/" + i, "GET", 404, 5, 0, "6.6.6.6"));
        }

        evaluator.evaluate(T0 + 30_000);

        assertThat(log.recent()).isEmpty();
        assertThat(availability.availability("NOT_FOUND")).isEmpty();
        assertThat(forecaster.forecast("NOT_FOUND")).isEmpty();
    }

    @Test
    void tickEvaluatesEachClosedWindowOnce() {
        traffic(T0, 100, 0.02);
        traffic(T0 + 15_000, 100, 0.80);

        evaluator.tick();
        evaluator.tick();

        assertThat(log.recent()).hasSize(1);
    }
}
