package io.velora.internal.language;

import io.velora.api.language.*;
import io.velora.api.compiler.Diagnostic;
import io.velora.api.compiler.SourceFile;
import io.velora.api.event.EventRegistry;
import io.velora.api.interop.JavaImportRegistry;
import io.velora.api.function.ApiRegistry;
import io.velora.api.registry.ConstantRegistry;
import io.velora.api.registry.SettingRegistry;
import io.velora.api.registry.TypeRegistry;
import io.velora.host.SourceSnapshot;
import io.velora.host.VeloraHost;
import io.velora.internal.lexer.Lexer;
import io.velora.internal.lexer.LexerResult;
import io.velora.internal.parser.Parser;
import io.velora.internal.parser.ParseResult;
import io.velora.internal.semantic.SemanticAnalyzer;
import io.velora.internal.semantic.ResolvedScript;
import io.velora.internal.source.SourceBundler;
import io.velora.internal.source.SourceHash;

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
    private final VeloraHost host;
    private String content = "";
    private long revisionToken = 0;
    private boolean closed = false;
    private EditorSnapshot cachedSnapshot;
    private ResolvedScript cachedResolved;
    private ResolvedScript lastGoodResolved;

    public DefaultEditorSession(String scriptId, String filePath) {
        this(scriptId, filePath, null, null, null, null, null, null, null);
    }

    public DefaultEditorSession(String scriptId, String filePath, ApiRegistry apiRegistry, TypeRegistry typeRegistry,
                                EventRegistry eventRegistry, SettingRegistry settingRegistry, ConstantRegistry constantRegistry) {
        this(scriptId, filePath, apiRegistry, typeRegistry, eventRegistry, settingRegistry, constantRegistry, null, null);
    }

    public DefaultEditorSession(String scriptId, String filePath, ApiRegistry apiRegistry, TypeRegistry typeRegistry,
                                EventRegistry eventRegistry, SettingRegistry settingRegistry, ConstantRegistry constantRegistry,
                                JavaImportRegistry javaImportRegistry) {
        this(scriptId, filePath, apiRegistry, typeRegistry, eventRegistry, settingRegistry, constantRegistry, javaImportRegistry, null);
    }

    public DefaultEditorSession(String scriptId, String filePath, ApiRegistry apiRegistry, TypeRegistry typeRegistry,
                                EventRegistry eventRegistry, SettingRegistry settingRegistry, ConstantRegistry constantRegistry,
                                JavaImportRegistry javaImportRegistry, VeloraHost host) {
        this.scriptId = scriptId;
        this.filePath = filePath;
        this.apiRegistry = apiRegistry;
        this.typeRegistry = typeRegistry;
        this.eventRegistry = eventRegistry;
        this.settingRegistry = settingRegistry;
        this.constantRegistry = constantRegistry;
        this.javaImportRegistry = javaImportRegistry;
        this.host = host;
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
        List<Diagnostic> diagnostics = analyzeProject(lr);
        cachedSnapshot = new EditorSnapshot(scriptId, filePath, content,
                SourceHash.compute(content), diagnostics, tokens, revisionToken);
        return cachedSnapshot;
    }

    private List<Diagnostic> analyzeProject(LexerResult openLexer) {
        List<SourceFile> projectSources = collectProjectSources();
        if (projectSources.size() <= 1) {
            return analyzeSingleOrBundled(content, filePath, openLexer.diagnostics());
        }
        List<Diagnostic> bundleDiagnostics = new ArrayList<>();
        SourceBundler.SourceBundle bundle = SourceBundler.bundle(projectSources, bundleDiagnostics);
        if (bundle == null) return bundleDiagnostics;
        LexerResult blr = new Lexer(bundle.source(), bundle.filePath()).lex();
        List<Diagnostic> allDiagnostics = analyzeSingleOrBundled(bundle.source(), bundle.filePath(), blr.diagnostics());
        return bundle.remap(allDiagnostics);
    }

    private List<Diagnostic> analyzeSingleOrBundled(String source, String path, List<Diagnostic> lexDiagnostics) {
        List<Diagnostic> diagnostics = new ArrayList<>(lexDiagnostics);
        boolean lexFailed = diagnostics.stream().anyMatch(Diagnostic::isError);
        ParseResult pr = Parser.parse(source, path);
        if (!lexFailed) diagnostics.addAll(pr.diagnostics());
        boolean parseFailed = diagnostics.stream().anyMatch(Diagnostic::isError);
        if (pr.scriptNode() != null && typeRegistry != null && settingRegistry != null && apiRegistry != null && constantRegistry != null) {
            try {
                SemanticAnalyzer analyzer = new SemanticAnalyzer(typeRegistry, settingRegistry, apiRegistry, constantRegistry, eventRegistry, javaImportRegistry);
                ResolvedScript resolved = analyzer.analyze(pr.scriptNode());
                cachedResolved = resolved;
                lastGoodResolved = resolved;
                if (!lexFailed && !parseFailed) diagnostics.addAll(analyzer.diagnostics());
            } catch (RuntimeException ignored) {
            }
        }
        return diagnostics;
    }

    private List<SourceFile> collectProjectSources() {
        String normalizedOpen = SourceBundler.normalizedSourcePath(filePath);
        if (host == null || host.fileSystem() == null || scriptId == null || normalizedOpen == null) {
            return List.of();
        }
        try {
            Map<String, String> byPath = new LinkedHashMap<>();
            for (var entry : host.fileSystem().listScripts()) {
                if (!scriptId.equals(entry.scriptId())) continue;
                String rel = SourceBundler.normalizedSourcePath(entry.relativePath());
                if (rel == null || !rel.toLowerCase(Locale.ROOT).endsWith(".vls")) continue;
                if (rel.equals(normalizedOpen)) {
                    byPath.put(rel, content);
                } else {
                    SourceSnapshot snap = host.fileSystem().readSource(scriptId, rel);
                    if (snap != null) byPath.put(rel, snap.content());
                }
            }
            byPath.putIfAbsent(normalizedOpen, content);
            List<SourceFile> out = new ArrayList<>(byPath.size());
            for (var entry : byPath.entrySet()) out.add(SourceFile.of(entry.getKey(), entry.getValue()));
            return out;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private ResolvedScript resolvedContext() {
        return cachedResolved != null ? cachedResolved : lastGoodResolved;
    }

    @Override
    public List<CompletionItem> completions(int line, int column) {
        ensureOpen();
        snapshot();
        return CompletionEngine.getCompletions(content, line, column, apiRegistry, typeRegistry, eventRegistry, settingRegistry, constantRegistry, javaImportRegistry, resolvedContext());
    }

    @Override
    public Optional<HoverInfo> hover(int line, int column) {
        ensureOpen();
        snapshot();
        return HoverEngine.getHover(content, line, column, filePath, apiRegistry, typeRegistry, constantRegistry, eventRegistry, javaImportRegistry, resolvedContext());
    }

    @Override
    public Optional<SignatureHelp> signatureHelp(int line, int column) {
        ensureOpen();
        snapshot();
        return SignatureHelpEngine.getSignatureHelp(content, line, column, apiRegistry, javaImportRegistry, typeRegistry, resolvedContext());
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
    public void close() { closed = true; cachedSnapshot = null; cachedResolved = null; lastGoodResolved = null; }
    public boolean isClosed() { return closed; }
    private void ensureOpen() { if (closed) throw new IllegalStateException("Editor session is closed"); }
}
