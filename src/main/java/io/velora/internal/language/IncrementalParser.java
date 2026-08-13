package io.velora.internal.language;

import io.velora.internal.lexer.LexerResult;
import io.velora.internal.lexer.Token;
import io.velora.internal.parser.ParseResult;

public final class IncrementalParser {

    public static ParseResult parse(LexerResult lexerResult) {
        return io.velora.internal.parser.Parser.parse(
                lexerResult.tokens().stream().map(Token::text).reduce("", (a, b) -> a + b),
                "main.vls");
    }
}
