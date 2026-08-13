package io.velora.api.script;

public record ScriptRevision(
        String scriptId,
        long revisionNumber,
        String sourceHash,
        long createdAtNanos
) {
    public ScriptRevision {
        java.util.Objects.requireNonNull(scriptId);
        java.util.Objects.requireNonNull(sourceHash);
    }

    public static ScriptRevision initial(String scriptId, String sourceHash) {
        return new ScriptRevision(scriptId, 1, sourceHash, System.nanoTime());
    }

    public ScriptRevision next(String newSourceHash) {
        return new ScriptRevision(scriptId, revisionNumber + 1, newSourceHash, System.nanoTime());
    }
}
