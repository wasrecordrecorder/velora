package io.velora.api;

/**
 * Unchecked exception thrown by the Velora engine.
 */
public class VeloraException extends RuntimeException {

    public VeloraException(String message) {
        super(message);
    }

    public VeloraException(String message, Throwable cause) {
        super(message, cause);
    }
}
