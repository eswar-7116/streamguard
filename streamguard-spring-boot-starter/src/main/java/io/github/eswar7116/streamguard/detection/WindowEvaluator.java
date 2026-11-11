package io.github.eswar7116.streamguard.detection;

import io.github.eswar7116.streamguard.aggregation.WindowedAggregator;
import io.github.eswar7116.streamguard.alerting.AlertPublisher;
import io.github.eswar7116.streamguard.availability.AvailabilityStateMachine;
import io.github.eswar7116.streamguard.config.StreamGuardProperties;
import io.github.eswar7116.streamguard.forecast.TrafficForecaster;
import io.github.eswar7116.streamguard.model.WindowSnapshot;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;

/**
 * Each time an evaluation window closes, compares every route's window with the one before it
 * (anomalies), feeds it to the availability state machine and updates its traffic forecast. The
 * groups for unmatched requests ({@code NOT_FOUND} and friends) are skipped.
 *
 * <p>Windows are tumbling and aligned to the epoch (e.g. :00–:15, :15–:30 for 15s windows), so a
 * late or skipped tick still evaluates the same windows. Evaluation waits one extra bucket after
 * a window closes so that requests finishing right at the boundary have been recorded.
 */
public class WindowEvaluator {

    private final WindowedAggregator aggregator;
    private final AnomalyDetector detector;
    private final AnomalyLog anomalyLog;
    private final AlertPublisher alertPublisher;
    private final AvailabilityStateMachine availability;
    private final TrafficForecaster forecaster;
    private final Clock clock;
    private final Duration window;

    private long lastEvaluatedEndMs;

    public WindowEvaluator(WindowedAggregator aggregator, AnomalyDetector detector, AnomalyLog anomalyLog,
                           AlertPublisher alertPublisher, AvailabilityStateMachine availability,
                           TrafficForecaster forecaster, Clock clock, StreamGuardProperties properties) {
        this.aggregator = aggregator;
        this.detector = detector;
        this.anomalyLog = anomalyLog;
        this.alertPublisher = alertPublisher;
        this.availability = availability;
        this.forecaster = forecaster;
        this.clock = clock;
        this.window = Duration.ofSeconds(properties.detection().evaluationWindowSeconds());
    }

    public void tick() {
        long windowMs = window.toMillis();
        long settled = clock.millis() - aggregator.bucketSize().toMillis();
        long endMs = Math.floorDiv(settled, windowMs) * windowMs;
        if (endMs > lastEvaluatedEndMs) {
            evaluate(endMs);
            lastEvaluatedEndMs = endMs;
        }
    }

    private void detect(long endMs, WindowSnapshot current, WindowSnapshot previous) {
        AnomalyType type = detector.detect(current, previous);
        if (type != null) {
            AnomalyEvent event = new AnomalyEvent(
                    endMs, current.endpoint(), type, type.metric(current), type.metric(previous));
            anomalyLog.add(event);
            alertPublisher.publish(event);
        }
    }

    /** Evaluates the window ending at {@code endMs} against the one ending at {@code endMs - window}. */
    void evaluate(long endMs) {
        for (WindowSnapshot current : aggregator.closedSnapshots(endMs, window)) {
            String endpoint = current.endpoint();
            // NOT_FOUND, REDIRECTION and UNKNOWN group unmatched requests; they aren't services with
            // a normal level to deviate from, and probing is already caught as SCANNER
            if (!endpoint.startsWith("/")) {
                continue;
            }
            // The previous window must be fully observed: after a restart or a route's first request,
            // a partial window would make the next full one look like a traffic spike
            long previousStartMs = endMs - 2 * window.toMillis();
            if (aggregator.firstEventMs(endpoint) <= previousStartMs) {
                aggregator.closedSnapshot(endpoint, endMs - window.toMillis(), window)
                        .ifPresent(previous -> detect(endMs, current, previous));
            }
            availability.evaluate(endpoint, current, endMs);

            // EWMA forecast: update the prediction and alert if traffic overshot the forecast band
            forecaster.observe(endpoint, current).ifPresent(spike -> {
                AnomalyEvent event = new AnomalyEvent(
                        endMs, endpoint, AnomalyType.FORECAST_SPIKE, spike.observedRps(), spike.predictedRps());
                anomalyLog.add(event);
                alertPublisher.publish(event);
            });
        }

        // Endpoints whose windows were evicted as idle no longer need forecast or availability state
        Set<String> live = aggregator.endpoints();
        forecaster.retainEndpoints(live);
        availability.retainEndpoints(live);
    }
}
