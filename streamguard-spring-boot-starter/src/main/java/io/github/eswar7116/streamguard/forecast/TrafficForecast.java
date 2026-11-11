package io.github.eswar7116.streamguard.forecast;

/**
 * The forecaster's current view of one endpoint.
 *
 * @param endpoint             the endpoint key
 * @param forecastRps          predicted requests/sec for the next window (the EWMA level)
 * @param bandRps              half-width of the spike band above the forecast (spikeSigma * deviation)
 * @param spikeThresholdRps    forecastRps + bandRps; an observed rate above this is a spike
 * @param warm                 false until the predictor has seen enough windows to flag spikes
 */
public record TrafficForecast(
        String endpoint,
        double forecastRps,
        double bandRps,
        double spikeThresholdRps,
        boolean warm
) {
}
