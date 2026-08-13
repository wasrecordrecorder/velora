package io.velora.api.setting;

import io.velora.api.type.VeloraType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable descriptor for a single setting of a compiled script.
 *
 * <p>Produced by the compiler from a setting annotation declaration. Used by the
 * GUI, persistence, type checker, runtime, documentation and node editor.
 */
public final class SettingDescriptor {

    private final String id;
    private final String displayName;
    private final VeloraType type;
    private final Object defaultValue;
    private final SettingEditorDescriptor editor;
    private final String description;
    private final String category;
    private final int order;
    private final boolean advanced;
    private final boolean restartRequired;
    private final boolean secret;
    private final String idAlias;
    private final List<Constraint> constraints;
    private final int index;

    public SettingDescriptor(String id, String displayName, VeloraType type, Object defaultValue,
                             SettingEditorDescriptor editor, String description, String category,
                             int order, boolean advanced, boolean restartRequired, boolean secret,
                             String idAlias, List<Constraint> constraints, int index) {
        this.id = Objects.requireNonNull(id);
        this.displayName = displayName;
        this.type = Objects.requireNonNull(type);
        this.defaultValue = defaultValue;
        this.editor = editor;
        this.description = description;
        this.category = category;
        this.order = order;
        this.advanced = advanced;
        this.restartRequired = restartRequired;
        this.secret = secret;
        this.idAlias = idAlias;
        this.constraints = constraints == null ? List.of() : List.copyOf(constraints);
        this.index = index;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public VeloraType type() { return type; }
    public Object defaultValue() { return defaultValue; }
    public Optional<SettingEditorDescriptor> editor() { return Optional.ofNullable(editor); }
    public Optional<String> description() { return Optional.ofNullable(description); }
    public Optional<String> category() { return Optional.ofNullable(category); }
    public int order() { return order; }
    public boolean advanced() { return advanced; }
    public boolean restartRequired() { return restartRequired; }
    public boolean secret() { return secret; }
    public Optional<String> idAlias() { return Optional.ofNullable(idAlias); }
    public List<Constraint> constraints() { return constraints; }
    public int index() { return index; }

    public SettingDescriptor withIndex(int index) {
        return new SettingDescriptor(id, displayName, type, defaultValue, editor, description, category,
                order, advanced, restartRequired, secret, idAlias, constraints, index);
    }

    /** A validation constraint on a setting value (range, pattern, max length, ...). */
    public record Constraint(Kind kind, Object min, Object max, Object extra) {
        public enum Kind { RANGE, MIN, MAX, STEP, MAX_LENGTH, PATTERN, ALLOW_AIR, ALLOW_TAG, ALLOW_ALPHA, MODE }

        public static Constraint range(Object min, Object max) {
            return new Constraint(Kind.RANGE, min, max, null);
        }

        public static Constraint step(Object step) {
            return new Constraint(Kind.STEP, null, null, step);
        }

        public static Constraint maxLength(int max) {
            return new Constraint(Kind.MAX_LENGTH, null, max, null);
        }

        public static Constraint pattern(String regex) {
            return new Constraint(Kind.PATTERN, null, null, regex);
        }
    }
}
