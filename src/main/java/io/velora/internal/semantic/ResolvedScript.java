package io.velora.internal.semantic;

import io.velora.api.function.ApiRegistry;
import io.velora.api.setting.SettingDescriptor;
import io.velora.api.setting.SettingSchema;
import io.velora.api.type.VeloraType;
import io.velora.internal.ast.BlockNode;

import java.util.List;
import java.util.Map;

public final class ResolvedScript {
    private final ScriptMetadata metadata;
    private final List<SettingDescriptor> settings;
    private final Map<String, ResolvedProperty> properties;
    private final Map<String, ResolvedFunction> functions;
    private final Map<LifecycleHook, ResolvedFunction> lifecycle;
    private final List<ResolvedEventHandler> eventHandlers;
    private final int languageVersion;
    private ApiRegistry apiRegistry;

    public ResolvedScript(ScriptMetadata metadata, List<SettingDescriptor> settings,
                          Map<String, ResolvedProperty> properties, Map<String, ResolvedFunction> functions,
                          Map<LifecycleHook, ResolvedFunction> lifecycle,
                          List<ResolvedEventHandler> eventHandlers, int languageVersion) {
        this.metadata = metadata;
        this.settings = settings;
        this.properties = properties;
        this.functions = functions;
        this.lifecycle = lifecycle;
        this.eventHandlers = eventHandlers;
        this.languageVersion = languageVersion;
    }

    public ScriptMetadata metadata() { return metadata; }
    public List<SettingDescriptor> settings() { return settings; }
    public SettingSchema settingSchema() { return new SettingSchema(settings); }
    public Map<String, ResolvedProperty> properties() { return properties; }
    public Map<String, ResolvedFunction> functions() { return functions; }
    public Map<LifecycleHook, ResolvedFunction> lifecycle() { return lifecycle; }
    public List<ResolvedEventHandler> eventHandlers() { return eventHandlers; }
    public int languageVersion() { return languageVersion; }
    public ApiRegistry apiRegistry() { return apiRegistry; }
    public void setApiRegistry(ApiRegistry apiRegistry) { this.apiRegistry = apiRegistry; }
    public ResolvedFunction lifecycle(LifecycleHook hook) { return lifecycle.get(hook); }

    public record ScriptMetadata(String id, String name, String version, String author, String description) {
        public ScriptMetadata {
            java.util.Objects.requireNonNull(id);
            java.util.Objects.requireNonNull(name);
            version = version == null ? "" : version;
            author = author == null ? "" : author;
            description = description == null ? "" : description;
        }
    }

    public static final class ResolvedProperty {
        private final String name;
        private final VeloraType type;
        private final boolean mutable;
        private final boolean persistent;
        private final String persistentId;
        private final int fieldIndex;
        private final boolean isStatic;
        private final boolean isConst;
        private final Object constValue;

        public ResolvedProperty(String name, VeloraType type, boolean mutable, boolean persistent, String persistentId, int fieldIndex) {
            this(name, type, mutable, persistent, persistentId, fieldIndex, false, false, null);
        }

        public ResolvedProperty(String name, VeloraType type, boolean mutable, boolean persistent, String persistentId,
                                int fieldIndex, boolean isStatic, boolean isConst, Object constValue) {
            this.name = name;
            this.type = type;
            this.mutable = mutable;
            this.persistent = persistent;
            this.persistentId = persistentId != null ? persistentId : name;
            this.fieldIndex = fieldIndex;
            this.isStatic = isStatic;
            this.isConst = isConst;
            this.constValue = constValue;
        }

        public String name() { return name; }
        public VeloraType type() { return type; }
        public boolean mutable() { return mutable; }
        public boolean persistent() { return persistent; }
        public String persistentId() { return persistentId; }
        public int fieldIndex() { return fieldIndex; }
        public boolean isStatic() { return isStatic; }
        public boolean isConst() { return isConst; }
        public Object constValue() { return constValue; }
    }

    public static final class ResolvedFunction {
        private final String name;
        private final List<ResolvedParam> parameters;
        private VeloraType returnType;
        private final boolean explicitReturnType;
        private final boolean suspending;
        private final BlockNode body;
        private final int functionIndex;
        private final boolean isLifecycle;

        public ResolvedFunction(String name, List<ResolvedParam> parameters, VeloraType returnType, boolean explicitReturnType,
                                boolean suspending, BlockNode body, int functionIndex, boolean isLifecycle) {
            this.name = name;
            this.parameters = List.copyOf(parameters);
            this.returnType = returnType;
            this.explicitReturnType = explicitReturnType;
            this.suspending = suspending;
            this.body = body;
            this.functionIndex = functionIndex;
            this.isLifecycle = isLifecycle;
        }

        public String name() { return name; }
        public List<ResolvedParam> parameters() { return parameters; }
        public VeloraType returnType() { return returnType; }
        public void returnType(VeloraType returnType) { this.returnType = returnType; }
        public boolean explicitReturnType() { return explicitReturnType; }
        public boolean suspending() { return suspending; }
        public BlockNode body() { return body; }
        public int functionIndex() { return functionIndex; }
        public boolean isLifecycle() { return isLifecycle; }
    }

    public record ResolvedParam(String name, VeloraType type, boolean hasDefault, int index, Object defaultValue) {}

    public static final class ResolvedEventHandler {
        private final String eventReference;
        private final String functionName;
        private final String parameterName;
        private final VeloraType parameterType;
        private final boolean suspending;
        private final BlockNode body;
        private final int functionIndex;

        public ResolvedEventHandler(String eventReference, String functionName, String parameterName,
                                    VeloraType parameterType, boolean suspending, BlockNode body, int functionIndex) {
            this.eventReference = eventReference;
            this.functionName = functionName;
            this.parameterName = parameterName;
            this.parameterType = parameterType;
            this.suspending = suspending;
            this.body = body;
            this.functionIndex = functionIndex;
        }

        public String eventReference() { return eventReference; }
        public String functionName() { return functionName; }
        public String parameterName() { return parameterName; }
        public VeloraType parameterType() { return parameterType; }
        public boolean suspending() { return suspending; }
        public BlockNode body() { return body; }
        public int functionIndex() { return functionIndex; }
    }
}
