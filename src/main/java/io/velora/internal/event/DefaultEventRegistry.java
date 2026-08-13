package io.velora.internal.event;

import io.velora.api.event.*;
import io.velora.api.type.VeloraType;
import io.velora.host.VeloraHost;
import io.velora.internal.vm.ScriptValue;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class DefaultEventRegistry implements EventRegistry {

    private final Map<String, EventDescriptor> byId = new LinkedHashMap<>();
    private final List<EventDescriptor> all = new ArrayList<>();
    private final List<Consumer<EventDiagnostic>> diagnosticsListeners = new CopyOnWriteArrayList<>();
    private final VeloraHost host;
    private boolean frozen;

    // Pending events waiting to be dispatched to script handlers
    private final Map<String, Deque<PendingEvent>> perEventQueues = new ConcurrentHashMap<>();
    private int maxQueueDepth = 0;
    private int droppedEvents = 0;
    private int coalescedEvents = 0;

    // Dispatcher callback set by the engine
    private EventDispatcherCallback dispatcher;

    public record PendingEvent(String eventId, Object payload, long timestamp) {}

    @FunctionalInterface
    public interface EventDispatcherCallback {
        void dispatch(String eventId, Object payload);
    }

    public DefaultEventRegistry() {
        this(null);
    }

    public DefaultEventRegistry(VeloraHost host) {
        this.host = host;
    }

    public void setDispatcher(EventDispatcherCallback dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void register(EventDescriptor descriptor) {
        checkFrozen();
        if (byId.containsKey(descriptor.id())) {
            throw new IllegalStateException("Event already registered: " + descriptor.id());
        }
        byId.put(descriptor.id(), descriptor);
        all.add(descriptor);
    }

    @Override
    public EventDescriptor find(String id) {
        return byId.get(id);
    }

    @Override
    public EventDescriptor findByIndex(int index) {
        if (index < 0 || index >= all.size()) return null;
        return all.get(index);
    }

    @Override
    public List<EventDescriptor> all() {
        return List.copyOf(all);
    }

    @Override
    public Collection<String> ids() {
        return Collections.unmodifiableSet(byId.keySet());
    }

    @Override
    public boolean isFrozen() {
        return frozen;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> void emitSafe(EventKey<T> key, T payload) {
        String eventId = key.id();
        EventDescriptor descriptor = byId.get(eventId);

        if (descriptor == null) {
            // No descriptor registered, fire DELIVERED to host listeners only
            EventDiagnostic diag = new EventDiagnostic(eventId, EventDiagnostic.Type.DELIVERED, System.nanoTime());
            for (Consumer<EventDiagnostic> listener : diagnosticsListeners) {
                listener.accept(diag);
            }
            return;
        }

        // Queue the event for dispatch during tick
        PendingEvent pe = new PendingEvent(eventId, payload, System.nanoTime());

        // Handle backpressure based on concurrency policy
        EventConcurrency concurrency = descriptor.defaultConcurrency();
        int queueLimit = descriptor.queueLimit();

        if (concurrency == EventConcurrency.LATEST) {
            // Replace any pending event for this event id
            Deque<PendingEvent> q = perEventQueues.computeIfAbsent(eventId, k -> new ConcurrentLinkedDeque<>());
            if (!q.isEmpty()) {
                q.clear();
                coalescedEvents++;
                EventDiagnostic coalesced = new EventDiagnostic(eventId, EventDiagnostic.Type.COALESCED, System.nanoTime());
                for (Consumer<EventDiagnostic> listener : diagnosticsListeners) {
                    listener.accept(coalesced);
                }
            }
            q.add(pe);
        } else if (concurrency == EventConcurrency.DROP) {
            // Only queue if no pending event
            Deque<PendingEvent> q = perEventQueues.computeIfAbsent(eventId, k -> new ConcurrentLinkedDeque<>());
            if (q.isEmpty()) {
                q.add(pe);
            } else {
                // Drop this event
                droppedEvents++;
                EventDiagnostic dropped = new EventDiagnostic(eventId, EventDiagnostic.Type.DROPPED, System.nanoTime());
                for (Consumer<EventDiagnostic> listener : diagnosticsListeners) {
                    listener.accept(dropped);
                }
            }
        } else {
            // QUEUE, RESTART, PARALLEL - add to queue with backpressure
            Deque<PendingEvent> q = perEventQueues.computeIfAbsent(eventId, k -> new ConcurrentLinkedDeque<>());
            if (q.size() >= queueLimit) {
                EventOverflowPolicy policy = descriptor.overflowPolicy();
                if (policy == EventOverflowPolicy.DROP_OLDEST || policy == EventOverflowPolicy.KEEP_LATEST) {
                    q.pollFirst();
                    q.add(pe);
                    droppedEvents++;
                    EventDiagnostic dropped = new EventDiagnostic(eventId, EventDiagnostic.Type.DROPPED, System.nanoTime());
                    for (Consumer<EventDiagnostic> listener : diagnosticsListeners) {
                        listener.accept(dropped);
                    }
                } else if (policy == EventOverflowPolicy.DROP_NEWEST) {
                    // Drop the new event
                    droppedEvents++;
                    EventDiagnostic dropped = new EventDiagnostic(eventId, EventDiagnostic.Type.DROPPED, System.nanoTime());
                    for (Consumer<EventDiagnostic> listener : diagnosticsListeners) {
                        listener.accept(dropped);
                    }
                } else {
                    // COALESCE or FAIL_SCRIPT - replace latest
                    q.pollLast();
                    q.add(pe);
                    coalescedEvents++;
                }
            } else {
                q.add(pe);
            }
        }
        // Track max queue depth across all event queues
        int totalDepth = totalQueueDepth();
        if (totalDepth > maxQueueDepth) maxQueueDepth = totalDepth;

        // Fire DELIVERED diagnostic for successfully processed event
        EventDiagnostic delivered = new EventDiagnostic(eventId, EventDiagnostic.Type.DELIVERED, System.nanoTime());
        for (Consumer<EventDiagnostic> listener : diagnosticsListeners) {
            listener.accept(delivered);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> void emitOnMain(EventKey<T> key, PayloadFactory<T> payloadFactory) {
        if (host != null) {
            host.mainThread().execute(() -> {
                T payload = payloadFactory.create();
                emitSafe(key, payload);
            });
        }
    }

    @Override
    public EventSubscription subscribeHostDiagnostics(Consumer<EventDiagnostic> listener) {
        diagnosticsListeners.add(listener);
        return () -> diagnosticsListeners.remove(listener);
    }

    public void freeze() {
        frozen = true;
        for (int i = 0; i < all.size(); i++) {
            EventDescriptor d = all.get(i);
            all.set(i, d.withIndex(i));
            byId.put(d.id(), d.withIndex(i));
        }
    }

    public void rollbackTo(int snapshotSize) {
        while (all.size() > snapshotSize) {
            EventDescriptor removed = all.remove(all.size() - 1);
            byId.remove(removed.id());
        }
    }

    /**
     * Dispatch pending events to script handlers. Called from the engine's tick().
     * The callback handles finding matching handlers and spawning fibers.
     */
    public void dispatchPending() {
        if (dispatcher == null) return;

        for (var entry : perEventQueues.entrySet()) {
            String eventId = entry.getKey();
            Deque<PendingEvent> q = entry.getValue();
            while (!q.isEmpty()) {
                PendingEvent pe = q.pollFirst();
                if (pe != null) {
                    dispatcher.dispatch(pe.eventId(), pe.payload);
                    // Fire DELIVERED diagnostic
                    EventDiagnostic delivered = new EventDiagnostic(eventId, EventDiagnostic.Type.DELIVERED, System.nanoTime());
                    for (Consumer<EventDiagnostic> listener : diagnosticsListeners) {
                        listener.accept(delivered);
                    }
                }
            }
        }
    }

    /**
     * Get the script name for an event id (used for matching handlers).
     */
    public String scriptNameForEvent(String eventId) {
        EventDescriptor d = byId.get(eventId);
        return d != null ? d.scriptName() : null;
    }

    /**
     * Get the event descriptor for an event id.
     */
    public EventDescriptor descriptorForEvent(String eventId) {
        return byId.get(eventId);
    }

    /**
     * Fire a diagnostic event.
     */
    public void fireDiagnostic(String eventId, EventDiagnostic.Type type) {
        EventDiagnostic diag = new EventDiagnostic(eventId, type, System.nanoTime());
        for (Consumer<EventDiagnostic> listener : diagnosticsListeners) {
            listener.accept(diag);
        }
    }

    /**
     * Total pending events across all event queues.
     */
    public int totalQueueDepth() {
        int total = 0;
        for (var q : perEventQueues.values()) total += q.size();
        return total;
    }

    /**
     * Maximum queue depth observed since engine start.
     */
    public int maxQueueDepth() {
        return maxQueueDepth;
    }

    /**
     * Total dropped events since engine start.
     */
    public int droppedEvents() {
        return droppedEvents;
    }

    /**
     * Total coalesced events since engine start.
     */
    public int coalescedEvents() {
        return coalescedEvents;
    }

    /**
     * Current queue depth for a specific event id.
     */
    public int queueDepth(String eventId) {
        Deque<PendingEvent> q = perEventQueues.get(eventId);
        return q != null ? q.size() : 0;
    }

    private void checkFrozen() {
        if (frozen) throw new IllegalStateException("EventRegistry is frozen");
    }
}
