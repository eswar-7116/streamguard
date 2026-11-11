package io.github.eswar7116.streamguard.detection;

import io.github.eswar7116.streamguard.model.WindowSnapshot;

import java.util.function.ToDoubleFunction;

public enum AnomalyType {
    ERROR_SPIKE(WindowSnapshot::errorRate),
    LATENCY_SPIKE(WindowSnapshot::p99Ms),
    TRAFFIC_SPIKE(WindowSnapshot::totalRequests),
    TRAFFIC_DROP(WindowSnapshot::totalRequests),
    // Forecast band; current/previous carry observed/forecast rps
    FORECAST_SPIKE(WindowSnapshot::requestsPerSecond);

    private final ToDoubleFunction<WindowSnapshot> metric;

    AnomalyType(ToDoubleFunction<WindowSnapshot> metric) {
        this.metric = metric;
    }

    /** The value this anomaly compares, e.g. error rate for ERROR_SPIKE. */
    public double metric(WindowSnapshot snapshot) {
        return metric.applyAsDouble(snapshot);
    }
}
