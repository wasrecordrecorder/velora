package io.velora.internal.language;

import io.velora.api.language.*;
import io.velora.api.compiler.Diagnostic;
import io.velora.internal.lexer.Lexer;
import io.velora.internal.lexer.LexerResult;
import io.velora.internal.parser.Parser;
import io.velora.internal.parser.ParseResult;

import java.util.*;

public final class DefaultEditorSession implements EditorSession {

    private final String scriptId;
    private final String filePath;
    private String content = "";
    private long revisionToken = 0;
    private boolean closed = false;

    public DefaultEditorSession(String scriptId, String filePath) {
        this.scriptId = scriptId;
        this.filePath = filePath;
    }

    @Override public String scriptId() { return scriptId; }
    @Override public String filePath() { return filePath; }

    @Override
    public void updateText(String text) {
        this.content = text != null ? text : "";
        this.revisionToken++;
    }

    @Override
    public EditorSnapshot snapshot() {
        LexerResult lr = new Lexer(content, filePath).lex();
        List<SyntaxToken> tokens = SyntaxHighlighter.highlight(lr);
        List<Diagnostic> diagnostics = new ArrayList<>(lr.diagnostics());
        if (diagnostics.stream().noneMatch(Diagnostic::isError)) {
            ParseResult pr = Parser.parse(content, filePath);
            diagnostics.addAll(pr.diagnostics());
        }
        return new EditorSnapshot(scriptId, filePath, content,
                io.velora.internal.source.SourceHash.compute(content), diagnostics, tokens, revisionToken);
    }

    @Override
    public List<CompletionItem> completions(int line, int column) {
        return CompletionEngine.getCompletions(content, line, column);
    }

    @Override
    public Optional<HoverInfo> hover(int line, int column) {
        return HoverEngine.getHover(content, line, column, filePath);
    }

    @Override
    public Optional<SignatureHelp> signatureHelp(int line, int column) {
        return SignatureHelpEngine.getSignatureHelp(content, line, column);
    }

    @Override
    public Optional<DefinitionLocation> definition(int line, int column) {
        return DefinitionEngine.getDefinition(content, line, column, filePath);
    }

    @Override
    public List<TextEdit> format() {
        return Formatter.format(content, filePath);
    }

    @Override
    public List<TextEdit> rename(String oldName, String newName) {
        return RenameEngine.rename(content, oldName, newName, filePath);
    }

    @Override
    public void close() { closed = true; }
    public boolean isClosed() { return closed; }
}
