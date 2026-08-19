package io.velora.api.function;

import io.velora.api.type.VeloraType;

import java.util.*;

/**
 * Descriptor for a registered host function.
 */
public final class FunctionDescriptor {

    private final String namespace;
    private final String name;
    private final String description;
    private final String categoryId;
    private final String extensionId;
    private final List<ParameterDescriptor> parameters;
    private final VeloraType returnType;
    private final boolean suspending;
    private final boolean property;
    private final ScriptThread thread;
    private final int cost;
    private final FunctionInvoker invoker;
    private final int index; // assigned at freeze

    private FunctionDescriptor(Builder b) {
        this.namespace = b.namespace;
        this.name = b.name;
        this.description = b.description == null ? "" : b.description;
        this.categoryId = b.categoryId;
        this.extensionId = b.extensionId;
        this.parameters = List.copyOf(b.parameters);
        this.returnType = b.returnType;
        this.suspending = b.suspending;
        this.property = b.property;
        this.thread = b.thread;
        this.cost = b.cost;
        this.invoker = b.invoker;
        this.index = b.index;
    }

    public FunctionDescriptor withIndex(int index) {
        Builder b = new Builder();
        b.namespace = this.namespace;
        b.name = this.name;
        b.description = this.description;
        b.categoryId = this.categoryId;
        b.extensionId = this.extensionId;
        b.parameters = new ArrayList<>(this.parameters);
        b.returnType = this.returnType;
        b.suspending = this.suspending;
        b.property = this.property;
        b.thread = this.thread;
        b.cost = this.cost;
        b.invoker = this.invoker;
        b.index = index;
        return b.buildWithIndex();
    }

    public String namespace() { return namespace; }
    public String name() { return name; }
    public String description() { return description; }
    public String categoryId() { return categoryId; }
    public String extensionId() { return extensionId; }
    public List<ParameterDescriptor> parameters() { return parameters; }
    public VeloraType returnType() { return returnType; }
    public boolean suspending() { return suspending; }
    public boolean property() { return property; }
    public ScriptThread thread() { return thread; }
    public int cost() { return cost; }
    public FunctionInvoker invoker() { return invoker; }
    public int index() { return index; }
    public boolean variadic() { return !parameters.isEmpty() && parameters.get(parameters.size() - 1).variadic(); }
    public int minimumArgumentCount() { return (int) parameters.stream().filter(ParameterDescriptor::required).count(); }
    public int maximumArgumentCount() { return variadic() ? Integer.MAX_VALUE : parameters.size(); }

