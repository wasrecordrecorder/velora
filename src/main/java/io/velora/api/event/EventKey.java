package io.velora.api.event;

import java.util.Objects;

/**
 * Type-safe key for a registered event.
 *
 * <p>Keys are stable identifiers shared between the host (registration/emit) and
 * scripts ({@code @EventHandler}). The payload type is carried for compile-time
 * safety on the host side.
 *
 * @param <T> the immutable payload type
 */
public final class EventKey<T> {

    private final String id;
    private final Class<T> payloadType;

    private EventKey(String id, Class<T> payloadType) {
        this.id = Objects.requireNonNull(id);
        this.payloadType = Objects.requireNonNull(payloadType);
    }

    public static <T> EventKey<T> of(String id, Class<T> payloadType) {
        return new EventKey<>(id, payloadType);
    }

    /** Stable event id, e.g. {@code "client.aura.targetChanged"}. */
    public String id() {
        return id;
    }

    public Class<T> payloadType() {
        return payloadType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EventKey<?> k)) return false;
        return id.equals(k.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "EventKey(" + id + ")";
    }
}
