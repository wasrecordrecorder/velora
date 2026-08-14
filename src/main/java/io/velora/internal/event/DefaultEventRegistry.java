package io.velora.internal.event;

import io.velora.api.event.*;
import io.velora.api.type.VeloraTypes;
import io.velora.host.VeloraHost;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class DefaultEventRegistry implements EventRegistry {
    private final Map<String, EventDescriptor> byId = new LinkedHashMap<>();
    private final Map<String, EventDescriptor> byScriptName = new LinkedHashMap<>();
    private final List<EventDescriptor> all = new ArrayList<>();
    private final List<Consumer<EventDiagnostic>> diagnosticListeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, EventQueue> queues = new ConcurrentHashMap<>();
    private final AtomicInteger totalQueueDepth = new AtomicInteger();
    private final AtomicInteger maxQueueDepth = new AtomicInteger();
    private final AtomicInteger droppedEvents = new AtomicInteger();
    private final AtomicInteger coalescedEvents = new AtomicInteger();
    private final VeloraHost host;
    private boolean frozen;
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
    public synchronized void register(EventDescriptor descriptor) {
        Objects.requireNonNull(descriptor);
        checkFrozen();
        if (byId.containsKey(descriptor.id())) throw new IllegalStateException("Event already registered: " + descriptor.id());
        if (byScriptName.containsKey(descriptor.scriptName())) throw new IllegalStateException("Event script name already registered: " + descriptor.scriptName());
        byId.put(descriptor.id(), descriptor);
        byScriptName.put(descriptor.scriptName(), descriptor);
        all.add(descriptor);
    }

    @Override public synchronized EventDescriptor find(String id) { return byId.get(id); }
    @Override public synchronized EventDescriptor findByScriptName(String scriptName) { return byScriptName.get(scriptName); }
    @Override public synchronized EventDescriptor findByIndex(int index) { return index >= 0 && index < all.size() ? all.get(index) : null; }
    @Override public synchronized List<EventDescriptor> all() { return List.copyOf(all); }
    @Override public synchronized Collection<String> ids() { return List.copyOf(byId.keySet()); }
    @Override public synchronized boolean isFrozen() { return frozen; }

    @Override
    public <T> void emitSafe(EventKey<T> key, T payload) {
        Objects.requireNonNull(key);
        EventDescriptor descriptor = find(key.id());
        if (descriptor == null) {
            diagnostic(key.id(), EventDiagnostic.Type.DROPPED);
            return;
        }
        validatePayload(descriptor, key, payload);
        PendingEvent event = new PendingEvent(key.id(), payload, nanoTime());
        EventQueue queue = queues.computeIfAbsent(key.id(), ignored -> new EventQueue());
        int depth = queue.offer(descriptor, event);
        maxQueueDepth.accumulateAndGet(depth, Math::max);
    }

    private <T> void validatePayload(EventDescriptor descriptor, EventKey<T> key, T payload) {
        if (payload == null) {
            if (!descriptor.payloadType().isNullable() && descriptor.payloadType() != VeloraTypes.UNIT && descriptor.payloadType() != VeloraTypes.NOTHING) {
                throw new IllegalArgumentException("Event " + descriptor.id() + " does not accept null payload");
            }
            return;
        }
        Class<?> keyType = boxed(key.payloadType());
        if (!keyType.isInstance(payload)) throw new IllegalArgumentException("Payload for " + descriptor.id() + " must be " + keyType.getTypeName() + ", got " + payload.getClass().getTypeName());
        Class<?> declared = boxed(descriptor.payloadType().javaClass());
        if (declared != Void.class && !declared.isAssignableFrom(keyType) && !keyType.isAssignableFrom(declared)) {
            throw new IllegalArgumentException("Event key payload type " + keyType.getTypeName() + " does not match registered Velora type " + descriptor.payloadType().name());
        }
    }


    private long nanoTime() {
        return host != null && host.clock() != null ? host.clock().nanoTime() : System.nanoTime();
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return Void.class;
    }

    @Override
    public <T> void emitOnMain(EventKey<T> key, PayloadFactory<T> payloadFactory) {
        Objects.requireNonNull(payloadFactory);
        if (host == null) {
            emitSafe(key, payloadFactory.create());
            return;
        }
        host.mainThread().execute(() -> emitSafe(key, payloadFactory.create()));
    }

    @Override
    public EventSubscription subscribeHostDiagnostics(Consumer<EventDiagnostic> listener) {
        Objects.requireNonNull(listener);
        diagnosticListeners.add(listener);
        return () -> diagnosticListeners.remove(listener);
    }

    public synchronized void freeze() {
        if (frozen) return;
        frozen = true;
        byId.clear();
        byScriptName.clear();
        for (int i = 0; i < all.size(); i++) {
            EventDescriptor descriptor = all.get(i).withIndex(i);
            all.set(i, descriptor);
            byId.put(descriptor.id(), descriptor);
            byScriptName.put(descriptor.scriptName(), descriptor);
        }
    }

    public synchronized void rollbackTo(int snapshotSize) {
        while (all.size() > snapshotSize) all.remove(all.size() - 1);
        byId.clear();
        byScriptName.clear();
        for (EventDescriptor descriptor : all) {
            byId.put(descriptor.id(), descriptor);
            byScriptName.put(descriptor.scriptName(), descriptor);
        }
    }

    public void dispatchPending() {
        EventDispatcherCallback callback = dispatcher;
        if (callback == null) return;
        for (EventQueue queue : queues.values()) {
            PendingEvent event;
            while ((event = queue.poll()) != null) {
                callback.dispatch(event.eventId(), event.payload());
                diagnostic(event.eventId(), EventDiagnostic.Type.DELIVERED);
            }
        }
    }

    public String scriptNameForEvent(String eventId) {
        EventDescriptor descriptor = find(eventId);
        return descriptor != null ? descriptor.scriptName() : null;
    }

    public EventDescriptor descriptorForEvent(String eventId) { return find(eventId); }
    public void fireDiagnostic(String eventId, EventDiagnostic.Type type) { diagnostic(eventId, type); }
    public int totalQueueDepth() { return totalQueueDepth.get(); }
    public int maxQueueDepth() { return maxQueueDepth.get(); }
    public int droppedEvents() { return droppedEvents.get(); }
    public int coalescedEvents() { return coalescedEvents.get(); }
    public int queueDepth(String eventId) {
        EventQueue queue = queues.get(eventId);
        return queue != null ? queue.size() : 0;
    }

    private void diagnostic(String eventId, EventDiagnostic.Type type) {
        EventDiagnostic diagnostic = new EventDiagnostic(eventId, type, nanoTime());
        Runnable notify = () -> {
            for (Consumer<EventDiagnostic> listener : diagnosticListeners) {
                try { listener.accept(diagnostic); } catch (RuntimeException ignored) {}
            }
        };
        if (host != null && !host.mainThread().isMainThread()) host.mainThread().execute(notify);
        else notify.run();
    }

    private synchronized void checkFrozen() {
        if (frozen) throw new IllegalStateException("EventRegistry is frozen");
    }

    private final class EventQueue {
        private final ArrayDeque<PendingEvent> values = new ArrayDeque<>();

        private synchronized int offer(EventDescriptor descriptor, PendingEvent event) {
            EventConcurrency concurrency = descriptor.defaultConcurrency();
            if ((concurrency == EventConcurrency.LATEST || concurrency == EventConcurrency.RESTART) && !values.isEmpty()) {
                int removed = values.size();
                values.clear();
                totalQueueDepth.addAndGet(-removed);
                coalescedEvents.addAndGet(removed);
                diagnostic(descriptor.id(), EventDiagnostic.Type.COALESCED);
            } else if (concurrency == EventConcurrency.DROP && !values.isEmpty()) {
                droppedEvents.incrementAndGet();
                diagnostic(descriptor.id(), EventDiagnostic.Type.DROPPED);
                return totalQueueDepth.get();
            } else if (values.size() >= descriptor.queueLimit()) {
                overflow(descriptor, event);
                return totalQueueDepth.get();
            }
            values.addLast(event);
            return totalQueueDepth.incrementAndGet();
        }

        private void overflow(EventDescriptor descriptor, PendingEvent event) {
            switch (descriptor.overflowPolicy()) {
                case DROP_NEWEST -> {
                    droppedEvents.incrementAndGet();
                    diagnostic(descriptor.id(), EventDiagnostic.Type.DROPPED);
                }
                case DROP_OLDEST -> {
                    values.pollFirst();
                    values.addLast(event);
                    droppedEvents.incrementAndGet();
                    diagnostic(descriptor.id(), EventDiagnostic.Type.DROPPED);
                }
                case KEEP_LATEST -> {
                    int removed = values.size();
                    values.clear();
                    values.addLast(event);
                    totalQueueDepth.addAndGet(1 - removed);
                    droppedEvents.addAndGet(removed);
                    coalescedEvents.incrementAndGet();
                    diagnostic(descriptor.id(), EventDiagnostic.Type.COALESCED);
                }
                case COALESCE -> {
                    PendingEvent previous = values.pollLast();
                    Object payload = previous == null ? event.payload() : descriptor.coalescer().apply(previous.payload(), event.payload());
                    values.addLast(new PendingEvent(event.eventId(), payload, event.timestamp()));
                    coalescedEvents.incrementAndGet();
                    diagnostic(descriptor.id(), EventDiagnostic.Type.COALESCED);
                }
                case FAIL_SCRIPT -> {
                    droppedEvents.incrementAndGet();
                    diagnostic(descriptor.id(), EventDiagnostic.Type.OVERFLOW);
                    Consumer<String> handler = overflowHandler;
                    if (handler != null) {
                        Runnable fail = () -> handler.accept(descriptor.id());
                        if (host != null && !host.mainThread().isMainThread()) host.mainThread().execute(fail);
                        else fail.run();
                    }
                }
            }
        }

        private synchronized PendingEvent poll() {
            PendingEvent event = values.pollFirst();
            if (event != null) totalQueueDepth.decrementAndGet();
            return event;
        }

        private synchronized int size() { return values.size(); }
    }
}
