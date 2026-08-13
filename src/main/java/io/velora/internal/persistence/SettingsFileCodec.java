package io.velora.internal.persistence;

import io.velora.api.setting.SettingValue;
import io.velora.api.type.VeloraType;
import io.velora.api.type.VeloraTypes;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class SettingsFileCodec {

    private SettingsFileCodec() {}

    public static String encode(Map<String, SettingValue> values) {
        StringBuilder out = new StringBuilder();
        for (var entry : values.entrySet()) {
            SettingValue value = entry.getValue();
            out.append(entry.getKey()).append('=').append(encodeValue(value)).append('\n');
        }
        return out.toString();
    }

    public static Map<String, SettingValue> decode(String content) {
        Map<String, SettingValue> result = new LinkedHashMap<>();
        if (content == null || content.isEmpty()) return result;
        for (String rawLine : content.split("\\R", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int equals = line.indexOf('=');
            if (equals <= 0) continue;
            String key = line.substring(0, equals).trim();
            String encoded = line.substring(equals + 1).trim();
            try {
                result.put(key, encoded.startsWith("!2|") ? decodeV2(encoded) : decodeLegacy(encoded));
            } catch (RuntimeException ignored) {}
        }
        return result;
    }

    private static String encodeValue(SettingValue value) {
        VeloraType type = value.type().nonNull();
        Object raw = value.value();
        if (raw == null) return "!2|" + type.name() + "|~";
        String payload;
        if (type == VeloraTypes.STRING) payload = base64((String) raw);
        else if (type == VeloraTypes.CHAR) payload = Integer.toString((Character) raw);
        else if (type == VeloraTypes.UUID) payload = raw.toString();
        else payload = String.valueOf(raw);
        return "!2|" + type.name() + "|" + payload;
    }

    private static SettingValue decodeV2(String encoded) {
        String[] parts = encoded.split("\\|", 3);
        if (parts.length != 3) throw new IllegalArgumentException("Invalid settings value");
        VeloraType type = typeByName(parts[1]);
        String payload = parts[2];
        if (payload.equals("~")) return SettingValue.of(type.nullable(), null);
        Object value = switch (type.nonNull().name()) {
            case "Byte" -> Byte.parseByte(payload);
            case "Int" -> Integer.parseInt(payload);
            case "Long", "Duration" -> Long.parseLong(payload);
            case "Float" -> Float.parseFloat(payload);
            case "Double" -> Double.parseDouble(payload);
            case "Boolean" -> parseBoolean(payload);
            case "Char" -> (char) Integer.parseInt(payload);
            case "String" -> new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8);
            case "UUID" -> UUID.fromString(payload);
            default -> throw new IllegalArgumentException("Unsupported setting type " + type.name());
        };
        return SettingValue.of(type, value);
    }

    private static SettingValue decodeLegacy(String value) {
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            String string = value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
            return SettingValue.ofString(string);
        }
        if (value.equals("true") || value.equals("false")) return SettingValue.ofBoolean(Boolean.parseBoolean(value));
        try { return SettingValue.ofInt(Integer.parseInt(value)); }
        catch (NumberFormatException ignored) {}
        try { return SettingValue.ofDouble(Double.parseDouble(value)); }
        catch (NumberFormatException ignored) {}
        return SettingValue.ofString(value);
    }

    private static VeloraType typeByName(String name) {
        return switch (name) {
            case "Byte" -> VeloraTypes.BYTE;
            case "Int" -> VeloraTypes.INT;
            case "Long" -> VeloraTypes.LONG;
            case "Float" -> VeloraTypes.FLOAT;
            case "Double" -> VeloraTypes.DOUBLE;
            case "Boolean" -> VeloraTypes.BOOLEAN;
            case "Char" -> VeloraTypes.CHAR;
            case "String" -> VeloraTypes.STRING;
            case "Duration" -> VeloraTypes.DURATION;
            case "UUID" -> VeloraTypes.UUID;
            default -> throw new IllegalArgumentException("Unknown setting type " + name);
        };
    }

    private static boolean parseBoolean(String value) {
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Invalid boolean");
        return Boolean.parseBoolean(value);
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
