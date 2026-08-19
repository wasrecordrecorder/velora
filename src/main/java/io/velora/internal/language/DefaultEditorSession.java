package io.velora.internal.language;

import io.velora.api.language.*;
import io.velora.api.compiler.Diagnostic;
import io.velora.api.event.EventRegistry;
import io.velora.api.interop.JavaImportRegistry;
import io.velora.api.function.ApiRegistry;
import io.velora.api.registry.ConstantRegistry;
import io.velora.api.registry.SettingRegistry;
import io.velora.api.registry.TypeRegistry;
import io.velora.internal.lexer.Lexer;
import io.velora.internal.lexer.LexerResult;
import io.velora.internal.parser.Parser;
import io.velora.internal.parser.ParseResult;
import io.velora.internal.semantic.SemanticAnalyzer;
import io.velora.internal.semantic.ResolvedScript;

import java.util.*;

public final class DefaultEditorSession implements EditorSession {

    private final String scriptId;
    private final String filePath;
    private final ApiRegistry apiRegistry;
    private final TypeRegistry typeRegistry;
    private final EventRegistry eventRegistry;
    private final SettingRegistry settingRegistry;
    private final ConstantRegistry constantRegistry;
    private final JavaImportRegistry javaImportRegistry;
    private String content = "";
    private long revisionToken = 0;
    private boolean closed = false;
    private EditorSnapshot cachedSnapshot;
    private ResolvedScript cachedResolved;

    public DefaultEditorSession(String scriptId, String filePath) {
        this(scriptId, filePath, null, null, null, null, null, null);
    }

    public DefaultEditorSession(String scriptId, String filePath, ApiRegistry apiRegistry, TypeRegistry typeRegistry,
                                EventRegistry eventRegistry, SettingRegistry settingRegistry, ConstantRegistry constantRegistry) {
        this(scriptId, filePath, apiRegistry, typeRegistry, eventRegistry, settingRegistry, constantRegistry, null);
    }

    public DefaultEditorSession(String scriptId, String filePath, ApiRegistry apiRegistry, TypeRegistry typeRegistry,
                                EventRegistry eventRegistry, SettingRegistry settingRegistry, ConstantRegistry constantRegistry,
                                JavaImportRegistry javaImportRegistry) {
        this.scriptId = scriptId;
        this.filePath = filePath;
        this.apiRegistry = apiRegistry;
        this.typeRegistry = typeRegistry;
        this.eventRegistry = eventRegistry;
        this.settingRegistry = settingRegistry;
        this.constantRegistry = constantRegistry;
        this.javaImportRegistry = javaImportRegistry;
    }

    @Override public String scriptId() { return scriptId; }
    @Override public String filePath() { return filePath; }

    @Override
    public void updateText(String text) {
        ensureOpen();
        String next = text != null ? text : "";
        if (content.equals(next)) return;
        content = next;
        revisionToken++;
        cachedSnapshot = null;
        cachedResolved = null;
    }

    @Override
    public EditorSnapshot snapshot() {
        ensureOpen();
        if (cachedSnapshot != null) return cachedSnapshot;
        LexerResult lr = new Lexer(content, filePath).lex();
        List<SyntaxToken> tokens = SyntaxHighlighter.highlight(lr);
        List<Diagnostic> diagnostics = new ArrayList<>(lr.diagnostics());
        if (diagnostics.stream().noneMatch(Diagnostic::isError)) {
            ParseResult pr = Parser.parse(content, filePath);
            diagnostics.addAll(pr.diagnostics());
            if (pr.scriptNode() != null && diagnostics.stream().noneMatch(Diagnostic::isError)
                    && typeRegistry != null && settingRegistry != null && apiRegistry != null && constantRegistry != null) {
                SemanticAnalyzer analyzer = new SemanticAnalyzer(typeRegistry, settingRegistry, apiRegistry, constantRegistry, eventRegistry, javaImportRegistry);
                cachedResolved = analyzer.analyze(pr.scriptNode());
                diagnostics.addAll(analyzer.diagnostics());
            }
        }
        cachedSnapshot = new EditorSnapshot(scriptId, filePath, content,
                io.velora.internal.source.SourceHash.compute(content), diagnostics, tokens, revisionToken);
        return cachedSnapshot;
    }

    @Override
    public List<CompletionItem> completions(int line, int column) {
        ensureOpen();
        snapshot();
        return CompletionEngine.getCompletions(content, line, column, apiRegistry, typeRegistry, eventRegistry, settingRegistry, constantRegistry, javaImportRegistry, cachedResolved);
    }

    @Override
    public Optional<HoverInfo> hover(int line, int column) {
        ensureOpen();
        snapshot();
        return HoverEngine.getHover(content, line, column, filePath, apiRegistry, typeRegistry, constantRegistry, eventRegistry, javaImportRegistry, cachedResolved);
    }

    @Override
    public Optional<SignatureHelp> signatureHelp(int line, int column) {
        ensureOpen();
        snapshot();
        return SignatureHelpEngine.getSignatureHelp(content, line, column, apiRegistry, javaImportRegistry, typeRegistry, cachedResolved);
    }

    @Override
    public Optional<DefinitionLocation> definition(int line, int column) {
        ensureOpen();
        return DefinitionEngine.getDefinition(content, line, column, filePath);
    }

    @Override
    public List<TextEdit> format() {
        ensureOpen();
        return Formatter.format(content, filePath);
    }

    @Override
    public List<TextEdit> rename(String oldName, String newName) {
        ensureOpen();
        return RenameEngine.rename(content, oldName, newName, filePath);
    }

    @Override
    public void close() { closed = true; cachedSnapshot = null; cachedResolved = null; }
    public boolean isClosed() { return closed; }
    private void ensureOpen() { if (closed) throw new IllegalStateException("Editor session is closed"); }
}
