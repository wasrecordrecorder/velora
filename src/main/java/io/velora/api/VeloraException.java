package io.velora.api;

/**
 * Unchecked exception thrown by the Velora engine.
 */
public class VeloraException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public VeloraException(String message) {
        super(message);
    }

    public VeloraException(String message, Throwable cause) {
        super(message, cause);
    }
}
