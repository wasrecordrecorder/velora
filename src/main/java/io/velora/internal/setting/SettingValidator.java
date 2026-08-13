package io.velora.internal.setting;

import io.velora.api.setting.SettingDescriptor;
import io.velora.api.setting.SettingValidationResult;
import io.velora.api.setting.SettingValue;
import io.velora.api.type.VeloraType;
import io.velora.api.type.VeloraTypes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.PatternSyntaxException;

public final class SettingValidator {

    private SettingValidator() {}

    public static SettingValidationResult validate(SettingDescriptor descriptor, SettingValue value) {
        if (descriptor == null || value == null) return SettingValidationResult.invalid("Setting descriptor and value are required");
        if (!VeloraTypes.isCompatible(value.type(), descriptor.type())) {
            return SettingValidationResult.invalid("Setting '" + descriptor.id() + "' expects " + descriptor.type().name() + ", got " + value.type().name());
        }
        Object normalized;
        try {
            normalized = normalizeValue(descriptor.type(), value.value());
        } catch (RuntimeException e) {
            return SettingValidationResult.invalid(e.getMessage());
        }
        if (normalized == null && !descriptor.type().isNullable()) {
            return SettingValidationResult.invalid("Value cannot be null for setting '" + descriptor.id() + "'");
        }
        for (SettingDescriptor.Constraint constraint : descriptor.constraints()) {
            SettingValidationResult result = validateConstraint(descriptor, constraint, normalized);
            if (!result.isValid()) return result;
        }
        return SettingValidationResult.ok();
    }

    public static SettingValue normalize(SettingDescriptor descriptor, SettingValue value) {
        SettingValidationResult validation = validate(descriptor, value);
        if (!validation.isValid()) throw new IllegalArgumentException(validation.errorMessage());
        return SettingValue.of(descriptor.type(), normalizeValue(descriptor.type(), value.value()));
    }

    private static Object normalizeValue(VeloraType type, Object value) {
        if (value == null) return null;
        VeloraType expected = type.nonNull();
        if (expected == VeloraTypes.BYTE && value instanceof Number n) return n.byteValue();
        if (expected == VeloraTypes.INT && value instanceof Number n) return n.intValue();
        if ((expected == VeloraTypes.LONG || expected == VeloraTypes.DURATION) && value instanceof Number n) return n.longValue();
        if (expected == VeloraTypes.FLOAT && value instanceof Number n) return n.floatValue();
        if (expected == VeloraTypes.DOUBLE && value instanceof Number n) return n.doubleValue();
        if (expected == VeloraTypes.BOOLEAN && value instanceof Boolean) return value;
        if (expected == VeloraTypes.CHAR && value instanceof Character) return value;
        if (expected == VeloraTypes.STRING && value instanceof String) return value;
        if (expected == VeloraTypes.UUID && value instanceof java.util.UUID) return value;
        if (expected == VeloraTypes.UUID && value instanceof String string) return java.util.UUID.fromString(string);
        if (expected.javaClass().isInstance(value)) return value;
        throw new IllegalArgumentException("Value carrier " + value.getClass().getTypeName() + " is invalid for setting type " + type.name());
    }

    private static SettingValidationResult validateConstraint(SettingDescriptor descriptor, SettingDescriptor.Constraint constraint, Object value) {
        if (value == null) return SettingValidationResult.ok();
        return switch (constraint.kind()) {
            case RANGE -> validateRange(value, constraint.min(), constraint.max());
            case MIN -> validateRange(value, constraint.min(), null);
            case MAX -> validateRange(value, null, constraint.max());
            case STEP -> validateStep(descriptor, value, constraint.extra());
            case MAX_LENGTH -> {
                if (!(value instanceof String string)) yield SettingValidationResult.invalid("MAX_LENGTH requires a String setting");
                int max = ((Number) constraint.max()).intValue();
                yield string.length() <= max ? SettingValidationResult.ok() : SettingValidationResult.invalid("String length " + string.length() + " exceeds max " + max);
            }
            case PATTERN -> {
                if (!(value instanceof String string)) yield SettingValidationResult.invalid("PATTERN requires a String setting");
                try {
                    String pattern = String.valueOf(constraint.extra());
                    yield string.matches(pattern) ? SettingValidationResult.ok() : SettingValidationResult.invalid("Value does not match pattern " + pattern);
                } catch (PatternSyntaxException e) {
                    yield SettingValidationResult.invalid("Invalid setting pattern: " + e.getDescription());
                }
            }
            case ALLOW_AIR, ALLOW_TAG, ALLOW_ALPHA, MODE -> SettingValidationResult.ok();
        };
    }

    private static SettingValidationResult validateRange(Object value, Object minValue, Object maxValue) {
        if (!(value instanceof Number number)) return SettingValidationResult.invalid("Numeric range constraint requires a number");
        BigDecimal current = decimal(number);
        if (minValue instanceof Number min && current.compareTo(decimal(min)) < 0) return SettingValidationResult.invalid("Value " + current + " is below minimum " + min);
        if (maxValue instanceof Number max && current.compareTo(decimal(max)) > 0) return SettingValidationResult.invalid("Value " + current + " exceeds maximum " + max);
        return SettingValidationResult.ok();
    }

    private static SettingValidationResult validateStep(SettingDescriptor descriptor, Object value, Object stepValue) {
        if (!(value instanceof Number number) || !(stepValue instanceof Number stepNumber)) return SettingValidationResult.invalid("STEP requires numeric values");
        BigDecimal step = decimal(stepNumber).abs();
        if (step.signum() == 0) return SettingValidationResult.invalid("Step must be greater than zero");
        BigDecimal base = BigDecimal.ZERO;
        for (SettingDescriptor.Constraint constraint : descriptor.constraints()) {
            if ((constraint.kind() == SettingDescriptor.Constraint.Kind.RANGE || constraint.kind() == SettingDescriptor.Constraint.Kind.MIN) && constraint.min() instanceof Number min) {
                base = decimal(min);
                break;
            }
        }
        BigDecimal quotient = decimal(number).subtract(base).divide(step, 12, RoundingMode.HALF_UP);
        BigDecimal nearest = quotient.setScale(0, RoundingMode.HALF_UP);
        return quotient.subtract(nearest).abs().compareTo(new BigDecimal("0.000000001")) <= 0
                ? SettingValidationResult.ok()
                : SettingValidationResult.invalid("Value " + number + " does not align to step " + stepNumber);
    }

    private static BigDecimal decimal(Number number) {
        return new BigDecimal(number.toString());
    }
}
