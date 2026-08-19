package io.velora.internal.registry;

import io.velora.api.function.*;
import io.velora.api.registry.TypeRegistry;
import io.velora.api.type.VeloraType;

import java.util.*;
import java.util.function.Consumer;

public final class DefaultApiRegistry implements ApiRegistry {

    private final Map<String, Map<String, FunctionDescriptor>> byNamespace = new LinkedHashMap<>();
    private final TypeRegistry typeRegistry;
    private final List<FunctionDescriptor> all = new ArrayList<>();
    private boolean frozen;

    public DefaultApiRegistry(TypeRegistry typeRegistry) {
        this.typeRegistry = Objects.requireNonNull(typeRegistry);
    }

    @Override
    public void namespace(String name, Consumer<NamespaceBuilder> configurator) {
        checkFrozen();
        validateNamespace(name);
        Objects.requireNonNull(configurator, "configurator");
        int snapshot = all.size();
        try {
            configurator.accept(new NamespaceBuilderImpl(name));
        } catch (Throwable error) {
            rollbackTo(snapshot);
            throw error;
        }
    }

    private void validateNamespace(String name) {
        if (!isIdentifier(name)) throw new IllegalArgumentException("Namespace must be a script identifier: " + name);
    }

    private void validateFunctionName(String name) {
        if (!isIdentifier(name)) throw new IllegalArgumentException("Function name must be a script identifier: " + name);
    }

