package io.github.eswar7116.streamguard.alerting;

import io.github.eswar7116.streamguard.detection.AnomalyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Logs each anomaly as a structured warning and POSTs it as JSON to every registered webhook.
 * The POSTs run on virtual threads so a slow webhook can't stall detection.
 *
 * <p>Webhooks come from {@code streamguard.alert.webhook-urls} and can be replaced at runtime via
 * {@link #setWebhooks} when {@code streamguard.alert.allow-runtime-updates} is on.
 */
public class AlertPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AlertPublisher.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final CopyOnWriteArrayList<String> webhooks = new CopyOnWriteArrayList<>();
    private final RestClient restClient;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** @param builder the application's RestClient builder, so its message converters are reused */
    public AlertPublisher(List<String> webhookUrls, RestClient.Builder builder) {
        webhooks.addAll(clean(webhookUrls));
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        requestFactory.setReadTimeout(TIMEOUT);
        this.restClient = builder.requestFactory(requestFactory).build();
    }

    public AlertPublisher(List<String> webhookUrls) {
        this(webhookUrls, RestClient.builder());
    }

    /** The webhook URLs alerts are currently POSTed to. */
    public List<String> webhooks() {
        return List.copyOf(webhooks);
    }

    /** Replaces the webhook list (blank entries dropped, duplicates removed, order kept). */
    public void setWebhooks(List<String> urls) {
        List<String> cleaned = clean(urls);
        webhooks.clear();
        webhooks.addAll(cleaned);
        log.info("StreamGuard alert webhooks updated: {} endpoint(s)", webhooks.size());
    }

    public void publish(AnomalyEvent event) {
        log.warn("StreamGuard anomaly type={} endpoint={} current={} previous={} timestamp={}",
                event.type(), event.endpoint(),
                String.format("%.2f", event.currentValue()), String.format("%.2f", event.previousValue()),
                event.timestamp());

        for (String webhookUrl : webhooks) {
            executor.execute(() -> {
                try {
                    restClient.post()
                            .uri(webhookUrl)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(event)
                            .retrieve()
                            .toBodilessEntity();
                } catch (RuntimeException e) {
                    log.warn("StreamGuard alert webhook POST to {} failed: {}", webhookUrl, e.getMessage());
                }
            });
        }
    }

    /** Stops accepting alerts and waits for in-flight webhook POSTs. */
    @Override
    public void close() {
        executor.close();
    }

    private static List<String> clean(List<String> urls) {
        return urls == null ? List.of() : urls.stream()
                .filter(u -> u != null && !u.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
    }
}
