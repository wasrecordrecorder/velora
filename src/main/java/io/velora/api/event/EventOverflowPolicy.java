package io.velora.api.event;

/**
 * Overflow policy applied when an event queue exceeds its limit.
 */
public enum EventOverflowPolicy {
    /** Drop the newest event when the queue is full. */
    DROP_NEWEST,
    /** Drop the oldest event to make room for the newest. */
    DROP_OLDEST,
    /** Keep only the latest event, discarding all older pending ones. */
    KEEP_LATEST,
    /** Merge the new event with the pending one via a coalescer. */
    COALESCE,
    /** Fail the script when the queue overflows. */
    FAIL_SCRIPT
}
