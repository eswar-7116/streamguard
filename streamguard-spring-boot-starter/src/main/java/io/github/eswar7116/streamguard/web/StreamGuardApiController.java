package io.github.eswar7116.streamguard.web;

import io.github.eswar7116.streamguard.aggregation.WindowedAggregator;
import io.github.eswar7116.streamguard.alerting.AlertPublisher;
import io.github.eswar7116.streamguard.availability.AvailabilityStateMachine;
import io.github.eswar7116.streamguard.availability.EndpointAvailability;
import io.github.eswar7116.streamguard.config.StreamGuardProperties;
import io.github.eswar7116.streamguard.detection.AnomalyEvent;
import io.github.eswar7116.streamguard.detection.AnomalyLog;
import io.github.eswar7116.streamguard.forecast.TrafficForecast;
import io.github.eswar7116.streamguard.forecast.TrafficForecaster;
import io.github.eswar7116.streamguard.model.WindowSnapshot;
import io.github.eswar7116.streamguard.security.SecurityAnalyzer;
import io.github.eswar7116.streamguard.security.SuspiciousIp;
import io.github.eswar7116.streamguard.security.Threat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * StreamGuard's JSON API, under {@code <streamguard.web.base-path>/api}. Collection endpoints that
 * take an optional {@code route} parameter return just that route (or an empty list), so every
 * response has the same shape.
 */
@RestController
@RequestMapping("${streamguard.web.base-path:/streamguard}/api")
public class StreamGuardApiController {

    private final WindowedAggregator aggregator;
    private final AnomalyLog anomalyLog;
    private final AvailabilityStateMachine availability;
    private final SecurityAnalyzer securityAnalyzer;
    private final TrafficForecaster forecaster;
    private final AlertPublisher alertPublisher;
    private final StreamGuardProperties properties;

    public StreamGuardApiController(WindowedAggregator aggregator, AnomalyLog anomalyLog,
                                    AvailabilityStateMachine availability, SecurityAnalyzer securityAnalyzer,
                                    TrafficForecaster forecaster, AlertPublisher alertPublisher,
                                    StreamGuardProperties properties) {
        this.aggregator = aggregator;
        this.anomalyLog = anomalyLog;
        this.availability = availability;
        this.securityAnalyzer = securityAnalyzer;
        this.forecaster = forecaster;
        this.alertPublisher = alertPublisher;
        this.properties = properties;
    }

    /** Window settings the dashboard needs to label and align its charts. */
    @GetMapping("/info")
    public Map<String, Object> info() {
        return Map.of(
                "evaluationWindowSeconds", properties.detection().evaluationWindowSeconds(),
                "maxWindowSeconds", properties.windows().maxWindowSeconds(),
                "securityWindowSeconds", WindowedAggregator.SECURITY_WINDOW.toSeconds());
    }

    @GetMapping("/routes")
    public List<WindowSnapshot> routes(@RequestParam(name = "window", defaultValue = "1m") String window,
                                       @RequestParam(name = "route", required = false) String route) {
        if (route != null) {
            return asList(aggregator.snapshot(route, WindowParam.parse(window)));
        }
        return aggregator.snapshotAll(WindowParam.parse(window));
    }

    @GetMapping("/routes/top")
    public Map<String, Integer> top(@RequestParam(name = "window", defaultValue = "5m") String window,
                                    @RequestParam(name = "limit", defaultValue = "5") int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        return aggregator.topEndpoints(WindowParam.parse(window), limit);
    }

    @GetMapping("/anomalies")
    public List<AnomalyEvent> anomalies() {
        return anomalyLog.recent();
    }

    @GetMapping("/availability")
    public List<EndpointAvailability> availability(@RequestParam(name = "route", required = false) String route) {
        return route != null ? asList(availability.availability(route)) : availability.all();
    }

    @GetMapping("/forecast")
    public List<TrafficForecast> forecast(@RequestParam(name = "route", required = false) String route) {
        return route != null ? asList(forecaster.forecast(route)) : forecaster.forecasts();
    }

    @GetMapping("/security/suspicious-ips")
    public List<SuspiciousIp> suspiciousIps(@RequestParam(name = "window", defaultValue = "1m") String window) {
        return securityAnalyzer.suspiciousIps(WindowParam.parse(window));
    }

    @GetMapping("/security/threats")
    public List<Threat> threats() {
        return securityAnalyzer.activeThreats();
    }

    @GetMapping("/alerts/webhooks")
    public List<String> webhooks() {
        return alertPublisher.webhooks();
    }

    @PutMapping("/alerts/webhooks")
    public ResponseEntity<?> setWebhooks(@RequestBody List<String> urls) {
        if (!properties.alert().allowRuntimeUpdates()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                    "Runtime webhook updates are disabled; set streamguard.alert.allow-runtime-updates=true"));
        }
        alertPublisher.setWebhooks(urls);
        return ResponseEntity.ok(alertPublisher.webhooks());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private static <T> List<T> asList(Optional<T> value) {
        return value.map(List::of).orElse(List.of());
    }
}
