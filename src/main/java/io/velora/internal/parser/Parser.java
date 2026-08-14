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

    private final ParserContext context;
    private final ExpressionParser exprParser;

    public Parser(TokenStream tokens, String filePath) {
        this.context = new ParserContext(tokens, filePath);
        this.exprParser = new ExpressionParser(context);
    }

    public ParseResult parse() {
        try {
            List<AnnotationNode> annotations = parseAnnotations();
            ScriptNode script = parseScript(annotations);
            if (script == null) return ParseResult.failure(context.diagnostics());
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

    private ScriptNode parseScript(List<AnnotationNode> annotations) {
        context.skipTrivia();
        if (!context.check(TokenType.KW_SCRIPT)) {
            context.error(DiagnosticCode.PARSER_MISSING_TOKEN, "Expected 'script' keyword", context.currentLine(), context.currentColumn());
            return null;
        }

        Token scriptToken = context.advance();
        Token nameToken = context.expect(TokenType.IDENTIFIER, "Expected script name");
        String scriptName = nameToken.text();
        BlockNode body = parseScriptBody();

        List<SettingDeclarationNode> settings = new ArrayList<>();
        List<ScriptMemberNode> members = new ArrayList<>();
        if (body != null) {
            for (StatementNode statement : body.statements()) {
                if (statement instanceof SettingBlockNode block) settings.addAll(block.declarations());
                else if (statement instanceof ScriptMemberNode member) members.add(member);
            }
        }

        SettingBlockNode settingBlock = settings.isEmpty() ? null
                : new SettingBlockNode(context.filePath(), scriptToken.line(), scriptToken.column(), settings);
        return new ScriptNode(context.filePath(), scriptToken.line(), scriptToken.column(),
                annotations, scriptName, settingBlock, members);
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
        List<AnnotationNode> annotations = new ArrayList<>();
        while (context.check(TokenType.ANNOTATION)) {
            annotations.add(parseAnnotation());
            context.skipTrivia();
        }

        boolean isAsync = context.match(TokenType.KW_ASYNC);
        context.skipTrivia();
        boolean isStatic = context.match(TokenType.KW_STATIC);
        context.skipTrivia();
        boolean isConst = context.match(TokenType.HASH);
        context.skipTrivia();

        if (context.check(TokenType.IDENTIFIER) && Set.of("settings", "entry", "event", "void", "private", "public").contains(context.peek().text())) {
            Token old = context.advance();
            context.error(DiagnosticCode.PARSER_UNEXPECTED_TOKEN, "Removed Velora syntax: '" + old.text() + "'", old);
            skipBrokenMember();
            return null;
        }

        AnnotationNode setting = null;
        for (AnnotationNode annotation : annotations) {
            if (annotation.name().equals("Setting")) {
                if (setting != null) context.error(DiagnosticCode.SEMANTIC_INVALID_ARGUMENT, "Duplicate @Setting annotation", context.peek());
                setting = annotation;
            }
        }

        if (setting != null) {
            if (isAsync || isStatic || isConst) context.error(DiagnosticCode.PARSER_UNEXPECTED_TOKEN, "@Setting cannot be async, static or const", context.peek());
            for (AnnotationNode annotation : annotations) {
                if (annotation != setting) context.error(DiagnosticCode.SEMANTIC_UNKNOWN_ANNOTATION, "@" + annotation.name() + " cannot be combined with @Setting", context.peek());
            }
            return parseInlineSetting(setting);
        }

        Token first = context.peek();
        if (!first.is(TokenType.IDENTIFIER)) {
            context.error(DiagnosticCode.PARSER_UNEXPECTED_TOKEN,
                    "Expected field or function declaration, found " + first.type() + " '" + first.text() + "'", first);
            context.advance();
            return null;
        }

        Token second = peekAhead(1);
        if (second.is(TokenType.LPAREN)) {
            Token name = context.advance();
            return parseMethodDeclarationRest(annotations, isAsync, null, name.text(), name);
        }
        if (second.is(TokenType.EQ)) {
            Token name = context.advance();
            return parseFieldDeclarationRest(annotations, isStatic, isConst, null, name.text(), name);
        }

        TypeNode type = parseTypeNode();
        context.skipTrivia();
        Token name = context.expect(TokenType.IDENTIFIER, "Expected declaration name");
        context.skipTrivia();
        if (context.check(TokenType.LPAREN)) {
            if (isStatic || isConst) context.error(DiagnosticCode.PARSER_UNEXPECTED_TOKEN, "Functions cannot be static or const", name);
            return parseMethodDeclarationRest(annotations, isAsync, type, name.text(), name);
        }
        return parseFieldDeclarationRest(annotations, isStatic, isConst, type, name.text(), name);
    }

    private StatementNode parseMethodDeclarationRest(List<AnnotationNode> annotations, boolean isAsync,
                                                      TypeNode returnType, String name, Token nameToken) {
        context.expect(TokenType.LPAREN, "Expected '(' after function name");
        List<ParameterNode> parameters = new ArrayList<>();
        context.skipTrivia();
        if (!context.check(TokenType.RPAREN)) {
            parameters.add(parseParameter());
            while (context.match(TokenType.COMMA)) parameters.add(parseParameter());
        }
        context.expect(TokenType.RPAREN, "Expected ')' after parameters");
        BlockNode body = parseBlock();
        return new FunctionNode(context.filePath(), nameToken.line(), nameToken.column(),
                name, parameters, returnType, isAsync, body, annotations);
    }

    private StatementNode parseFieldDeclarationRest(List<AnnotationNode> annotations, boolean isStatic,
                                                     boolean isConst, TypeNode type, String name, Token nameToken) {
        ExpressionNode initializer = null;
        context.skipTrivia();
        if (context.match(TokenType.EQ)) initializer = exprParser.parseExpression();
        if (initializer == null) context.error(DiagnosticCode.SEMANTIC_MISSING_INITIALIZER, "Field '" + name + "' requires an initializer", nameToken);

        boolean persistent = false;
        String persistentId = null;
        for (AnnotationNode annotation : annotations) {
            if (!annotation.name().equals("Persistent")) {
                context.error(DiagnosticCode.SEMANTIC_UNKNOWN_ANNOTATION, "Annotation @" + annotation.name() + " is not supported on fields", nameToken);
                continue;
            }
            persistent = true;
            if (annotation.positionalArgs().size() > 1 || !annotation.namedArgs().isEmpty()) {
                context.error(DiagnosticCode.SEMANTIC_INVALID_ARGUMENT, "@Persistent accepts at most one positional String id", nameToken);
            }
            if (!annotation.positionalArgs().isEmpty()) {
                Object value = annotation.positionalArg(0);
                if (value instanceof String id) persistentId = id;
                else context.error(DiagnosticCode.SEMANTIC_INVALID_ARGUMENT, "@Persistent id must be String", nameToken);
            }
        }
        return new PropertyDeclarationNode(context.filePath(), nameToken.line(), nameToken.column(),
                !isConst, isStatic, isConst, name, type, initializer, annotations, persistent, persistentId);
    }

    private SettingBlockNode parseInlineSetting(AnnotationNode annotation) {
        context.skipTrivia();
        TypeNode type = null;
        Token name;
        Token first = context.peek();
        Token second = peekAhead(1);
        if (first.is(TokenType.IDENTIFIER) && second.is(TokenType.EQ)) {
            name = context.advance();
        } else {
            type = parseTypeNode();
            context.skipTrivia();
            name = context.expect(TokenType.IDENTIFIER, "Expected setting name");
        }
        context.expect(TokenType.EQ, "Expected '=' after setting name");
        ExpressionNode initializer = exprParser.parseExpression();
        SettingDeclarationNode declaration = new SettingDeclarationNode(context.filePath(), annotation.line(), annotation.column(),
                name.text(), type, initializer, annotation.positionalArgs(), annotation.namedArgs());
        return new SettingBlockNode(context.filePath(), annotation.line(), annotation.column(), List.of(declaration));
    }

    private void skipBrokenMember() {
        int braces = 0;
        while (!context.check(TokenType.EOF)) {
            Token token = context.peek();
            if (token.is(TokenType.LBRACE)) braces++;
            else if (token.is(TokenType.RBRACE)) {
                if (braces == 0) return;
                braces--;
                if (braces == 0) {
                    context.advance();
                    return;
                }
            }
            context.advance();
            if (braces == 0 && token.is(TokenType.NEWLINE)) return;
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
        context.skipTrivia();
        TypeNode type = null;
        Token variable;
        if (context.check(TokenType.IDENTIFIER) && peekAhead(1).is(TokenType.KW_IN)) {
            variable = context.advance();
        } else {
            type = parseTypeNode();
            context.skipTrivia();
            variable = context.expect(TokenType.IDENTIFIER, "Expected loop variable");
        }
        context.expect(TokenType.KW_IN, "Expected 'in' in for loop");
        ExpressionNode iterable = exprParser.parseExpression();
        context.expect(TokenType.RPAREN, "Expected ')' after for iterable");
        BlockNode body = parseBlock();
        return new ForStatementNode(context.filePath(), forToken.line(), forToken.column(), type, variable.text(), iterable, body);
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
        Token nameToken = context.expect(TokenType.IDENTIFIER, "Expected type name");
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
