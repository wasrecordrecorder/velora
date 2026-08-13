package io.velora.internal.registry;

import io.velora.api.registry.ConstantRegistry;
import io.velora.api.type.VeloraType;

import java.util.*;

public final class DefaultConstantRegistry implements ConstantRegistry {

    private final Map<String, Map<String, Constant>> byNamespace = new LinkedHashMap<>();
    private final List<Constant> all = new ArrayList<>();
    private boolean frozen;

    @Override
    public void register(String namespace, String member, VeloraType type, Object value) {
        checkFrozen();
        Constant c = new Constant(namespace, member, type, value);
        byNamespace.computeIfAbsent(namespace, k -> new LinkedHashMap<>()).put(member, c);
        all.add(c);
    }

    @Override
    public Constant find(String namespace, String member) {
        Map<String, Constant> ns = byNamespace.get(namespace);
        return ns != null ? ns.get(member) : null;
    }

    @Override
    public List<Constant> namespace(String namespace) {
        Map<String, Constant> ns = byNamespace.get(namespace);
        return ns != null ? List.copyOf(ns.values()) : List.of();
    }

    @Override
    public Collection<String> namespaces() {
        return Collections.unmodifiableSet(byNamespace.keySet());
    }

    @Override
    public List<Constant> all() {
        return List.copyOf(all);
    }

    @Override
    public boolean isFrozen() {
        return frozen;
    }

    public void freeze() {
        frozen = true;
    }

    private void checkFrozen() {
        if (frozen) throw new IllegalStateException("ConstantRegistry is frozen");
    }
}
