package io.velora.api.setting;

/**
 * Result of validating a setting value against its descriptor.
 */
public record SettingValidationResult(boolean valid, String errorMessage) {

    public static SettingValidationResult ok() {
        return new SettingValidationResult(true, null);
    }

    public static SettingValidationResult invalid(String message) {
        return new SettingValidationResult(false, message);
    }

    public boolean isValid() {
        return valid;
    }
}
