package io.velora.api;

import io.velora.api.category.CategoryRegistry;
import io.velora.api.event.EventRegistry;
import io.velora.api.function.ApiRegistry;
import io.velora.api.interop.JavaImportRegistry;
import io.velora.api.registry.*;
import io.velora.api.script.ScriptTemplateRegistry;

/**
 * Context provided to extensions during registration (before freeze).
 */
public interface VeloraExtensionContext {

    ApiRegistry api();

    EventRegistry events();

    TypeRegistry types();

    SettingRegistry settings();

    ConstantRegistry constants();

    JavaImportRegistry javaImports();

    ScriptTemplateRegistry templates();

    CategoryRegistry categories();
}
