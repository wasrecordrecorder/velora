package io.velora.binding;

public class BindingValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public BindingValidationException(String message) {
        super(message);
    }

    public BindingValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
