package io.velora.internal.lexer;

import java.util.ArrayList;
import java.util.List;

/**
 * Cursor over significant tokens. Whitespace and comments are skipped; newlines
 * are preserved as they act as statement separators in the Kotlin-like grammar.
 */
public final class TokenStream {

    private final List<Token> significant;
    private final List<Token> raw;
    private int position;

    public TokenStream(List<Token> allTokens) {
        this.raw = allTokens;
        List<Token> sig = new ArrayList<>();
        for (Token t : allTokens) {
            if (t.type() == TokenType.WHITESPACE || t.type() == TokenType.COMMENT) {
                continue;
            }
            sig.add(t);
        }
        this.significant = List.copyOf(sig);
        this.position = 0;
    }

    /** All tokens including trivia (for the language service). */
    public List<Token> raw() {
        return raw;
    }

    public List<Token> significant() {
        return significant;
    }

    public Token peek() {
        return significant.get(position);
    }

    public Token peek(int offset) {
        int idx = position + offset;
        if (idx < 0 || idx >= significant.size()) {
            return Token.eof(0, 0, 0);
        }
        return significant.get(idx);
    }

    public Token advance() {
        Token token = significant.get(position);
        if (position < significant.size() - 1) {
            position++;
        }
        return token;
    }

    public boolean check(TokenType type) {
        return peek().is(type);
    }

    public boolean checkAny(TokenType... types) {
        return peek().isAny(types);
    }

    public boolean match(TokenType type) {
        if (check(type)) {
            advance();
            return true;
        }
        return false;
    }

    public Token previous() {
        int idx = Math.max(0, position - 1);
        return significant.get(idx);
    }

    public int position() {
        return position;
    }

    public void setPosition(int position) {
        this.position = Math.max(0, Math.min(position, significant.size() - 1));
    }

    public boolean isEof() {
        return peek().isEof();
    }

    public boolean hasMore() {
        return !isEof();
    }

    public int size() {
        return significant.size();
    }

    /** Skip any newline tokens at the current position. */
    public void skipNewlines() {
        while (check(TokenType.NEWLINE)) {
            advance();
        }
    }
}
