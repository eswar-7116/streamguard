package io.github.eswar7116.streamguard.forecast;

/**
 * Single-series EWMA traffic predictor with an adaptive smoothing factor, mirroring the glambdar
 * {@code ewma.TrafficPredictor}: when an observation deviates far from the current EWMA, {@code alpha}
 * rises toward 1 so the prediction tracks the shift quickly; when traffic is steady it settles back
 * toward {@code baseAlpha} and smooths out noise.
 *
 * <p>On top of the level it keeps an EWMA of the absolute prediction error ({@code deviation}), which
 * gives a self-tuning band: an observation more than {@code spikeSigma} deviations above the EWMA is a
 * spike. The band needs a few observations to mean anything (after one it is zero, so any rise would
 * "spike"), so nothing is flagged until {@value #WARMUP_OBSERVATIONS} observations have been folded in.
 *
 * <p>Steady traffic can shrink the learned deviation below ordinary randomness: request counts in a
 * window vary by about the square root of the count even when nothing changed. So the band never gets
 * narrower than that Poisson noise floor, or routine jitter would keep tripping spikes. The floor is
 * {@code sqrt(2 * rate / windowSeconds)}: the check compares a noisy observation against a forecast
 * that is itself built from noisy observations, and the variances of the two add.
 *
 * <p>Not thread-safe; callers guard per-instance access.
 */
public class TrafficPredictor {

    private static final double MIN_ALPHA = 0.05;
    private static final double MAX_ALPHA = 0.95;
    static final int WARMUP_OBSERVATIONS = 3;

    private final double baseAlpha;
    private final double spikeSigma;
    private final double windowSeconds;

    private double alpha;
    private double ewma;
    private double deviation;
    private boolean initialized;
    private int observations;

    /** A predictor with no noise floor, for series that aren't event rates. */
    public TrafficPredictor(double baseAlpha, double spikeSigma) {
        this(baseAlpha, spikeSigma, Double.POSITIVE_INFINITY);
    }

    /** @param windowSeconds length of each observation window, which sets the Poisson noise floor */
    public TrafficPredictor(double baseAlpha, double spikeSigma, double windowSeconds) {
        if (baseAlpha <= 0 || baseAlpha >= 1) {
            throw new IllegalArgumentException("baseAlpha must be in (0, 1), got " + baseAlpha);
        }
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("windowSeconds must be positive, got " + windowSeconds);
        }
        this.baseAlpha = baseAlpha;
        this.spikeSigma = spikeSigma;
        this.windowSeconds = windowSeconds;
        this.alpha = baseAlpha;
    }

    /** True if the spike threshold has been crossed, evaluated against the pre-update EWMA and band. */
    public boolean isSpike(double count) {
        return isWarm() && count > spikeThreshold();
    }

    /** EWMA + spikeSigma * max(deviation, noise floor): an observation above this is a spike. */
    public double spikeThreshold() {
        return ewma + spikeSigma * Math.max(deviation, noiseFloor());
    }

    /** Standard deviation of (observation - forecast) when both carry Poisson noise at the current level. */
    double noiseFloor() {
        return Math.sqrt(2 * Math.max(ewma, 0) / windowSeconds);
    }

    /** Folds a new observation into the EWMA (adaptive alpha) and its deviation, and returns the new EWMA. */
    public double update(double count) {
        if (observations < WARMUP_OBSERVATIONS) {
            observations++;
        }
        if (!initialized) {
            ewma = count;
            initialized = true;
            return ewma;
        }

        double error = Math.abs(count - ewma);
        deviation = baseAlpha * error + (1 - baseAlpha) * deviation;

        double sensitivity = error / (ewma + 1);
        alpha = clamp(baseAlpha + (1 - baseAlpha) * sensitivity, MIN_ALPHA, MAX_ALPHA);
        ewma = alpha * count + (1 - alpha) * ewma;
        return ewma;
    }

    /** The predicted rate for the next window (the current EWMA). */
    public double predict() {
        return ewma;
    }

    public double deviation() {
        return deviation;
    }

    public boolean initialized() {
        return initialized;
    }

    /** True once enough observations have built a deviation band for spike checks. */
    public boolean isWarm() {
        return observations >= WARMUP_OBSERVATIONS;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
