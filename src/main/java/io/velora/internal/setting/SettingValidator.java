package io.velora.internal.setting;

import io.velora.api.setting.SettingDescriptor;
import io.velora.api.setting.SettingValidationResult;

import java.util.*;

public final class SettingValidator {

    public static SettingValidationResult validate(SettingDescriptor descriptor, Object value) {
        if (value == null) {
            if (descriptor.defaultValue() == null) return SettingValidationResult.ok();
            return SettingValidationResult.invalid("Value cannot be null for setting '" + descriptor.id() + "'");
        }
        for (SettingDescriptor.Constraint c : descriptor.constraints()) {
            var vr = validateConstraint(c, value);
            if (!vr.isValid()) return vr;
        }
        return SettingValidationResult.ok();
    }

    private static SettingValidationResult validateConstraint(SettingDescriptor.Constraint c, Object value) {
        return switch (c.kind()) {
            case RANGE -> {
                if (value instanceof Number n) {
                    double v = n.doubleValue();
                    double min = ((Number) c.min()).doubleValue();
                    double max = ((Number) c.max()).doubleValue();
                    if (v < min || v > max) yield SettingValidationResult.invalid("Value " + v + " is outside range " + min + ".." + max);
                }
                yield SettingValidationResult.ok();
            }
            case MAX_LENGTH -> {
                if (value instanceof String s) {
                    int maxLen = (Integer) c.max();
                    if (s.length() > maxLen) yield SettingValidationResult.invalid("String length " + s.length() + " exceeds max " + maxLen);
                }
                yield SettingValidationResult.ok();
            }
            case STEP -> SettingValidationResult.ok();
            case PATTERN -> {
                if (value instanceof String s) {
                    String pattern = (String) c.extra();
                    if (!s.matches(pattern)) yield SettingValidationResult.invalid("Value does not match pattern " + pattern);
                }
                yield SettingValidationResult.ok();
            }
            default -> SettingValidationResult.ok();
        };
    }
}
