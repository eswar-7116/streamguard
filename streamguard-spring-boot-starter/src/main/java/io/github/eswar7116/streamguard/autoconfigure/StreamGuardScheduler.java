package io.github.eswar7116.streamguard.autoconfigure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs StreamGuard's periodic work (window evaluation, eviction) on one daemon thread of its own,
 * so the starter neither needs nor turns on {@code @EnableScheduling} in the host application.
 */
public class StreamGuardScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(StreamGuardScheduler.class);

    private record Task(String name, Runnable action, long periodMs) {
    }

    private final List<Task> tasks = new ArrayList<>();
    private volatile ScheduledExecutorService executor;

    public StreamGuardScheduler every(long periodMs, String name, Runnable action) {
        tasks.add(new Task(name, action, periodMs));
        return this;
    }

    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "streamguard-scheduler");
            t.setDaemon(true);
            return t;
        });
        for (Task task : tasks) {
            executor.scheduleWithFixedDelay(() -> runSafely(task), task.periodMs(), task.periodMs(), TimeUnit.MILLISECONDS);
        }
    }

    // An exception escaping a fixed-delay task would silently cancel all its future runs
    private static void runSafely(Task task) {
        try {
            task.action().run();
        } catch (RuntimeException e) {
            log.error("StreamGuard task '{}' failed", task.name(), e);
        }
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }
}
