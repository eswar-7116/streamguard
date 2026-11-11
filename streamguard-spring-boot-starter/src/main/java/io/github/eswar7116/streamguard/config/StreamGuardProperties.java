package io.github.eswar7116.streamguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * StreamGuard configuration, bound from {@code streamguard.*}.
 *
 * @param enabled   master switch; false leaves the application untouched
 * @param web       the JSON API and dashboard
 * @param routes    which requests are monitored and which are login routes
 * @param windows   sliding-window resolution and retention
 * @param detection closed-window anomaly and availability evaluation
 * @param forecast  EWMA traffic forecasting
 * @param filter    request filter behaviour
 * @param alert     alert webhooks
 */
@ConfigurationProperties("streamguard")
public record StreamGuardProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue Web web,
        @DefaultValue Routes routes,
        @DefaultValue Windows windows,
        @DefaultValue Detection detection,
        @DefaultValue Forecast forecast,
        @DefaultValue Filter filter,
        @DefaultValue Alert alert
) {
    /**
     * @param enabled  serve the JSON API and dashboard
     * @param basePath path the dashboard is served at; the API lives under {@code <base-path>/api}
     */
    public record Web(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("/streamguard") String basePath
    ) {
    }

    /**
     * Path patterns use Spring MVC syntax ({@code /api/**}, {@code /orders/{id}}). Routes can also be
     * registered in code with a {@code StreamGuardConfigurer} bean; both sources are combined.
     *
     * @param include patterns to monitor; empty monitors every request
     * @param exclude patterns never monitored, even if included
     * @param auth    login and token routes checked for brute-force attempts
     */
    public record Routes(
            @DefaultValue List<String> include,
            @DefaultValue List<String> exclude,
            @DefaultValue List<String> auth
    ) {
    }

    /**
     * @param bucketSizeSeconds resolution of the sliding windows
     * @param maxWindowSeconds  longest window that can be queried
     */
    public record Windows(
            @DefaultValue("1") int bucketSizeSeconds,
            @DefaultValue("900") int maxWindowSeconds
    ) {
    }

    /** @param evaluationWindowSeconds length of the closed windows compared for anomalies and availability */
    public record Detection(@DefaultValue("15") int evaluationWindowSeconds) {
    }

    /**
     * @param baseAlpha   resting smoothing factor for the level and deviation (0–1; higher reacts faster)
     * @param spikeSigma  how many deviations above the forecast counts as a spike
     * @param minRequests ignore windows below this volume so small samples don't false-alarm
     */
    public record Forecast(
            @DefaultValue("0.3") double baseAlpha,
            @DefaultValue("3.0") double spikeSigma,
            @DefaultValue("20") int minRequests
    ) {
    }

    /** @param trustForwardedFor read the client IP from X-Forwarded-For; only enable behind a trusted proxy */
    public record Filter(@DefaultValue("false") boolean trustForwardedFor) {
    }

    /**
     * @param webhookUrls         URLs every alert is POSTed to as JSON
     * @param allowRuntimeUpdates allow replacing the webhook list over the HTTP API; off by default
     *                            because anyone who can reach the API could redirect alerts
     */
    public record Alert(
            @DefaultValue List<String> webhookUrls,
            @DefaultValue("false") boolean allowRuntimeUpdates
    ) {
    }
}
