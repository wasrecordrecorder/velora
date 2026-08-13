package io.velora.api;

/**
 * Engine lifecycle states.
 */
public enum VeloraState {
    CREATED,
    CONFIGURING,
    FROZEN,
    RUNNING,
    CLOSING,
    CLOSED,
    FAILED
}
