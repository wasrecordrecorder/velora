package io.velora.internal.setting;

import java.util.*;

public final class SettingConditionCompiler {

    public interface Condition { boolean evaluate(Map<String, Object> values); }

    public static Condition alwaysTrue() { return values -> true; }

    public static Condition referenceEquals(String settingId, Object expected) {
        return values -> Objects.equals(values.get(settingId), expected);
    }

    public static Condition referenceNotEquals(String settingId, Object expected) {
        return values -> !Objects.equals(values.get(settingId), expected);
    }

    public static Condition and(Condition... conditions) {
        return values -> {
            for (Condition c : conditions) {
                if (!c.evaluate(values)) return false;
            }
            return true;
        };
    }

    public static Condition or(Condition... conditions) {
        return values -> {
            for (Condition c : conditions) {
                if (c.evaluate(values)) return true;
            }
            return false;
        };
    }
}
