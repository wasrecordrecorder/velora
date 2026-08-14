package io.velora.internal.parser;

import io.velora.api.compiler.Diagnostic;
import io.velora.api.compiler.DiagnosticCode;
import io.velora.api.compiler.DiagnosticSeverity;
import io.velora.api.compiler.SourceRange;
import io.velora.internal.ast.*;
import io.velora.internal.lexer.Lexer;
import io.velora.internal.lexer.Token;
import io.velora.internal.lexer.TokenStream;
import io.velora.internal.lexer.TokenType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Parser {

    private static final Set<String> OLD_KEYWORDS = Set.of("fun", "val", "var", "suspend", "setting");
    private static final Set<String> ENTRY_NAMES = Set.of(
            "onLoad", "onEnable", "onRun", "onDisable", "onUnload", "onTick");

    private final ParserContext context;
    private final ExpressionParser exprParser;

    public Parser(TokenStream tokens, String filePath) {
        this.context = new ParserContext(tokens, filePath);
        this.exprParser = new ExpressionParser(context);
    }

    public ParseResult parse() {
        try {
            String packageDecl = parsePackageDecl();
            List<String> imports = parseImports();
            List<AnnotationNode> annotations = parseAnnotations();
            ScriptNode script = parseScript(packageDecl, imports, annotations);
            if (script == null) {
                return ParseResult.failure(context.diagnostics());
            }
            // Check for multiple script declarations (skip trailing newlines)
            context.tokens().skipNewlines();
            if (context.tokens().hasMore()) {
                context.error(DiagnosticCode.PARSER_UNEXPECTED_TOKEN, "Multiple script declarations are not allowed", context.currentLine(), context.currentColumn());
            }
            return new ParseResult(script, context.diagnostics());
        } catch (Exception e) {
            context.error(DiagnosticCode.PARSER_UNEXPECTED_TOKEN, "Parser error: " + e.getMessage(), context.currentLine(), context.currentColumn());
            return ParseResult.failure(context.diagnostics());
        }
    }

    public static ParseResult parse(String source, String filePath) {
        Lexer lexer = new Lexer(source, filePath);
        var lexResult = lexer.lex();
        TokenStream stream = new TokenStream(lexResult.tokens());
        Parser parser = new Parser(stream, filePath);
        return parser.parse();
    }

    // Look ahead past newlines without consuming tokens
    private Token peekAhead(int offset) {
        List<Token> sig = context.tokens().significant();
        int pos = context.tokens().position();
        int count = 0;
        for (int i = pos; i < sig.size(); i++) {
            Token t = sig.get(i);
            if (t.type() == TokenType.NEWLINE) continue;
            if (count == offset) return t;
            count++;
        }
        return Token.eof(0, 0, 0);
    }

    private void migrationError(String oldKw, String suggestion, Token token) {
        context.error(DiagnosticCode.PARSER_UNEXPECTED_TOKEN,
                "Migration: '" + oldKw + "' is removed in V2. Use " + suggestion + " instead.", token);
    }

    private String parsePackageDecl() {
        context.skipTrivia();
        if (!context.check(TokenType.KW_PACKAGE)) return null;
        Token start = context.advance();
        StringBuilder sb = new StringBuilder();
        while (context.check(TokenType.IDENTIFIER) || context.check(TokenType.DOT)) sb.append(context.advance().text());
        context.error(DiagnosticCode.PARSER_UNEXPECTED_TOKEN, "Package declarations are not supported in Velora V2", start);
        return sb.toString();
    }

    private List<String> parseImports() {
        List<String> imports = new ArrayList<>();
        context.skipTrivia();
        while (context.check(TokenType.KW_IMPORT)) {
            Token start = context.advance();
            StringBuilder sb = new StringBuilder();
            while (context.check(TokenType.IDENTIFIER) || context.check(TokenType.DOT) || context.check(TokenType.STAR)) sb.append(context.advance().text());
            imports.add(sb.toString());
            context.error(DiagnosticCode.PARSER_UNEXPECTED_TOKEN, "Import declarations are not supported in Velora V2", start);
            context.skipTrivia();
        }
        return imports;
    }

    private List<AnnotationNode> parseAnnotations() {
        List<AnnotationNode> annotations = new ArrayList<>();
        context.skipTrivia();
        while (context.check(TokenType.ANNOTATION)) {
            annotations.add(parseAnnotation());
            context.skipTrivia();
        }
        return annotations;
    }

    private AnnotationNode parseAnnotation() {
        Token annotationToken = context.advance();
        String name = annotationToken.text().substring(1); // strip @

        List<Object> positionalArgs = new ArrayList<>();
        Map<String, Object> namedArgs = new LinkedHashMap<>();

        context.skipTrivia();
        if (context.match(TokenType.LPAREN)) {
            context.skipTrivia();
            if (!context.check(TokenType.RPAREN)) {
                parseAnnotationArguments(positionalArgs, namedArgs);
            }
            context.expect(TokenType.RPAREN, "Expected ')' after annotation arguments");
        }

        return new AnnotationNode(context.filePath(), annotationToken.line(), annotationToken.column(),
                name, positionalArgs, namedArgs);
    }

    private void parseAnnotationArguments(List<Object> positionalArgs, Map<String, Object> namedArgs) {
        boolean first = true;
        while (!context.check(TokenType.RPAREN) && context.tokens().hasMore()) {
            if (!first) {
                if (!context.match(TokenType.COMMA)) break;
                context.skipTrivia();
                if (context.check(TokenType.RPAREN)) break;
            }
            first = false;

            // Check for named argument: IDENTIFIER EQ (but not EQ_EQ)
            if (context.check(TokenType.IDENTIFIER) && peekAhead(1).is(TokenType.EQ)) {
                Token nameToken = context.advance();
                context.advance(); // EQ
                context.skipTrivia();
                boolean explicitNull = isExplicitNull();
                Object value = parseAnnotationValue();
                if (namedArgs.containsKey(nameToken.text())) context.error(DiagnosticCode.PARSER_UNEXPECTED_TOKEN, "Duplicate named argument: " + nameToken.text(), nameToken);
                else if (value != null || explicitNull) namedArgs.put(nameToken.text(), value);
            } else {
                boolean explicitNull = isExplicitNull();
                Object value = parseAnnotationValue();
                if (value != null || explicitNull) positionalArgs.add(value);
            }
            context.skipTrivia();
        }
    }

    private Object parseAnnotationValue() {
        context.skipTrivia();
        Token token = context.peek();
        if (isNumberStart(token)) {
            Number left = parseSignedNumberValue();
            if (left == null) return null;
            context.skipTrivia();
            if (context.check(TokenType.RANGE)) {
                context.advance();
                context.skipTrivia();
                Number right = parseSignedNumberValue();
                if (right == null) {
                    context.error(DiagnosticCode.PARSER_UNEXPECTED_TOKEN, "Expected number after '..'", context.peek());
                    return null;
                }
                return new RangeValue(left, right);
            }
            return left;
        }
        if (token.is(TokenType.STRING)) {
            context.advance();
            return unescapeString(token.text().substring(1, token.text().length() - 1));
        }
        if (token.is(TokenType.STRING_INTERP)) {
            context.error(DiagnosticCode.PARSER_UNEXPECTED_TOKEN, "String interpolation is not allowed in annotation arguments", token);
            context.advance();
            return unescapeString(token.text().substring(1, token.text().length() - 1));
        }
        if (token.is(TokenType.KW_TRUE) || token.is(TokenType.BOOLEAN) && token.text().equals("true")) {
            context.advance();
            return true;
        }
        if (token.is(TokenType.KW_FALSE) || token.is(TokenType.BOOLEAN) && token.text().equals("false")) {
            context.advance();
            return false;
        }
        if (token.is(TokenType.KW_NULL) || token.is(TokenType.NULL)) {
            context.advance();
            return null;
        }
        if (token.is(TokenType.IDENTIFIER)) {
            context.advance();
            StringBuilder value = new StringBuilder(token.text());
            while (context.check(TokenType.DOT)) {
                context.advance();
                Token member = context.expect(TokenType.IDENTIFIER, "Expected identifier after '.'");
                value.append('.').append(member.text());
            }
            return value.toString();
        }
        if (token.is(TokenType.ANNOTATION)) {
            context.advance();
            return token.text().substring(1);
        }
        if (token.is(TokenType.LBRACKET)) {
            context.advance();
            List<Object> list = new ArrayList<>();
            context.skipTrivia();
            while (!context.check(TokenType.RBRACKET) && context.tokens().hasMore()) {
                boolean explicitNull = isExplicitNull();
                Object value = parseAnnotationValue();
                if (value != null || explicitNull) list.add(value);
                context.skipTrivia();
                if (!context.match(TokenType.COMMA)) break;
                context.skipTrivia();
            }
            context.expect(TokenType.RBRACKET, "Expected ']' after list");
            return list;
        }
        context.error(DiagnosticCode.PARSER_UNEXPECTED_TOKEN, "Invalid annotation value: " + token.text(), token);
        context.advance();
        return null;
    }

    private boolean isExplicitNull() {
        Token token = context.peek();
        return token.is(TokenType.KW_NULL) || token.is(TokenType.NULL);
    }

    private boolean isNumberStart(Token token) {
        return token.is(TokenType.INTEGER) || token.is(TokenType.DOUBLE_LITERAL) || token.is(TokenType.FLOAT_LITERAL) || token.is(TokenType.LONG_LITERAL) || token.is(TokenType.MINUS);
    }

    private Number parseSignedNumberValue() {
        boolean negative = context.match(TokenType.MINUS);
        Token token = context.peek();
        Number value = parseNumberValue(token);
        if (value == null) return null;
        context.advance();
        if (!negative) return value;
        if (value instanceof Double d) return -d;
        if (value instanceof Float f) return -f;
        if (value instanceof Long l) return -l;
        return -value.intValue();
    }

    private Number parseNumberValue(Token token) {
        if (token.is(TokenType.INTEGER)) return Integer.parseInt(token.text());
        if (token.is(TokenType.LONG_LITERAL)) return Long.parseLong(token.text().replaceAll("[lL]$", ""));
        if (token.is(TokenType.DOUBLE_LITERAL)) return Double.parseDouble(token.text().replaceAll("[dD]$", ""));
        if (token.is(TokenType.FLOAT_LITERAL)) return Float.parseFloat(token.text().replaceAll("[fF]$", ""));
        return null;
    }

    private String unescapeString(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                result.append(c);
                continue;
            }
            char escaped = value.charAt(++i);
            result.append(switch (escaped) {
                case 'n' -> '\n';
                case 't' -> '\t';
                case 'r' -> '\r';
                case '\\' -> '\\';
                case '"' -> '"';
                case '\'' -> '\'';
                case '$' -> '$';
                default -> escaped;
            });
        }
        return result.toString();
    }

    /** Represents a range value like 8..128 in setting declarations. */
    public record RangeValue(Number min, Number max) {}

    private ScriptNode parseScript(String packageDecl, List<String> imports, List<AnnotationNode> annotations) {
        context.skipTrivia();
        if (!context.check(TokenType.KW_SCRIPT)) {
            context.error(DiagnosticCode.PARSER_MISSING_TOKEN, "Expected 'script' keyword", context.currentLine(), context.currentColumn());
            return null;
        }

        Token scriptToken = context.advance();
        Token nameToken = context.expect(TokenType.IDENTIFIER, "Expected script name");
        String scriptName = nameToken.text();

        // V2: no constructor settings. If we see '(', it's old syntax.
        context.skipTrivia();
        if (context.check(TokenType.LPAREN)) {
            context.error(DiagnosticCode.PARSER_UNEXPECTED_TOKEN,
                    "Migration: script constructor settings are removed in V2. Use 'settings { }' inside script body.",
                    context.peek());
            // Skip the constructor part
            context.advance();
            while (!context.check(TokenType.LBRACE) && !context.check(TokenType.EOF)) {
                context.advance();
            }
        }

        // Parse script body - settings block may appear as first member
        BlockNode body = parseScriptBody();

        // Extract settings block from body if present
        SettingBlockNode settingBlock = null;
        List<ScriptMemberNode> members = new ArrayList<>();
        if (body != null) {
            for (StatementNode s : body.statements()) {
                if (s instanceof SettingBlockNode sb) {
                    settingBlock = sb;
                } else if (s instanceof ScriptMemberNode sm) {
                    members.add(sm);
                }
            }
        }

        return new ScriptNode(context.filePath(), scriptToken.line(), scriptToken.column(),
                packageDecl, imports, annotations, scriptName, settingBlock, members);
    }

    private BlockNode parseScriptBody() {
        context.skipTrivia();
        Token lbrace = context.expect(TokenType.LBRACE, "Expected '{' for script body");
        List<StatementNode> statements = new ArrayList<>();
        context.skipTrivia();
        while (!context.check(TokenType.RBRACE) && context.tokens().hasMore()) {
            StatementNode member = parseScriptMember();
            if (member != null) {
                statements.add(member);
            }
            context.skipTrivia();
        }
        context.expect(TokenType.RBRACE, "Expected '}' after script body");
        return new BlockNode(context.filePath(), lbrace.line(), lbrace.column(), statements);
    }

    private StatementNode parseScriptMember() {
        context.skipTrivia();

        // Collect leading annotations
        List<AnnotationNode> leadingAnnotations = new ArrayList<>();
        while (context.check(TokenType.ANNOTATION)) {
            leadingAnnotations.add(parseAnnotation());
            context.skipTrivia();
        }

        // Check for old keywords (migration diagnostics)
        Token token = context.peek();
        if (token.is(TokenType.IDENTIFIER) && OLD_KEYWORDS.contains(token.text())) {
            String kw = token.text();
            String suggestion = switch (kw) {
                case "fun" -> "Type name(params) { }";
                case "val" -> "Type name = value";
                case "var" -> "Type name = value";
                case "suspend" -> "async";
                case "setting" -> "settings { }";
                default -> kw;
            };
            migrationError(kw, suggestion, token);
            // Skip to next member
            while (!context.check(TokenType.RBRACE) && !context.check(TokenType.EOF)) {
                context.advance();
                context.skipTrivia();
                if (context.check(TokenType.IDENTIFIER) || context.check(TokenType.ANNOTATION) ||
                    context.check(TokenType.KW_SETTINGS) || context.check(TokenType.KW_ENTRY) ||
                    context.check(TokenType.KW_EVENT) || context.check(TokenType.KW_ASYNC) ||
                    context.check(TokenType.KW_STATIC) || context.check(TokenType.HASH) ||
                    context.check(TokenType.KW_PRIVATE) || context.check(TokenType.KW_PUBLIC)) {
                    break;
                }
            }
            return null;
        }

        // Modifiers
        boolean isPrivate = false;
        boolean isPublic = false;
        if (context.check(TokenType.KW_PRIVATE)) {
            Token modifier = context.advance();
            isPrivate = true;
            context.error(DiagnosticCode.PARSER_UNEXPECTED_TOKEN, "Visibility modifiers are not supported in Velora V2", modifier);
        } else if (context.check(TokenType.KW_PUBLIC)) {
            Token modifier = context.advance();
            isPublic = true;
            context.error(DiagnosticCode.PARSER_UNEXPECTED_TOKEN, "Visibility modifiers are not supported in Velora V2", modifier);
        }
        context.skipTrivia();

        // settings block
        if (context.check(TokenType.KW_SETTINGS)) {
            return parseSettingBlock();
        }

        // async modifier
        boolean isAsync = false;
        if (context.check(TokenType.KW_ASYNC)) {
            context.advance();
            isAsync = true;
            context.skipTrivia();
        }

        // entry declaration
        if (context.check(TokenType.KW_ENTRY)) {
            return parseEntryDeclaration(leadingAnnotations, isAsync);
        }

        // event declaration
        if (context.check(TokenType.KW_EVENT)) {
            return parseEventDeclaration(leadingAnnotations, isAsync);
        }

        // static modifier
        boolean isStatic = false;
        if (context.check(TokenType.KW_STATIC)) {
            context.advance();
            isStatic = true;
            context.skipTrivia();
        }

        // const modifier (#)
        boolean isConst = false;
        if (context.check(TokenType.HASH)) {
            context.advance();
            isConst = true;
            context.skipTrivia();
        }

        // At this point, we should have a Type name pattern (field or method)
        // Parse the type
        Token typeToken = context.peek();
        if (!typeToken.is(TokenType.IDENTIFIER) && !typeToken.is(TokenType.KW_VOID)) {
            if (isAsync || isStatic || isConst || isPrivate || isPublic || !leadingAnnotations.isEmpty()) {
                context.error(DiagnosticCode.PARSER_UNEXPECTED_TOKEN,
                        "Expected type name in declaration but found: " + typeToken.type() + " '" + typeToken.text() + "'", typeToken);
            } else {
                context.error(DiagnosticCode.PARSER_UNEXPECTED_TOKEN,
                        "Unexpected token in script body: " + typeToken.type() + " '" + typeToken.text() + "'", typeToken);
            }
            context.advance();
            return null;
        }

        TypeNode type = parseTypeNode();
        context.skipTrivia();

        // Now we need the name
        Token nameToken = context.expect(TokenType.IDENTIFIER, "Expected declaration name");
        String name = nameToken.text();
        context.skipTrivia();

        // Check if this is a method (followed by '(') or a field
        if (context.check(TokenType.LPAREN)) {
            // Method declaration
            return parseMethodDeclarationRest(leadingAnnotations, isPrivate, isAsync, type, name, nameToken);
        } else {
            // Field declaration
            return parseFieldDeclarationRest(leadingAnnotations, isPrivate, isStatic, isConst, type, name, nameToken);
        }
    }

    private StatementNode parseMethodDeclarationRest(List<AnnotationNode> annotations, boolean isPrivate,
                                                      boolean isAsync, TypeNode returnType, String name, Token nameToken) {
        for (AnnotationNode annotation : annotations) {
            context.error(DiagnosticCode.SEMANTIC_UNKNOWN_ANNOTATION, "Annotation @" + annotation.name() + " is not supported on functions", nameToken);
        }
        context.expect(TokenType.LPAREN, "Expected '(' after method name");
        List<ParameterNode> parameters = new ArrayList<>();
        context.skipTrivia();
        if (!context.check(TokenType.RPAREN)) {
            parameters.add(parseParameter());
            while (context.match(TokenType.COMMA)) {
                parameters.add(parseParameter());
            }
        }
        context.expect(TokenType.RPAREN, "Expected ')' after parameters");
        BlockNode body = parseBlock();
        return new FunctionNode(context.filePath(), nameToken.line(), nameToken.column(),
                name, parameters, returnType, isAsync, isPrivate, body);
    }

    private StatementNode parseFieldDeclarationRest(List<AnnotationNode> annotations, boolean isPrivate,
                                                     boolean isStatic, boolean isConst, TypeNode type,
                                                     String name, Token nameToken) {
        ExpressionNode initializer = null;
        context.skipTrivia();
        if (context.match(TokenType.EQ)) {
            context.skipTrivia();
            initializer = exprParser.parseExpression();
        }

        boolean persistent = false;
        String persistentId = null;
        for (AnnotationNode ann : annotations) {
            if (!ann.name().equals("Persistent")) {
                context.error(DiagnosticCode.SEMANTIC_UNKNOWN_ANNOTATION, "Annotation @" + ann.name() + " is not supported on fields", nameToken);
                continue;
            }
            persistent = true;
            if (ann.positionalArgs().size() > 1 || !ann.namedArgs().isEmpty()) {
                context.error(DiagnosticCode.SEMANTIC_INVALID_ARGUMENT, "@Persistent accepts at most one positional String id", nameToken);
            }
            if (!ann.positionalArgs().isEmpty()) {
                Object id = ann.positionalArg(0);
                if (id instanceof String stringId) persistentId = stringId;
                else context.error(DiagnosticCode.SEMANTIC_INVALID_ARGUMENT, "@Persistent id must be String", nameToken);
            }
        }

        return new PropertyDeclarationNode(context.filePath(), nameToken.line(), nameToken.column(),
                !isConst, isPrivate, isStatic, isConst, name, type, initializer, annotations, persistent, persistentId);
    }

    private StatementNode parseEntryDeclaration(List<AnnotationNode> annotations, boolean isAsync) {
        Token entryToken = context.expect(TokenType.KW_ENTRY);
        for (AnnotationNode annotation : annotations) {
            context.error(DiagnosticCode.SEMANTIC_UNKNOWN_ANNOTATION, "Annotation @" + annotation.name() + " is not supported on lifecycle entries", entryToken);
        }
        Token nameToken = context.expect(TokenType.IDENTIFIER, "Expected entry name");
        String entryName = nameToken.text();

        // Validate entry name
        if (!ENTRY_NAMES.contains(entryName)) {
            context.error(DiagnosticCode.PARSER_UNEXPECTED_TOKEN,
                    "Unknown entry point: " + entryName + ". Valid entries: onLoad, onEnable, onRun, onDisable, onUnload, onTick", nameToken);
        }

        context.expect(TokenType.LPAREN, "Expected '(' after entry name");
        List<ParameterNode> parameters = new ArrayList<>();
        context.skipTrivia();
        if (!context.check(TokenType.RPAREN)) {
            parameters.add(parseParameter());
            while (context.match(TokenType.COMMA)) {
                parameters.add(parseParameter());
            }
        }
        context.expect(TokenType.RPAREN, "Expected ')' after entry parameters");
        if (!parameters.isEmpty()) context.error(DiagnosticCode.PARSER_UNEXPECTED_TOKEN, "Lifecycle entries do not accept parameters", nameToken);
        BlockNode body = parseBlock();

        // Map entry name to lifecycle hook
        LifecycleNode.Hook hook = switch (entryName) {
            case "onLoad" -> LifecycleNode.Hook.ON_LOAD;
            case "onEnable" -> LifecycleNode.Hook.ON_ENABLE;
            case "onRun" -> LifecycleNode.Hook.ON_RUN;
            case "onDisable" -> LifecycleNode.Hook.ON_DISABLE;
            case "onUnload" -> LifecycleNode.Hook.ON_UNLOAD;
            case "onTick" -> LifecycleNode.Hook.ON_TICK;
            default -> LifecycleNode.Hook.ON_RUN;
        };

        return new LifecycleNode(context.filePath(), entryToken.line(), entryToken.column(), hook, isAsync, body);
    }

    private StatementNode parseEventDeclaration(List<AnnotationNode> annotations, boolean isAsync) {
        Token eventToken = context.expect(TokenType.KW_EVENT);
        Token nameToken = context.expect(TokenType.IDENTIFIER, "Expected event handler name");
        String handlerName = nameToken.text();

        context.expect(TokenType.LPAREN, "Expected '(' after event name");
        context.skipTrivia();
        TypeNode paramType = null;
        String paramName = null;
        if (!context.check(TokenType.RPAREN)) {
            paramType = parseTypeNode();
            context.skipTrivia();
            Token paramNameToken = context.expect(TokenType.IDENTIFIER, "Expected event parameter name");
            paramName = paramNameToken.text();
        }
        context.expect(TokenType.RPAREN, "Expected ')' after event parameter");
        BlockNode body = parseBlock();

        String eventRef = handlerName;
        boolean eventAnnotation = false;
        for (AnnotationNode ann : annotations) {
            String annName = ann.name();
            if (!annName.equals("Event") && !annName.startsWith("Event.")) {
                context.error(DiagnosticCode.SEMANTIC_UNKNOWN_ANNOTATION, "Annotation @" + annName + " is not supported on event handlers", eventToken);
                continue;
            }
            if (eventAnnotation) {
                context.error(DiagnosticCode.SEMANTIC_INVALID_ARGUMENT, "Only one event annotation is allowed", eventToken);
                continue;
            }
            eventAnnotation = true;
            if (annName.startsWith("Event.")) {
                if (!ann.positionalArgs().isEmpty() || !ann.namedArgs().isEmpty()) context.error(DiagnosticCode.SEMANTIC_INVALID_ARGUMENT, "@" + annName + " does not accept arguments", eventToken);
                eventRef = annName;
            } else {
                if (ann.positionalArgs().size() != 1 || !(ann.positionalArg(0) instanceof String) || !ann.namedArgs().isEmpty()) {
                    context.error(DiagnosticCode.SEMANTIC_INVALID_ARGUMENT, "@Event requires exactly one positional String event name", eventToken);
                } else eventRef = (String) ann.positionalArg(0);
            }
        }

        return new EventHandlerNode(context.filePath(), eventToken.line(), eventToken.column(),
                eventRef, annotations, paramName, paramType, isAsync, body);
    }

    private SettingBlockNode parseSettingBlock() {
        Token settingToken = context.expect(TokenType.KW_SETTINGS);
        context.expect(TokenType.LBRACE, "Expected '{' after 'settings'");
        List<SettingDeclarationNode> declarations = new ArrayList<>();
        context.skipTrivia();
        while (!context.check(TokenType.RBRACE) && context.tokens().hasMore()) {
            if (context.check(TokenType.ANNOTATION)) {
                declarations.add(parseSettingDeclaration());
            } else {
                context.skipTrivia();
                if (context.check(TokenType.RBRACE)) break;
                Token token = context.peek();
                context.error(DiagnosticCode.PARSER_INVALID_SETTING_DECL, "Expected a setting annotation, got '" + token.text() + "'", token);
                context.advance();
            }
            context.skipTrivia();
        }
        context.expect(TokenType.RBRACE, "Expected '}' after settings block");
        return new SettingBlockNode(context.filePath(), settingToken.line(), settingToken.column(), declarations);
    }

    private SettingDeclarationNode parseSettingDeclaration() {
        Token annotationToken = context.advance();
        String annotationName = annotationToken.text().substring(1); // strip @

        // Check for old @Slider syntax
        if (annotationName.equals("Slider")) {
            context.error(DiagnosticCode.PARSER_UNEXPECTED_TOKEN,
                    "Migration: @Slider is removed in V2. Use @Number with range syntax: @Number id (\"name\", min..max, step, default, @Number.Slider)",
                    annotationToken);
        }

        context.skipTrivia();
        Token identifierToken = context.expect(TokenType.IDENTIFIER, "Expected setting identifier");
        String identifier = identifierToken.text();

        context.skipTrivia();
        context.expect(TokenType.LPAREN, "Expected '(' after setting identifier");

        List<Object> positionalArgs = new ArrayList<>();
        Map<String, Object> namedArgs = new LinkedHashMap<>();

        context.skipTrivia();
        if (!context.check(TokenType.RPAREN)) {
            parseSettingArguments(positionalArgs, namedArgs);
        }

        context.expect(TokenType.RPAREN, "Expected ')' after setting declaration");

        return new SettingDeclarationNode(context.filePath(), annotationToken.line(), annotationToken.column(),
                annotationName, identifier, positionalArgs, namedArgs);
    }

    private void parseSettingArguments(List<Object> positionalArgs, Map<String, Object> namedArgs) {
        boolean first = true;
        while (!context.check(TokenType.RPAREN) && context.tokens().hasMore()) {
            if (!first) {
                if (!context.match(TokenType.COMMA)) break;
                context.skipTrivia();
            }
            first = false;

            // Named argument
            if (context.check(TokenType.IDENTIFIER) && peekAhead(1).is(TokenType.EQ)) {
                Token nameToken = context.advance();
                context.advance(); // EQ
                context.skipTrivia();
                boolean explicitNull = isExplicitNull();
                Object value = parseAnnotationValue();
                if (namedArgs.containsKey(nameToken.text())) context.error(DiagnosticCode.PARSER_INVALID_SETTING_DECL, "Duplicate named setting argument: " + nameToken.text(), nameToken);
                else if (value != null || explicitNull) namedArgs.put(nameToken.text(), value);
            } else {
                boolean explicitNull = isExplicitNull();
                Object value = parseAnnotationValue();
                if (value instanceof RangeValue rv) {
                    positionalArgs.add(rv.min());
                    positionalArgs.add(rv.max());
                } else if (value != null || explicitNull) {
                    positionalArgs.add(value);
                }
            }
            context.skipTrivia();
        }
    }

    private ParameterNode parseParameter() {
        context.skipTrivia();
        // V2: Type name [= default]
        TypeNode type = parseTypeNode();
        context.skipTrivia();
        Token nameToken = context.expect(TokenType.IDENTIFIER, "Expected parameter name");
        boolean hasDefault = false;
        ExpressionNode defaultValue = null;
        context.skipTrivia();
        if (context.match(TokenType.EQ)) {
            hasDefault = true;
            defaultValue = exprParser.parseExpression();
        }
        return new ParameterNode(context.filePath(), nameToken.line(), nameToken.column(),
                nameToken.text(), type, hasDefault, defaultValue);
    }

    private BlockNode parseBlock() {
        context.skipTrivia();
        Token lbrace = context.expect(TokenType.LBRACE, "Expected '{'");
        List<StatementNode> statements = new ArrayList<>();
        context.skipTrivia();
        while (!context.check(TokenType.RBRACE) && context.tokens().hasMore()) {
            StatementNode stmt = parseStatement();
            if (stmt != null) {
                statements.add(stmt);
            }
            context.skipTrivia();
        }
        context.expect(TokenType.RBRACE, "Expected '}'");
        return new BlockNode(context.filePath(), lbrace.line(), lbrace.column(), statements);
    }

    private boolean isLocalDeclarationStart() {
        context.skipTrivia();
        Token token = context.peek();

        // # const local
        if (token.is(TokenType.HASH)) return true;

        if (!token.is(TokenType.IDENTIFIER)) return false;

        // "await" is a keyword-like identifier, not a type
        if (token.text().equals("await")) return false;

        Token next = peekAhead(1);
        if (next.is(TokenType.IDENTIFIER)) return true;
        if (next.is(TokenType.QUESTION)) return peekAhead(2).is(TokenType.IDENTIFIER);
        if (!next.is(TokenType.LT)) return false;

        int depth = 0;
        for (int offset = 1; ; offset++) {
            Token current = peekAhead(offset);
            if (current.is(TokenType.EOF)) return false;
            if (current.is(TokenType.LT)) depth++;
            else if (current.is(TokenType.GT)) {
                depth--;
                if (depth == 0) {
                    Token after = peekAhead(offset + 1);
                    if (after.is(TokenType.QUESTION)) after = peekAhead(offset + 2);
                    return after.is(TokenType.IDENTIFIER);
                }
            }
        }
    }

    private StatementNode parseStatement() {
        context.skipTrivia();
        Token token = context.peek();

        if (token.is(TokenType.KW_IF)) {
            return parseIfStatement();
        }
        if (token.is(TokenType.KW_WHILE)) {
            return parseWhileStatement();
        }
        if (token.is(TokenType.KW_FOR)) {
            return parseForStatement();
        }
        if (token.is(TokenType.KW_WHEN)) {
            return parseWhenStatement();
        }
        if (token.is(TokenType.KW_RETURN)) {
            return parseReturnStatement();
        }
        if (token.is(TokenType.KW_SPAWN)) {
            return parseSpawnStatement();
        }

        // Check for old keywords in statement position
        if (token.is(TokenType.IDENTIFIER) && OLD_KEYWORDS.contains(token.text())) {
            String kw = token.text();
            String suggestion = switch (kw) {
                case "val" -> "Type name = value";
                case "var" -> "Type name = value";
                default -> kw;
            };
            migrationError(kw, suggestion, token);
            context.advance();
            return null;
        }

        // Check for local variable declaration: #Type name or Type name
        if (isLocalDeclarationStart()) {
            return parseLocalVariable();
        }

        ExpressionNode expr = exprParser.parseExpression();
        return new ExpressionStatementNode(context.filePath(), token.line(), token.column(), expr);
    }

    private StatementNode parseLocalVariable() {
        context.skipTrivia();
        Token startToken = context.peek();

        boolean isConst = false;
        if (context.check(TokenType.HASH)) {
            context.advance();
            isConst = true;
            context.skipTrivia();
        }

        TypeNode type = parseTypeNode();
        context.skipTrivia();
        Token nameToken = context.expect(TokenType.IDENTIFIER, "Expected variable name");
        ExpressionNode initializer = null;
        context.skipTrivia();
        if (context.match(TokenType.EQ)) {
            context.skipTrivia();
            initializer = exprParser.parseExpression();
        }
        return new VariableDeclarationNode(context.filePath(), startToken.line(), startToken.column(),
                isConst, nameToken.text(), type, initializer);
    }

    private StatementNode parseIfStatement() {
        Token ifToken = context.expect(TokenType.KW_IF);
        context.expect(TokenType.LPAREN, "Expected '(' after 'if'");
        ExpressionNode condition = exprParser.parseExpression();
        context.expect(TokenType.RPAREN, "Expected ')' after if condition");
        BlockNode thenBlock = parseBlock();
        BlockNode elseBlock = null;
        context.skipTrivia();
        if (context.check(TokenType.KW_ELSE)) {
            context.advance();
            context.skipTrivia();
            if (context.check(TokenType.KW_IF)) {
                StatementNode elseIf = parseIfStatement();
                elseBlock = new BlockNode(context.filePath(), elseIf.line(), elseIf.column(), List.of(elseIf));
            } else {
                elseBlock = parseBlock();
            }
        }
        return new IfStatementNode(context.filePath(), ifToken.line(), ifToken.column(), condition, thenBlock, elseBlock);
    }

    private StatementNode parseWhileStatement() {
        Token whileToken = context.expect(TokenType.KW_WHILE);
        context.expect(TokenType.LPAREN, "Expected '(' after 'while'");
        ExpressionNode condition = exprParser.parseExpression();
        context.expect(TokenType.RPAREN, "Expected ')' after while condition");
        BlockNode body = parseBlock();
        return new WhileStatementNode(context.filePath(), whileToken.line(), whileToken.column(), condition, body);
    }

    private StatementNode parseForStatement() {
        Token forToken = context.expect(TokenType.KW_FOR);
        context.expect(TokenType.LPAREN, "Expected '(' after 'for'");
        // V2: for (Type var in iterable)
        TypeNode type = parseTypeNode();
        context.skipTrivia();
        Token varToken = context.expect(TokenType.IDENTIFIER, "Expected loop variable");
        context.expect(TokenType.KW_IN, "Expected 'in' in for loop");
        ExpressionNode iterable = exprParser.parseExpression();
        context.expect(TokenType.RPAREN, "Expected ')' after for iterable");
        BlockNode body = parseBlock();
        return new ForStatementNode(context.filePath(), forToken.line(), forToken.column(),
                type, varToken.text(), iterable, body);
    }

    private StatementNode parseWhenStatement() {
        Token whenToken = context.expect(TokenType.KW_WHEN);
        context.expect(TokenType.LPAREN, "Expected '(' after 'when'");
        ExpressionNode subject = exprParser.parseExpression();
        context.expect(TokenType.RPAREN, "Expected ')' after when subject");
        context.expect(TokenType.LBRACE, "Expected '{' after when subject");
        List<WhenStatementNode.Case> cases = new ArrayList<>();
        BlockNode elseBody = null;
        context.skipTrivia();
        while (!context.check(TokenType.RBRACE) && context.tokens().hasMore()) {
            if (context.check(TokenType.KW_ELSE)) {
                context.advance();
                context.expect(TokenType.ARROW, "Expected '->' after else");
                elseBody = parseBlock();
                context.skipTrivia();
                continue;
            }
            List<ExpressionNode> conditions = new ArrayList<>();
            conditions.add(exprParser.parseExpression());
            while (context.match(TokenType.COMMA)) {
                conditions.add(exprParser.parseExpression());
            }
            context.expect(TokenType.ARROW, "Expected '->' in when case");
            BlockNode caseBody = parseBlock();
            cases.add(new WhenStatementNode.Case(conditions, caseBody));
            context.skipTrivia();
        }
        context.expect(TokenType.RBRACE, "Expected '}' after when block");
        return new WhenStatementNode(context.filePath(), whenToken.line(), whenToken.column(), subject, cases, elseBody);
    }

    private StatementNode parseReturnStatement() {
        Token returnToken = context.expect(TokenType.KW_RETURN);
        ExpressionNode value = null;
        context.skipTrivia();
        if (!context.check(TokenType.RBRACE) && !context.check(TokenType.EOF) && !context.check(TokenType.SEMICOLON) && !context.check(TokenType.NEWLINE)) {
            value = exprParser.parseExpression();
        }
        return new ReturnStatementNode(context.filePath(), returnToken.line(), returnToken.column(), value);
    }

    private StatementNode parseSpawnStatement() {
        Token spawnToken = context.peek();
        ExpressionNode expr = exprParser.parseExpression();
        return new ExpressionStatementNode(context.filePath(), spawnToken.line(), spawnToken.column(), expr);
    }

    private TypeNode parseTypeNode() {
        context.skipTrivia();
        Token nameToken;
        if (context.check(TokenType.KW_VOID)) {
            nameToken = context.advance();
        } else {
            nameToken = context.expect(TokenType.IDENTIFIER, "Expected type name");
        }
        String typeName = nameToken.text();
        List<TypeNode> typeArgs = new ArrayList<>();
        context.skipTrivia();
        if (context.match(TokenType.LT)) {
            typeArgs.add(parseTypeNode());
            while (context.match(TokenType.COMMA)) {
                typeArgs.add(parseTypeNode());
            }
            context.skipTrivia();
            context.expect(TokenType.GT, "Expected '>' after type arguments");
        }
        boolean nullable = false;
        context.skipTrivia();
        if (context.match(TokenType.QUESTION)) {
            nullable = true;
        }
        return new TypeNode(context.filePath(), nameToken.line(), nameToken.column(), typeName, nullable, typeArgs);
    }
}
