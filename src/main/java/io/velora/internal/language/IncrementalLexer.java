package io.velora.internal.language;

import io.velora.internal.lexer.LexerResult;
import io.velora.internal.lexer.Token;

import java.util.*;

public final class IncrementalLexer {

    public static LexerResult lex(String content) {
        return new io.velora.internal.lexer.Lexer(content, "main.vls").lex();
    }

    public static LexerResult relex(LexerResult previous, String newContent, int changeStart, int changeEnd) {
        return new io.velora.internal.lexer.Lexer(newContent, "main.vls").lex();
    }
}
