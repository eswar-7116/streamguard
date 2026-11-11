package io.github.eswar7116.streamguard.availability;

// Lifecycle states for a single endpoint, driven by AvailabilityStateMachine
public enum EndpointState {
    HEALTHY,
    DEGRADED,
    DOWN,
    RECOVERING
}
