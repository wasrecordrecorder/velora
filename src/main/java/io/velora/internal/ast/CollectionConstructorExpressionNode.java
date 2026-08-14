package io.velora.internal.ast;

import java.util.List;

public final class CollectionConstructorExpressionNode extends ExpressionNode {
    public enum Kind { LIST, SET, MAP }

    private final Kind kind;
    private final List<TypeNode> typeArguments;

    public CollectionConstructorExpressionNode(String filePath, int line, int column, Kind kind, List<TypeNode> typeArguments) {
        super(filePath, line, column);
        this.kind = kind;
        this.typeArguments = List.copyOf(typeArguments);
    }

    public Kind kind() { return kind; }
    public List<TypeNode> typeArguments() { return typeArguments; }

    @Override
    public String nodeName() { return "CollectionConstructor:" + kind; }
}
