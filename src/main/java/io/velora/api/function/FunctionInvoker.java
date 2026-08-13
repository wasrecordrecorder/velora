package io.velora.api.function;

/**
 * Invoker for a host function.
 */
@FunctionalInterface
public interface FunctionInvoker {

    /**
     * Invoke the function. May return a value or null for Unit.
     * For suspending functions, may return a {@link io.velora.api.task.VeloraTask}.
     */
    Object invoke(FunctionContext context) throws Throwable;
}
