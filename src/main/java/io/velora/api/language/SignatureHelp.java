package io.velora.api.language;

import java.util.List;

public record SignatureHelp(
        String functionName,
        List<SignatureParameter> parameters,
        int activeParameter,
        String documentation
) {
    public SignatureHelp {
        java.util.Objects.requireNonNull(functionName);
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    public record SignatureParameter(String name, String type, String documentation) {
        public SignatureParameter {
            java.util.Objects.requireNonNull(name);
        }
    }

    public static SignatureHelp of(String functionName, List<SignatureParameter> parameters) {
        return new SignatureHelp(functionName, parameters, 0, null);
    }
}
