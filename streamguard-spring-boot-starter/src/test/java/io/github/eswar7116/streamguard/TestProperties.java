package io.github.eswar7116.streamguard;

import io.github.eswar7116.streamguard.config.StreamGuardProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

/** Binds {@link StreamGuardProperties} the way Spring Boot does, so tests get the real defaults. */
public final class TestProperties {

    private TestProperties() {
    }

    public static StreamGuardProperties defaults() {
        return of(Map.of());
    }

    /** @param overrides e.g. {@code Map.of("streamguard.detection.evaluation-window-seconds", "30")} */
    public static StreamGuardProperties of(Map<String, String> overrides) {
        return new Binder(new MapConfigurationPropertySource(overrides))
                .bindOrCreate("streamguard", StreamGuardProperties.class);
    }
}
