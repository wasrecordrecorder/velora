package io.velora.internal.language;

import io.velora.api.function.FunctionDescriptor;
import io.velora.api.function.ParameterDescriptor;
import io.velora.api.language.CompletionItem;
import io.velora.api.language.SignatureHelp;
import io.velora.api.registry.TypeRegistry;
import io.velora.api.type.StructType;
import io.velora.api.type.VeloraType;
import io.velora.api.type.VeloraTypes;
import io.velora.internal.semantic.ResolvedScript;

import java.util.ArrayList;
import java.util.List;

final class LanguageMetadata {
    private LanguageMetadata() {}

    record Member(String name, List<Param> parameters, VeloraType returnType, String description, boolean property, int minimumArguments) {
        Member {
            parameters = List.copyOf(parameters);
        }

        String detail() {
            if (property) return name + ": " + returnType.name();
            return name + "(" + String.join(", ", parameters.stream().map(Param::display).toList()) + ") -> " + returnType.name();
        }

        SignatureHelp signature(int activeParameter) {
            List<SignatureHelp.SignatureParameter> params = parameters.stream()
                    .map(param -> new SignatureHelp.SignatureParameter(param.name(), param.type().name(), param.description()))
                    .toList();
            int active = params.isEmpty() ? 0 : Math.min(activeParameter, params.size() - 1);
            return new SignatureHelp(name, params, active, description);
        }
    }

    record Param(String name, VeloraType type, String description, boolean optional) {
        String display() { return name + (optional ? "?" : "") + ": " + type.name(); }
    }

    static String typeDocumentation(String name) {
        return switch (name) {
            case "Any" -> "Dynamic Velora value used at host API boundaries when a concrete script type is not required.";
            case "Boolean", "boolean" -> "Boolean value: true or false.";
            case "Byte", "byte" -> "Signed 8-bit integer value.";
            case "Int", "int" -> "Signed 32-bit integer value.";
            case "Long", "long" -> "Signed 64-bit integer value.";
            case "Float", "float" -> "32-bit floating-point value.";
            case "Double", "double" -> "64-bit floating-point value.";
            case "Char", "char" -> "Single UTF-16 code unit.";
            case "String" -> "Immutable text value with common member utilities such as trim(), split() and substring().";
            case "Duration" -> "Duration value. Literals include 500.ms, 2.seconds and 1.minute.";
            case "Vec2" -> "Two-component vector with x and y properties.";
            case "Vec3" -> "Three-component vector with x, y and z properties.";
            case "Color" -> "RGBA color value with r, g, b and a components.";
            case "UUID" -> "Universally unique identifier value.";
            case "List" -> "Ordered mutable collection: List<T>.";
            case "Map" -> "Mutable key-value collection: Map<K, V>.";
            case "Set" -> "Mutable unique-value collection: Set<T>.";
            case "Task" -> "Handle to a spawned script computation: Task<T>.";
            default -> null;
        };
    }

    static String signature(FunctionDescriptor descriptor) {
        if (descriptor.property()) return descriptor.qualifiedName() + ": " + descriptor.returnType().name();
        return descriptor.qualifiedName() + "(" + String.join(", ", descriptor.parameters().stream().map(LanguageMetadata::parameter).toList()) + ") -> " + descriptor.returnType().name();
    }

    static SignatureHelp signatureHelp(FunctionDescriptor descriptor, int activeParameter) {
        List<SignatureHelp.SignatureParameter> parameters = descriptor.parameters().stream().map(parameter -> {
            String documentation = parameter.description();
            if (parameter.hasDefault()) documentation = append(documentation, "Default: " + parameter.defaultValue());
            if (parameter.variadic()) documentation = append(documentation, "Accepts zero or more values.");
            return new SignatureHelp.SignatureParameter(parameter.name() + (parameter.variadic() ? "..." : ""), parameter.type().name(), emptyToNull(documentation));
        }).toList();
        int active;
        if (parameters.isEmpty()) active = 0;
        else if (descriptor.variadic() && activeParameter >= parameters.size() - 1) active = parameters.size() - 1;
        else active = Math.min(activeParameter, parameters.size() - 1);
        return new SignatureHelp(descriptor.qualifiedName(), parameters, active, emptyToNull(descriptor.description()));
    }

