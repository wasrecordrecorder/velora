package io.velora.internal.event;

import io.velora.api.event.EventDescriptor;

import java.util.*;

public final class EventDescriptorStore {
    private final Map<String, EventDescriptor> byId = new LinkedHashMap<>();
    private final List<EventDescriptor> byIndex = new ArrayList<>();

    public void add(EventDescriptor d) {
        byId.put(d.id(), d);
        byIndex.add(d);
    }

    public EventDescriptor find(String id) { return byId.get(id); }
    public EventDescriptor findByIndex(int index) {
        return index >= 0 && index < byIndex.size() ? byIndex.get(index) : null;
    }
    public List<EventDescriptor> all() { return List.copyOf(byIndex); }
    public Collection<String> ids() { return Collections.unmodifiableSet(byId.keySet()); }
}
