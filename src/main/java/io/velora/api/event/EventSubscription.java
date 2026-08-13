package io.velora.api.event;

/**
 * A host-side subscription to script service diagnostics or event delivery.
 * Closing the subscription stops delivery.
 */
public interface EventSubscription extends AutoCloseable {
    @Override
    void close();
}
