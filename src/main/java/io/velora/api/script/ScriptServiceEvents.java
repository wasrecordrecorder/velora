package io.velora.api.script;

import java.util.function.Consumer;

public interface ScriptServiceEvents {

    void subscribe(Consumer<ScriptServiceEvent> listener);

    void unsubscribe(Consumer<ScriptServiceEvent> listener);

    record ScriptServiceEvent(
            Type type,
            String scriptId,
            ScriptStatus status,
            String message
    ) {
        public ScriptServiceEvent {
            java.util.Objects.requireNonNull(type);
            java.util.Objects.requireNonNull(scriptId);
        }

        public static ScriptServiceEvent of(Type type, String scriptId) {
            return new ScriptServiceEvent(type, scriptId, null, null);
        }

        public static ScriptServiceEvent of(Type type, String scriptId, ScriptStatus status) {
            return new ScriptServiceEvent(type, scriptId, status, null);
        }

        public static ScriptServiceEvent of(Type type, String scriptId, String message) {
            return new ScriptServiceEvent(type, scriptId, null, message);
        }
    }

    enum Type {
        DISCOVERED,
        SOURCE_CHANGED,
        COMPILE_STARTED,
        COMPILE_FINISHED,
        STATUS_CHANGED,
        ENABLED,
        DISABLED,
        RELOADED,
        ROLLED_BACK,
        SETTINGS_CHANGED,
        PERMISSIONS_CHANGED,
        RUNTIME_ERROR,
        LOG_ADDED,
        PROFILER_UPDATED
    }
}
