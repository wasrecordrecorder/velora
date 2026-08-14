package io.velora.internal.ast;

import java.util.List;
import java.util.Map;

public final class SettingDeclarationNode extends AstNode {
    private final String identifier;
    private final TypeNode declaredType;
    private final ExpressionNode initializer;
    private final List<Object> positionalArguments;
    private final Map<String, Object> namedArguments;

    public SettingDeclarationNode(String filePath, int line, int column,
                                   String identifier, TypeNode declaredType, ExpressionNode initializer,
                                   List<Object> positionalArguments, Map<String, Object> namedArguments) {
        super(filePath, line, column);
        this.identifier = identifier;
        this.declaredType = declaredType;
        this.initializer = initializer;
        this.positionalArguments = positionalArguments == null ? List.of() : List.copyOf(positionalArguments);
        this.namedArguments = namedArguments == null ? Map.of() : Map.copyOf(namedArguments);
    }

    public String identifier() { return identifier; }
    public TypeNode declaredType() { return declaredType; }
    public ExpressionNode initializer() { return initializer; }
    public List<Object> positionalArguments() { return positionalArguments; }
    public Map<String, Object> namedArguments() { return namedArguments; }

    @Override
    public String nodeName() { return "SettingDecl:" + identifier; }
}
