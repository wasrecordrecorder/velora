package io.velora.api.setting;

import io.velora.api.type.VeloraType;

import java.util.function.Function;

/**
 * Fluent builder for {@link SettingKind}, exposed through
 * {@link io.velora.api.registry.SettingRegistry#register(String, java.util.function.Consumer)}.
 */
public final class SettingKindBuilder {

    private final SettingKind.Builder delegate = SettingKind.named(null);

    private SettingKindBuilder(String name) {
        delegate.name(name);
    }

    public static SettingKindBuilder named(String name) {
        return new SettingKindBuilder(name);
    }

    public SettingKindBuilder resultType(VeloraType type) {
        delegate.resultType(type);
        return this;
    }

    public SettingKindBuilder resultTypeResolver(Function<SettingKind.SettingDeclaration, VeloraType> resolver) {
        delegate.resultTypeResolver(resolver);
        return this;
    }

    public SettingKindBuilder identifierParameter() {
        delegate.identifierParameter();
        return this;
    }

    public SettingKindBuilder positional(String name, SettingKind.Parameter.ParameterRole role, VeloraType type, boolean required) {
        delegate.positional(name, role, type, required);
        return this;
    }

    public SettingKindBuilder editor(String editorId) {
        delegate.editor(editorId);
        return this;
    }

    public SettingKindBuilder documentation(String doc) {
        delegate.documentation(doc);
        return this;
    }

    public SettingKind build() {
        return delegate.build();
    }
}
