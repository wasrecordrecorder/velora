package io.velora.api.event;

import io.velora.api.permission.ScriptPermission;
import io.velora.api.type.VeloraType;

import java.util.Objects;

/**
 * Immutable descriptor for a registered event.
 *
 * <p>Created through an EventDescriptor.Builder and frozen into the
 * {@link EventRegistry}. Scripts reference events by their {@link #scriptName()}.
 */
public final class EventDescriptor {

    private final String id;
    private final String scriptName;
    private final String description;
    private final String categoryId;
    private final String extensionId;
    private final VeloraType payloadType;
    private final ScriptPermission permission;
    private final EventConcurrency defaultConcurrency;
    private final int queueLimit;
    private final EventOverflowPolicy overflowPolicy;
    private final int cost;
    private final int index;

    private EventDescriptor(Builder b) {
        this.id = b.id;
        this.scriptName = b.scriptName;
        this.description = b.description;
        this.categoryId = b.categoryId;
        this.extensionId = b.extensionId;
        this.payloadType = b.payloadType;
        this.permission = b.permission;
        this.defaultConcurrency = b.defaultConcurrency;
        this.queueLimit = b.queueLimit;
        this.overflowPolicy = b.overflowPolicy;
        this.cost = b.cost;
        this.index = b.index;
    }

    public String id() { return id; }
    public String scriptName() { return scriptName; }
    public String description() { return description; }
    public String categoryId() { return categoryId; }
    public String extensionId() { return extensionId; }
    public VeloraType payloadType() { return payloadType; }
    public ScriptPermission permission() { return permission; }
    public EventConcurrency defaultConcurrency() { return defaultConcurrency; }
    public int queueLimit() { return queueLimit; }
    public EventOverflowPolicy overflowPolicy() { return overflowPolicy; }
    public int cost() { return cost; }
    public int index() { return index; }

    public EventDescriptor withIndex(int index) {
        Builder b = new Builder();
        b.id = this.id;
        b.scriptName = this.scriptName;
        b.description = this.description;
        b.categoryId = this.categoryId;
        b.extensionId = this.extensionId;
        b.payloadType = this.payloadType;
        b.permission = this.permission;
        b.defaultConcurrency = this.defaultConcurrency;
        b.queueLimit = this.queueLimit;
        b.overflowPolicy = this.overflowPolicy;
        b.cost = this.cost;
        b.index = index;
        return b.build();
    }

    public static <T> Builder builder(EventKey<T> key) {
        return new Builder().id(key.id());
    }

    public static Builder builder(String id) {
        return new Builder().id(id);
    }

    public static final class Builder {
        private String id;
        private String scriptName;
        private String description = "";
        private String categoryId = "";
        private String extensionId = "";
        private VeloraType payloadType;
        private ScriptPermission permission;
        private EventConcurrency defaultConcurrency = EventConcurrency.QUEUE;
        private int queueLimit = 256;
        private EventOverflowPolicy overflowPolicy = EventOverflowPolicy.DROP_OLDEST;
        private int cost = 1;
        private int index = -1;

        public Builder id(String v) { this.id = v; return this; }
        public Builder scriptName(String v) { this.scriptName = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder categoryId(String v) { this.categoryId = v; return this; }
        public Builder extensionId(String v) { this.extensionId = v; return this; }
        public Builder payloadType(VeloraType v) { this.payloadType = v; return this; }
        public Builder permission(ScriptPermission v) { this.permission = v; return this; }
        public Builder defaultConcurrency(EventConcurrency v) { this.defaultConcurrency = v; return this; }
        public Builder queueLimit(int v) { this.queueLimit = v; return this; }
        public Builder overflowPolicy(EventOverflowPolicy v) { this.overflowPolicy = v; return this; }
        public Builder cost(int v) { this.cost = v; return this; }

        public EventDescriptor build() {
            Objects.requireNonNull(id, "id");
            if (scriptName == null) {
                scriptName = id;
            }
            if (payloadType == null) {
                throw new IllegalStateException("payloadType required for event " + id);
            }
            if (queueLimit <= 0) {
                throw new IllegalArgumentException("queueLimit must be positive");
            }
            if (cost <= 0) {
                throw new IllegalArgumentException("cost must be positive");
            }
            return new EventDescriptor(this);
        }
    }
}
