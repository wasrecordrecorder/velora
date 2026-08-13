package io.velora.internal.ir;

import io.velora.api.permission.PermissionSet;
import io.velora.api.setting.SettingDescriptor;
import io.velora.internal.vm.ScriptValue;

import java.util.List;

public final class IrModule {
    private final String scriptId;
    private final String scriptName;
    private final String version;
    private final int languageVersion;
    private final String author;
    private final String description;
    private final List<IrFunction> functions;
    private final List<SettingDescriptor> settings;
    private final List<String> persistentFieldIds;
    private final List<String> persistentFieldTypes;
    private final List<Integer> persistentFieldIndices;
    private final List<Boolean> persistentFieldIsStatic;
    private final PermissionSet requiredPermissions;
    private final PermissionSet maximumPermissions;
    private final List<String> lifecycleHooks;
    private final List<EventHandlerInfo> eventHandlers;
    private final List<FieldInitializer> fieldInitializers;

    public IrModule(String scriptId, String scriptName, String version, int languageVersion,
                    List<IrFunction> functions, List<SettingDescriptor> settings,
                    List<String> persistentFieldIds, List<String> persistentFieldTypes,
                    PermissionSet requiredPermissions, PermissionSet maximumPermissions,
                    List<String> lifecycleHooks, List<EventHandlerInfo> eventHandlers) {
        this(scriptId, scriptName, version, languageVersion, functions, settings,
                persistentFieldIds, persistentFieldTypes, List.of(), List.of(),
                requiredPermissions, maximumPermissions, lifecycleHooks, eventHandlers, List.of(), null, null);
    }

    public IrModule(String scriptId, String scriptName, String version, int languageVersion,
                    List<IrFunction> functions, List<SettingDescriptor> settings,
                    List<String> persistentFieldIds, List<String> persistentFieldTypes,
                    List<Integer> persistentFieldIndices, List<Boolean> persistentFieldIsStatic,
                    PermissionSet requiredPermissions, PermissionSet maximumPermissions,
                    List<String> lifecycleHooks, List<EventHandlerInfo> eventHandlers,
                    List<FieldInitializer> fieldInitializers) {
        this(scriptId, scriptName, version, languageVersion, functions, settings,
                persistentFieldIds, persistentFieldTypes, persistentFieldIndices, persistentFieldIsStatic,
                requiredPermissions, maximumPermissions, lifecycleHooks, eventHandlers, fieldInitializers, null, null);
    }

    public IrModule(String scriptId, String scriptName, String version, int languageVersion,
                    List<IrFunction> functions, List<SettingDescriptor> settings,
                    List<String> persistentFieldIds, List<String> persistentFieldTypes,
                    List<Integer> persistentFieldIndices, List<Boolean> persistentFieldIsStatic,
                    PermissionSet requiredPermissions, PermissionSet maximumPermissions,
                    List<String> lifecycleHooks, List<EventHandlerInfo> eventHandlers,
                    List<FieldInitializer> fieldInitializers,
                    String author, String description) {
        this.scriptId = scriptId;
        this.scriptName = scriptName;
        this.version = version;
        this.languageVersion = languageVersion;
        this.author = author;
        this.description = description;
        this.functions = List.copyOf(functions);
        this.settings = List.copyOf(settings);
        this.persistentFieldIds = List.copyOf(persistentFieldIds);
        this.persistentFieldTypes = List.copyOf(persistentFieldTypes);
        this.persistentFieldIndices = List.copyOf(persistentFieldIndices);
        this.persistentFieldIsStatic = List.copyOf(persistentFieldIsStatic);
        this.requiredPermissions = requiredPermissions;
        this.maximumPermissions = maximumPermissions;
        this.lifecycleHooks = List.copyOf(lifecycleHooks);
        this.eventHandlers = List.copyOf(eventHandlers);
        this.fieldInitializers = List.copyOf(fieldInitializers);
    }

    public String scriptId() { return scriptId; }
    public String scriptName() { return scriptName; }
    public String version() { return version; }
    public int languageVersion() { return languageVersion; }
    public String author() { return author; }
    public String description() { return description; }
    public List<IrFunction> functions() { return functions; }
    public List<SettingDescriptor> settings() { return settings; }
    public List<String> persistentFieldIds() { return persistentFieldIds; }
    public List<String> persistentFieldTypes() { return persistentFieldTypes; }
    public List<Integer> persistentFieldIndices() { return persistentFieldIndices; }
    public List<Boolean> persistentFieldIsStatic() { return persistentFieldIsStatic; }
    public PermissionSet requiredPermissions() { return requiredPermissions; }
    public PermissionSet maximumPermissions() { return maximumPermissions; }
    public List<String> lifecycleHooks() { return lifecycleHooks; }
    public List<EventHandlerInfo> eventHandlers() { return eventHandlers; }
    public List<FieldInitializer> fieldInitializers() { return fieldInitializers; }

    public record EventHandlerInfo(String eventReference, String functionName, int functionIndex, boolean suspending) {}

    public record FieldInitializer(int fieldIndex, boolean isStatic, IrValue initialValue) {}
}
