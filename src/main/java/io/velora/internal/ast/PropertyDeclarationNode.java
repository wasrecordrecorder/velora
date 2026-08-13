package io.velora.internal.ast;

import java.util.List;

/**
 * A runtime-state field declared in the script body, e.g.
 * {@code int ticks = 0}, {@code static int totalMined = 0},
 * {@code #int MAX_ATTEMPTS = 5}, {@code Vec3 home = null}.
 */
public final class PropertyDeclarationNode extends ScriptMemberNode {
    private final boolean isVar;
    private final boolean isPrivate;
    private final boolean isStatic;
    private final boolean isConst;
    private final String name;
    private final TypeNode declaredType;
    private final ExpressionNode initializer;
    private final List<AnnotationNode> annotations;
    private final boolean persistent;
    private final String persistentId;

    public PropertyDeclarationNode(String filePath, int line, int column,
                                   boolean isVar, boolean isPrivate, boolean isStatic, boolean isConst,
                                   String name,
                                   TypeNode declaredType, ExpressionNode initializer,
                                   List<AnnotationNode> annotations, boolean persistent, String persistentId) {
        super(filePath, line, column);
        this.isVar = isVar;
        this.isPrivate = isPrivate;
        this.isStatic = isStatic;
        this.isConst = isConst;
        this.name = name;
        this.declaredType = declaredType;
        this.initializer = initializer;
        this.annotations = annotations == null ? List.of() : List.copyOf(annotations);
        this.persistent = persistent;
        this.persistentId = persistentId;
    }

    public boolean isVar() { return isVar; }
    public boolean isPrivate() { return isPrivate; }
    public boolean isStatic() { return isStatic; }
    public boolean isConst() { return isConst; }
    public String name() { return name; }
    public TypeNode declaredType() { return declaredType; }
    public ExpressionNode initializer() { return initializer; }
    public List<AnnotationNode> annotations() { return annotations; }
    public boolean persistent() { return persistent; }
    public String persistentId() { return persistentId; }

    @Override
    public String nodeName() { return (isConst ? "#const " : (isStatic ? "static " : "")) + name; }
}
