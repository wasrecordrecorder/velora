package io.velora.internal.event;

import io.velora.api.event.EventDescriptor;
import io.velora.api.event.EventKey;

import java.util.*;
import java.util.function.Consumer;

public final class EventDispatcher {

    public void dispatch(EventDescriptor descriptor, Object payload,
                         Map<String, List<Consumer<Object>>> subscriptions) {
        List<Consumer<Object>> handlers = subscriptions.get(descriptor.id());
        if (handlers != null) {
            for (Consumer<Object> handler : handlers) {
                try {
                    handler.accept(payload);
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