    private boolean isIdentifier(String value) {
        if (value == null || value.isEmpty() || !(Character.isLetter(value.charAt(0)) || value.charAt(0) == '_')) return false;
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') return false;
        }
        return true;
    }

    @Override
    public void register(FunctionDescriptor descriptor) {
        checkFrozen();
        validateNamespace(descriptor.namespace());
        validateDescriptor(descriptor);
        Map<String, FunctionDescriptor> ns = byNamespace.get(descriptor.namespace());
        if (ns != null && ns.containsKey(descriptor.name())) {
            throw new IllegalStateException("Function already registered: " + descriptor.namespace() + "." + descriptor.name());
        }
        byNamespace.computeIfAbsent(descriptor.namespace(), k -> new LinkedHashMap<>())
                .put(descriptor.name(), descriptor);
        all.add(descriptor);
    }

    private void validateDescriptor(FunctionDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        validateFunctionName(descriptor.name());
    }

    private void registerFromBuilder(FunctionDescriptor descriptor) {
        register(descriptor);
    }

    @Override
    public void registerAnnotated(Object binding) {
        checkFrozen();
        List<FunctionDescriptor> descriptors = new io.velora.binding.BindingScanner(new io.velora.binding.BindingDescriptorFactory(typeRegistry)).scan(binding);
        for (FunctionDescriptor descriptor : descriptors) {
            validateNamespace(descriptor.namespace());
            validateDescriptor(descriptor);
            Map<String, FunctionDescriptor> ns = byNamespace.get(descriptor.namespace());
            if (ns != null && ns.containsKey(descriptor.name())) throw new IllegalStateException("Function already registered: " + descriptor.qualifiedName());
        }
        for (FunctionDescriptor descriptor : descriptors) {
            byNamespace.computeIfAbsent(descriptor.namespace(), ignored -> new LinkedHashMap<>()).put(descriptor.name(), descriptor);
            all.add(descriptor);
        }
    }

    @Override
    public FunctionDescriptor find(String namespace, String name) {
        Map<String, FunctionDescriptor> ns = byNamespace.get(namespace);
        return ns != null ? ns.get(name) : null;
    }

    @Override
    public FunctionDescriptor findByIndex(int index) {
        if (index < 0 || index >= all.size()) return null;
        return all.get(index);
    }

    @Override
    public List<FunctionDescriptor> all() {
        return List.copyOf(all);
    }

    @Override
    public Collection<String> namespaces() {
        return Collections.unmodifiableSet(byNamespace.keySet());
    }

    @Override
    public boolean isFrozen() {
        return frozen;
    }

    public void freeze() {
        frozen = true;
        for (int i = 0; i < all.size(); i++) {
            FunctionDescriptor fd = all.get(i);
            FunctionDescriptor indexed = fd.withIndex(i);
            all.set(i, indexed);
            byNamespace.get(indexed.namespace()).put(indexed.name(), indexed);
        }
    }

    public void rollbackTo(int snapshotSize) {
        while (all.size() > snapshotSize) {
            FunctionDescriptor removed = all.remove(all.size() - 1);
            Map<String, FunctionDescriptor> ns = byNamespace.get(removed.namespace());
            if (ns != null) {
                ns.remove(removed.name());
                if (ns.isEmpty()) byNamespace.remove(removed.namespace());
            }
        }
    }

    private void checkFrozen() {
        if (frozen) throw new IllegalStateException("ApiRegistry is frozen");
    }

    private final class NamespaceBuilderImpl implements NamespaceBuilder {
        private final String ns;
        private FunctionDescriptor lastRegistered;

        NamespaceBuilderImpl(String ns) { this.ns = ns; }

        @Override public String namespace() { return ns; }

        @Override
        public NamespaceBuilder property(String name, VeloraType type, FunctionInvoker getter) {
            return property(name, type, getter, "");
        }

        @Override
        public NamespaceBuilder property(String name, VeloraType type, FunctionInvoker getter, String description) {
            FunctionDescriptor descriptor = FunctionDescriptor.builder()
                    .namespace(ns).name(name).returns(type).invoker(getter).description(description).property(true).build();
            registerFromBuilder(descriptor);
            lastRegistered = descriptor;
            return this;
        }

        @Override
        public NamespaceBuilder function(String name, VeloraType returnType, FunctionInvoker invoker) {
            return function(name, returnType, parameters -> {}, invoker);
        }

        @Override
        public NamespaceBuilder function(String name, VeloraType returnType, Consumer<ParameterListBuilder> parameters, FunctionInvoker invoker) {
            ParameterListBuilder builder = new ParameterListBuilder();
            parameters.accept(builder);
            FunctionDescriptor descriptor = FunctionDescriptor.builder()
                    .namespace(ns).name(name).parameters(builder.build()).returns(returnType).invoker(invoker).build();
            registerFromBuilder(descriptor);
            lastRegistered = descriptor;
            return this;
        }

        @Override
        public NamespaceBuilder suspendFunction(String name, VeloraType returnType, Consumer<ParameterListBuilder> parameters, FunctionInvoker invoker) {
            ParameterListBuilder builder = new ParameterListBuilder();
            parameters.accept(builder);
            FunctionDescriptor descriptor = FunctionDescriptor.builder()
                    .namespace(ns).name(name).parameters(builder.build()).returns(returnType).suspending(true).invoker(invoker).build();
            registerFromBuilder(descriptor);
            lastRegistered = descriptor;
            return this;
        }

        @Override
        public NamespaceBuilder description(String description) {
            if (lastRegistered != null) replaceLast(copyLast(description, lastRegistered.categoryId(), lastRegistered.thread(), lastRegistered.cost()));
            return this;
        }

        @Override
        public NamespaceBuilder categoryId(String categoryId) {
            if (lastRegistered != null) replaceLast(copyLast(lastRegistered.description(), categoryId, lastRegistered.thread(), lastRegistered.cost()));
            return this;
        }

        @Override
        public NamespaceBuilder thread(ScriptThread thread) {
            if (lastRegistered != null) replaceLast(copyLast(lastRegistered.description(), lastRegistered.categoryId(), thread, lastRegistered.cost()));
            return this;
        }

        @Override
        public NamespaceBuilder cost(int cost) {
            if (cost <= 0) throw new IllegalArgumentException("Cost must be positive");
            if (lastRegistered != null) replaceLast(copyLast(lastRegistered.description(), lastRegistered.categoryId(), lastRegistered.thread(), cost));
            return this;
        }

        private FunctionDescriptor copyLast(String description, String categoryId, ScriptThread thread, int cost) {
            return FunctionDescriptor.builder()
                    .namespace(lastRegistered.namespace())
                    .name(lastRegistered.name())
                    .parameters(lastRegistered.parameters())
                    .returns(lastRegistered.returnType())
                    .suspending(lastRegistered.suspending())
                    .property(lastRegistered.property())
                    .thread(thread)
                    .cost(cost)
                    .invoker(lastRegistered.invoker())
                    .description(description)
                    .categoryId(categoryId)
                    .build();
        }

        private void replaceLast(FunctionDescriptor descriptor) {
            Map<String, FunctionDescriptor> namespace = byNamespace.get(ns);
            if (namespace != null) namespace.remove(lastRegistered.name());
            all.remove(lastRegistered);
            registerFromBuilder(descriptor);
            lastRegistered = descriptor;
        }
    }
}
