package io.velora.api.setting;

import io.velora.api.type.VeloraType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Describes a setting annotation kind (e.g. {@code Slider}, {@code Toggle}).
 *
 * <p>Registered in the {@link io.velora.api.registry.SettingRegistry} before
 * freeze. The compiler uses the {@link #parameterSchema()} to validate setting
 * declarations and the {@link #resultTypeResolver()} to infer the resulting
 * {@link VeloraType}.
 */
public final class SettingKind {

    private final String name;
    private final String categoryId;
    private final VeloraType resultType;
    private final List<Parameter> parameterSchema;
    private final SettingEditorDescriptor editor;
    private final Function<SettingDeclaration, VeloraType> resultTypeResolver;
    private final String documentation;

    private SettingKind(Builder b) {
        this.name = Objects.requireNonNull(b.name);
        this.categoryId = b.categoryId;
        this.resultType = b.resultType;
        this.parameterSchema = List.copyOf(b.parameterSchema);
        this.editor = b.editor;
        this.resultTypeResolver = b.resultTypeResolver;
        this.documentation = b.documentation;
    }

    public String name() { return name; }
    public String categoryId() { return categoryId; }

    /** Fixed result type, or empty if resolved dynamically from the declaration. */
    public Optional<VeloraType> resultType() { return Optional.ofNullable(resultType); }

    public List<Parameter> parameterSchema() { return parameterSchema; }

    public Optional<SettingEditorDescriptor> editor() { return Optional.ofNullable(editor); }

    public Optional<Function<SettingDeclaration, VeloraType>> resultTypeResolver() {
        return Optional.ofNullable(resultTypeResolver);
    }

    public Optional<String> documentation() { return Optional.ofNullable(documentation); }

    /** Resolve the result type for a concrete declaration. */
    public VeloraType resolveType(SettingDeclaration declaration) {
        if (resultType != null) {
            return resultType;
        }
        if (resultTypeResolver != null) {
            return resultTypeResolver.apply(declaration);
        }
        throw new IllegalStateException("SettingKind " + name + " has no result type resolver");
    }

    public static Builder named(String name) {
        return new Builder().name(name);
    }

    /** A parameter slot in the setting annotation signature. */
    public record Parameter(String name, ParameterRole role, VeloraType type, boolean required) {
        public enum ParameterRole { IDENTIFIER, DISPLAY_NAME, DEFAULT_VALUE, MIN, MAX, STEP, VALUES, NAMED }

        public static Parameter identifier() {
            return new Parameter("id", ParameterRole.IDENTIFIER, null, true);
        }

        public static Parameter displayName() {
            return new Parameter("name", ParameterRole.DISPLAY_NAME, null, true);
        }

        public static Parameter defaultValue(VeloraType type) {
            return new Parameter("defaultValue", ParameterRole.DEFAULT_VALUE, type, true);
        }

        public static Parameter positional(String name, ParameterRole role, VeloraType type, boolean required) {
            return new Parameter(name, role, type, required);
        }
    }

    /** A parsed setting declaration (annotation name, identifier, positional and named args). */
    public record SettingDeclaration(String annotationName, String identifier,
                                      List<Object> positionalArguments,
                                      java.util.Map<String, Object> namedArguments) {
    }

    public static final class Builder {
        private String name;
        private String categoryId = "";
        private VeloraType resultType;
        private final java.util.List<Parameter> parameterSchema = new java.util.ArrayList<>();
        private SettingEditorDescriptor editor;
        private Function<SettingDeclaration, VeloraType> resultTypeResolver;
        private String documentation;

        public Builder name(String v) { this.name = v; return this; }
        public Builder categoryId(String v) { this.categoryId = v; return this; }
        public Builder resultType(VeloraType v) { this.resultType = v; return this; }
        public Builder resultTypeResolver(Function<SettingDeclaration, VeloraType> v) { this.resultTypeResolver = v; return this; }
        public Builder identifierParameter() { this.parameterSchema.add(Parameter.identifier()); return this; }
        public Builder positional(String name, Parameter.ParameterRole role, VeloraType type, boolean required) {
            this.parameterSchema.add(Parameter.positional(name, role, type, required));
            return this;
        }
        public Builder editor(String editorId) { this.editor = SettingEditorDescriptor.of(editorId); return this; }
        public Builder editor(SettingEditorDescriptor editor) { this.editor = editor; return this; }
        public Builder documentation(String v) { this.documentation = v; return this; }

        public SettingKind build() {
            Objects.requireNonNull(name, "name");
            if (!isIdentifier(name)) throw new IllegalArgumentException("Setting kind name must be a script identifier: " + name);
            if ((resultType == null) == (resultTypeResolver == null)) throw new IllegalStateException("SettingKind " + name + " must declare exactly one result type or resolver");
            java.util.Set<String> names = new java.util.HashSet<>();
            for (Parameter parameter : parameterSchema) {
                if (!names.add(parameter.name())) throw new IllegalArgumentException("Duplicate setting parameter: " + parameter.name());
            }
            return new SettingKind(this);
        }

        private boolean isIdentifier(String value) {
            if (value.isEmpty() || !(Character.isLetter(value.charAt(0)) || value.charAt(0) == '_')) return false;
            for (int i = 1; i < value.length(); i++) {
                char c = value.charAt(i);
                if (!Character.isLetterOrDigit(c) && c != '_') return false;
            }
            return true;
        }
    }
}