    /**
     * Qualified name: namespace.name
     */
    public String qualifiedName() {
        return namespace + "." + name;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String namespace;
        private String name;
        private String description = "";
        private String categoryId = "";
        private String extensionId = "";
        private List<ParameterDescriptor> parameters = new ArrayList<>();
        private VeloraType returnType;
        private boolean suspending = false;
        private boolean property = false;
        private ScriptThread thread = ScriptThread.ANY;
        private int cost = 1;
        private FunctionInvoker invoker;
        private int index = -1;

        public Builder namespace(String v) { this.namespace = v; return this; }
        public Builder name(String v) { this.name = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder categoryId(String v) { this.categoryId = v; return this; }
        public Builder extensionId(String v) { this.extensionId = v; return this; }
        public Builder parameter(String name, VeloraType type) { this.parameters.add(ParameterDescriptor.required(name, type)); return this; }
        public Builder parameter(String name, VeloraType type, String description) { this.parameters.add(ParameterDescriptor.required(name, type, description)); return this; }
        public Builder variadicParameter(String name, VeloraType type) { this.parameters.add(ParameterDescriptor.variadic(name, type)); return this; }
        public Builder variadicParameter(String name, VeloraType type, String description) { this.parameters.add(ParameterDescriptor.variadic(name, type, description)); return this; }
        public Builder parameter(ParameterDescriptor param) { this.parameters.add(param); return this; }
        public Builder parameters(List<ParameterDescriptor> params) { this.parameters = new ArrayList<>(params); return this; }
        public Builder returns(VeloraType type) { this.returnType = type; return this; }
        public Builder suspending(boolean v) { this.suspending = v; return this; }
        public Builder property(boolean v) { this.property = v; return this; }
        public Builder thread(ScriptThread v) { this.thread = v; return this; }
        public Builder cost(int v) { this.cost = v; return this; }
        public Builder invoker(FunctionInvoker v) { this.invoker = v; return this; }

        public FunctionDescriptor build() {
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(returnType, "returnType");
            if (!isIdentifier(namespace)) throw new IllegalArgumentException("Namespace must be a script identifier: " + namespace);
            if (!isIdentifier(name)) throw new IllegalArgumentException("Function name must be a script identifier: " + name);
            Objects.requireNonNull(thread, "thread");
            Objects.requireNonNull(invoker, "invoker");
            if (cost <= 0) throw new IllegalArgumentException("Cost must be positive");
            if (thread == ScriptThread.WORKER && !suspending) throw new IllegalArgumentException("WORKER functions must be suspending");
            if (property && (!parameters.isEmpty() || suspending)) throw new IllegalArgumentException("Properties cannot declare parameters or suspend");
            if (description != null && !description.isEmpty() && description.isBlank()) throw new IllegalArgumentException("Description cannot be blank");
            Set<String> names = new HashSet<>();
            boolean optionalSeen = false;
            boolean variadicSeen = false;
            for (int i = 0; i < parameters.size(); i++) {
                ParameterDescriptor parameter = parameters.get(i);
                if (!isIdentifier(parameter.name())) throw new IllegalArgumentException("Invalid parameter name: " + parameter.name());
                if (!names.add(parameter.name())) throw new IllegalArgumentException("Duplicate parameter name: " + parameter.name());
                if (parameter.description() != null && !parameter.description().isEmpty() && parameter.description().isBlank()) throw new IllegalArgumentException("Parameter description cannot be blank: " + parameter.name());
                if (parameter.variadic()) {
                    if (variadicSeen || i != parameters.size() - 1) throw new IllegalArgumentException("Variadic parameter must be the last parameter: " + parameter.name());
                    variadicSeen = true;
                } else if (parameter.hasDefault()) {
                    optionalSeen = true;
                    validateDefault(parameter);
                } else if (optionalSeen) {
                    throw new IllegalArgumentException("Required parameter cannot follow an optional parameter: " + parameter.name());
                }
            }
            return new FunctionDescriptor(this);
        }

        private void validateDefault(ParameterDescriptor parameter) {
            Object value = parameter.defaultValue();
            VeloraType target = parameter.type();
            if (value == null) {
                if (!target.isNullable()) throw new IllegalArgumentException("Null default requires nullable parameter type: " + parameter.name());
                return;
            }
            VeloraType source = defaultType(value);
            if (source == null) throw new IllegalArgumentException("Unsupported host default value for parameter '" + parameter.name() + "': " + value.getClass().getTypeName());
            if (!io.velora.api.type.VeloraTypes.isCompatible(source, target.nonNull())) {
                throw new IllegalArgumentException("Default value type mismatch for parameter '" + parameter.name() + "': expected " + target.name() + ", got " + source.name());
            }
            if (source == io.velora.api.type.VeloraTypes.BYTE || source == io.velora.api.type.VeloraTypes.CHAR) {
                throw new IllegalArgumentException("Byte and Char defaults are not supported in bytecode parameters: " + parameter.name());
            }
        }

        private VeloraType defaultType(Object value) {
            if (value instanceof Byte) return io.velora.api.type.VeloraTypes.BYTE;
            if (value instanceof Short || value instanceof Integer) return io.velora.api.type.VeloraTypes.INT;
            if (value instanceof Long) return io.velora.api.type.VeloraTypes.LONG;
            if (value instanceof Float) return io.velora.api.type.VeloraTypes.FLOAT;
            if (value instanceof Double) return io.velora.api.type.VeloraTypes.DOUBLE;
            if (value instanceof Boolean) return io.velora.api.type.VeloraTypes.BOOLEAN;
            if (value instanceof Character) return io.velora.api.type.VeloraTypes.CHAR;
            if (value instanceof String) return io.velora.api.type.VeloraTypes.STRING;
            if (value instanceof java.time.Duration) return io.velora.api.type.VeloraTypes.DURATION;
            if (value instanceof java.util.UUID) return io.velora.api.type.VeloraTypes.UUID;
            return null;
        }

        private boolean isIdentifier(String value) {
            if (value == null || value.isEmpty() || !(Character.isLetter(value.charAt(0)) || value.charAt(0) == '_')) return false;
            for (int i = 1; i < value.length(); i++) {
                char c = value.charAt(i);
                if (!Character.isLetterOrDigit(c) && c != '_') return false;
            }
            return true;
        }

        FunctionDescriptor buildWithIndex() {
            FunctionDescriptor d = new FunctionDescriptor(this);
            return d;
        }
    }
}
