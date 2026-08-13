package io.velora.api.event;

/**
 * Priority of an event handler. Higher priority handlers run first.
 */
public enum EventPriority {
    LOWEST(0),
    LOW(1),
    NORMAL(2),
    HIGH(3),
    HIGHEST(4),
    MONITOR(5); // observation only, never modifies state

    private final int weight;

    EventPriority(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }
}
