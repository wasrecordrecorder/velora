package io.velora.internal.ast;

public final class VariableDeclarationNode extends StatementNode {
    private final boolean isConst;
    private final String name;
    private final TypeNode declaredType;
    private final ExpressionNode initializer;

    public VariableDeclarationNode(String filePath, int line, int column,
                                   boolean isConst, String name, TypeNode declaredType, ExpressionNode initializer) {
        super(filePath, line, column);
        this.isConst = isConst;
        this.name = name;
        this.declaredType = declaredType;
        this.initializer = initializer;
    }

    public boolean isConst() { return isConst; }
    public String name() { return name; }
    public TypeNode declaredType() { return declaredType; }
    public ExpressionNode initializer() { return initializer; }

    @Override
    public String nodeName() { return (isConst ? "#const " : "") + name; }
}
