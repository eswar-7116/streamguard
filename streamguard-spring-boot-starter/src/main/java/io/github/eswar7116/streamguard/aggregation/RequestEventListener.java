package io.github.eswar7116.streamguard.aggregation;

import io.github.eswar7116.streamguard.model.RequestEvent;

/** Gets every event the aggregator records, for consumers that need more than windowed counters. */
@FunctionalInterface
public interface RequestEventListener {
    void onEvent(RequestEvent event);
}
