package io.github.eswar7116.streamguard.forecast;

import io.github.eswar7116.streamguard.config.StreamGuardProperties;
import io.github.eswar7116.streamguard.model.WindowSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps one {@link TrafficPredictor} per endpoint and drives it from closed windows.
 *
 * <p>Each closed window, {@link #observe} checks the observed request rate against the predictor's
 * prior forecast band (flagging a spike) and then folds it in to update the prediction for the next
 * window. The spike check runs before the update, so it compares the observation against a genuine
 * prior forecast. Each predictor is guarded by its own lock, so the scheduled evaluator and the API
 * can touch different endpoints concurrently.
 */
public class TrafficForecaster {

    private final double baseAlpha;
    private final double spikeSigma;
    private final int minRequests;
    private final double windowSeconds;

    private final Map<String, TrafficPredictor> predictors = new ConcurrentHashMap<>();

    public TrafficForecaster(StreamGuardProperties properties) {
        StreamGuardProperties.Forecast cfg = properties.forecast();
        this.baseAlpha = cfg.baseAlpha();
        this.spikeSigma = cfg.spikeSigma();
        this.minRequests = cfg.minRequests();
        this.windowSeconds = properties.detection().evaluationWindowSeconds();
    }

    /** A detected overshoot of the forecast band. */
    public record Spike(String endpoint, double observedRps, double predictedRps, double thresholdRps) {
    }

    /**
     * Folds one window's observed rate into the endpoint's predictor and returns a spike if the
     * observation overshot the prior band. Call once per endpoint per closed window.
     */
    public Optional<Spike> observe(String endpoint, WindowSnapshot window) {
        double observed = window.requestsPerSecond();
        TrafficPredictor predictor = predictors.computeIfAbsent(
                endpoint, k -> new TrafficPredictor(baseAlpha, spikeSigma, windowSeconds));
        synchronized (predictor) {
            Optional<Spike> spike = Optional.empty();
            if (window.totalRequests() >= minRequests && predictor.isSpike(observed)) {
                spike = Optional.of(new Spike(endpoint, observed, predictor.predict(), predictor.spikeThreshold()));
            }
            predictor.update(observed);
            return spike;
        }
    }

    /** The current forecast for an endpoint, or empty if it has not been seen yet. */
    public Optional<TrafficForecast> forecast(String endpoint) {
        TrafficPredictor predictor = predictors.get(endpoint);
        if (predictor == null) {
            return Optional.empty();
        }
        synchronized (predictor) {
            return Optional.of(toForecast(endpoint, predictor));
        }
    }

    /** A forecast for every tracked endpoint, sorted by endpoint. */
    public List<TrafficForecast> forecasts() {
        List<TrafficForecast> out = new ArrayList<>();
        predictors.forEach((endpoint, predictor) -> {
            synchronized (predictor) {
                out.add(toForecast(endpoint, predictor));
            }
        });
        out.sort(Comparator.comparing(TrafficForecast::endpoint));
        return out;
    }

    /** Drops predictors for endpoints not in {@code live}, so one-off URLs don't accumulate. */
    public void retainEndpoints(Set<String> live) {
        predictors.keySet().retainAll(live);
    }

    private TrafficForecast toForecast(String endpoint, TrafficPredictor predictor) {
        double band = predictor.spikeThreshold() - predictor.predict();
        return new TrafficForecast(
                endpoint, predictor.predict(), band, predictor.spikeThreshold(), predictor.isWarm());
    }
}
