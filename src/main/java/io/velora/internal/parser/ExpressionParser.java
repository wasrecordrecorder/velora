package io.velora.internal.parser;

import io.velora.internal.ast.*;
import io.velora.internal.lexer.Lexer;
import io.velora.internal.lexer.Token;
import io.velora.internal.lexer.TokenStream;
import io.velora.internal.lexer.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ExpressionParser {

    private static final Set<String> DURATION_UNITS = Set.of(
            "millis", "milliseconds", "ms",
            "seconds", "second", "sec", "s",
            "minutes", "minute", "min",
            "hours", "hour", "h",
            "days", "day"
    );

    private final ParserContext context;

    public ExpressionParser(ParserContext context) {
        this.context = context;
    }

    public ExpressionNode parseExpression() {
        return parseAssignment();
    }

    private ExpressionNode parseAssignment() {
        ExpressionNode left = parseElvis();
        if (context.check(TokenType.EQ)) {
            Token op = context.advance();
            ExpressionNode value = parseAssignment();
            return new AssignmentExpressionNode(context.filePath(), op.line(), op.column(), left, "=", value);
        }
        if (context.check(TokenType.PLUS_EQ)) {
            Token op = context.advance();
            ExpressionNode value = parseAssignment();
            return new AssignmentExpressionNode(context.filePath(), op.line(), op.column(), left, "+=", value);
        }
        if (context.check(TokenType.MINUS_EQ)) {
            Token op = context.advance();
            ExpressionNode value = parseAssignment();
            return new AssignmentExpressionNode(context.filePath(), op.line(), op.column(), left, "-=", value);
        }
        if (context.check(TokenType.STAR_EQ)) {
            Token op = context.advance();
            ExpressionNode value = parseAssignment();
            return new AssignmentExpressionNode(context.filePath(), op.line(), op.column(), left, "*=", value);
        }
        if (context.check(TokenType.SLASH_EQ)) {
            Token op = context.advance();
            ExpressionNode value = parseAssignment();
            return new AssignmentExpressionNode(context.filePath(), op.line(), op.column(), left, "/=", value);
        }
        if (context.check(TokenType.PERCENT_EQ)) {
            Token op = context.advance();
            ExpressionNode value = parseAssignment();
            return new AssignmentExpressionNode(context.filePath(), op.line(), op.column(), left, "%=", value);
        }
        // postfix ++ / --
        if (context.check(TokenType.INCREMENT)) {
            Token op = context.advance();
            return new AssignmentExpressionNode(context.filePath(), op.line(), op.column(), left, "++", null);
        }
        if (context.check(TokenType.DECREMENT)) {
            Token op = context.advance();
            return new AssignmentExpressionNode(context.filePath(), op.line(), op.column(), left, "--", null);
        }
        return left;
    }

    private ExpressionNode parseElvis() {
        ExpressionNode left = parseOr();
        while (context.check(TokenType.QUESTION) && context.tokens().peek(1).is(TokenType.COLON)) {
            Token q = context.advance();
            context.advance(); // colon
            ExpressionNode right = parseOr();
            left = new ElvisExpressionNode(context.filePath(), q.line(), q.column(), left, right);
        }
        return left;
    }

    private ExpressionNode parseOr() {
        ExpressionNode left = parseAnd();
        while (context.check(TokenType.OR_OR)) {
            Token op = context.advance();
            ExpressionNode right = parseAnd();
            left = new BinaryExpressionNode(context.filePath(), op.line(), op.column(), left, "||", right);
        }
        return left;
    }

    private ExpressionNode parseAnd() {
        ExpressionNode left = parseEquality();
        while (context.check(TokenType.AND_AND)) {
            Token op = context.advance();
            ExpressionNode right = parseEquality();
            left = new BinaryExpressionNode(context.filePath(), op.line(), op.column(), left, "&&", right);
        }
        return left;
    }

    private ExpressionNode parseEquality() {
        ExpressionNode left = parseComparison();
        while (context.check(TokenType.EQ_EQ) || context.check(TokenType.BANG_EQ)) {
            Token op = context.advance();
            ExpressionNode right = parseComparison();
            left = new BinaryExpressionNode(context.filePath(), op.line(), op.column(), left, op.text(), right);
        }
        return left;
    }

    private ExpressionNode parseComparison() {
        ExpressionNode left = parseIs();
        while (context.check(TokenType.LT) || context.check(TokenType.LE) || context.check(TokenType.GT) || context.check(TokenType.GE)) {
            Token op = context.advance();
            ExpressionNode right = parseIs();
            left = new BinaryExpressionNode(context.filePath(), op.line(), op.column(), left, op.text(), right);
        }
        return left;
    }

    private ExpressionNode parseIs() {
        ExpressionNode left = parseAdditive();
        if (context.check(TokenType.KW_IS)) {
            Token isToken = context.advance();
            TypeNode type = parseTypeReference();
            return new IsExpressionNode(context.filePath(), isToken.line(), isToken.column(), left, type);
        }
        return left;
    }

    private ExpressionNode parseAdditive() {
        ExpressionNode left = parseMultiplicative();
        while (context.check(TokenType.PLUS) || context.check(TokenType.MINUS)) {
            Token op = context.advance();
            ExpressionNode right = parseMultiplicative();
            left = new BinaryExpressionNode(context.filePath(), op.line(), op.column(), left, op.text(), right);
        }
        return left;
    }

    private ExpressionNode parseMultiplicative() {
        ExpressionNode left = parseUnary();
        while (context.check(TokenType.STAR) || context.check(TokenType.SLASH) || context.check(TokenType.PERCENT)) {
            Token op = context.advance();
            ExpressionNode right = parseUnary();
            left = new BinaryExpressionNode(context.filePath(), op.line(), op.column(), left, op.text(), right);
        }
        return left;
    }

    private ExpressionNode parseUnary() {
        if (context.check(TokenType.KW_SPAWN)) {
            Token spawnToken = context.advance();
            ExpressionNode expr = parsePostfix();
            if (expr instanceof CallExpressionNode call) {
                return new SpawnExpressionNode(context.filePath(), spawnToken.line(), spawnToken.column(),
                        call.callee(), call.arguments());
            }
            return new SpawnExpressionNode(context.filePath(), spawnToken.line(), spawnToken.column(), expr, List.of());
        }
        if (context.check(TokenType.IDENTIFIER) && context.peek().text().equals("await")) {
            Token awaitToken = context.advance();
            ExpressionNode expr = parseUnary();
            return new CallExpressionNode(context.filePath(), awaitToken.line(), awaitToken.column(),
                    new IdentifierExpressionNode(context.filePath(), awaitToken.line(), awaitToken.column(), "await"),
                    List.of(expr));
        }
        if (context.check(TokenType.BANG) || context.check(TokenType.MINUS)) {
            Token op = context.advance();
            ExpressionNode operand = parseUnary();
            return new UnaryExpressionNode(context.filePath(), op.line(), op.column(), op.text(), operand, true);
        }
        return parsePostfix();
    }

    private ExpressionNode parsePostfix() {
        ExpressionNode expr = parsePrimary();
        while (true) {
            if (context.check(TokenType.DOT)) {
                Token dot = context.advance();
                Token member = context.expect(TokenType.IDENTIFIER, "Expected member name after '.'");
                // Duration literal: numeric value followed by a duration unit
                if (expr instanceof LiteralExpressionNode lit && isNumericLiteral(lit) && DURATION_UNITS.contains(member.text())) {
                    expr = new DurationExpressionNode(context.filePath(), dot.line(), dot.column(), expr, member.text());
                } else {
                    expr = new MemberAccessExpressionNode(context.filePath(), dot.line(), dot.column(), expr, member.text(), false);
                }
            } else if (context.check(TokenType.QUESTION) && context.tokens().peek(1).is(TokenType.DOT)) {
                Token q = context.advance();
                context.advance();
                Token member = context.expect(TokenType.IDENTIFIER, "Expected member name after '?.'");
                expr = new MemberAccessExpressionNode(context.filePath(), q.line(), q.column(), expr, member.text(), true);
            } else if (context.check(TokenType.LPAREN)) {
                Token lparen = context.advance();
                List<ExpressionNode> args = new ArrayList<>();
                context.skipTrivia();
                if (!context.check(TokenType.RPAREN)) {
                    args.add(parseCallArgument());
                    while (context.match(TokenType.COMMA)) {
                        args.add(parseCallArgument());
                    }
                }
                context.expect(TokenType.RPAREN, "Expected ')' after arguments");
                expr = new CallExpressionNode(context.filePath(), lparen.line(), lparen.column(), expr, args);
            } else if (context.check(TokenType.LBRACKET)) {
                Token lbracket = context.advance();
                ExpressionNode index = parseExpression();
                context.expect(TokenType.RBRACKET, "Expected ']' after index");
                expr = new IndexExpressionNode(context.filePath(), lbracket.line(), lbracket.column(), expr, index);
            } else {
                break;
            }
        }
        return expr;
    }

    /** Parse a call argument, detecting named arguments (name = value). */
    private ExpressionNode parseCallArgument() {
        context.skipTrivia();
        // Check for named argument: IDENTIFIER = expr (but not == or +=)
        if (context.check(TokenType.IDENTIFIER)) {
            Token id = context.tokens().peek();
            Token next = context.tokens().peek(1);
            if (next.is(TokenType.EQ)) {
                // Named argument
                context.advance(); // identifier
                context.advance(); // =
                context.skipTrivia();
                ExpressionNode value = parseAssignment();
                return new NamedArgumentExpressionNode(context.filePath(), id.line(), id.column(), id.text(), value);
            }
        }
        return parseExpression();
    }

    private boolean isNumericLiteral(LiteralExpressionNode lit) {
        var k = lit.kind();
        return k == LiteralExpressionNode.LiteralKind.INTEGER
                || k == LiteralExpressionNode.LiteralKind.LONG
                || k == LiteralExpressionNode.LiteralKind.FLOAT
                || k == LiteralExpressionNode.LiteralKind.DOUBLE;
    }

    private ExpressionNode parsePrimary() {
        context.skipTrivia();
        Token token = context.peek();

        if (token.is(TokenType.INTEGER)) {
            context.advance();
            return new LiteralExpressionNode(context.filePath(), token.line(), token.column(),
                    Integer.parseInt(token.text()), LiteralExpressionNode.LiteralKind.INTEGER);
        }
        if (token.is(TokenType.LONG_LITERAL)) {
            context.advance();
            String text = token.text().replaceAll("[lL]$", "");
            return new LiteralExpressionNode(context.filePath(), token.line(), token.column(),
                    Long.parseLong(text), LiteralExpressionNode.LiteralKind.LONG);
        }
        if (token.is(TokenType.FLOAT_LITERAL)) {
            context.advance();
            String text = token.text().replaceAll("[fF]$", "");
            return new LiteralExpressionNode(context.filePath(), token.line(), token.column(),
                    Float.parseFloat(text), LiteralExpressionNode.LiteralKind.FLOAT);
        }
        if (token.is(TokenType.DOUBLE_LITERAL)) {
            context.advance();
            String text = token.text().replaceAll("[dD]$", "");
            return new LiteralExpressionNode(context.filePath(), token.line(), token.column(),
                    Double.parseDouble(text), LiteralExpressionNode.LiteralKind.DOUBLE);
        }
        if (token.is(TokenType.STRING)) {
            context.advance();
            String text = token.text();
            String unquoted = text.substring(1, text.length() - 1);
            return new LiteralExpressionNode(context.filePath(), token.line(), token.column(),
                    unquoted, LiteralExpressionNode.LiteralKind.STRING);
        }
        if (token.is(TokenType.STRING_INTERP)) {
            context.advance();
            return parseInterpolation(token);
        }
        if (token.is(TokenType.BOOLEAN) || token.is(TokenType.KW_TRUE) || token.is(TokenType.KW_FALSE)) {
            context.advance();
            return new LiteralExpressionNode(context.filePath(), token.line(), token.column(),
                    Boolean.parseBoolean(token.text()), LiteralExpressionNode.LiteralKind.BOOLEAN);
        }
        if (token.is(TokenType.NULL) || token.is(TokenType.KW_NULL)) {
            context.advance();
            return new LiteralExpressionNode(context.filePath(), token.line(), token.column(),
                    null, LiteralExpressionNode.LiteralKind.NULL);
        }
        if (token.is(TokenType.IDENTIFIER)) {
            context.advance();
            return new IdentifierExpressionNode(context.filePath(), token.line(), token.column(), token.text());
        }
        if (token.is(TokenType.LPAREN)) {
            context.advance();
            ExpressionNode expr = parseExpression();
            context.expect(TokenType.RPAREN, "Expected ')' after expression");
            return expr;
        }
        if (token.is(TokenType.LBRACKET)) {
            return parseListLiteral();
        }
        if (token.is(TokenType.LBRACE)) {
            return parseMapLiteral();
        }

        context.error(io.velora.api.compiler.DiagnosticCode.PARSER_UNEXPECTED_TOKEN,
                "Unexpected token: " + token.type() + " '" + token.text() + "'", token);
        context.advance();
        return new LiteralExpressionNode(context.filePath(), token.line(), token.column(), null, LiteralExpressionNode.LiteralKind.NULL);
    }

    private ExpressionNode parseInterpolation(Token token) {
        String raw = token.text();
        // raw includes surrounding quotes
        String body = raw.substring(1, raw.length() - 1);
        List<InterpolationExpressionNode.Segment> segments = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (c == '$' && i + 1 < body.length() && body.charAt(i + 1) == '{') {
                if (text.length() > 0) {
                    segments.add(new InterpolationExpressionNode.Text(text.toString()));
                    text = new StringBuilder();
                }
                i += 2; // skip ${
                int depth = 1;
                int start = i;
                while (i < body.length() && depth > 0) {
                    char cc = body.charAt(i);
                    if (cc == '{') depth++;
                    else if (cc == '}') depth--;
                    if (depth > 0) i++;
                }
                String exprSource = body.substring(start, i);
                if (i < body.length()) i++; // skip }
                ExpressionNode expr = parseSubExpression(exprSource, token.line(), token.column());
                segments.add(new InterpolationExpressionNode.Expr(expr));
            } else if (c == '\\') {
                // unescape
                if (i + 1 < body.length()) {
                    char esc = body.charAt(i + 1);
                    switch (esc) {
                        case 'n' -> text.append('\n');
                        case 't' -> text.append('\t');
                        case 'r' -> text.append('\r');
                        case '\\' -> text.append('\\');
                        case '"' -> text.append('"');
                        case '\'' -> text.append('\'');
                        case '$' -> text.append('$');
                        default -> text.append(esc);
                    }
                    i += 2;
                } else {
                    text.append(c);
                    i++;
                }
            } else {
                text.append(c);
                i++;
            }
        }
        if (text.length() > 0) {
            segments.add(new InterpolationExpressionNode.Text(text.toString()));
        }
        return new InterpolationExpressionNode(context.filePath(), token.line(), token.column(), segments);
    }

    private ExpressionNode parseSubExpression(String source, int line, int column) {
        try {
            Lexer subLexer = new Lexer(source, context.filePath() + ":interp");
            var subResult = subLexer.lex();
            TokenStream subStream = new TokenStream(subResult.tokens());
            ParserContext subContext = new ParserContext(subStream, context.filePath());
            ExpressionParser subParser = new ExpressionParser(subContext);
            return subParser.parseExpression();
        } catch (Exception e) {
            context.error(io.velora.api.compiler.DiagnosticCode.PARSER_UNEXPECTED_TOKEN,
                    "Invalid interpolation expression: " + e.getMessage(), line, column);
            return new LiteralExpressionNode(context.filePath(), line, column, "", LiteralExpressionNode.LiteralKind.STRING);
        }
    }

    private ExpressionNode parseListLiteral() {
        Token lbracket = context.expect(TokenType.LBRACKET, "Expected '['");
        List<ExpressionNode> elements = new ArrayList<>();
        context.skipTrivia();
        if (!context.check(TokenType.RBRACKET)) {
            elements.add(parseExpression());
            while (context.match(TokenType.COMMA)) {
                if (context.check(TokenType.RBRACKET)) break;
                elements.add(parseExpression());
            }
        }
        context.expect(TokenType.RBRACKET, "Expected ']' after list elements");
        return new ListLiteralExpressionNode(context.filePath(), lbracket.line(), lbracket.column(), elements);
    }

    private ExpressionNode parseMapLiteral() {
        Token lbrace = context.expect(TokenType.LBRACE, "Expected '{'");
        List<Map.Entry<ExpressionNode, ExpressionNode>> entries = new ArrayList<>();
        context.skipTrivia();
        if (!context.check(TokenType.RBRACE)) {
            entries.add(parseMapEntry());
            while (context.match(TokenType.COMMA)) {
                if (context.check(TokenType.RBRACE)) break;
                entries.add(parseMapEntry());
            }
        }
        context.expect(TokenType.RBRACE, "Expected '}' after map literal");
        return new MapLiteralExpressionNode(context.filePath(), lbrace.line(), lbrace.column(), entries);
    }

    private Map.Entry<ExpressionNode, ExpressionNode> parseMapEntry() {
        ExpressionNode key = parseExpression();
        context.skipTrivia();
        context.expect(TokenType.COLON, "Expected ':' in map entry");
        context.skipTrivia();
        ExpressionNode value = parseExpression();
        return Map.entry(key, value);
    }

    private TypeNode parseTypeReference() {
        context.skipTrivia();
        Token nameToken = context.expect(TokenType.IDENTIFIER, "Expected type name after 'is'");
        List<TypeNode> typeArgs = new ArrayList<>();
        context.skipTrivia();
        if (context.match(TokenType.LT)) {
            typeArgs.add(parseTypeReference());
            while (context.match(TokenType.COMMA)) {
                typeArgs.add(parseTypeReference());
            }
            context.expect(TokenType.GT, "Expected '>' after type arguments");
        }
        boolean nullable = false;
        if (context.match(TokenType.QUESTION)) {
            nullable = true;
        }
        return new TypeNode(context.filePath(), nameToken.line(), nameToken.column(),
                nameToken.text(), nullable, typeArgs);
    }
}
