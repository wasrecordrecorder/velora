package io.velora.api.task;

/**
 * A handle to an asynchronous operation observable from script code.
 *
 * <p>Host adapters create tasks through {@link VeloraTaskSource}. The terminal
 * transition (succeed/fail/cancel) is atomic and happens exactly once. Completion
 * listeners are never invoked on the emitter thread; they enqueue a completion
 * record into the scheduler which resumes the waiting fiber inside
 * {@code engine.tick()}.
 *
 * @param <T> the result type
 */
public interface VeloraTask<T> {

    TaskState state();

    /**
     * Result value. Only valid when {@link #state()} is {@link TaskState#SUCCEEDED}.
     *
     * @throws IllegalStateException if the task is not succeeded
     */
    T result();

    /**
     * Failure cause. Only valid when {@link #state()} is {@link TaskState#FAILED}.
     *
     * @throws IllegalStateException if the task is not failed
     */
    Throwable failure();

    /**
     * Request cancellation. Returns {@code false} if the task already reached a
     * terminal state. The cancellation callback registered on the source must be
     * idempotent.
     */
    boolean cancel();

    /**
     * Register a one-shot completion listener. The listener is invoked exactly
     * once and must not execute VM code directly.
     */
    void onComplete(TaskListener<T> listener);

    /**
     * Whether this task was cancelled before reaching a terminal success/failure.
     */
    default boolean isCancelled() {
        return state() == TaskState.CANCELLED;
    }
}
