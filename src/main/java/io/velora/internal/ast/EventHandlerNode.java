package io.velora.internal.ast;

import java.util.List;

public final class EventHandlerNode extends ScriptMemberNode {
    private final String eventReference;
    private final List<AnnotationNode> handlerAnnotations;
    private final String parameterName;
    private final TypeNode parameterType;
    private final boolean suspending;
    private final BlockNode body;

    public EventHandlerNode(String filePath, int line, int column,
                            String eventReference, List<AnnotationNode> handlerAnnotations,
                            String parameterName, TypeNode parameterType,
                            boolean suspending, BlockNode body) {
        super(filePath, line, column);
        this.eventReference = eventReference;
        this.handlerAnnotations = handlerAnnotations == null ? List.of() : List.copyOf(handlerAnnotations);
        this.parameterName = parameterName;
        this.parameterType = parameterType;
        this.suspending = suspending;
        this.body = body;
    }

    public String eventReference() { return eventReference; }
    public List<AnnotationNode> handlerAnnotations() { return handlerAnnotations; }
    public String parameterName() { return parameterName; }
    public TypeNode parameterType() { return parameterType; }
    public boolean suspending() { return suspending; }
    public BlockNode body() { return body; }

    @Override
    public String nodeName() { return "EventHandler:" + eventReference; }
}
