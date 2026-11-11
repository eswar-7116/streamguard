package io.github.eswar7116.streamguard.security;

public record SuspiciousIp(String ip, int requestCount, double percentOfTotal, String flag) {
}
