package io.velora.api;

import java.util.Collection;

/**
 * Registry for extensions. Extensions must be registered before freeze.
 */
public interface VeloraExtensionRegistry {

    void register(VeloraExtension extension);

    Collection<VeloraExtension> extensions();

    boolean isFrozen();
}
