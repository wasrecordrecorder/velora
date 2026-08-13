package io.velora.internal.runtime;

import io.velora.api.script.ScriptStatus;

public final class ActivationController {

    public boolean canActivate(ScriptStatusMachine statusMachine) {
        return statusMachine.canEnable();
    }

    public boolean canDeactivate(ScriptStatusMachine statusMachine) {
        return statusMachine.canDisable();
    }

    public void activate(ScriptStatusMachine statusMachine) {
        statusMachine.transition(ScriptStatus.ENABLING);
        statusMachine.transition(ScriptStatus.ENABLED);
    }

    public void deactivate(ScriptStatusMachine statusMachine) {
        statusMachine.transition(ScriptStatus.DISABLING);
        statusMachine.transition(ScriptStatus.DISABLED);
    }
}
