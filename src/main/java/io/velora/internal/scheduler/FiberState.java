package io.velora.internal.scheduler;

public enum FiberState {
    READY,
    RUNNING,
    WAITING_TASK,
    SLEEPING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}
