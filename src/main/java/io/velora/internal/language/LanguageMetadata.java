package io.velora.internal.language;

import io.velora.api.function.ApiRegistry;
import io.velora.api.function.FunctionDescriptor;
import io.velora.api.function.ParameterDescriptor;
import io.velora.api.interop.JavaImportRegistry;
import io.velora.api.language.CompletionItem;
import io.velora.api.language.SignatureHelp;
import io.velora.api.registry.ConstantRegistry;
import io.velora.api.registry.TypeRegistry;
import io.velora.api.type.EnumType;
import io.velora.api.type.StructType;
import io.velora.api.type.VeloraType;
import io.velora.api.type.VeloraTypes;
import io.velora.internal.lexer.Lexer;
import io.velora.internal.lexer.Token;
import io.velora.internal.lexer.TokenType;
import io.velora.internal.semantic.ResolvedScript;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    record VisibleVariable(String name, VeloraType type, boolean parameter) {}

    private static final Set<String> DURATION_UNITS = Set.of(
            "millis", "milliseconds", "ms",
            "seconds", "second", "sec", "s",
            "minutes", "minute", "min",
            "hours", "hour", "h",
            "days", "day"
    );

    static String typeDocumentation(String name) {
        return switch (name) {
            case "Any" -> "Dynamic Velora value used at host API boundaries when a concrete script type is not required.";
            case "Unit" -> "Represents no meaningful return value.";
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

    static String extractQualifierBackward(String source, int end) {
        if (end > 0 && source.charAt(end - 1) == '?') end--;
        int start = end;
        int parenDepth = 0;
        int bracketDepth = 0;
        char quote = 0;
        while (start > 0) {
            char c = source.charAt(start - 1);
            if (quote != 0) {
                start--;
                if (c == quote && !isEscapedBackward(source, start)) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') { quote = c; start--; continue; }
            if (c == ')') { parenDepth++; start--; continue; }
            if (c == '(') { if (parenDepth == 0) break; parenDepth--; start--; continue; }
            if (c == ']') { bracketDepth++; start--; continue; }
            if (c == '[') { if (bracketDepth == 0) break; bracketDepth--; start--; continue; }
            if (parenDepth > 0 || bracketDepth > 0) { start--; continue; }
            if (Character.isJavaIdentifierPart(c) || c == '.' || c == '?' || c == '<' || c == '>') start--;
            else break;
        }
        if (start == end || quote != 0 || parenDepth != 0 || bracketDepth != 0) return null;
        String qualifier = source.substring(start, end);
        return qualifier.startsWith(".") || qualifier.endsWith(".") ? null : qualifier;
    }

    private static boolean isEscapedBackward(String source, int index) {
        int slashes = 0;
        for (int i = index - 1; i >= 0 && source.charAt(i) == '\\'; i--) slashes++;
        return (slashes & 1) == 1;
    }

    static VeloraType qualifierType(String content, String qualifier, int cursorOffset, TypeRegistry typeRegistry, ResolvedScript resolved) {
        return qualifierType(content, qualifier, cursorOffset, null, typeRegistry, null, null, resolved);
    }

    static VeloraType qualifierType(String content, String qualifier, int cursorOffset, ApiRegistry apiRegistry,
                                    TypeRegistry typeRegistry, ConstantRegistry constantRegistry,
                                    JavaImportRegistry javaImportRegistry, ResolvedScript resolved) {
        if (qualifier == null || qualifier.isBlank() || cursorOffset <= 0) return null;
        String expr = qualifier.trim();
        if (expr.endsWith("?")) expr = expr.substring(0, expr.length() - 1);
        List<Token> exprTokens = lexCodeTokens(expr);
        if (exprTokens.isEmpty()) return null;
        Map<String, VisibleVariable> locals = visibleLocals(content, cursorOffset, apiRegistry, typeRegistry, constantRegistry, javaImportRegistry, resolved);
        return inferExprType(exprTokens, content, locals, apiRegistry, typeRegistry, constantRegistry, javaImportRegistry, resolved);
    }

    static Map<String, VisibleVariable> visibleLocals(String content, int cursorOffset, ApiRegistry apiRegistry,
                                                      TypeRegistry typeRegistry, ConstantRegistry constantRegistry,
                                                      JavaImportRegistry javaImportRegistry, ResolvedScript resolved) {
        List<Token> all = new Lexer(content, "locals.vls").lex().tokens();
        List<Token> tokens = new ArrayList<>();
        for (Token t : all) {
            if (t.is(TokenType.WHITESPACE) || t.is(TokenType.COMMENT) || t.is(TokenType.EOF)) continue;
            if (t.offset() >= cursorOffset) break;
            tokens.add(t);
        }
        Deque<Map<String, VisibleVariable>> stack = new ArrayDeque<>();
        stack.push(new LinkedHashMap<>());
        Map<String, VisibleVariable> pendingScope = new LinkedHashMap<>();
        int parenDepth = 0;
        int bracketDepth = 0;

        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.is(TokenType.LBRACE)) {
                Map<String, VisibleVariable> frame = new LinkedHashMap<>(pendingScope);
                pendingScope.clear();
                stack.push(frame);
                continue;
            }
            if (t.is(TokenType.RBRACE)) {
                pendingScope.clear();
                if (stack.size() > 1) stack.pop();
                continue;
            }
            if (t.is(TokenType.LPAREN)) {
                parenDepth++;
                continue;
            }
            if (t.is(TokenType.RPAREN)) {
                if (parenDepth > 0) parenDepth--;
                continue;
            }
            if (t.is(TokenType.LBRACKET)) {
                bracketDepth++;
                continue;
            }
            if (t.is(TokenType.RBRACKET)) {
                if (bracketDepth > 0) bracketDepth--;
                continue;
            }
            if (t.is(TokenType.NEWLINE) || t.is(TokenType.SEMICOLON)) continue;

            if (parenDepth == 0 && bracketDepth == 0 && t.is(TokenType.KW_FOR) && i + 1 < tokens.size() && tokens.get(i + 1).is(TokenType.LPAREN)) {
                int close = matchingDelimiter(tokens, i + 1, TokenType.LPAREN, TokenType.RPAREN);
                if (close > i + 1) {
                    int inPos = -1;
                    for (int k = i + 2; k < close; k++) {
                        if (tokens.get(k).is(TokenType.KW_IN)) {
                            inPos = k;
                            break;
                        }
                    }
                    if (inPos > i + 2) {
                        Token varToken = tokens.get(inPos - 1);
                        if (varToken.is(TokenType.IDENTIFIER)) {
                            VeloraType varType = null;
                            if (inPos - 1 > i + 2) {
                                int[] next = new int[1];
                                varType = parseTypeFromTokens(tokens, i + 2, next, typeRegistry);
                                if (next[0] != inPos - 1) varType = null;
                            }
                            if (varType == null && inPos + 1 < close) {
                                VeloraType iterType = inferExprType(tokens.subList(inPos + 1, close), content, flatten(stack), apiRegistry, typeRegistry, constantRegistry, javaImportRegistry, resolved);
                                if (iterType != null) {
                                    VeloraType base = iterType.nonNull();
                                    varType = VeloraTypes.listElement(base);
                                    if (varType == null) varType = VeloraTypes.setElement(base);
                                }
                            }
                            if (varType != null) pendingScope.put(varToken.text(), new VisibleVariable(varToken.text(), varType, false));
                        }
                    }
                }
                continue;
            }

            if (parenDepth == 0 && bracketDepth == 0 && t.is(TokenType.IDENTIFIER)
                    && i + 1 < tokens.size() && tokens.get(i + 1).is(TokenType.LPAREN)
                    && (i == 0 || !tokens.get(i - 1).is(TokenType.DOT))
                    && stack.size() <= 2) {
                int close = matchingDelimiter(tokens, i + 1, TokenType.LPAREN, TokenType.RPAREN);
                if (close > i + 1) {
                    int after = close + 1;
                    while (after < tokens.size() && tokens.get(after).is(TokenType.NEWLINE)) after++;
                    if (after < tokens.size() && tokens.get(after).is(TokenType.LBRACE)) {
                        pendingScope.clear();
                        extractParameters(tokens, i + 2, close, pendingScope, typeRegistry);
                        i = after - 1;
                        continue;
                    }
                }
            }

            if (parenDepth == 0 && bracketDepth == 0 && isStatementBoundary(tokens, i)) {
                int typeStart = t.is(TokenType.HASH) ? i + 1 : i;
                int[] nextIdx = new int[1];
                VeloraType explicit = parseTypeFromTokens(tokens, typeStart, nextIdx, typeRegistry);
                int namePos = nextIdx[0];
                if (explicit != null && namePos > typeStart && namePos < tokens.size() && tokens.get(namePos).is(TokenType.IDENTIFIER)) {
                    Token afterName = namePos + 1 < tokens.size() ? tokens.get(namePos + 1) : null;
                    if (afterName == null || afterName.is(TokenType.EQ) || afterName.is(TokenType.NEWLINE) || afterName.is(TokenType.SEMICOLON) || afterName.is(TokenType.RBRACE)) {
                        String varName = tokens.get(namePos).text();
                        stack.peek().put(varName, new VisibleVariable(varName, explicit, false));
                        i = namePos;
                        continue;
                    }
                }
                if (t.is(TokenType.IDENTIFIER) && i + 1 < tokens.size() && tokens.get(i + 1).is(TokenType.EQ)) {
                    String varName = t.text();
                    if (!varName.equals("await") && resolveInStack(stack, varName) == null) {
                        int rhsStart = i + 2;
                        int rhsEnd = expressionEnd(tokens, rhsStart);
                        VeloraType inferred = rhsEnd > rhsStart
                                ? inferExprType(tokens.subList(rhsStart, rhsEnd), content, flatten(stack), apiRegistry, typeRegistry, constantRegistry, javaImportRegistry, resolved)
                                : null;
                        if (inferred != null) stack.peek().put(varName, new VisibleVariable(varName, inferred, false));
                        i = Math.max(i, rhsEnd - 1);
                    }
                }
            }
        }
        return flatten(stack);
    }

    private static void extractParameters(List<Token> tokens, int from, int close, Map<String, VisibleVariable> target, TypeRegistry typeRegistry) {
        int i = from;
        while (i < close) {
            if (tokens.get(i).is(TokenType.NEWLINE)) { i++; continue; }
            int[] next = new int[1];
            VeloraType paramType = parseTypeFromTokens(tokens, i, next, typeRegistry);
            int namePos = next[0];
            if (paramType != null && namePos < close && tokens.get(namePos).is(TokenType.IDENTIFIER)) {
                target.put(tokens.get(namePos).text(), new VisibleVariable(tokens.get(namePos).text(), paramType, true));
            }
            int pDepth = 0;
            int aDepth = 0;
            while (i < close) {
                Token cur = tokens.get(i++);
                if (cur.is(TokenType.LPAREN) || cur.is(TokenType.LBRACKET)) pDepth++;
                else if (cur.is(TokenType.RPAREN) || cur.is(TokenType.RBRACKET)) { if (pDepth > 0) pDepth--; }
                else if (cur.is(TokenType.LT)) aDepth++;
                else if (cur.is(TokenType.GT)) { if (aDepth > 0) aDepth--; }
                else if (cur.is(TokenType.COMMA) && pDepth == 0 && aDepth == 0) break;
            }
        }
    }

    private static Map<String, VisibleVariable> flatten(Deque<Map<String, VisibleVariable>> stack) {
        Map<String, VisibleVariable> result = new LinkedHashMap<>();
        var it = stack.descendingIterator();
        while (it.hasNext()) result.putAll(it.next());
        return result;
    }

    private static VisibleVariable resolveInStack(Deque<Map<String, VisibleVariable>> stack, String name) {
        for (Map<String, VisibleVariable> frame : stack) {
            VisibleVariable v = frame.get(name);
            if (v != null) return v;
        }
        return null;
    }

    private static boolean isStatementBoundary(List<Token> tokens, int index) {
        if (index == 0) return true;
        Token prev = tokens.get(index - 1);
        return prev.is(TokenType.NEWLINE) || prev.is(TokenType.LBRACE) || prev.is(TokenType.RBRACE) || prev.is(TokenType.SEMICOLON);
    }

    private static int expressionEnd(List<Token> tokens, int from) {
        int p = 0;
        int b = 0;
        int c = 0;
        int i = from;
        while (i < tokens.size()) {
            Token t = tokens.get(i);
            if (t.is(TokenType.LPAREN)) p++;
            else if (t.is(TokenType.RPAREN)) { if (p > 0) p--; else break; }
            else if (t.is(TokenType.LBRACKET)) b++;
            else if (t.is(TokenType.RBRACKET)) { if (b > 0) b--; else break; }
            else if (t.is(TokenType.LBRACE)) c++;
            else if (t.is(TokenType.RBRACE)) { if (c > 0) c--; else break; }
            else if ((t.is(TokenType.NEWLINE) || t.is(TokenType.SEMICOLON)) && p == 0 && b == 0 && c == 0) break;
            i++;
        }
        return i;
    }

    private static int matchingDelimiter(List<Token> tokens, int open, TokenType openType, TokenType closeType) {
        int depth = 0;
        for (int i = open; i < tokens.size(); i++) {
            if (tokens.get(i).is(openType)) depth++;
            else if (tokens.get(i).is(closeType) && --depth == 0) return i;
        }
        return -1;
    }

    private static VeloraType parseTypeFromTokens(List<Token> tokens, int start, int[] nextOut, TypeRegistry types) {
        if (start >= tokens.size() || !tokens.get(start).is(TokenType.IDENTIFIER)) return null;
        StringBuilder sb = new StringBuilder(tokens.get(start).text());
        int i = start + 1;
        if (i < tokens.size() && tokens.get(i).is(TokenType.LT)) {
            int depth = 0;
            while (i < tokens.size()) {
                Token tok = tokens.get(i++);
                if (tok.is(TokenType.NEWLINE)) return null;
                sb.append(tok.text());
                if (tok.is(TokenType.LT)) depth++;
                else if (tok.is(TokenType.GT) && --depth == 0) break;
            }
            if (depth != 0) return null;
        }
        if (i < tokens.size() && tokens.get(i).is(TokenType.QUESTION)) {
            sb.append('?');
            i++;
        }
        VeloraType parsed = types != null ? parseType(sb.toString(), types) : parseBuiltinTypeOnly(sb.toString());
        if (parsed == null) return null;
        nextOut[0] = i;
        return parsed;
    }

    private static List<Token> lexCodeTokens(String text) {
        List<Token> out = new ArrayList<>();
        for (Token t : new Lexer(text, "expr.vls").lex().tokens()) {
            if (!t.isTrivia() && !t.is(TokenType.EOF)) out.add(t);
        }
        return out;
    }

    private static VeloraType inferExprType(List<Token> tokens, String content, Map<String, VisibleVariable> locals,
                                            ApiRegistry apiRegistry, TypeRegistry typeRegistry, ConstantRegistry constantRegistry,
                                            JavaImportRegistry javaImportRegistry, ResolvedScript resolved) {
        if (tokens == null || tokens.isEmpty()) return null;
        int start = 0;
        int end = tokens.size();
        while (start < end && tokens.get(start).is(TokenType.LPAREN) && matchingDelimiter(tokens, start, TokenType.LPAREN, TokenType.RPAREN) == end - 1) {
            start++;
            end--;
        }
        if (start >= end) return null;
        List<Token> slice = start == 0 && end == tokens.size() ? tokens : tokens.subList(start, end);
        int n = slice.size();

        int pDepth = 0, bDepth = 0, cDepth = 0;
        for (int i = 0; i < n; i++) {
            Token tok = slice.get(i);
            if (tok.is(TokenType.LPAREN)) pDepth++;
            else if (tok.is(TokenType.RPAREN)) { if (pDepth > 0) pDepth--; }
            else if (tok.is(TokenType.LBRACKET)) bDepth++;
            else if (tok.is(TokenType.RBRACKET)) { if (bDepth > 0) bDepth--; }
            else if (tok.is(TokenType.LBRACE)) cDepth++;
            else if (tok.is(TokenType.RBRACE)) { if (cDepth > 0) cDepth--; }
            else if (pDepth == 0 && bDepth == 0 && cDepth == 0 && tok.is(TokenType.QUESTION) && i + 1 < n && slice.get(i + 1).is(TokenType.COLON)) {
                VeloraType left = inferExprType(slice.subList(0, i), content, locals, apiRegistry, typeRegistry, constantRegistry, javaImportRegistry, resolved);
                VeloraType right = inferExprType(slice.subList(i + 2, n), content, locals, apiRegistry, typeRegistry, constantRegistry, javaImportRegistry, resolved);
                if (left == null || left.nonNull() == VeloraTypes.ANY) return right;
                return left.nonNull();
            }
        }
        pDepth = bDepth = cDepth = 0;
        int opIndex = -1;
        String opText = null;
        for (int i = 0; i < n; i++) {
            Token tok = slice.get(i);
            if (tok.is(TokenType.LPAREN)) pDepth++;
            else if (tok.is(TokenType.RPAREN)) { if (pDepth > 0) pDepth--; }
            else if (tok.is(TokenType.LBRACKET)) bDepth++;
            else if (tok.is(TokenType.RBRACKET)) { if (bDepth > 0) bDepth--; }
            else if (tok.is(TokenType.LBRACE)) cDepth++;
            else if (tok.is(TokenType.RBRACE)) { if (cDepth > 0) cDepth--; }
            else if (pDepth == 0 && bDepth == 0 && cDepth == 0 && i > 0) {
                if (tok.is(TokenType.LT) && isCollectionStart(slice, i)) {
                    int ad = 1;
                    while (i + 1 < n && ad > 0) {
                        i++;
                        if (slice.get(i).is(TokenType.LT)) ad++;
                        else if (slice.get(i).is(TokenType.GT)) ad--;
                    }
                    continue;
                }
                if (tok.is(TokenType.OR_OR) || tok.is(TokenType.AND_AND) || tok.is(TokenType.EQ_EQ) || tok.is(TokenType.BANG_EQ)
                        || tok.is(TokenType.LT) || tok.is(TokenType.LE) || tok.is(TokenType.GT) || tok.is(TokenType.GE)
                        || tok.is(TokenType.KW_IS)) {
                    return VeloraTypes.BOOLEAN;
                }
                if (tok.is(TokenType.PLUS) || tok.is(TokenType.MINUS) || tok.is(TokenType.STAR) || tok.is(TokenType.SLASH) || tok.is(TokenType.PERCENT)) {
                    opIndex = i;
                    opText = tok.text();
                }
            }
        }
        if (opIndex > 0 && opIndex + 1 < n) {
            VeloraType lt = inferExprType(slice.subList(0, opIndex), content, locals, apiRegistry, typeRegistry, constantRegistry, javaImportRegistry, resolved);
            VeloraType rt = inferExprType(slice.subList(opIndex + 1, n), content, locals, apiRegistry, typeRegistry, constantRegistry, javaImportRegistry, resolved);
            if ("+".equals(opText) && (lt != null && lt.nonNull() == VeloraTypes.STRING || rt != null && rt.nonNull() == VeloraTypes.STRING)) {
                return VeloraTypes.STRING;
            }
            return widenNumeric(lt, rt);
        }

        if (n >= 2 && slice.get(0).is(TokenType.IDENTIFIER) && slice.get(0).text().equals("await")) {
            VeloraType awaited = inferExprType(slice.subList(1, n), content, locals, apiRegistry, typeRegistry, constantRegistry, javaImportRegistry, resolved);
            return awaited == null ? null : VeloraTypes.taskResult(awaited.nonNull());
        }
        if (n >= 2 && slice.get(0).is(TokenType.KW_SPAWN) && slice.get(1).is(TokenType.IDENTIFIER) && resolved != null) {
            var fn = resolved.functions().get(slice.get(1).text());
            return fn != null && fn.returnType() != null ? VeloraTypes.task(fn.returnType()) : null;
        }

        if (n == 1) {
            Token tok = slice.get(0);
            if (tok.is(TokenType.INTEGER)) return VeloraTypes.INT;
            if (tok.is(TokenType.LONG_LITERAL)) return VeloraTypes.LONG;
            if (tok.is(TokenType.FLOAT_LITERAL)) return VeloraTypes.FLOAT;
            if (tok.is(TokenType.DOUBLE_LITERAL)) return VeloraTypes.DOUBLE;
            if (tok.is(TokenType.STRING) || tok.is(TokenType.STRING_INTERP)) return VeloraTypes.STRING;
            if (tok.is(TokenType.KW_TRUE) || tok.is(TokenType.KW_FALSE) || tok.is(TokenType.BOOLEAN)) return VeloraTypes.BOOLEAN;
            if (tok.is(TokenType.KW_NULL) || tok.is(TokenType.NULL)) return VeloraTypes.ANY.nullable();
            if (tok.is(TokenType.IDENTIFIER)) {
                String name = tok.text();
                VisibleVariable local = locals.get(name);
                if (local != null) return local.type();
                if (resolved != null) {
                    var prop = resolved.properties().get(name);
                    if (prop != null) return prop.type();
                    for (var s : resolved.settings()) if (s.id().equals(name)) return s.type();
                }
            }
            return null;
        }

        if (slice.get(0).is(TokenType.LBRACKET) && matchingDelimiter(slice, 0, TokenType.LBRACKET, TokenType.RBRACKET) == n - 1) {
            if (n == 2) return VeloraTypes.list(VeloraTypes.ANY);
            VeloraType first = inferExprType(firstCommaItem(slice.subList(1, n - 1)), content, locals, apiRegistry, typeRegistry, constantRegistry, javaImportRegistry, resolved);
            return first != null ? VeloraTypes.list(first) : VeloraTypes.list(VeloraTypes.ANY);
        }
        if (slice.get(0).is(TokenType.LBRACE) && matchingDelimiter(slice, 0, TokenType.LBRACE, TokenType.RBRACE) == n - 1) {
            if (n == 2) return VeloraTypes.map(VeloraTypes.ANY, VeloraTypes.ANY);
            List<Token> inner = firstCommaItem(slice.subList(1, n - 1));
            int colon = topLevelColon(inner);
            if (colon > 0) {
                VeloraType k = inferExprType(inner.subList(0, colon), content, locals, apiRegistry, typeRegistry, constantRegistry, javaImportRegistry, resolved);
                VeloraType v = inferExprType(inner.subList(colon + 1, inner.size()), content, locals, apiRegistry, typeRegistry, constantRegistry, javaImportRegistry, resolved);
                if (k != null && v != null) return VeloraTypes.map(k, v);
            }
            return VeloraTypes.map(VeloraTypes.ANY, VeloraTypes.ANY);
        }

        if (slice.get(0).is(TokenType.IDENTIFIER) && (slice.get(0).text().equals("list") || slice.get(0).text().equals("set") || slice.get(0).text().equals("map"))
                && slice.get(1).is(TokenType.LT) && n >= 5 && slice.get(n - 1).is(TokenType.RPAREN)
                && matchingOpenBackward(slice, n - 1, TokenType.LPAREN, TokenType.RPAREN) == n - 2) {
            StringBuilder sb = new StringBuilder(capitalizeCollection(slice.get(0).text()));
            for (int i = 1; i < n - 2; i++) sb.append(slice.get(i).text());
            return parseType(sb.toString(), typeRegistry);
        }

        if (slice.get(n - 1).is(TokenType.RBRACKET)) {
            int open = matchingOpenBackward(slice, n - 1, TokenType.LBRACKET, TokenType.RBRACKET);
            if (open > 0) {
                VeloraType recv = inferExprType(slice.subList(0, open), content, locals, apiRegistry, typeRegistry, constantRegistry, javaImportRegistry, resolved);
                if (recv == null) return null;
                VeloraType base = recv.nonNull();
                VeloraType listEl = VeloraTypes.listElement(base);
                if (listEl != null) return listEl;
                VeloraType mapVal = VeloraTypes.mapValue(base);
                if (mapVal != null) return mapVal;
                if (base == VeloraTypes.STRING) return VeloraTypes.CHAR;
                if (base == VeloraTypes.VEC2 || base == VeloraTypes.VEC3) return VeloraTypes.DOUBLE;
                if (base == VeloraTypes.COLOR) return VeloraTypes.INT;
                return null;
            }
        }

        if (slice.get(n - 1).is(TokenType.RPAREN)) {
            int open = matchingOpenBackward(slice, n - 1, TokenType.LPAREN, TokenType.RPAREN);
            if (open == 1 && slice.get(0).is(TokenType.IDENTIFIER)) {
                String fnName = slice.get(0).text();
                if (resolved != null) {
                    var fn = resolved.functions().get(fnName);
                    if (fn != null) return fn.returnType();
                }
                return null;
            }
            if (open >= 3 && slice.get(open - 1).is(TokenType.IDENTIFIER) && slice.get(open - 2).is(TokenType.DOT)) {
                boolean safe = open >= 4 && slice.get(open - 3).is(TokenType.QUESTION);
                int dotEnd = safe ? open - 3 : open - 2;
                String memberName = slice.get(open - 1).text();
                List<Token> recvTokens = slice.subList(0, dotEnd);
                if (recvTokens.size() == 1 && recvTokens.get(0).is(TokenType.IDENTIFIER) && apiRegistry != null) {
                    String ns = JavaImportAliases.namespace(content, recvTokens.get(0).text(), javaImportRegistry);
                    FunctionDescriptor fd = apiRegistry.find(ns, memberName);
                    if (fd != null) return fd.returnType();
                }
                VeloraType recv = inferExprType(recvTokens, content, locals, apiRegistry, typeRegistry, constantRegistry, javaImportRegistry, resolved);
                Member m = member(recv, memberName);
                if (m != null) return safe ? m.returnType().nullable() : m.returnType();
                return null;
            }
        }

        if (n >= 3 && slice.get(n - 1).is(TokenType.IDENTIFIER) && slice.get(n - 2).is(TokenType.DOT)) {
            boolean safe = n >= 4 && slice.get(n - 3).is(TokenType.QUESTION);
            int dotEnd = safe ? n - 3 : n - 2;
            String memberName = slice.get(n - 1).text();
            List<Token> recvTokens = slice.subList(0, dotEnd);
            if (recvTokens.size() == 1) {
                Token rt = recvTokens.get(0);
                if ((rt.is(TokenType.INTEGER) || rt.is(TokenType.LONG_LITERAL) || rt.is(TokenType.FLOAT_LITERAL) || rt.is(TokenType.DOUBLE_LITERAL))
                        && DURATION_UNITS.contains(memberName)) {
                    return VeloraTypes.DURATION;
                }
                if (rt.is(TokenType.IDENTIFIER)) {
                    String name = rt.text();
                    if (constantRegistry != null) {
                        var c = constantRegistry.find(name, memberName);
                        if (c != null) return c.type();
                    }
                    if (typeRegistry != null && typeRegistry.find(name) instanceof EnumType enumType && enumType.hasConstant(memberName)) {
                        return enumType;
                    }
                    if (apiRegistry != null) {
                        String ns = JavaImportAliases.namespace(content, name, javaImportRegistry);
                        FunctionDescriptor fd = apiRegistry.find(ns, memberName);
                        if (fd != null && fd.property()) return fd.returnType();
                    }
                }
            }
            VeloraType recv = inferExprType(recvTokens, content, locals, apiRegistry, typeRegistry, constantRegistry, javaImportRegistry, resolved);
            Member m = member(recv, memberName);
            if (m != null && m.property()) return safe ? m.returnType().nullable() : m.returnType();
        }
        return null;
    }

    private static List<Token> firstCommaItem(List<Token> tokens) {
        int p = 0, b = 0, c = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.is(TokenType.LPAREN)) p++;
            else if (t.is(TokenType.RPAREN)) { if (p > 0) p--; }
            else if (t.is(TokenType.LBRACKET)) b++;
            else if (t.is(TokenType.RBRACKET)) { if (b > 0) b--; }
            else if (t.is(TokenType.LBRACE)) c++;
            else if (t.is(TokenType.RBRACE)) { if (c > 0) c--; }
            else if (t.is(TokenType.COMMA) && p == 0 && b == 0 && c == 0) return tokens.subList(0, i);
        }
        return tokens;
    }

    private static int topLevelColon(List<Token> tokens) {
        int p = 0, b = 0, c = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.is(TokenType.LPAREN)) p++;
            else if (t.is(TokenType.RPAREN)) { if (p > 0) p--; }
            else if (t.is(TokenType.LBRACKET)) b++;
            else if (t.is(TokenType.RBRACKET)) { if (b > 0) b--; }
            else if (t.is(TokenType.LBRACE)) c++;
            else if (t.is(TokenType.RBRACE)) { if (c > 0) c--; }
            else if (t.is(TokenType.COLON) && p == 0 && b == 0 && c == 0) return i;
        }
        return -1;
    }

    private static int matchingOpenBackward(List<Token> tokens, int close, TokenType openType, TokenType closeType) {
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            if (tokens.get(i).is(closeType)) depth++;
            else if (tokens.get(i).is(openType) && --depth == 0) return i;
        }
        return -1;
    }

    private static String capitalizeCollection(String name) {
        return switch (name) {
            case "list" -> "List";
            case "set" -> "Set";
            default -> "Map";
        };
    }

    private static VeloraType parseBuiltinTypeOnly(String text) {
        boolean nullable = text.endsWith("?");
        if (nullable) text = text.substring(0, text.length() - 1);
        VeloraType type = switch (text) {
            case "Any" -> VeloraTypes.ANY;
            case "Unit" -> VeloraTypes.UNIT;
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
            default -> null;
        };
        return type != null && nullable ? type.nullable() : type;
    }


    static Member member(VeloraType type, String name) {
        for (Member member : members(type)) if (member.name().equals(name)) return member;
        return null;
    }

    private static boolean isCollectionStart(List<Token> slice, int ltIndex) {
        if (ltIndex <= 0) return false;
        String prev = slice.get(ltIndex - 1).text();
        return prev.equals("list") || prev.equals("set") || prev.equals("map") || prev.equals("List") || prev.equals("Set") || prev.equals("Map");
    }

    private static VeloraType widenNumeric(VeloraType left, VeloraType right) {
        if (left == null || right == null) return null;
        VeloraType l = left.nonNull();
        VeloraType r = right.nonNull();
        if (l == VeloraTypes.DOUBLE || r == VeloraTypes.DOUBLE) return VeloraTypes.DOUBLE;
        if (l == VeloraTypes.FLOAT || r == VeloraTypes.FLOAT) return VeloraTypes.FLOAT;
        if (l == VeloraTypes.LONG || r == VeloraTypes.LONG) return VeloraTypes.LONG;
        if (l == VeloraTypes.INT || r == VeloraTypes.INT || l == VeloraTypes.BYTE || r == VeloraTypes.BYTE) return VeloraTypes.INT;
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
        } else {
            type = parseBuiltinTypeOnly(text);
            if (type == null && types != null) type = types.find(text);
        }
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
