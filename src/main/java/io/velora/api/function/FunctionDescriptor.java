package io.velora.api.function;

import io.velora.api.permission.ScriptPermission;
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
    private final ScriptThread thread;
    private final ScriptPermission permission;
    private final int cost;
    private final FunctionInvoker invoker;
    private final int index; // assigned at freeze

    private FunctionDescriptor(Builder b) {
        this.namespace = b.namespace;
        this.name = b.name;
        this.description = b.description;
        this.categoryId = b.categoryId;
        this.extensionId = b.extensionId;
        this.parameters = List.copyOf(b.parameters);
        this.returnType = b.returnType;
        this.suspending = b.suspending;
        this.thread = b.thread;
        this.permission = b.permission;
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
        b.thread = this.thread;
        b.permission = this.permission;
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
    public ScriptThread thread() { return thread; }
    public ScriptPermission permission() { return permission; }
    public int cost() { return cost; }
    public FunctionInvoker invoker() { return invoker; }
    public int index() { return index; }

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
        private ScriptThread thread = ScriptThread.ANY;
        private ScriptPermission permission;
        private int cost = 1;
        private FunctionInvoker invoker;
        private int index = -1;

        public Builder namespace(String v) { this.namespace = v; return this; }
        public Builder name(String v) { this.name = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder categoryId(String v) { this.categoryId = v; return this; }
        public Builder extensionId(String v) { this.extensionId = v; return this; }
        public Builder parameter(String name, VeloraType type) { this.parameters.add(ParameterDescriptor.required(name, type)); return this; }
        public Builder parameter(ParameterDescriptor param) { this.parameters.add(param); return this; }
        public Builder parameters(List<ParameterDescriptor> params) { this.parameters = new ArrayList<>(params); return this; }
        public Builder returns(VeloraType type) { this.returnType = type; return this; }
        public Builder suspending(boolean v) { this.suspending = v; return this; }
        public Builder thread(ScriptThread v) { this.thread = v; return this; }
        public Builder permission(ScriptPermission v) { this.permission = v; return this; }
        public Builder cost(int v) { this.cost = v; return this; }
        public Builder invoker(FunctionInvoker v) { this.invoker = v; return this; }

        public FunctionDescriptor build() {
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(returnType, "returnType");
            Objects.requireNonNull(invoker, "invoker");
            if (cost <= 0) {
                throw new IllegalArgumentException("Cost must be positive");
            }
            if (description != null && !description.isEmpty() && description.isBlank()) {
                throw new IllegalArgumentException("Description cannot be blank");
            }
            return new FunctionDescriptor(this);
        }

        FunctionDescriptor buildWithIndex() {
            FunctionDescriptor d = new FunctionDescriptor(this);
            return d;
        }
    }
}
