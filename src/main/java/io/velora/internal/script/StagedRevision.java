package io.velora.internal.script;

public record StagedRevision(
        long revisionNumber,
        String sourceHash,
        io.velora.internal.bytecode.CompiledModule compiledModule,
        io.velora.internal.setting.SettingStore settingStore
) {}
