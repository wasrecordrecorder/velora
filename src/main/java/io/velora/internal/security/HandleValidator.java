package io.velora.internal.security;

import java.util.*;

public final class HandleValidator {
    private final Set<String> validHandleTypes = new HashSet<>();

    public void registerHandleType(String typeName) {
        validHandleTypes.add(typeName);
    }

    public boolean isValid(String typeName) {
        return validHandleTypes.contains(typeName);
    }

    public void validate(String typeName) {
        if (!isValid(typeName)) {
            throw new SecurityException("Invalid handle type: " + typeName);
        }
    }
}
