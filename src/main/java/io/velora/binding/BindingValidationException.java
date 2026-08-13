package io.velora.binding;

public class BindingValidationException extends RuntimeException {
    public BindingValidationException(String message) {
        super(message);
    }

    public BindingValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
