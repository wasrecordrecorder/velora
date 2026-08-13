package io.velora.internal.event;

import java.util.*;
import java.util.function.Consumer;

public final class EventSubscriptionStore {

    private final Map<String, List<Consumer<Object>>> subscriptions = new LinkedHashMap<>();

    public void subscribe(String eventId, Consumer<Object> handler) {
        subscriptions.computeIfAbsent(eventId, k -> new ArrayList<>()).add(handler);
    }

    public void unsubscribe(String eventId, Consumer<Object> handler) {
        List<Consumer<Object>> list = subscriptions.get(eventId);
        if (list != null) list.remove(handler);
    }

    public List<Consumer<Object>> handlers(String eventId) {
        return subscriptions.getOrDefault(eventId, List.of());
    }

    public Map<String, List<Consumer<Object>>> all() {
        Map<String, List<Consumer<Object>>> copy = new LinkedHashMap<>();
        for (var e : subscriptions.entrySet()) {
            copy.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return copy;
    }

    public void clear() { subscriptions.clear(); }
}
