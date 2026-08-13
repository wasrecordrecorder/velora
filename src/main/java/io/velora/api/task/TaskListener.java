package io.velora.api.task;

/**
 * One-shot completion listener for a {@link VeloraTask}.
 *
 * @param <T> the result type
 */
@FunctionalInterface
public interface TaskListener<T> {
    /**
     * Called exactly once when the task reaches a terminal state. Implementations
     * must not execute VM code directly; they should enqueue work into the
     * scheduler completion queue.
     */
    void onComplete(VeloraTask<T> task);
}
