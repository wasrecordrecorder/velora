package io.velora.api.script;

import io.velora.api.setting.SettingValue;

public interface ScriptTransaction {

    ScriptTransaction expectRevision(long revisionNumber);

    ScriptTransaction write(String relativePath, String content, String expectedFileRevision);

    ScriptTransaction updateSetting(String settingId, SettingValue value);

    ScriptTransaction delete(String relativePath);

    ScriptTransactionResult validateAndCommit(CommitMode mode);

    enum CommitMode {
        VALIDATE_ONLY,
        COMMIT_WITHOUT_RELOAD,
        RELOAD_IF_VALID
    }

    enum ConflictReason {
        SOURCE_REVISION_CONFLICT,
        SETTING_MIGRATION_CONFLICT,
        PERMISSION_CONFLICT,
        COMPILE_ERROR,
        ACTIVATION_ERROR
    }
}