    static CompletionItem apiCompletion(FunctionDescriptor descriptor) {
        return new CompletionItem(descriptor.name(), signature(descriptor), emptyToNull(descriptor.description()), descriptor.name(),
                descriptor.property() ? CompletionItem.CompletionKind.PROPERTY : CompletionItem.CompletionKind.FUNCTION);
    }

    static List<Member> members(VeloraType type) {
        if (type == null) return List.of();
        VeloraType base = type.nonNull();
        List<Member> members = new ArrayList<>();
        if (base == VeloraTypes.STRING) {
            members.add(property("length", VeloraTypes.INT, "Number of UTF-16 code units in this string."));
            members.add(property("isEmpty", VeloraTypes.BOOLEAN, "Whether this string has no characters."));
            members.add(property("isBlank", VeloraTypes.BOOLEAN, "Whether this string is empty or contains only whitespace."));
            members.add(method("lower", VeloraTypes.STRING, "Returns a lowercase copy using locale-independent casing."));
            members.add(method("upper", VeloraTypes.STRING, "Returns an uppercase copy using locale-independent casing."));
            members.add(method("trim", VeloraTypes.STRING, "Returns this string without leading and trailing whitespace."));
            members.add(method("contains", VeloraTypes.BOOLEAN, "Whether this string contains the given text.", param("text", VeloraTypes.STRING, "Text to search for.")));
            members.add(method("startsWith", VeloraTypes.BOOLEAN, "Whether this string starts with the given prefix.", param("prefix", VeloraTypes.STRING, "Prefix to test.")));
            members.add(method("endsWith", VeloraTypes.BOOLEAN, "Whether this string ends with the given suffix.", param("suffix", VeloraTypes.STRING, "Suffix to test.")));
            members.add(method("equalsIgnoreCase", VeloraTypes.BOOLEAN, "Compares two strings without case sensitivity.", param("other", VeloraTypes.STRING, "String to compare with.")));
            members.add(method("indexOf", VeloraTypes.INT, "Returns the first index of the given text, or -1.", param("text", VeloraTypes.STRING, "Text to locate.")));
            members.add(method("lastIndexOf", VeloraTypes.INT, "Returns the last index of the given text, or -1.", param("text", VeloraTypes.STRING, "Text to locate.")));
            members.add(method("charAt", VeloraTypes.CHAR, "Returns the character at a zero-based index.", param("index", VeloraTypes.INT, "Zero-based character index.")));
            members.add(new Member("substring", List.of(param("start", VeloraTypes.INT, "Inclusive start index."), optional("end", VeloraTypes.INT, "Exclusive end index; defaults to the string length.")), VeloraTypes.STRING, "Returns a substring between the requested indices.", false, 1));
            members.add(method("replace", VeloraTypes.STRING, "Returns a copy with literal text replaced.", param("old", VeloraTypes.STRING, "Text to replace."), param("replacement", VeloraTypes.STRING, "Replacement text.")));
            members.add(method("split", VeloraTypes.list(VeloraTypes.STRING), "Splits this string by a literal delimiter.", param("delimiter", VeloraTypes.STRING, "Non-empty delimiter.")));
            members.add(method("repeat", VeloraTypes.STRING, "Repeats this string the requested number of times.", param("count", VeloraTypes.INT, "Number of repetitions.")));
        }
        VeloraType list = VeloraTypes.listElement(base);
        VeloraType set = VeloraTypes.setElement(base);
        VeloraType mapKey = VeloraTypes.mapKey(base);
        VeloraType mapValue = VeloraTypes.mapValue(base);
        if (list != null || set != null || mapKey != null) {
            members.add(property("size", VeloraTypes.INT, "Number of elements in this collection."));
            members.add(property("isEmpty", VeloraTypes.BOOLEAN, "Whether this collection contains no elements."));
        }
        if (list != null) {
            members.add(method("add", VeloraTypes.UNIT, "Appends a value to this list.", param("value", list, "Value to append.")));
            members.add(method("remove", VeloraTypes.BOOLEAN, "Removes the first matching value.", param("value", list, "Value to remove.")));
            members.add(method("contains", VeloraTypes.BOOLEAN, "Whether this list contains the value.", param("value", list, "Value to find.")));
            members.add(method("indexOf", VeloraTypes.INT, "Returns the first index of the value, or -1.", param("value", list, "Value to find.")));
            members.add(method("removeAt", list, "Removes and returns the value at an index.", param("index", VeloraTypes.INT, "Zero-based index.")));
            members.add(method("first", list, "Returns the first value; fails if the list is empty."));
            members.add(method("last", list, "Returns the last value; fails if the list is empty."));
            members.add(method("clear", VeloraTypes.UNIT, "Removes all values from this list."));
        } else if (set != null) {
            members.add(method("add", VeloraTypes.UNIT, "Adds a value to this set.", param("value", set, "Value to add.")));
            members.add(method("remove", VeloraTypes.BOOLEAN, "Removes a matching value.", param("value", set, "Value to remove.")));
            members.add(method("contains", VeloraTypes.BOOLEAN, "Whether this set contains the value.", param("value", set, "Value to find.")));
            members.add(method("clear", VeloraTypes.UNIT, "Removes all values from this set."));
        } else if (mapKey != null) {
            members.add(method("put", VeloraTypes.UNIT, "Stores a value for a key.", param("key", mapKey, "Map key."), param("value", mapValue, "Value to store.")));
            members.add(method("get", mapValue.nullable(), "Returns the value for a key, or null when absent.", param("key", mapKey, "Map key.")));
            members.add(method("getOrDefault", mapValue, "Returns the value for a key, or the supplied default when absent.", param("key", mapKey, "Map key."), param("defaultValue", mapValue, "Fallback value.")));
            members.add(method("remove", VeloraTypes.BOOLEAN, "Removes an entry by key.", param("key", mapKey, "Map key.")));
            members.add(method("containsKey", VeloraTypes.BOOLEAN, "Whether this map contains the key.", param("key", mapKey, "Map key.")));
            members.add(method("keys", VeloraTypes.list(mapKey), "Returns the current keys as a list."));
            members.add(method("values", VeloraTypes.list(mapValue), "Returns the current values as a list."));
            members.add(method("clear", VeloraTypes.UNIT, "Removes all entries from this map."));
        }
        if (base == VeloraTypes.VEC2 || base == VeloraTypes.VEC3 || base == VeloraTypes.COLOR) members.add(property("size", VeloraTypes.INT, "Number of components in this value."));
        if (base == VeloraTypes.VEC2 || base == VeloraTypes.VEC3) {
            members.add(property("x", VeloraTypes.DOUBLE, "X component."));
            members.add(property("y", VeloraTypes.DOUBLE, "Y component."));
            if (base == VeloraTypes.VEC3) members.add(property("z", VeloraTypes.DOUBLE, "Z component."));
        } else if (base == VeloraTypes.COLOR) {
            members.add(property("r", VeloraTypes.INT, "Red component."));
            members.add(property("g", VeloraTypes.INT, "Green component."));
            members.add(property("b", VeloraTypes.INT, "Blue component."));
            members.add(property("a", VeloraTypes.INT, "Alpha component."));
        }
        if (base instanceof StructType struct) {
            for (StructType.Property property : struct.properties()) members.add(property(property.name(), property.type(), struct.propertyDescription(property.name()).isBlank() ? "Property of " + struct.name() + "." : struct.propertyDescription(property.name())));
        }
        members.add(method("toString", VeloraTypes.STRING, "Returns a readable string representation of this value."));
        return List.copyOf(members);
    }

