package io.velora.internal.ast;

public final class ParameterNode extends AstNode {
    private final String name;
    private final TypeNode type;
    private final boolean hasDefault;
    private final ExpressionNode defaultValue;

    public ParameterNode(String filePath, int line, int column,
                         String name, TypeNode type, boolean hasDefault, ExpressionNode defaultValue) {
        super(filePath, line, column);
        this.name = name;
        this.type = type;
        this.hasDefault = hasDefault;
        this.defaultValue = defaultValue;
    }

    public String name() { return name; }
    public TypeNode type() { return type; }
    public boolean hasDefault() { return hasDefault; }
    public ExpressionNode defaultValue() { return defaultValue; }

    @Override
    public String nodeName() { return "Parameter:" + name; }
}
