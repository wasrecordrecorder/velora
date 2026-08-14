package io.velora.api.event;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Registry for typed events.
 *
 * <p>Host integrations register {@link EventDescriptor}s before freeze and emit
 * events via {@link #emitSafe(EventKey, Object)} or {@link #emitOnMain(EventKey, PayloadFactory)}.
 * Handlers are never executed on the emitter thread; delivery happens inside
 * {@code engine.tick()}.
 */
public interface EventRegistry {

    /** Register an event descriptor. Must be called before freeze. */
    void register(EventDescriptor descriptor);

    /** Find a descriptor by event id. */
    EventDescriptor find(String id);

    /** Find a descriptor by its script-facing name. */
    EventDescriptor findByScriptName(String scriptName);

    /** Find a descriptor by its assigned index (after freeze). */
    EventDescriptor findByIndex(int index);

    /** All registered event descriptors. */
    List<EventDescriptor> all();

    /** All registered event ids. */
    Collection<String> ids();

    boolean isFrozen();

    /**
     * Emit an already-safe immutable payload. Allowed from any thread. The payload
     * must not reference live host state.
     */
    <T> void emitSafe(EventKey<T> key, T payload);

    /**
     * Emit an event whose payload must be constructed on the main thread. The
     * factory runs via the host main-thread executor, then the resulting payload is
     * published as safe.
     */
    <T> void emitOnMain(EventKey<T> key, PayloadFactory<T> payloadFactory);

    /**
     * Subscribe to host-side diagnostics about event delivery (dropped, coalesced,
     * queue overflow). Forwards callbacks to the main thread.
     */
    EventSubscription subscribeHostDiagnostics(Consumer<EventDiagnostic> listener);

    /** Diagnostic record produced for the profiler / GUI. */
    record EventDiagnostic(String eventId, Type type, long timestamp) {
        public enum Type { DROPPED, COALESCED, OVERFLOW, DELIVERED }
    }
}
