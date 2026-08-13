package io.velora.internal.parser;

import io.velora.internal.lexer.TokenType;

public final class RecoveryStrategy {

    private RecoveryStrategy() {}

    public static void recoverTo(ParserContext context, TokenType... stopTokens) {
        while (context.tokens().hasMore()) {
            for (TokenType stop : stopTokens) {
                if (context.tokens().check(stop)) {
                    return;
                }
            }
            context.advance();
        }
    }

    public static void recoverToClosingBrace(ParserContext context) {
        int depth = 0;
        while (context.tokens().hasMore()) {
            var token = context.peek();
            if (token.is(TokenType.LBRACE)) {
                depth++;
            } else if (token.is(TokenType.RBRACE)) {
                if (depth == 0) {
                    context.advance();
                    return;
                }
                depth--;
            }
            context.advance();
        }
    }
}
