package io.velora.internal.foreign;

public record ForeignExecutionResult(
        int status,
        Object value,
        long suspendParam,
        String error
) {
    public static final int STATUS_SUCCESS = 0;
    public static final int STATUS_SUSPENDED_DELAY = 1;
    public static final int STATUS_SUSPENDED_AWAIT = 2;
    public static final int STATUS_SUSPENDED_YIELD = 3;
    public static final int STATUS_ERROR = 4;

    public static ForeignExecutionResult success(Object value) {
        return new ForeignExecutionResult(STATUS_SUCCESS, value, 0, null);
    }

    public static ForeignExecutionResult delay(long durationNanos) {
        return new ForeignExecutionResult(STATUS_SUSPENDED_DELAY, null, durationNanos, null);
    }

    public static ForeignExecutionResult awaitTask(long taskId) {
        return new ForeignExecutionResult(STATUS_SUSPENDED_AWAIT, null, taskId, null);
    }

    public static ForeignExecutionResult yieldFiber() {
        return new ForeignExecutionResult(STATUS_SUSPENDED_YIELD, null, 0, null);
    }

    public static ForeignExecutionResult error(String message) {
        return new ForeignExecutionResult(STATUS_ERROR, null, 0, message);
    }

    public boolean isSuccess() {
        return status == STATUS_SUCCESS;
    }

    public boolean isSuspended() {
        return status == STATUS_SUSPENDED_DELAY || status == STATUS_SUSPENDED_AWAIT || status == STATUS_SUSPENDED_YIELD;
    }

    public boolean isError() {
        return status == STATUS_ERROR;
    }

    public boolean isSuspendedDelay() {
        return status == STATUS_SUSPENDED_DELAY;
    }

    public boolean isSuspendedYield() {
        return status == STATUS_SUSPENDED_YIELD;
    }

    public long suspendedDurationNanos() {
        return suspendParam;
    }

    public String errorMessage() {
        return error;
    }

    public Object result() {
        return value;
    }
}
