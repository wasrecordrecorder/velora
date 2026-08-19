package io.velora.internal.registry;

import io.velora.api.registry.TypeRegistry;
import io.velora.api.type.*;

import java.util.*;
import java.util.function.Consumer;

public final class DefaultTypeRegistry implements TypeRegistry {

    private final Map<String, VeloraType> byName = new LinkedHashMap<>();
    private final List<VeloraType> all = new ArrayList<>();
    private boolean frozen;

    public DefaultTypeRegistry() {
        registerBuiltins();
    }

    private void registerBuiltins() {
        for (VeloraType t : new VeloraType[]{
                VeloraTypes.ANY, VeloraTypes.UNIT, VeloraTypes.NOTHING, VeloraTypes.BOOLEAN, VeloraTypes.BYTE,
                VeloraTypes.INT, VeloraTypes.LONG, VeloraTypes.FLOAT, VeloraTypes.DOUBLE,
                VeloraTypes.CHAR, VeloraTypes.STRING, VeloraTypes.DURATION, VeloraTypes.VEC2,
                VeloraTypes.VEC3, VeloraTypes.COLOR, VeloraTypes.UUID
        }) {
            byName.put(t.name(), t);
            all.add(t);
        }
    }

    @Override
    public VeloraType struct(String name, Class<?> javaClass, Consumer<StructTypeBuilder> config) {
        checkFrozen();
        StructTypeBuilder b = new StructTypeBuilder(name, javaClass);
        config.accept(b);
        VeloraType t = b.build();
        register(t);
        return t;
    }

    @Override
    public VeloraType enumType(String name, Class<?> javaClass, List<EnumType.Constant> constants) {
        checkFrozen();
        VeloraType t = new EnumType(name, javaClass, constants);
        register(t);
        return t;
    }

    @Override
    public VeloraType handle(String name, Class<?> javaClass) {
        checkFrozen();
        VeloraType t = new HandleType(name, javaClass);
        register(t);
        return t;
    }

    @Override
    public void register(VeloraType type) {
        checkFrozen();
        Objects.requireNonNull(type, "type");
        if (type.isNullable()) throw new IllegalArgumentException("Registered type must be non-null: " + type.name());
        if (!isIdentifier(type.name())) throw new IllegalArgumentException("Type name must be a script identifier: " + type.name());
        if (byName.containsKey(type.name())) throw new IllegalStateException("Type already registered: " + type.name());
        byName.put(type.name(), type);
        all.add(type);
    }

    @Override
    public VeloraType find(String name) {
        return byName.get(name);
    }

    @Override
    public VeloraType findByIndex(int index) {
        if (index < 0 || index >= all.size()) return null;
        return all.get(index);
    }

    @Override
    public List<VeloraType> all() {
        return List.copyOf(all);
    }

    @Override
    public Collection<String> names() {
        return Collections.unmodifiableSet(byName.keySet());
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
        byName.clear();
        for (VeloraType type : all) byName.put(type.name(), type);
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
        if (frozen) throw new IllegalStateException("TypeRegistry is frozen");
    }
}
