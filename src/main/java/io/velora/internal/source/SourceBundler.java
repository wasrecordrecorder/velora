package io.velora.internal.source;

import io.velora.api.compiler.Diagnostic;
import io.velora.api.compiler.DiagnosticCode;
import io.velora.api.compiler.SourceFile;
import io.velora.api.compiler.SourceRange;
import io.velora.internal.lexer.Lexer;
import io.velora.internal.lexer.Token;
import io.velora.internal.lexer.TokenType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

public final class SourceBundler {
    private SourceBundler() {}

    public record SourceSpan(int startLine, int endLineExclusive, String filePath, int sourceStartLine) {
        public boolean contains(int line) { return line >= startLine && line < endLineExclusive; }
        public int sourceLine(int line) { return sourceStartLine + line - startLine; }
    }

    public record SourceBundle(String source, String filePath, String sourceHash, List<SourceSpan> spans) {
        public List<Diagnostic> remap(List<Diagnostic> diagnostics) {
            if (spans.isEmpty()) return List.copyOf(diagnostics);
            return diagnostics.stream().map(this::remap).toList();
        }

        private Diagnostic remap(Diagnostic diagnostic) {
            SourceRange range = diagnostic.range();
            if (range == null || !filePath.equals(range.filePath())) return diagnostic;
            for (SourceSpan span : spans) {
                if (!span.contains(range.startLine())) continue;
                int start = span.sourceLine(range.startLine());
                int end = span.contains(range.endLine()) ? span.sourceLine(range.endLine()) : start;
                return new Diagnostic(diagnostic.severity(), diagnostic.code(), diagnostic.message(), SourceRange.of(span.filePath(), start, range.startColumn(), end, range.endColumn()));
            }
            return diagnostic;
        }
    }

    public enum ScriptLanguage {
        VLS, JAVASCRIPT, LUA
    }

    public static ScriptLanguage detectLanguage(List<SourceFile> sources) {
        boolean hasVls = false;
        boolean hasJs = false;
        boolean hasLua = false;
        for (SourceFile source : sources) {
            String path = source.relativePath().toLowerCase(Locale.ROOT);
            if (path.endsWith(".vls")) hasVls = true;
            else if (path.endsWith(".js") || path.endsWith(".mjs")) hasJs = true;
            else if (path.endsWith(".lua")) hasLua = true;
        }
        int count = (hasVls ? 1 : 0) + (hasJs ? 1 : 0) + (hasLua ? 1 : 0);
        if (count > 1) return null;
        if (hasJs) return ScriptLanguage.JAVASCRIPT;
        if (hasLua) return ScriptLanguage.LUA;
        return ScriptLanguage.VLS;
    }

