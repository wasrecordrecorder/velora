package io.velora.internal.script;

import io.velora.api.script.ScriptDescriptor;
import io.velora.api.script.ScriptStatus;

public final class ScriptInstanceFactory {

    public static ScriptInstance create(String scriptId, ScriptDescriptor descriptor) {
        ScriptDescriptor desc = new ScriptDescriptor(
                descriptor.id(), descriptor.name(), descriptor.version(),
                descriptor.author(), descriptor.description(),
                ScriptStatus.DISCOVERED, false, descriptor.sourceFiles(),
                descriptor.permissions(), descriptor.activeRevision(),
                descriptor.errorCount(), descriptor.warningCount(),
                descriptor.lastReloadTimeNanos()
        );
        return new ScriptInstance(scriptId, desc);
    }
}
