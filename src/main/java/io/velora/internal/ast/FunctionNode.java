package io.velora.internal.ast;

import java.util.List;

public final class FunctionNode extends ScriptMemberNode {
    private final String name;
    private final List<ParameterNode> parameters;
    private final TypeNode returnType;
    private final boolean suspending;
    private final BlockNode body;
    private final List<AnnotationNode> annotations;

    public FunctionNode(String filePath, int line, int column,
                        String name, List<ParameterNode> parameters, TypeNode returnType,
                        boolean suspending, BlockNode body, List<AnnotationNode> annotations) {
        super(filePath, line, column);
        this.name = name;
        this.parameters = parameters == null ? List.of() : List.copyOf(parameters);
        this.returnType = returnType;
        this.suspending = suspending;
        this.body = body;
        this.annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }

    public String name() { return name; }
    public List<ParameterNode> parameters() { return parameters; }
    public TypeNode returnType() { return returnType; }
    public boolean suspending() { return suspending; }
    public BlockNode body() { return body; }
    public List<AnnotationNode> annotations() { return annotations; }

    @Override
    public String nodeName() { return "Function:" + name; }
}
