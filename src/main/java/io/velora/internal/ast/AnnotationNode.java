package io.velora.internal.ast;

import java.util.List;
import java.util.Map;

public final class AnnotationNode extends AstNode {
    private final String name;
    private final List<Object> positionalArgs;
    private final Map<String, Object> namedArgs;

    public AnnotationNode(String filePath, int line, int column,
                          String name, List<Object> positionalArgs, Map<String, Object> namedArgs) {
        super(filePath, line, column);
        this.name = name;
        this.positionalArgs = positionalArgs == null ? List.of() : List.copyOf(positionalArgs);
        this.namedArgs = namedArgs == null ? Map.of() : Map.copyOf(namedArgs);
    }

    public String name() { return name; }
    public List<Object> positionalArgs() { return positionalArgs; }
    public Map<String, Object> namedArgs() { return namedArgs; }

    public Object positionalArg(int index) {
        return index < positionalArgs.size() ? positionalArgs.get(index) : null;
    }

    public Object namedArg(String key) {
        return namedArgs.get(key);
    }

    public boolean hasNamedArg(String key) {
        return namedArgs.containsKey(key);
    }

    @Override
    public String nodeName() { return "Annotation:" + name; }
}
