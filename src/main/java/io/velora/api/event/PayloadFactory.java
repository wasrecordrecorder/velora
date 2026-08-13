package io.velora.api.event;

/**
 * Factory that produces an immutable, script-safe payload on the main thread.
 *
 * <p>Used by {@link EventRegistry#emitOnMain(EventKey, PayloadFactory)} so that
 * host state is read safely before the payload is published.
 */
@FunctionalInterface
public interface PayloadFactory<T> {
    T create();
}
