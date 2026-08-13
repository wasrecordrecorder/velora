package io.velora.internal.event;

import io.velora.api.event.*;
import io.velora.host.VeloraHost;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class DefaultEventRegistry implements EventRegistry {
    private final Map<String, EventDescriptor> byId = new LinkedHashMap<>();
    private final List<EventDescriptor> all = new ArrayList<>();
    private final List<Consumer<EventDiagnostic>> diagnosticListeners = new CopyOnWriteArrayList<>();
    private final Map<String, Deque<PendingEvent>> queues = new ConcurrentHashMap<>();
    private final VeloraHost host;
    private boolean frozen;
    private int maxQueueDepth;
    private int droppedEvents;
    private int coalescedEvents;
    private EventDispatcherCallback dispatcher;
    private Consumer<String> overflowHandler;

    public record PendingEvent(String eventId, Object payload, long timestamp) {}

    @FunctionalInterface
    public interface EventDispatcherCallback { void dispatch(String eventId, Object payload); }

    public DefaultEventRegistry() { this(null); }
    public DefaultEventRegistry(VeloraHost host) { this.host = host; }
    public void setDispatcher(EventDispatcherCallback dispatcher) { this.dispatcher = dispatcher; }
    public void setOverflowHandler(Consumer<String> overflowHandler) { this.overflowHandler = overflowHandler; }

    @Override
    public void register(EventDescriptor descriptor) {
        checkFrozen();
        if (byId.containsKey(descriptor.id())) throw new IllegalStateException("Event already registered: " + descriptor.id());
        byId.put(descriptor.id(), descriptor);
        all.add(descriptor);
    }

    @Override public EventDescriptor find(String id) { return byId.get(id); }
    @Override public EventDescriptor findByIndex(int index) { return index >= 0 && index < all.size() ? all.get(index) : null; }
    @Override public List<EventDescriptor> all() { return List.copyOf(all); }
    @Override public Collection<String> ids() { return Collections.unmodifiableSet(byId.keySet()); }
    @Override public boolean isFrozen() { return frozen; }

    @Override
    public <T> void emitSafe(EventKey<T> key, T payload) {
        EventDescriptor descriptor = byId.get(key.id());
        if (descriptor == null) {
            diagnostic(key.id(), EventDiagnostic.Type.DROPPED);
            return;
        }
        PendingEvent event = new PendingEvent(key.id(), payload, System.nanoTime());
        Deque<PendingEvent> queue = queues.computeIfAbsent(key.id(), ignored -> new ConcurrentLinkedDeque<>());
        EventConcurrency concurrency = descriptor.defaultConcurrency();
        if (concurrency == EventConcurrency.LATEST) {
            if (!queue.isEmpty()) {
                queue.clear();
                coalescedEvents++;
                diagnostic(key.id(), EventDiagnostic.Type.COALESCED);
            }
            queue.add(event);
        } else if (concurrency == EventConcurrency.DROP && !queue.isEmpty()) {
            droppedEvents++;
            diagnostic(key.id(), EventDiagnostic.Type.DROPPED);
        } else if (queue.size() >= descriptor.queueLimit()) {
            overflow(descriptor, queue, event);
        } else {
            queue.add(event);
        }
        maxQueueDepth = Math.max(maxQueueDepth, totalQueueDepth());
    }

    private void overflow(EventDescriptor descriptor, Deque<PendingEvent> queue, PendingEvent event) {
        String eventId = descriptor.id();
        switch (descriptor.overflowPolicy()) {
            case DROP_NEWEST -> {
                droppedEvents++;
                diagnostic(eventId, EventDiagnostic.Type.DROPPED);
            }
            case DROP_OLDEST -> {
                queue.pollFirst();
                queue.add(event);
                droppedEvents++;
                diagnostic(eventId, EventDiagnostic.Type.DROPPED);
            }
            case KEEP_LATEST -> {
                int removed = queue.size();
                queue.clear();
                queue.add(event);
                droppedEvents += removed;
                coalescedEvents++;
                diagnostic(eventId, EventDiagnostic.Type.COALESCED);
            }
            case COALESCE -> {
                queue.pollLast();
                queue.add(event);
                coalescedEvents++;
                diagnostic(eventId, EventDiagnostic.Type.COALESCED);
            }
            case FAIL_SCRIPT -> {
                droppedEvents++;
                diagnostic(eventId, EventDiagnostic.Type.OVERFLOW);
                if (overflowHandler != null) overflowHandler.accept(eventId);
            }
        }
    }

    @Override
    public <T> void emitOnMain(EventKey<T> key, PayloadFactory<T> payloadFactory) {
        if (host == null) return;
        host.mainThread().execute(() -> emitSafe(key, payloadFactory.create()));
    }

    @Override
    public EventSubscription subscribeHostDiagnostics(Consumer<EventDiagnostic> listener) {
        diagnosticListeners.add(listener);
        return () -> diagnosticListeners.remove(listener);
    }

    public void freeze() {
        if (frozen) return;
        frozen = true;
        for (int i = 0; i < all.size(); i++) {
            EventDescriptor descriptor = all.get(i).withIndex(i);
            all.set(i, descriptor);
            byId.put(descriptor.id(), descriptor);
        }
    }

    public void rollbackTo(int snapshotSize) {
        while (all.size() > snapshotSize) byId.remove(all.remove(all.size() - 1).id());
    }

    public void dispatchPending() {
        if (dispatcher == null) return;
        for (var entry : queues.entrySet()) {
            PendingEvent event;
            while ((event = entry.getValue().pollFirst()) != null) {
                dispatcher.dispatch(event.eventId(), event.payload());
                diagnostic(event.eventId(), EventDiagnostic.Type.DELIVERED);
            }
        }
    }

    public String scriptNameForEvent(String eventId) {
        EventDescriptor descriptor = byId.get(eventId);
        return descriptor != null ? descriptor.scriptName() : null;
    }

    public EventDescriptor descriptorForEvent(String eventId) { return byId.get(eventId); }
    public void fireDiagnostic(String eventId, EventDiagnostic.Type type) { diagnostic(eventId, type); }
    public int totalQueueDepth() { return queues.values().stream().mapToInt(Deque::size).sum(); }
    public int maxQueueDepth() { return maxQueueDepth; }
    public int droppedEvents() { return droppedEvents; }
    public int coalescedEvents() { return coalescedEvents; }
    public int queueDepth(String eventId) { Deque<PendingEvent> queue = queues.get(eventId); return queue != null ? queue.size() : 0; }

    private void diagnostic(String eventId, EventDiagnostic.Type type) {
        EventDiagnostic diagnostic = new EventDiagnostic(eventId, type, System.nanoTime());
        for (Consumer<EventDiagnostic> listener : diagnosticListeners) listener.accept(diagnostic);
    }

    private void checkFrozen() { if (frozen) throw new IllegalStateException("EventRegistry is frozen"); }
}
