package io.velora.internal.registry;

import io.velora.api.registry.ConstantRegistry;
import io.velora.api.type.VeloraType;
import io.velora.internal.vm.VirtualMachine;

import java.util.*;

public final class DefaultConstantRegistry implements ConstantRegistry {

    private final Map<String, Map<String, Constant>> byNamespace = new LinkedHashMap<>();
    private final List<Constant> all = new ArrayList<>();
    private boolean frozen;

    @Override
    public void register(String namespace, String member, VeloraType type, Object value) {
        checkFrozen();
        Objects.requireNonNull(type, "type");
        if (!isIdentifier(namespace)) throw new IllegalArgumentException("Constant namespace must be a script identifier: " + namespace);
        if (!isIdentifier(member)) throw new IllegalArgumentException("Constant member must be a script identifier: " + member);
        try {
            VirtualMachine.javaToValue(type, value);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Constant " + namespace + "." + member + " does not match type " + type.name(), error);
        }
        Map<String, Constant> values = byNamespace.computeIfAbsent(namespace, ignored -> new LinkedHashMap<>());
        if (values.containsKey(member)) throw new IllegalStateException("Constant already registered: " + namespace + "." + member);
        Constant constant = new Constant(namespace, member, type, value);
        values.put(member, constant);
        all.add(constant);
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

    public void rollbackTo(int snapshotSize) {
        while (all.size() > snapshotSize) all.remove(all.size() - 1);
        byNamespace.clear();
        for (Constant constant : all) {
            byNamespace.computeIfAbsent(constant.namespace(), ignored -> new LinkedHashMap<>()).put(constant.member(), constant);
        }
    }

    private static boolean isIdentifier(String value) {
        if (value == null || value.isEmpty() || !(Character.isLetter(value.charAt(0)) || value.charAt(0) == '_')) return false;
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') return false;
        }
        return true;
    }

    private void checkFrozen() {
        if (frozen) throw new IllegalStateException("ConstantRegistry is frozen");
    }
}
