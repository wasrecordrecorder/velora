package io.velora.internal.setting;

import java.util.*;

public final class SettingMigration {

    public record MigrationResult(boolean success, Map<String, Object> migratedValues, List<String> warnings) {
        public static MigrationResult success(Map<String, Object> values) {
            return new MigrationResult(true, values, List.of());
        }
        public static MigrationResult failure(String warning) {
            return new MigrationResult(false, Map.of(), List.of(warning));
        }
    }

    public static MigrationResult migrate(Map<String, Object> oldValues, Map<String, String> oldTypes,
                                          Map<String, String> newTypes) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        for (var entry : oldValues.entrySet()) {
            String id = entry.getKey();
            Object value = entry.getValue();
            String oldType = oldTypes.get(id);
            String newType = newTypes.get(id);

            if (newType == null) {
                warnings.add("Setting '" + id + "' was removed; value kept as orphaned");
                continue;
            }

            if (oldType != null && !oldType.equals(newType)) {
                Object converted = tryConvert(value, oldType, newType);
                if (converted != null) {
                    result.put(id, converted);
                } else {
                    warnings.add("Cannot convert setting '" + id + "' from " + oldType + " to " + newType);
                }
            } else {
                result.put(id, value);
            }
        }

        return new MigrationResult(true, result, warnings);
    }

    private static Object tryConvert(Object value, String fromType, String toType) {
        if (fromType.equals("Int") && toType.equals("Long")) return ((Number) value).longValue();
        if (fromType.equals("Int") && toType.equals("Double")) return ((Number) value).doubleValue();
        if (fromType.equals("Float") && toType.equals("Double")) return ((Number) value).doubleValue();
        return null;
    }
}
