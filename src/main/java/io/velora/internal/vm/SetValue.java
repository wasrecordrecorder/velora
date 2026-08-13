package io.velora.internal.vm;

import java.util.LinkedHashSet;
import java.util.Set;

public record SetValue(Set<ScriptValue> elements) implements ScriptValue {
    public SetValue {
        elements = Set.copyOf(elements);
    }

    @Override public boolean isNull() { return false; }

    @Override
    public Object boxed() {
        Set<Object> result = new LinkedHashSet<>();
        for (ScriptValue element : elements) result.add(element.boxed());
        return result;
    }
}
