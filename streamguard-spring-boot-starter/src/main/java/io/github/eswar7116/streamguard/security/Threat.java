package io.github.eswar7116.streamguard.security;

/**
 * @param endpoint the targeted endpoint for BRUTE_FORCE; null for SCANNER
 * @param count    auth failures (BRUTE_FORCE) or distinct targets (SCANNER) in the last minute
 */
public record Threat(ThreatType type, String ip, String endpoint, int count) {

    public enum ThreatType {
        BRUTE_FORCE,
        SCANNER
    }
}
