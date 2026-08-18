package io.velora.internal.language;

import io.velora.api.interop.JavaImportDescriptor;
import io.velora.api.interop.JavaImportRegistry;
import io.velora.internal.lexer.Lexer;
import io.velora.internal.lexer.Token;
import io.velora.internal.lexer.TokenType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JavaImportAliases {
    private JavaImportAliases() { }

    static Map<String, JavaImportDescriptor> resolve(String content, JavaImportRegistry registry) {
        if (registry == null || content == null || content.isEmpty()) return Map.of();
        List<Token> tokens = new Lexer(content, "imports.vls").lex().tokens();
        Map<String, JavaImportDescriptor> result = new LinkedHashMap<>();
        int index = skipTrivia(tokens, 0);
        while (index < tokens.size() && tokens.get(index).is(TokenType.KW_IMPORT)) {
            index = skipTrivia(tokens, index + 1);
            if (index >= tokens.size() || !tokens.get(index).is(TokenType.IDENTIFIER)) break;
            StringBuilder name = new StringBuilder(tokens.get(index++).text());
            boolean valid = true;
            while (true) {
                index = skipHorizontalTrivia(tokens, index);
                if (index >= tokens.size() || !tokens.get(index).is(TokenType.DOT)) break;
                index = skipHorizontalTrivia(tokens, index + 1);
                if (index >= tokens.size() || !tokens.get(index).is(TokenType.IDENTIFIER)) {
                    valid = false;
                    break;
                }
                name.append('.').append(tokens.get(index++).text());
            }
            if (!valid) break;
            JavaImportDescriptor descriptor = registry.find(name.toString());
            if (descriptor != null) result.putIfAbsent(descriptor.alias(), descriptor);
            index = skipTrivia(tokens, index);
            if (index < tokens.size() && tokens.get(index).is(TokenType.SEMICOLON)) index = skipTrivia(tokens, index + 1);
        }
        return Map.copyOf(result);
    }

    static String namespace(String content, String alias, JavaImportRegistry registry) {
        JavaImportDescriptor descriptor = resolve(content, registry).get(alias);
        return descriptor != null ? descriptor.namespace() : alias;
    }

    private static int skipTrivia(List<Token> tokens, int index) {
        while (index < tokens.size() && tokens.get(index).isTrivia()) index++;
        return index;
    }

    private static int skipHorizontalTrivia(List<Token> tokens, int index) {
        while (index < tokens.size() && (tokens.get(index).is(TokenType.WHITESPACE) || tokens.get(index).is(TokenType.COMMENT))) index++;
        return index;
    }
}
