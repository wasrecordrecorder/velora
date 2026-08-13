package io.velora.api.function;

/**
 * Thread requirement for a host function.
 */
public enum ScriptThread {
    /** Can be called on any thread (pure computation). */
    ANY,
    /** Must be called on the main/client thread. */
    MAIN,
    /** Must be called on a worker thread. */
    WORKER
}
