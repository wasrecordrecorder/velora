package io.velora.internal.ast;

/**
 * Named argument in a call expression, e.g. {@code add(a = 40, b = 2)}.
 * Wraps the value expression with the parameter name.
 */
public final class NamedArgumentExpressionNode extends ExpressionNode {
    private final String name;
    private final ExpressionNode value;

    public NamedArgumentExpressionNode(String filePath, int line, int column, String name, ExpressionNode value) {
        super(filePath, line, column);
        this.name = name;
        this.value = value;
    }

    public String argumentName() { return name; }
    public ExpressionNode value() { return value; }

    @Override
    public String nodeName() { return "NamedArg:" + name; }
}
