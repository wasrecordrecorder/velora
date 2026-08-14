package io.velora.internal.vm;

import java.util.ArrayList;
import java.util.List;

public record ListValue(List<ScriptValue> elements) implements ScriptValue {
    public ListValue { elements = new ArrayList<>(elements); }
    @Override public boolean isNull() { return false; }
    @Override public Object boxed() { return elements.stream().map(ScriptValue::boxed).toList(); }
}
