package io.velora.internal.ast;

public final class LifecycleNode extends ScriptMemberNode {
    public enum Hook { ON_LOAD, ON_ENABLE, ON_DISABLE, ON_UNLOAD, ON_TICK, ON_RUN }

    private final Hook hook;
    private final boolean suspending;
    private final BlockNode body;

    public LifecycleNode(String filePath, int line, int column, Hook hook, boolean suspending, BlockNode body) {
        super(filePath, line, column);
        this.hook = hook;
        this.suspending = suspending;
        this.body = body;
    }

    public Hook hook() { return hook; }
    public boolean suspending() { return suspending; }
    public BlockNode body() { return body; }

    @Override
    public String nodeName() { return "Lifecycle:" + hook; }
}
