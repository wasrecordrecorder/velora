package io.velora.internal.semantic;

import io.velora.api.type.VeloraType;

/**
 * Base class for resolved symbols. Kind enum distinguishes all symbol types.
 */
public class Symbol {
    private final String name;
    private final VeloraType type;
    private final Kind kind;

    public Symbol(String name, VeloraType type, Kind kind) {
        this.name = name;
        this.type = type;
        this.kind = kind;
    }

    public String name() { return name; }
    public VeloraType type() { return type; }
    public Kind kind() { return kind; }

    public enum Kind {
        SETTING, PROPERTY, CONST_PROPERTY, LOCAL, PARAMETER, FUNCTION, API_NAMESPACE, ENUM, ENUM_CONSTANT, CONSTANT
    }

    public boolean isSetting() { return kind == Kind.SETTING; }
    public boolean isProperty() { return kind == Kind.PROPERTY; }
    public boolean isConstProperty() { return kind == Kind.CONST_PROPERTY; }
    public boolean isLocal() { return kind == Kind.LOCAL; }
    public boolean isParameter() { return kind == Kind.PARAMETER; }
    public boolean isFunction() { return kind == Kind.FUNCTION; }
}
