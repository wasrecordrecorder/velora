package io.velora.internal.ast;

import java.util.List;

public final class FunctionNode extends ScriptMemberNode {
    private final String name;
    private final List<ParameterNode> parameters;
    private final TypeNode returnType;
    private final boolean suspending;
    private final boolean isPrivate;
    private final BlockNode body;

    public FunctionNode(String filePath, int line, int column,
                        String name, List<ParameterNode> parameters, TypeNode returnType,
                        boolean suspending, boolean isPrivate, BlockNode body) {
        super(filePath, line, column);
        this.name = name;
        this.parameters = parameters == null ? List.of() : List.copyOf(parameters);
        this.returnType = returnType;
        this.suspending = suspending;
        this.isPrivate = isPrivate;
        this.body = body;
    }

    public String name() { return name; }
    public List<ParameterNode> parameters() { return parameters; }
    public TypeNode returnType() { return returnType; }
    public boolean suspending() { return suspending; }
    public boolean isPrivate() { return isPrivate; }
    public BlockNode body() { return body; }

    @Override
    public String nodeName() { return "Function:" + name; }
}
