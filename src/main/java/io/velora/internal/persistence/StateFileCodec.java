package io.velora.internal.persistence;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class StateFileCodec {

    private StateFileCodec() {}

    public static String encode(Map<String, Object> state) {
        StringBuilder out = new StringBuilder();
        for (var entry : state.entrySet()) out.append(entry.getKey()).append('=').append(encodeValue(entry.getValue())).append('\n');
        return out.toString();
    }

    public static Map<String, Object> decode(String content) {
        Map<String, Object> result = new LinkedHashMap<>();
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

    private static String encodeValue(Object value) {
        if (value == null) return "!2|null|";
        if (value instanceof Byte v) return "!2|byte|" + v;
        if (value instanceof Short v) return "!2|short|" + v;
        if (value instanceof Integer v) return "!2|int|" + v;
        if (value instanceof Long v) return "!2|long|" + v;
        if (value instanceof Float v) return "!2|float|" + v;
        if (value instanceof Double v) return "!2|double|" + v;
        if (value instanceof Boolean v) return "!2|boolean|" + v;
        if (value instanceof Character v) return "!2|char|" + (int) v.charValue();
        if (value instanceof String v) return "!2|string|" + base64(v);
        if (value instanceof UUID v) return "!2|uuid|" + v;
        if (value instanceof Duration v) return "!2|duration|" + v.toNanos();
        throw new IllegalArgumentException("Unsupported persistent value type " + value.getClass().getTypeName());
    }

    private static Object decodeV2(String encoded) {
        String[] parts = encoded.split("\\|", 3);
        if (parts.length < 2) throw new IllegalArgumentException("Invalid state value");
        String payload = parts.length == 3 ? parts[2] : "";
        return switch (parts[1]) {
            case "null" -> null;
            case "byte" -> Byte.parseByte(payload);
            case "short" -> Short.parseShort(payload);
            case "int" -> Integer.parseInt(payload);
            case "long" -> Long.parseLong(payload);
            case "float" -> Float.parseFloat(payload);
            case "double" -> Double.parseDouble(payload);
            case "boolean" -> parseBoolean(payload);
            case "char" -> (char) Integer.parseInt(payload);
            case "string" -> new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8);
            case "uuid" -> UUID.fromString(payload);
            case "duration" -> Duration.ofNanos(Long.parseLong(payload));
            default -> throw new IllegalArgumentException("Unknown state type " + parts[1]);
        };
    }

    private static Object decodeLegacy(String value) {
        if (value.equals("null")) return null;
        if (value.equals("true") || value.equals("false")) return Boolean.parseBoolean(value);
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) {}
        try { return Double.parseDouble(value); }
        catch (NumberFormatException ignored) {}
        return value;
    }

    private static boolean parseBoolean(String value) {
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Invalid boolean");
        return Boolean.parseBoolean(value);
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
