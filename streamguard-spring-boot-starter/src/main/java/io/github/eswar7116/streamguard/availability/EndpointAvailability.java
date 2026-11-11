package io.github.eswar7116.streamguard.availability;

import java.time.Instant;

/**
 * Snapshot of an endpoint's current availability.
 *
 * @param endpoint   the endpoint path
 * @param state      current lifecycle state
 * @param since      when the endpoint last transitioned into {@code state}
 * @param durationMs how long it has been in {@code state}, in milliseconds
 */
public record EndpointAvailability(String endpoint, EndpointState state, Instant since, long durationMs) {
}
