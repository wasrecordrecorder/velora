package io.velora.api.function;

import io.velora.api.permission.ScriptPermission;
import io.velora.api.type.VeloraType;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Builder for registering functions and properties within a namespace.
 */
public interface NamespaceBuilder {

    String namespace();

    /**
     * Register a read-only property.
     */
    NamespaceBuilder property(String name, VeloraType type, FunctionInvoker getter);

    /**
     * Register a read-only property with description.
     */
    NamespaceBuilder property(String name, VeloraType type, FunctionInvoker getter, String description);

    /**
     * Register a property with explicit permission.
     */
    NamespaceBuilder property(String name, VeloraType type, ScriptPermission permission, FunctionInvoker getter);

    /**
     * Register a property with explicit permission and description.
     */
    NamespaceBuilder property(String name, VeloraType type, ScriptPermission permission, FunctionInvoker getter, String description);

    /**
     * Register a synchronous function.
     */
    NamespaceBuilder function(String name, VeloraType returnType, FunctionInvoker invoker);

    NamespaceBuilder function(String name, VeloraType returnType, Consumer<ParameterListBuilder> parameters, FunctionInvoker invoker);

    NamespaceBuilder function(String name, VeloraType returnType, ScriptPermission permission, FunctionInvoker invoker);

    NamespaceBuilder function(String name, VeloraType returnType, Consumer<ParameterListBuilder> parameters, ScriptPermission permission, FunctionInvoker invoker);

    /**
     * Set description for the last registered function or property.
     */
    NamespaceBuilder description(String description);

    /**
     * Set category ID for the last registered function or property.
     */
    NamespaceBuilder categoryId(String categoryId);

    NamespaceBuilder thread(ScriptThread thread);

    NamespaceBuilder cost(int cost);

    /**
     * Register a suspending function with parameters.
     */
    NamespaceBuilder suspendFunction(
            String name,
            VeloraType returnType,
            Consumer<ParameterListBuilder> parameters,
            FunctionInvoker invoker
    );

    /**
     * Register a suspending function with parameters and permission.
     */
    NamespaceBuilder suspendFunction(
            String name,
            VeloraType returnType,
            Consumer<ParameterListBuilder> parameters,
            ScriptPermission permission,
            FunctionInvoker invoker
    );
}
