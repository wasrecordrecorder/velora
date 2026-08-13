package io.velora.api.task;

/**
 * Factory for creating task sources bound to a script instance.
 */
@FunctionalInterface
public interface TaskFactory {

    /**
     * Create a new task source. The returned source is linked to the calling
     * fiber's cancellation token so that cancelling the fiber cancels the task.
     */
    <T> VeloraTaskSource<T> create();
}
