package io.velora.api.task;

/**
 * Producer side of a {@link VeloraTask}. Host adapters hold the source and drive
 * the task to a terminal state exactly once.
 *
 * @param <T> the result type
 */
public interface VeloraTaskSource<T> {

    /** The observable task. */
    VeloraTask<T> task();

    /**
     * Complete the task successfully. Returns {@code false} if a terminal state
     * was already reached.
     */
    boolean succeed(T value);

    /**
     * Fail the task. Returns {@code false} if a terminal state was already reached.
     */
    boolean fail(Throwable error);

    /**
     * Cancel the task. Returns {@code false} if a terminal state was already reached.
     */
    boolean cancel();

    /**
     * Register a callback invoked when the task is cancelled. Must be idempotent.
     */
    void onCancel(Runnable callback);
}
