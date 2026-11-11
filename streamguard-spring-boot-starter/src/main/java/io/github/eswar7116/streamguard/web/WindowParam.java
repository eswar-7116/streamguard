package io.github.eswar7116.streamguard.web;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses window query params such as {@code 30s}, {@code 1m}, {@code 15m}, {@code 1h}. */
final class WindowParam {

    private static final Pattern FORMAT = Pattern.compile("(\\d{1,6})([smh])");

    private WindowParam() {
    }

    static Duration parse(String value) {
        Matcher m = FORMAT.matcher(value.trim().toLowerCase());
        if (!m.matches()) {
            throw new IllegalArgumentException("window must look like 30s, 1m, 5m or 15m; got '" + value + "'");
        }
        long amount = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            default -> Duration.ofHours(amount);
        };
    }
}
