package io.github.eswar7116.streamguard.detection;

public record AnomalyEvent(
        long timestamp,
        String endpoint,
        AnomalyType type,
        double currentValue,
        double previousValue
) {
}