    public static SourceBundle bundle(List<SourceFile> sources, List<Diagnostic> diagnostics) {
        ScriptLanguage lang = detectLanguage(sources);
        if (lang == null) {
            if (diagnostics != null) {
                diagnostics.add(Diagnostic.error(DiagnosticCode.COMPILER_BAD_SOURCE,
                        "Cannot mix multiple script languages (.vls, .js, .lua) in a single script", SourceRange.of("main", 1, 1)));
            }
            return null;
        }

        if (lang == ScriptLanguage.JAVASCRIPT || lang == ScriptLanguage.LUA) {
            String ext = lang == ScriptLanguage.JAVASCRIPT ? ".js" : ".lua";
            StringBuilder hashInput = new StringBuilder();
            List<SourceFile> ordered = sources.stream()
                    .filter(source -> normalizedSourcePath(source.relativePath()) != null)
                    .sorted(Comparator.comparing(source -> normalizedSourcePath(source.relativePath())))
                    .toList();
            SourceFile mainFile = null;
            for (SourceFile source : ordered) {
                String path = normalizedSourcePath(source.relativePath());
                hashInput.append(path).append('\0').append(SourceHash.compute(source.content())).append('\0');
                String lower = path.toLowerCase(Locale.ROOT);
                if (mainFile == null) {
                    if (lang == ScriptLanguage.JAVASCRIPT && (lower.equals("main.js") || lower.equals("main.mjs"))) mainFile = source;
                    else if (lang == ScriptLanguage.LUA && lower.equals("main.lua")) mainFile = source;
                }
            }
            if (mainFile == null && !ordered.isEmpty()) {
                mainFile = ordered.stream()
                        .filter(s -> {
                            String p = s.relativePath().toLowerCase(Locale.ROOT);
                            return lang == ScriptLanguage.JAVASCRIPT ? (p.endsWith(".js") || p.endsWith(".mjs")) : p.endsWith(".lua");
                        })
                        .findFirst()
                        .orElse(ordered.get(0));
            }
            if (mainFile == null) {
                if (diagnostics != null) {
                    diagnostics.add(Diagnostic.error(DiagnosticCode.COMPILER_NO_SCRIPT,
                            "No " + ext + " source files provided", SourceRange.of("main" + ext, 1, 1)));
                }
                return null;
            }
            String mainPath = normalizedSourcePath(mainFile.relativePath());
            return new SourceBundle(mainFile.content(), mainPath, SourceHash.compute(hashInput.toString()), List.of());
        }

        StringBuilder hashInput = new StringBuilder();
        String mainSource = null;
        String mainPath = null;
        List<SourceFile> helpers = new ArrayList<>();
        List<SourceFile> ordered = sources.stream()
                .filter(source -> source.relativePath().toLowerCase(Locale.ROOT).endsWith(".vls"))
                .filter(source -> normalizedSourcePath(source.relativePath()) != null)
                .sorted(Comparator.comparing(source -> normalizedSourcePath(source.relativePath())))
                .toList();
        for (SourceFile source : ordered) {
            String path = normalizedSourcePath(source.relativePath());
            hashInput.append(path).append('\0').append(SourceHash.compute(source.content())).append('\0');
            boolean declaresScript = new Lexer(source.content(), path).lex().tokens().stream().anyMatch(token -> token.type() == TokenType.KW_SCRIPT);
            if (declaresScript) {
                if (mainSource != null) {
                    if (diagnostics != null) {
                        diagnostics.add(Diagnostic.error(DiagnosticCode.COMPILER_MULTIPLE_SCRIPTS,
                                "Multiple script declarations across files", SourceRange.of(path, 1, 1)));
                    }
                    return null;
                }
                mainSource = source.content();
                mainPath = path;
            } else {
                helpers.add(source);
            }
        }
        if (mainSource == null) {
            if (diagnostics != null) {
                diagnostics.add(Diagnostic.error(DiagnosticCode.COMPILER_NO_SCRIPT,
                        sources.isEmpty() ? "No .vls source files provided" : "No script declaration found", SourceRange.of("main.vls", 1, 1)));
            }
            return null;
        }
        List<SourceSpan> spans = new ArrayList<>();
        if (!helpers.isEmpty()) {
            int closingBrace = scriptClosingBrace(mainSource, mainPath);
            int capacity = mainSource.length() + helpers.stream().mapToInt(helper -> helper.content().length()).sum() + helpers.size() * 2;
            StringBuilder merged = new StringBuilder(capacity);
            if (closingBrace >= 0) {
                int insertion = lineInsertionOffset(mainSource, closingBrace);
                merged.append(mainSource, 0, insertion);
                if (!merged.isEmpty() && merged.charAt(merged.length() - 1) != '\n') merged.append('\n');
                for (SourceFile helper : helpers) appendHelper(merged, helper, spans);
                int suffixStartLine = lineAt(merged, merged.length());
                int sourceStartLine = lineAt(mainSource, insertion);
                merged.append(mainSource, insertion, mainSource.length());
                spans.add(new SourceSpan(suffixStartLine, lineAt(merged, merged.length()) + 1, mainPath, sourceStartLine));
            } else {
                merged.append(mainSource);
                if (!merged.isEmpty() && merged.charAt(merged.length() - 1) != '\n') merged.append('\n');
                for (SourceFile helper : helpers) appendHelper(merged, helper, spans);
            }
            mainSource = merged.toString();
        }
        return new SourceBundle(mainSource, mainPath, SourceHash.compute(hashInput.toString()), List.copyOf(spans));
    }

    public static String normalizedSourcePath(String path) {
        if (path == null || path.isBlank() || path.indexOf('\0') >= 0 || path.startsWith("/") || path.startsWith("\\") || path.matches("^[A-Za-z]:.*")) return null;
        String normalized = path.replace('\\', '/');
        Deque<String> parts = new ArrayDeque<>();
        for (String part : normalized.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) return null;
            parts.addLast(part);
        }
        return parts.isEmpty() ? null : String.join("/", parts);
    }

    private static int scriptClosingBrace(String source, String filePath) {
        List<Token> tokens = new Lexer(source, filePath).lex().tokens();
        int script = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).type() == TokenType.KW_SCRIPT) {
                script = i;
                break;
            }
        }
        if (script < 0) return -1;
        int depth = 0;
        boolean opened = false;
        for (int i = script + 1; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.type() == TokenType.LBRACE) {
                depth++;
                opened = true;
            } else if (token.type() == TokenType.RBRACE && opened && --depth == 0) {
                return token.offset();
            }
        }
        return -1;
    }

    private static int lineInsertionOffset(String source, int offset) {
        int lineStart = source.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        for (int i = lineStart; i < offset; i++) if (!Character.isWhitespace(source.charAt(i))) return offset;
        return lineStart;
    }

    private static void appendHelper(StringBuilder merged, SourceFile helper, List<SourceSpan> spans) {
        int startLine = lineAt(merged, merged.length());
        merged.append(helper.content());
        if (merged.isEmpty() || merged.charAt(merged.length() - 1) != '\n') merged.append('\n');
        spans.add(new SourceSpan(startLine, lineAt(merged, merged.length()), normalizedSourcePath(helper.relativePath()), 1));
    }

    private static int lineAt(CharSequence source, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) if (source.charAt(i) == '\n') line++;
        return line;
    }
}
