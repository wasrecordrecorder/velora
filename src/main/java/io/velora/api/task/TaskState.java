package io.velora.api.task;

/**
 * Terminal states of a {@link VeloraTask}.
 *
 * <p>Transitions: {@code PENDING -> SUCCEEDED | FAILED | CANCELLED}.
 * Any terminal transition is performed exactly once atomically.
 */
public enum TaskState {
    PENDING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this != PENDING;
    }

    public boolean isSuccess() {
        return this == SUCCEEDED;
    }

    public boolean isCancelled() {
        return this == CANCELLED;
    }

    public boolean isFailure() {
        return this == FAILED;
    }
}
