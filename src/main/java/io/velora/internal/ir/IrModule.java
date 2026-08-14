package io.velora.internal.ir;

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
    private final List<String> lifecycleHooks;
    private final List<EventHandlerInfo> eventHandlers;
    private final List<FieldInitializer> fieldInitializers;

    public IrModule(String scriptId, String scriptName, String version, int languageVersion,
                    List<IrFunction> functions, List<SettingDescriptor> settings,
                    List<String> persistentFieldIds, List<String> persistentFieldTypes,
                    List<Integer> persistentFieldIndices, List<Boolean> persistentFieldIsStatic,
                    List<String> lifecycleHooks, List<EventHandlerInfo> eventHandlers,
                    List<FieldInitializer> fieldInitializers, String author, String description) {
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
    public List<String> lifecycleHooks() { return lifecycleHooks; }
    public List<EventHandlerInfo> eventHandlers() { return eventHandlers; }
    public List<FieldInitializer> fieldInitializers() { return fieldInitializers; }

    public record EventHandlerInfo(String eventReference, String functionName, int functionIndex, boolean suspending) {}
    public record FieldInitializer(int fieldIndex, boolean isStatic, ScriptValue initialValue) {}
}
