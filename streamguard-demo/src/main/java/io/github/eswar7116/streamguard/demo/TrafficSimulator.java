package io.github.eswar7116.streamguard.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Sends real HTTP requests to this application so the dashboard has live data: steady shop traffic
 * from 25 simulated clients, plus a scripted incident every 40 seconds (an error spike, a brute-force
 * burst or a path scanner). Client IPs are simulated with X-Forwarded-For, which the demo trusts.
 *
 * <p>Disable with {@code demo.traffic.enabled=false} to drive the API by hand.
 */
@Component
@ConditionalOnBooleanProperty(name = "demo.traffic.enabled", matchIfMissing = true)
class TrafficSimulator implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TrafficSimulator.class);
    private static final List<String> SCAN_PATHS = List.of(
            "/.env", "/wp-admin", "/wp-login.php", "/admin", "/phpmyadmin", "/.git/config", "/config.json",
            "/server-status", "/api/v1/debug", "/backup.zip", "/actuator/env", "/console", "/login.php",
            "/.aws/credentials", "/xmlrpc.php", "/cgi-bin/test", "/api/swagger.json", "/db.sql", "/old",
            "/test", "/shell", "/upload", "/.DS_Store", "/web.config", "/vendor/phpunit");

    private final FaultInjector faults;
    private final RestClient http = RestClient.create();
    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
    private volatile ScheduledExecutorService scheduler;
    private volatile String baseUrl;
    private int incident;

    TrafficSimulator(FaultInjector faults) {
        this.faults = faults;
    }

    @EventListener
    void onServerStarted(WebServerInitializedEvent event) {
        baseUrl = "http://localhost:" + event.getWebServer().getPort();
    }

    @Override
    public synchronized void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "demo-traffic"));
        scheduler.scheduleWithFixedDelay(this::burst, 1_000, 100, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::nextIncident, 40, 40, TimeUnit.SECONDS);
        log.info("Demo traffic simulator started; disable with demo.traffic.enabled=false");
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        requests.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }

    private void burst() {
        if (baseUrl == null) {
            return;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0, n = rnd.nextInt(4, 10); i < n; i++) {
            String client = "10.0.0." + rnd.nextInt(1, 26);
            switch (rnd.nextInt(10)) {
                case 0, 1, 2 -> send(HttpMethod.GET, "/api/products", client);
                case 3, 4 -> send(HttpMethod.GET, "/api/products/" + rnd.nextInt(1, 1100), client);
                case 5, 6 -> send(HttpMethod.GET, "/api/orders/" + rnd.nextInt(1, 500), client);
                case 7 -> send(HttpMethod.POST, "/api/orders", client);
                case 8 -> send(HttpMethod.POST, "/api/payments", client);
                default -> send(HttpMethod.POST, "/api/auth/login?user=" + (rnd.nextInt(15) == 0 ? "typo" : "demo"), client);
            }
        }
    }

    private void nextIncident() {
        switch (incident++ % 3) {
            case 0 -> {
                String route = List.of("/api/products", "/api/orders/{id}", "/api/payments")
                        .get(ThreadLocalRandom.current().nextInt(3));
                log.info("Demo incident: 80% of {} requests fail for 30s", route);
                faults.fail(route, 0.8, 30_000);
            }
            case 1 -> {
                log.info("Demo incident: brute-force login attempts from 203.0.113.7");
                for (int i = 0; i < 20; i++) {
                    send(HttpMethod.POST, "/api/auth/login?user=admin" + i, "203.0.113.7");
                }
            }
            default -> {
                log.info("Demo incident: path scan from 198.51.100.23");
                SCAN_PATHS.forEach(p -> send(HttpMethod.GET, p, "198.51.100.23"));
            }
        }
    }

    private void send(HttpMethod method, String path, String clientIp) {
        requests.execute(() -> {
            try {
                // exchange() hands back error statuses instead of throwing; they're the point
                http.method(method).uri(baseUrl + path)
                        .header("X-Forwarded-For", clientIp)
                        .exchange((req, res) -> res.getStatusCode());
            } catch (RuntimeException e) {
                log.debug("Demo request {} {} failed: {}", method, path, e.getMessage());
            }
        });
    }
}