    static VeloraType qualifierType(String content, String qualifier, int cursorOffset, TypeRegistry typeRegistry, ResolvedScript resolved) {
        if (qualifier == null || qualifier.isBlank() || cursorOffset <= 0) return null;
        String normalized = qualifier.replace("?.", ".");
        if (normalized.endsWith("?")) normalized = normalized.substring(0, normalized.length() - 1);
        String[] chain = normalized.split("\\.");
        if (chain.length == 0) return null;
        VeloraType type = rootType(content, chain[0], cursorOffset, typeRegistry, resolved);
        for (int i = 1; type != null && i < chain.length; i++) {
            Member member = member(type, chain[i]);
            if (member == null || !member.property()) return null;
            type = member.returnType();
        }
        return type;
    }

    private static VeloraType rootType(String content, String qualifier, int cursorOffset, TypeRegistry typeRegistry, ResolvedScript resolved) {
        if (resolved != null) {
            var property = resolved.properties().get(qualifier);
            if (property != null) return property.type();
            for (var setting : resolved.settings()) if (setting.id().equals(qualifier)) return setting.type();
        }
        if (typeRegistry == null) return null;
        String before = content.substring(0, Math.min(cursorOffset, content.length()));
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?s)([A-Za-z_][A-Za-z0-9_]*(?:\\s*<[^;{}()=]+>)?\\??)\\s+" + java.util.regex.Pattern.quote(qualifier) + "\\b").matcher(before);
        VeloraType found = null;
        while (matcher.find()) {
            VeloraType candidate = parseType(matcher.group(1).replaceAll("\\s+", ""), typeRegistry);
            if (candidate != null) found = candidate;
        }
        return found;
    }


    static Member member(VeloraType type, String name) {
        for (Member member : members(type)) if (member.name().equals(name)) return member;
        return null;
    }

    private static VeloraType parseType(String text, TypeRegistry types) {
        boolean nullable = text.endsWith("?");
        if (nullable) text = text.substring(0, text.length() - 1);
        VeloraType type;
        if ((text.startsWith("List<") || text.startsWith("Set<") || text.startsWith("Task<")) && text.endsWith(">")) {
            int offset = text.startsWith("Set<") ? 4 : 5;
            VeloraType inner = parseType(text.substring(offset, text.length() - 1), types);
            if (inner == null) return null;
            type = text.startsWith("List<") ? VeloraTypes.list(inner) : text.startsWith("Set<") ? VeloraTypes.set(inner) : VeloraTypes.task(inner);
        } else if (text.startsWith("Map<") && text.endsWith(">")) {
            int comma = topLevelComma(text.substring(4, text.length() - 1));
            if (comma < 0) return null;
            String inner = text.substring(4, text.length() - 1);
            VeloraType key = parseType(inner.substring(0, comma), types);
            VeloraType value = parseType(inner.substring(comma + 1), types);
            if (key == null || value == null) return null;
            type = VeloraTypes.map(key, value);
        } else type = switch (text) {
            case "Any" -> VeloraTypes.ANY;
            case "boolean", "Boolean" -> VeloraTypes.BOOLEAN;
            case "byte", "Byte" -> VeloraTypes.BYTE;
            case "int", "Int" -> VeloraTypes.INT;
            case "long", "Long" -> VeloraTypes.LONG;
            case "float", "Float" -> VeloraTypes.FLOAT;
            case "double", "Double" -> VeloraTypes.DOUBLE;
            case "char", "Char" -> VeloraTypes.CHAR;
            case "String" -> VeloraTypes.STRING;
            case "Duration" -> VeloraTypes.DURATION;
            case "Vec2" -> VeloraTypes.VEC2;
            case "Vec3" -> VeloraTypes.VEC3;
            case "Color" -> VeloraTypes.COLOR;
            case "UUID" -> VeloraTypes.UUID;
            default -> types.find(text);
        };
        return type != null && nullable ? type.nullable() : type;
    }

    private static int topLevelComma(String value) {
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) return i;
        }
        return -1;
    }

    private static Member property(String name, VeloraType type, String description) {
        return new Member(name, List.of(), type, description, true, 0);
    }

    private static Member method(String name, VeloraType type, String description, Param... parameters) {
        return new Member(name, List.of(parameters), type, description, false, parameters.length);
    }

    private static Param param(String name, VeloraType type, String description) { return new Param(name, type, description, false); }
    private static Param optional(String name, VeloraType type, String description) { return new Param(name, type, description, true); }

    private static String parameter(ParameterDescriptor parameter) {
        if (parameter.variadic()) return parameter.name() + "...: " + parameter.type().name();
        if (parameter.hasDefault()) return parameter.name() + ": " + parameter.type().name() + " = " + parameter.defaultValue();
        return parameter.name() + ": " + parameter.type().name();
    }

    private static String append(String first, String second) {
        if (first == null || first.isBlank()) return second;
        return first + "\n" + second;
    }

    private static String emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }
}
