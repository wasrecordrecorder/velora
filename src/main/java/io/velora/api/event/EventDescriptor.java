package io.velora.api.event;

import io.velora.api.type.VeloraType;

import java.util.Objects;
import java.util.function.BinaryOperator;

/**
 * Immutable descriptor for a registered event.
 *
 * <p>Created through an EventDescriptor.Builder and frozen into the
 * {@link EventRegistry}. A descriptor with script name {@code Tick} is referenced as {@code @Tick} in Velora.
 */
public final class EventDescriptor {

    private final String id;
    private final String scriptName;
    private final String description;
    private final String categoryId;
    private final String extensionId;
    private final VeloraType payloadType;
    private final EventConcurrency defaultConcurrency;
    private final int queueLimit;
    private final EventOverflowPolicy overflowPolicy;
    private final BinaryOperator<Object> coalescer;
    private final int cost;
    private final int index;

    private EventDescriptor(Builder b) {
        this.id = b.id;
        this.scriptName = b.scriptName;
        this.description = b.description;
        this.categoryId = b.categoryId;
        this.extensionId = b.extensionId;
        this.payloadType = b.payloadType;
        this.defaultConcurrency = b.defaultConcurrency;
        this.queueLimit = b.queueLimit;
        this.overflowPolicy = b.overflowPolicy;
        this.coalescer = b.coalescer;
        this.cost = b.cost;
        this.index = b.index;
    }

    public String id() { return id; }
    public String scriptName() { return scriptName; }
    public String description() { return description; }
    public String categoryId() { return categoryId; }
    public String extensionId() { return extensionId; }
    public VeloraType payloadType() { return payloadType; }
    public EventConcurrency defaultConcurrency() { return defaultConcurrency; }
    public int queueLimit() { return queueLimit; }
    public EventOverflowPolicy overflowPolicy() { return overflowPolicy; }
    public BinaryOperator<Object> coalescer() { return coalescer; }
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
        b.defaultConcurrency = this.defaultConcurrency;
        b.queueLimit = this.queueLimit;
        b.overflowPolicy = this.overflowPolicy;
        b.coalescer = this.coalescer;
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
        private EventConcurrency defaultConcurrency = EventConcurrency.QUEUE;
        private int queueLimit = 256;
        private EventOverflowPolicy overflowPolicy = EventOverflowPolicy.DROP_OLDEST;
        private BinaryOperator<Object> coalescer;
        private int cost = 1;
        private int index = -1;

        public Builder id(String v) { this.id = v; return this; }
        public Builder scriptName(String v) { this.scriptName = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder categoryId(String v) { this.categoryId = v; return this; }
        public Builder extensionId(String v) { this.extensionId = v; return this; }
        public Builder payloadType(VeloraType v) { this.payloadType = v; return this; }
        public Builder defaultConcurrency(EventConcurrency v) { this.defaultConcurrency = v; return this; }
        public Builder queueLimit(int v) { this.queueLimit = v; return this; }
        public Builder overflowPolicy(EventOverflowPolicy v) { this.overflowPolicy = v; return this; }
        public Builder coalescer(BinaryOperator<Object> v) { this.coalescer = v; return this; }
        public Builder cost(int v) { this.cost = v; return this; }

        public EventDescriptor build() {
            Objects.requireNonNull(id, "id");
            if (id.isBlank()) throw new IllegalArgumentException("Event id cannot be blank");
            if (scriptName == null) scriptName = deriveScriptName(id);
            if (!isScriptIdentifier(scriptName)) throw new IllegalArgumentException("Event scriptName must be a script annotation identifier: " + scriptName);
            if (isReservedAnnotation(scriptName)) throw new IllegalArgumentException("Event scriptName conflicts with a built-in annotation: " + scriptName);
            Objects.requireNonNull(defaultConcurrency, "defaultConcurrency");
            Objects.requireNonNull(overflowPolicy, "overflowPolicy");
            if (overflowPolicy == EventOverflowPolicy.COALESCE && coalescer == null) throw new IllegalStateException("COALESCE overflow policy requires a coalescer for event " + id);
            if (payloadType == null) throw new IllegalStateException("payloadType required for event " + id);
            if (queueLimit <= 0) {
                throw new IllegalArgumentException("queueLimit must be positive");
            }
            if (cost <= 0) {
                throw new IllegalArgumentException("cost must be positive");
            }
            return new EventDescriptor(this);
        }

        private static String deriveScriptName(String id) {
            String tail = id.replaceFirst("^.*[.:/]", "");
            StringBuilder result = new StringBuilder();
            for (String part : tail.split("[^A-Za-z0-9]+")) {
                if (part.isEmpty()) continue;
                result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
            if (result.isEmpty()) throw new IllegalArgumentException("Cannot derive script annotation name from event id: " + id);
            if (Character.isDigit(result.charAt(0))) result.insert(0, "Event");
            return result.toString();
        }

        private static boolean isScriptIdentifier(String value) {
            if (value == null || value.isEmpty() || !Character.isJavaIdentifierStart(value.charAt(0))) return false;
            for (int i = 1; i < value.length(); i++) if (!Character.isJavaIdentifierPart(value.charAt(i))) return false;
            return true;
        }

        private static boolean isReservedAnnotation(String value) {
            return switch (value) {
                case "Script", "Version", "Author", "Description", "Setting", "Persistent",
                     "Load", "Enable", "Run", "Disable", "Unload" -> true;
                default -> false;
            };
        }
    }
}
