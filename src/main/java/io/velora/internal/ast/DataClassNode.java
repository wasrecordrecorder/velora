package io.velora.internal.ast;

import java.util.List;

/**
 * A data class (record-like) declaration inside a script, e.g.
 * {@code data class Point(val x: Int, val y: Int)}.
 */
public final class DataClassNode extends ScriptMemberNode {
    private final String name;
    private final List<Field> fields;
    private final boolean isPrivate;

    public DataClassNode(String filePath, int line, int column,
                        String name, List<Field> fields, boolean isPrivate) {
        super(filePath, line, column);
        this.name = name;
        this.fields = fields == null ? List.of() : List.copyOf(fields);
        this.isPrivate = isPrivate;
    }

    public String name() { return name; }
    public List<Field> fields() { return fields; }
    public boolean isPrivate() { return isPrivate; }

    @Override
    public String nodeName() { return "Data:" + name; }

    public record Field(String name, TypeNode type) {}
}
