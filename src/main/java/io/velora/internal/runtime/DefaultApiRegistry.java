package io.velora.internal.runtime;

import io.velora.api.function.*;
import io.velora.api.permission.ScriptPermission;
import io.velora.api.type.VeloraType;

import java.util.*;
import java.util.function.Consumer;

public final class DefaultApiRegistry implements ApiRegistry {

    private final Map<String, Map<String, FunctionDescriptor>> byNamespace = new LinkedHashMap<>();
    private final List<FunctionDescriptor> all = new ArrayList<>();
    private boolean frozen;

    @Override
    public void namespace(String name, Consumer<NamespaceBuilder> configurator) {
        checkFrozen();
        validateNamespace(name);
        NamespaceBuilderImpl builder = new NamespaceBuilderImpl(name);
        configurator.accept(builder);
    }

    private void validateNamespace(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Namespace cannot be empty");
        }
        if (name.trim().isEmpty()) {
            throw new IllegalArgumentException("Namespace cannot be whitespace only");
        }
        if (Character.isDigit(name.charAt(0))) {
            throw new IllegalArgumentException("Namespace cannot start with a digit");
        }
        if (name.contains(" ")) {
            throw new IllegalArgumentException("Namespace cannot contain spaces");
        }
        if (name.contains("/")) {
            throw new IllegalArgumentException("Namespace cannot contain slashes");
        }
        if (name.contains("..")) {
            throw new IllegalArgumentException("Namespace cannot contain consecutive dots");
        }
        if (name.contains("@")) {
            throw new IllegalArgumentException("Namespace cannot contain @");
        }
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
        if (descriptor.description() == null || descriptor.description().isBlank()) {
            throw new IllegalArgumentException("Function descriptor requires non-blank description: " + descriptor.namespace() + "." + descriptor.name());
        }
        if (descriptor.categoryId() == null || descriptor.categoryId().isBlank()) {
            throw new IllegalArgumentException("Function descriptor requires non-blank categoryId: " + descriptor.namespace() + "." + descriptor.name());
        }
    }

    void registerOrReplace(FunctionDescriptor descriptor) {
        checkFrozen();
        validateNamespace(descriptor.namespace());
        Map<String, FunctionDescriptor> ns = byNamespace.get(descriptor.namespace());
        if (ns != null && ns.containsKey(descriptor.name())) {
            FunctionDescriptor old = ns.get(descriptor.name());
            if (builtInFunctions.contains(descriptor.namespace() + "." + descriptor.name())) {
                ns.remove(descriptor.name());
                all.remove(old);
            } else {
                throw new IllegalStateException("Function already registered: " + descriptor.namespace() + "." + descriptor.name());
            }
        }
        byNamespace.computeIfAbsent(descriptor.namespace(), k -> new LinkedHashMap<>())
                .put(descriptor.name(), descriptor);
        all.add(descriptor);
    }

    void markBuiltIn(String namespace, String name) {
        builtInFunctions.add(namespace + "." + name);
    }

    private final Set<String> builtInFunctions = new HashSet<>();

    @Override
    public void registerAnnotated(Object binding) {
        checkFrozen();
        new io.velora.binding.BindingScanner(new io.velora.binding.BindingDescriptorFactory(new io.velora.binding.JavaTypeAdapterRegistry()))
                .scan(binding).forEach(this::register);
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

    void freeze() {
        frozen = true;
        for (int i = 0; i < all.size(); i++) {
            FunctionDescriptor fd = all.get(i);
            FunctionDescriptor indexed = fd.withIndex(i);
            all.set(i, indexed);
            byNamespace.get(indexed.namespace()).put(indexed.name(), indexed);
        }
    }

    void rollbackTo(int snapshotSize) {
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
        private FunctionDescriptor lastRegistered = null;

        NamespaceBuilderImpl(String ns) {
            this.ns = ns;
        }

        @Override
        public String namespace() {
            return ns;
        }

        @Override
        public NamespaceBuilder property(String name, VeloraType type, FunctionInvoker getter) {
            return property(name, type, null, getter, "");
        }

        @Override
        public NamespaceBuilder property(String name, VeloraType type, FunctionInvoker getter, String description) {
            return property(name, type, null, getter, description);
        }

        @Override
        public NamespaceBuilder property(String name, VeloraType type, ScriptPermission permission, FunctionInvoker getter) {
            return property(name, type, permission, getter, "");
        }

        @Override
        public NamespaceBuilder property(String name, VeloraType type, ScriptPermission permission, FunctionInvoker getter, String description) {
            FunctionDescriptor fd = FunctionDescriptor.builder()
                    .namespace(ns).name(name)
                    .returns(type)
                    .permission(permission)
                    .invoker(getter)
                    .description(description)
                    .build();
            registerOrReplace(fd);
            lastRegistered = fd;
            return this;
        }

        @Override
        public NamespaceBuilder function(String name, VeloraType returnType, FunctionInvoker invoker) {
            return function(name, returnType, null, invoker);
        }

        @Override
        public NamespaceBuilder function(String name, VeloraType returnType, ScriptPermission permission, FunctionInvoker invoker) {
            FunctionDescriptor fd = FunctionDescriptor.builder()
                    .namespace(ns).name(name)
                    .returns(returnType)
                    .permission(permission)
                    .invoker(invoker)
                    .build();
            registerOrReplace(fd);
            lastRegistered = fd;
            return this;
        }

        @Override
        public NamespaceBuilder description(String description) {
            if (lastRegistered != null) {
                Map<String, FunctionDescriptor> nsMap = byNamespace.get(ns);
                if (nsMap != null) {
                    nsMap.remove(lastRegistered.name());
                }
                all.remove(lastRegistered);

                FunctionDescriptor newFd = FunctionDescriptor.builder()
                        .namespace(lastRegistered.namespace())
                        .name(lastRegistered.name())
                        .parameters(lastRegistered.parameters())
                        .returns(lastRegistered.returnType())
                        .suspending(lastRegistered.suspending())
                        .thread(lastRegistered.thread())
                        .permission(lastRegistered.permission())
                        .cost(lastRegistered.cost())
                        .invoker(lastRegistered.invoker())
                        .description(description)
                        .categoryId(lastRegistered.categoryId())
                        .build();
                registerOrReplace(newFd);
                lastRegistered = newFd;
            }
            return this;
        }

        @Override
        public NamespaceBuilder categoryId(String categoryId) {
            if (lastRegistered != null) {
                Map<String, FunctionDescriptor> nsMap = byNamespace.get(ns);
                if (nsMap != null) {
                    nsMap.remove(lastRegistered.name());
                }
                all.remove(lastRegistered);

                FunctionDescriptor newFd = FunctionDescriptor.builder()
                        .namespace(lastRegistered.namespace())
                        .name(lastRegistered.name())
                        .parameters(lastRegistered.parameters())
                        .returns(lastRegistered.returnType())
                        .suspending(lastRegistered.suspending())
                        .thread(lastRegistered.thread())
                        .permission(lastRegistered.permission())
                        .cost(lastRegistered.cost())
                        .invoker(lastRegistered.invoker())
                        .description(lastRegistered.description())
                        .categoryId(categoryId)
                        .build();
                registerOrReplace(newFd);
                lastRegistered = newFd;
            }
            return this;
        }

        @Override
        public NamespaceBuilder suspendFunction(String name, VeloraType returnType, Consumer<ParameterListBuilder> parameters, FunctionInvoker invoker) {
            return suspendFunction(name, returnType, parameters, null, invoker);
        }

        @Override
        public NamespaceBuilder suspendFunction(String name, VeloraType returnType, Consumer<ParameterListBuilder> parameters, ScriptPermission permission, FunctionInvoker invoker) {
            ParameterListBuilder pb = new ParameterListBuilder();
            parameters.accept(pb);
            FunctionDescriptor fd = FunctionDescriptor.builder()
                    .namespace(ns).name(name)
                    .parameters(pb.build())
                    .returns(returnType)
                    .suspending(true)
                    .permission(permission)
                    .invoker(invoker)
                    .build();
            registerOrReplace(fd);
            lastRegistered = fd;
            return this;
        }
    }
}
