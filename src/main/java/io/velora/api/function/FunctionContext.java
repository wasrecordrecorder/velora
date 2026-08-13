package io.velora.api.function;

/**
 * Context provided to function invokers at call time.
 */
public interface FunctionContext {

    /**
     * Get an argument by name.
     */
    <T> T argument(String name, Class<T> type);

    /**
     * Get an argument by index.
     */
    <T> T argument(int index, Class<T> type);

    /**
     * Get an argument by name without type checking.
     */
    Object argument(String name);

    /**
     * Get an argument by index without type checking.
     */
    Object argument(int index);

    /**
     * Number of arguments.
     */
    int argumentCount();

    /**
     * The script id of the caller.
     */
    String scriptId();

    /**
     * The fiber id of the caller.
     */
    long fiberId();
}
