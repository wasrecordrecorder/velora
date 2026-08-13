package io.velora.internal.vm;

import java.util.List;

public record ListValue(List<ScriptValue> elements) implements ScriptValue {
    public ListValue {
        elements = List.copyOf(elements);
    }
    public boolean isNull() { return false; }
    public Object boxed() { return elements.stream().map(ScriptValue::boxed).toList(); }
}
