package io.velora.api.type;

/**
 * Adapter for converting between Java values and Velora script values.
 */
@FunctionalInterface
public interface TypeAdapter<T> {

    /**
     * Convert a Java value to a Velora-compatible representation.
     */
    Object toVelora(T javaValue);

    /**
     * Convert a Velora value back to Java.
     */
    default T fromVelora(Object veloraValue) {
        @SuppressWarnings("unchecked")
        T result = (T) veloraValue;
        return result;
    }
}
