package io.velora.internal.bytecode;

import io.velora.api.permission.PermissionSet;
import io.velora.api.setting.SettingDescriptor;
import io.velora.api.setting.SettingSchema;
import io.velora.internal.vm.ScriptValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A fully compiled, verified script module ready for VM execution.
 */
public final class CompiledModule {

    private final String scriptId;
    private final String scriptName;
    private final String version;
    private final int languageVersion;
    private final String author;
    private final String description;
    private final String sourceHash;
    private final String registryHash;
    private final ConstantPool constantPool;
    private final List<CompiledFunction> functions;
    private final Map<String, CompiledFunction> functionsByName;
    private final List<SettingDescriptor> settings;
    private final List<String> persistentFieldIds;
    private final List<String> persistentFieldTypes;
    private final List<Integer> persistentFieldIndices;
    private final List<Boolean> persistentFieldIsStatic;
    private final PermissionSet requiredPermissions;
    private final PermissionSet maximumPermissions;
    private final List<String> lifecycleHooks;        // names of lifecycle functions
    private final List<EventHandlerInfo> eventHandlers;
    private final List<FieldInitializer> fieldInitializers;

    public CompiledModule(String scriptId, String scriptName, String version, int languageVersion,
                          String sourceHash, String registryHash, ConstantPool constantPool,
                          List<CompiledFunction> functions, List<SettingDescriptor> settings,
                          List<String> persistentFieldIds, List<String> persistentFieldTypes,
                          List<Integer> persistentFieldIndices, List<Boolean> persistentFieldIsStatic,
                          PermissionSet requiredPermissions, PermissionSet maximumPermissions,
                          List<String> lifecycleHooks, List<EventHandlerInfo> eventHandlers) {
        this(scriptId, scriptName, version, languageVersion, sourceHash, registryHash, constantPool,
                functions, settings, persistentFieldIds, persistentFieldTypes, persistentFieldIndices, persistentFieldIsStatic,
                requiredPermissions, maximumPermissions, lifecycleHooks, eventHandlers, List.of(), null, null);
    }

    public CompiledModule(String scriptId, String scriptName, String version, int languageVersion,
                          String sourceHash, String registryHash, ConstantPool constantPool,
                          List<CompiledFunction> functions, List<SettingDescriptor> settings,
                          List<String> persistentFieldIds, List<String> persistentFieldTypes,
                          List<Integer> persistentFieldIndices, List<Boolean> persistentFieldIsStatic,
                          PermissionSet requiredPermissions, PermissionSet maximumPermissions,
                          List<String> lifecycleHooks, List<EventHandlerInfo> eventHandlers,
                          List<FieldInitializer> fieldInitializers) {
        this(scriptId, scriptName, version, languageVersion, sourceHash, registryHash, constantPool,
                functions, settings, persistentFieldIds, persistentFieldTypes, persistentFieldIndices, persistentFieldIsStatic,
                requiredPermissions, maximumPermissions, lifecycleHooks, eventHandlers, fieldInitializers, null, null);
    }

    public CompiledModule(String scriptId, String scriptName, String version, int languageVersion,
                          String sourceHash, String registryHash, ConstantPool constantPool,
                          List<CompiledFunction> functions, List<SettingDescriptor> settings,
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
        this.sourceHash = sourceHash;
        this.registryHash = registryHash;
        this.constantPool = constantPool;
        this.functions = List.copyOf(functions);
        Map<String, CompiledFunction> byName = new LinkedHashMap<>();
        for (CompiledFunction function : this.functions) byName.put(function.name(), function);
        this.functionsByName = Map.copyOf(byName);
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
    public String sourceHash() { return sourceHash; }
    public String registryHash() { return registryHash; }
    public ConstantPool constantPool() { return constantPool; }
    public List<CompiledFunction> functions() { return functions; }
    public List<SettingDescriptor> settings() { return settings; }
    public SettingSchema settingSchema() { return new SettingSchema(settings); }
    public List<String> persistentFieldIds() { return persistentFieldIds; }
    public List<String> persistentFieldTypes() { return persistentFieldTypes; }
    public List<Integer> persistentFieldIndices() { return persistentFieldIndices; }
    public List<Boolean> persistentFieldIsStatic() { return persistentFieldIsStatic; }
    public PermissionSet requiredPermissions() { return requiredPermissions; }
    public PermissionSet maximumPermissions() { return maximumPermissions; }
    public List<String> lifecycleHooks() { return lifecycleHooks; }
    public List<EventHandlerInfo> eventHandlers() { return eventHandlers; }
    public List<FieldInitializer> fieldInitializers() { return fieldInitializers; }

    public CompiledFunction function(int index) {
        return index >= 0 && index < functions.size() ? functions.get(index) : null;
    }

    public CompiledFunction functionByName(String name) {
        return functionsByName.get(name);
    }

    public record EventHandlerInfo(String eventReference, String functionName, int functionIndex, boolean suspending) {}

    public record FieldInitializer(int fieldIndex, boolean isStatic, ScriptValue initialValue) {}
}
