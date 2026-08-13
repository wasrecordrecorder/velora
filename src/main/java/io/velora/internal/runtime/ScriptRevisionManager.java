package io.velora.internal.runtime;

import java.util.*;

public final class ScriptRevisionManager {
    private final Map<String, Long> revisions = new HashMap<>();
    private final Map<String, StagedRevision> staged = new HashMap<>();

    public long currentRevision(String scriptId) {
        return revisions.getOrDefault(scriptId, 0L);
    }

    public void commit(String scriptId, StagedRevision revision) {
        revisions.put(scriptId, revision.revisionNumber());
        staged.remove(scriptId);
    }

    public void stage(String scriptId, StagedRevision revision) {
        staged.put(scriptId, revision);
    }

    public StagedRevision staged(String scriptId) {
        return staged.get(scriptId);
    }

    public void clearStaged(String scriptId) {
        staged.remove(scriptId);
    }

    public void clear(String scriptId) {
        revisions.remove(scriptId);
        staged.remove(scriptId);
    }
}
