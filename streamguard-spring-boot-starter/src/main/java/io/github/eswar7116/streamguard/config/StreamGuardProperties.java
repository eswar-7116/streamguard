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

    /**
     * @param evaluationWindowSeconds        length of the closed windows compared for anomalies and availability
     * @param minRequests                    windows below this volume are not evaluated, so small samples don't false-alarm
     * @param errorRateFloorPct              ERROR_SPIKE compares against the previous error rate, but never one below this
     * @param errorSpikeMinZ                 standard errors a rise must clear before ERROR_SPIKE fires; 0 disables the check
     * @param errorRateThresholdPct          error rate (0–100) that opens an ERROR_RATE_HIGH alert; above 100 disables it
     * @param errorRateLongWindowSeconds     window the threshold must also hold over, so blips don't open the alert
     * @param errorRateRecoveryPct           error rate the route must drop below to resolve the alert
     * @param errorRateResolveWindows        consecutive windows below the recovery rate before the alert resolves
     * @param errorRateRepeatIntervalSeconds how often an open alert is raised again; 0 never repeats
     */
    public record Detection(
            @DefaultValue("15") int evaluationWindowSeconds,
            @DefaultValue("20") int minRequests,
            @DefaultValue("5.0") double errorRateFloorPct,
            @DefaultValue("3.09") double errorSpikeMinZ,
            @DefaultValue("50.0") double errorRateThresholdPct,
            @DefaultValue("180") int errorRateLongWindowSeconds,
            @DefaultValue("25.0") double errorRateRecoveryPct,
            @DefaultValue("2") int errorRateResolveWindows,
            @DefaultValue("14400") long errorRateRepeatIntervalSeconds
    ) {
        public Detection {
            if (minRequests < 1) {
                throw new IllegalArgumentException("min-requests must be >= 1, got " + minRequests);
            }
            if (errorSpikeMinZ < 0) {
                throw new IllegalArgumentException("error-spike-min-z must be >= 0, got " + errorSpikeMinZ);
            }
            if (errorRateRecoveryPct > errorRateThresholdPct) {
                throw new IllegalArgumentException("error-rate-recovery-pct (" + errorRateRecoveryPct
                        + ") must not exceed error-rate-threshold-pct (" + errorRateThresholdPct + ")");
            }
            if (errorRateLongWindowSeconds < evaluationWindowSeconds) {
                throw new IllegalArgumentException("error-rate-long-window-seconds (" + errorRateLongWindowSeconds
                        + ") must be at least evaluation-window-seconds (" + evaluationWindowSeconds + ")");
            }
            if (errorRateResolveWindows < 1) {
                throw new IllegalArgumentException(
                        "error-rate-resolve-windows must be >= 1, got " + errorRateResolveWindows);
            }
            if (errorRateRepeatIntervalSeconds < 0) {
                throw new IllegalArgumentException(
                        "error-rate-repeat-interval-seconds must be >= 0, got " + errorRateRepeatIntervalSeconds);
            }
        }
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
