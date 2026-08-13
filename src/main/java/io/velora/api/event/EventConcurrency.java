package io.velora.api.event;

/**
 * Concurrency policy for an event handler.
 */
public enum EventConcurrency {
    /** Drop new invocations while a previous handler is still running. */
    DROP,
    /** Queue invocations up to the queue limit. */
    QUEUE,
    /** Cancel the running handler and restart with the latest event. */
    RESTART,
    /** Run handlers concurrently (bounded by fiber limit). */
    PARALLEL,
    /** Keep only the latest event, replacing any pending one. */
    LATEST
}
