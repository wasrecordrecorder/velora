package io.velora.internal.vm;

public record VmExecutionResult(
        boolean success,
        ScriptValue returnValue,
        VmError error,
        int instructionsExecuted,
        long wallTimeNanos,
        SuspendReason suspendReason,
        long suspendData
) {
    public enum SuspendReason { SLEEP, AWAIT, YIELD, CANCELLED, INSTRUCTION_LIMIT }

    public static VmExecutionResult success(ScriptValue returnValue, int instructionsExecuted, long wallTimeNanos) {
        return new VmExecutionResult(true, returnValue, null, instructionsExecuted, wallTimeNanos, null, 0);
    }

    public static VmExecutionResult failure(VmError error, int instructionsExecuted, long wallTimeNanos) {
        return new VmExecutionResult(false, null, error, instructionsExecuted, wallTimeNanos, null, 0);
    }

    public static VmExecutionResult suspended(SuspendReason reason, long data, int instructionsExecuted, long wallTimeNanos) {
        return new VmExecutionResult(false, null, null, instructionsExecuted, wallTimeNanos, reason, data);
    }

    public boolean isSuspended() {
        return suspendReason != null;
    }
}
