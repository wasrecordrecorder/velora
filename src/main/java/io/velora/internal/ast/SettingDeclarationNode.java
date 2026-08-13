package io.velora.internal.ast;

import java.util.List;
import java.util.Map;

public final class SettingDeclarationNode extends AstNode {
    private final String annotationName;
    private final String identifier;
    private final List<Object> positionalArguments;
    private final Map<String, Object> namedArguments;

    public SettingDeclarationNode(String filePath, int line, int column,
                                   String annotationName, String identifier,
                                   List<Object> positionalArguments, Map<String, Object> namedArguments) {
        super(filePath, line, column);
        this.annotationName = annotationName;
        this.identifier = identifier;
        this.positionalArguments = positionalArguments == null ? List.of() : List.copyOf(positionalArguments);
        this.namedArguments = namedArguments == null ? Map.of() : Map.copyOf(namedArguments);
    }

    public String annotationName() { return annotationName; }
    public String identifier() { return identifier; }
    public List<Object> positionalArguments() { return positionalArguments; }
    public Map<String, Object> namedArguments() { return namedArguments; }

    @Override
    public String nodeName() { return "SettingDecl:" + annotationName; }
}
