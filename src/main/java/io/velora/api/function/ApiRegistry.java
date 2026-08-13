package io.velora.api.function;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Registry for host API functions and properties.
 */
public interface ApiRegistry {

    /**
     * Open a namespace for registration.
     */
    void namespace(String name, Consumer<NamespaceBuilder> configurator);

    /**
     * Register a function descriptor directly.
     */
    void register(FunctionDescriptor descriptor);

    /**
     * Register an annotated binding object.
     */
    void registerAnnotated(Object binding);

    /**
     * Find a function by namespace and name.
     */
    FunctionDescriptor find(String namespace, String name);

    /**
     * Find a function by its assigned index (after freeze).
     */
    FunctionDescriptor findByIndex(int index);

    /**
     * All registered function descriptors.
     */
    List<FunctionDescriptor> all();

    /**
     * All registered namespaces.
     */
    Collection<String> namespaces();

    boolean isFrozen();
}
