package io.velora.api;

/**
 * Extension point for host integrations to register API, types, events, etc.
 */
public interface VeloraExtension {

    String id();

    String version();

    void register(VeloraExtensionContext context);
}
