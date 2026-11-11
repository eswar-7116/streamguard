package io.github.eswar7116.streamguard.detection;


import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** The most recent {@value #CAPACITY} anomalies; the oldest is dropped when full. */
public class AnomalyLog {

    static final int CAPACITY = 100;

    private final ArrayDeque<AnomalyEvent> events = new ArrayDeque<>(CAPACITY);

    public synchronized void add(AnomalyEvent event) {
        if (events.size() == CAPACITY) {
            events.pollFirst();
        }
        events.addLast(event);
    }

    /** Newest first. */
    public synchronized List<AnomalyEvent> recent() {
        return new ArrayList<>(events.reversed());
    }
}
