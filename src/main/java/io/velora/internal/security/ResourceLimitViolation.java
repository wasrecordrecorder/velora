package io.velora.internal.security;

public final class ResourceLimitViolation extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ResourceLimitViolation(String message) {
        super(message);
    }
}
