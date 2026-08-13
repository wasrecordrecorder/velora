package io.velora.internal.runtime;

import io.velora.api.script.ScriptStatus;

public final class RollbackController {

    public boolean shouldRollback(Throwable activationError) {
        return activationError != null;
    }

    public void rollback(ScriptStatusMachine statusMachine, StagedRevision staged) {
        statusMachine.transition(ScriptStatus.FAILED);
    }

    public void rollbackToOld(ScriptStatusMachine statusMachine) {
        statusMachine.transition(ScriptStatus.DISABLED);
    }
}
