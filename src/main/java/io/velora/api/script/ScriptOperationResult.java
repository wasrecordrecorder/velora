package io.velora.api.script;

public record ScriptOperationResult(
        boolean success,
        String scriptId,
        ScriptStatus newStatus,
        String message,
        Throwable cause
) {
    public static ScriptOperationResult success(String scriptId, ScriptStatus newStatus) {
        return new ScriptOperationResult(true, scriptId, newStatus, null, null);
    }

    public static ScriptOperationResult failure(String scriptId, String message) {
        return new ScriptOperationResult(false, scriptId, null, message, null);
    }

    public static ScriptOperationResult failure(String scriptId, String message, Throwable cause) {
        return new ScriptOperationResult(false, scriptId, null, message, cause);
    }

    public boolean isFailure() {
        return !success;
    }
}
