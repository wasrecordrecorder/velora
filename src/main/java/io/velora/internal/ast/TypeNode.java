package io.velora.internal.ast;

import java.util.List;

public final class TypeNode extends AstNode {
    private final String typeName;
    private final boolean nullable;
    private final List<TypeNode> typeArguments;

    public TypeNode(String filePath, int line, int column, String typeName, boolean nullable, List<TypeNode> typeArguments) {
        super(filePath, line, column);
        this.typeName = typeName;
        this.nullable = nullable;
        this.typeArguments = typeArguments == null ? List.of() : List.copyOf(typeArguments);
    }

    public String typeName() { return typeName; }
    public boolean nullable() { return nullable; }
    public List<TypeNode> typeArguments() { return typeArguments; }

    public String displayName() {
        StringBuilder sb = new StringBuilder(typeName);
        if (!typeArguments.isEmpty()) {
            sb.append("<");
            for (int i = 0; i < typeArguments.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(typeArguments.get(i).displayName());
            }
            sb.append(">");
        }
        if (nullable) sb.append("?");
        return sb.toString();
    }

    @Override
    public String nodeName() { return "Type:" + displayName(); }
}
