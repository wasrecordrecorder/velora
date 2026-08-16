package io.velora.api;

import io.velora.api.category.CategoryRegistry;
import io.velora.api.compiler.ScriptCompiler;
import io.velora.api.debug.DebugService;
import io.velora.api.event.EventRegistry;
import io.velora.api.function.ApiRegistry;
import io.velora.api.language.LanguageService;
import io.velora.api.interop.JavaImportRegistry;
import io.velora.api.registry.*;
import io.velora.api.script.ScriptManager;
import io.velora.host.VeloraHost;

/**
 * Main facade for the Velora scripting engine.
 * Integrators interact with the engine exclusively through this interface.
 */
public interface VeloraEngine extends AutoCloseable {

    VeloraHost host();

    ApiRegistry api();

    EventRegistry events();

    TypeRegistry types();

    SettingRegistry settings();

    ConstantRegistry constants();

    JavaImportRegistry javaImports();

    VeloraExtensionRegistry extensions();

    ScriptCompiler compiler();

    ScriptManager scripts();

    LanguageService language();

    DebugService debug();

    VeloraState state();

    VeloraLimits limits();

    /**
     * Returns the category registry for API categorization.
     */
    CategoryRegistry categories();

    /**
     * Freeze all registries. After freeze, no new types/functions/events/settings
     * can be registered. The engine transitions to FROZEN state.
     */
    void freeze();

    /**
     * Advance the engine by one tick. Must be called on the main thread.
     */
    void tick();

    @Override
    void close();
}
