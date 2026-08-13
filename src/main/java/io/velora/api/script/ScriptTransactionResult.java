package io.velora.api.script;

public record ScriptTransactionResult(
        boolean success,
        String scriptId,
        ScriptTransaction.ConflictReason conflictReason,
        String message
) {
    public static ScriptTransactionResult success(String scriptId) {
        return new ScriptTransactionResult(true, scriptId, null, null);
    }

    public static ScriptTransactionResult conflict(String scriptId, ScriptTransaction.ConflictReason reason, String message) {
        return new ScriptTransactionResult(false, scriptId, reason, message);
    }

    public static ScriptTransactionResult failure(String scriptId, String message) {
        return new ScriptTransactionResult(false, scriptId, null, message);
    }

}
