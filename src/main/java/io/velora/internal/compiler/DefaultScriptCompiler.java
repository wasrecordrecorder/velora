package io.velora.internal.compiler;

import io.velora.api.compiler.*;
import io.velora.api.event.EventDescriptor;
import io.velora.api.event.EventRegistry;
import io.velora.api.function.ApiRegistry;
import io.velora.api.function.FunctionDescriptor;
import io.velora.api.interop.JavaImportRegistry;
import io.velora.api.registry.*;
import io.velora.api.setting.SettingKind;
import io.velora.api.type.EnumType;
import io.velora.api.type.StructType;
import io.velora.api.type.VeloraType;
import io.velora.internal.bytecode.*;
import io.velora.internal.ir.*;
import io.velora.internal.lexer.Lexer;
import io.velora.internal.lexer.LexerResult;
import io.velora.internal.lexer.TokenType;
import io.velora.internal.parser.ParseResult;
import io.velora.internal.parser.Parser;
import io.velora.internal.persistence.BytecodeCache;
import io.velora.internal.semantic.ResolvedScript;
import io.velora.internal.semantic.SemanticAnalyzer;
import io.velora.internal.source.SourceHash;
import io.velora.internal.vm.*;

import java.io.*;
import java.util.*;

public final class DefaultScriptCompiler implements ScriptCompiler {
    private final TypeRegistry typeRegistry;
    private final SettingRegistry settingRegistry;
    private final ApiRegistry apiRegistry;
    private final ConstantRegistry constantRegistry;
    private final EventRegistry eventRegistry;
    private final JavaImportRegistry javaImportRegistry;
    private final BytecodeCache cache = new BytecodeCache();
    private boolean frozen;

    public DefaultScriptCompiler(TypeRegistry typeRegistry, SettingRegistry settingRegistry,
                                 ApiRegistry apiRegistry, ConstantRegistry constantRegistry) {
        this(typeRegistry, settingRegistry, apiRegistry, constantRegistry, null, null);
    }

    public DefaultScriptCompiler(TypeRegistry typeRegistry, SettingRegistry settingRegistry,
                                 ApiRegistry apiRegistry, ConstantRegistry constantRegistry,
                                 EventRegistry eventRegistry) {
        this(typeRegistry, settingRegistry, apiRegistry, constantRegistry, eventRegistry, null);
    }

    public DefaultScriptCompiler(TypeRegistry typeRegistry, SettingRegistry settingRegistry,
                                 ApiRegistry apiRegistry, ConstantRegistry constantRegistry,
                                 EventRegistry eventRegistry, JavaImportRegistry javaImportRegistry) {
        this.typeRegistry = typeRegistry;
        this.settingRegistry = settingRegistry;
        this.apiRegistry = apiRegistry;
        this.constantRegistry = constantRegistry;
        this.eventRegistry = eventRegistry;
        this.javaImportRegistry = javaImportRegistry;
    }

    @Override
    public CompileResult compile(CompileRequest request) {
        Compilation compilation = compileInternal(request);
        if (compilation.module == null) return CompileResult.failure(request.scriptId(), compilation.diagnostics);
        try {
            return new CompileResult(true, request.scriptId(), compilation.diagnostics, serializeBytecode(compilation.module), compilation.registryHash, compilation.sourceHash);
        } catch (RuntimeException error) {
            List<Diagnostic> diagnostics = new ArrayList<>(compilation.diagnostics);
            diagnostics.add(Diagnostic.error(DiagnosticCode.BYTECODE_BAD_OPERAND, "Bytecode serialization failed: " + error.getMessage(), SourceRange.of("main.vls", 0, 0)));
            return CompileResult.failure(request.scriptId(), diagnostics);
        }
    }

    public CompiledModule compileToModule(CompileRequest request) {
        return compileInternal(request).module;
    }

    private Compilation compileInternal(CompileRequest request) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (request.languageVersion() != 2) {
            diagnostics.add(Diagnostic.error(DiagnosticCode.COMPILER_UNSUPPORTED_VERSION,
                    "Unsupported language version: " + request.languageVersion(), SourceRange.of("main.vls", 0, 0)));
            return Compilation.failure(diagnostics);
        }

        Set<String> sourcePaths = new HashSet<>();
        for (SourceFile source : request.sources()) {
            String path = normalizedSourcePath(source.relativePath());
            if (path == null) {
                diagnostics.add(Diagnostic.error(DiagnosticCode.COMPILER_PATH_TRAVERSAL,
                        "Invalid source path: " + source.relativePath(), SourceRange.of(source.relativePath(), 0, 0)));
                return Compilation.failure(diagnostics);
            }
            if (!sourcePaths.add(path)) {
                diagnostics.add(Diagnostic.error(DiagnosticCode.COMPILER_BAD_SOURCE,
                        "Duplicate source path: " + path, SourceRange.of(path, 0, 0)));
                return Compilation.failure(diagnostics);
            }
        }

        SourceBundle bundle = sourceBundle(request.sources(), diagnostics);
        if (bundle == null) return Compilation.failure(diagnostics);
        String registryHash = computeRegistryHash();
        CompiledModule cached = cache.get(request.scriptId(), bundle.sourceHash, registryHash);
        if (cached != null && request.mode() != CompileMode.FULL) {
            return new Compilation(cached, bundle.remap(diagnostics), bundle.sourceHash, registryHash);
        }
        if (request.mode() == CompileMode.CACHE_ONLY) {
            diagnostics.add(Diagnostic.error(DiagnosticCode.COMPILER_CACHE_MISS,
                    "No matching cached bytecode available", SourceRange.of("main.vls", 0, 0)));
            return Compilation.failure(bundle.remap(diagnostics));
        }

        LexerResult lexerResult = new Lexer(bundle.source, bundle.filePath).lex();
        diagnostics.addAll(lexerResult.diagnostics());
        if (hasErrors(diagnostics)) return Compilation.failure(bundle.remap(diagnostics));

        ParseResult parseResult = Parser.parse(bundle.source, bundle.filePath);
        diagnostics.addAll(parseResult.diagnostics());
        if (hasErrors(diagnostics) || parseResult.scriptNode() == null) return Compilation.failure(bundle.remap(diagnostics));

        SemanticAnalyzer analyzer = new SemanticAnalyzer(typeRegistry, settingRegistry, apiRegistry, constantRegistry, eventRegistry, javaImportRegistry);
        ResolvedScript resolved = analyzer.analyze(parseResult.scriptNode());
        diagnostics.addAll(analyzer.diagnostics());
        validateScriptMetadata(request, parseResult, resolved, diagnostics);
        if (hasErrors(diagnostics)) return Compilation.failure(bundle.remap(diagnostics));

        IrModule irModule = new IrOptimizer().optimize(new IrBuilder(resolved, apiRegistry, constantRegistry, typeRegistry, request.scriptId()).build());
        diagnostics.addAll(new IrVerifier().verify(irModule));
        if (hasErrors(diagnostics)) return Compilation.failure(bundle.remap(diagnostics));

        CompiledModule module = new BytecodeWriter().write(irModule, bundle.sourceHash, registryHash);
        diagnostics.addAll(new BytecodeVerifier(apiRegistry).verify(module));
        if (hasErrors(diagnostics)) return Compilation.failure(bundle.remap(diagnostics));
        cache.put(request.scriptId(), bundle.sourceHash, registryHash, module);
        return new Compilation(module, bundle.remap(diagnostics), bundle.sourceHash, registryHash);
    }


    private void validateScriptMetadata(CompileRequest request, ParseResult parseResult, ResolvedScript resolved, List<Diagnostic> diagnostics) {
        if (resolved.languageVersion() != request.languageVersion()) {
            diagnostics.add(Diagnostic.error(DiagnosticCode.COMPILER_UNSUPPORTED_VERSION,
                    "Script language version does not match compile request", SourceRange.of(parseResult.scriptNode().filePath(), 0, 0)));
        }
    }

    private SourceBundle sourceBundle(List<SourceFile> sources, List<Diagnostic> diagnostics) {
        StringBuilder hashInput = new StringBuilder();
        String mainSource = null;
        String mainPath = null;
        List<SourceFile> helpers = new ArrayList<>();
        List<SourceFile> ordered = sources.stream()
                .filter(source -> source.relativePath().toLowerCase(Locale.ROOT).endsWith(".vls"))
                .sorted(Comparator.comparing(source -> normalizedSourcePath(source.relativePath())))
                .toList();
        for (SourceFile source : ordered) {
            String path = normalizedSourcePath(source.relativePath());
            hashInput.append(path).append('\0').append(SourceHash.compute(source.content())).append('\0');
            boolean declaresScript = new Lexer(source.content(), path).lex().tokens().stream().anyMatch(token -> token.type() == TokenType.KW_SCRIPT);
            if (declaresScript) {
                if (mainSource != null) {
                    diagnostics.add(Diagnostic.error(DiagnosticCode.COMPILER_MULTIPLE_SCRIPTS,
                            "Multiple script declarations across files", SourceRange.of(path, 0, 0)));
                    return null;
                }
                mainSource = source.content();
                mainPath = path;
            } else {
                helpers.add(source);
            }
        }
        if (mainSource == null) {
            diagnostics.add(Diagnostic.error(DiagnosticCode.COMPILER_NO_SCRIPT,
                    sources.isEmpty() ? "No .vls source files provided" : "No script declaration found", SourceRange.of("main.vls", 0, 0)));
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

    private int scriptClosingBrace(String source, String filePath) {
        List<io.velora.internal.lexer.Token> tokens = new Lexer(source, filePath).lex().tokens();
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
            var token = tokens.get(i);
            if (token.type() == TokenType.LBRACE) {
                depth++;
                opened = true;
            } else if (token.type() == TokenType.RBRACE && opened && --depth == 0) {
                return token.offset();
            }
        }
        return -1;
    }

    private int lineInsertionOffset(String source, int offset) {
        int lineStart = source.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        for (int i = lineStart; i < offset; i++) if (!Character.isWhitespace(source.charAt(i))) return offset;
        return lineStart;
    }

    private void appendHelper(StringBuilder merged, SourceFile helper, List<SourceSpan> spans) {
        int startLine = lineAt(merged, merged.length());
        merged.append(helper.content());
        if (merged.isEmpty() || merged.charAt(merged.length() - 1) != '\n') merged.append('\n');
        spans.add(new SourceSpan(startLine, lineAt(merged, merged.length()), normalizedSourcePath(helper.relativePath()), 1));
    }

    private int lineAt(CharSequence source, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) if (source.charAt(i) == '\n') line++;
        return line;
    }

    @Override
    public boolean isFrozen() { return frozen; }

    public void freeze() { frozen = true; }

    private boolean hasErrors(List<Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(Diagnostic::isError);
    }

    private String computeRegistryHash() {
        StringBuilder out = new StringBuilder();
        typeRegistry.all().stream().sorted(Comparator.comparing(VeloraType::name)).forEach(type -> {
            out.append("T|").append(type.name()).append('|').append(type.javaClass().getName()).append('|').append(type.isHashable()).append('|').append(type.isHandle()).append(';');
            if (type instanceof StructType struct) {
                struct.properties().forEach(property -> out.append(property.name()).append(':').append(property.type().name()).append(','));
                out.append(struct.valueEquality()).append(';');
            } else if (type instanceof EnumType enumType) {
                enumType.constants().forEach(constant -> out.append(constant.name()).append('=').append(stableValue(constant.value())).append(','));
            }
        });
        apiRegistry.all().stream().sorted(Comparator.comparing(FunctionDescriptor::qualifiedName)).forEach(function -> {
            out.append("F|").append(function.qualifiedName()).append('|').append(function.returnType().name()).append('|').append(function.suspending()).append('|').append(function.property()).append('|').append(function.thread()).append('|').append(function.cost()).append('|');
            function.parameters().forEach(parameter -> out.append(parameter.name()).append(':').append(parameter.type().name()).append(':').append(parameter.required()).append(':').append(parameter.hasDefault()).append(':').append(parameter.variadic()).append(':').append(stableValue(parameter.defaultValue())).append(','));
            out.append(';');
        });
        settingRegistry.all().stream().sorted(Comparator.comparing(SettingKind::name)).forEach(kind -> {
            out.append("S|").append(kind.name()).append('|').append(kind.categoryId()).append('|').append(kind.resultType().map(VeloraType::name).orElse("dynamic")).append('|');
            kind.parameterSchema().forEach(parameter -> out.append(parameter.name()).append(':').append(parameter.role()).append(':').append(parameter.type() != null ? parameter.type().name() : "-").append(':').append(parameter.required()).append(','));
            out.append(kind.editor().map(editor -> editor.editorId()).orElse("")).append(';');
        });
        constantRegistry.all().stream().sorted(Comparator.comparing(ConstantRegistry.Constant::qualifiedName)).forEach(constant -> out.append("C|").append(constant.qualifiedName()).append('|').append(constant.type().name()).append('|').append(stableValue(constant.value())).append(';'));
        if (eventRegistry != null) {
            eventRegistry.all().stream().sorted(Comparator.comparing(EventDescriptor::id)).forEach(event -> out.append("E|").append(event.id()).append('|').append(event.scriptName()).append('|').append(event.payloadType().name()).append('|').append(event.defaultConcurrency()).append('|').append(event.queueLimit()).append('|').append(event.overflowPolicy()).append('|').append(event.cost()).append(';'));
        }
        return SourceHash.compute(out.toString());
    }

    private String stableValue(Object value) {
        if (value == null) return "null";
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean || value instanceof Character || value instanceof UUID || value instanceof java.time.Duration || value instanceof Enum<?>) return value.getClass().getName() + ':' + value;
        if (value instanceof List<?> list) return list.stream().map(this::stableValue).toList().toString();
        if (value instanceof Set<?> set) return set.stream().map(this::stableValue).sorted().toList().toString();
        if (value instanceof Map<?, ?> map) return map.entrySet().stream().map(entry -> stableValue(entry.getKey()) + "=" + stableValue(entry.getValue())).sorted().toList().toString();
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<String> values = new ArrayList<>(length);
            for (int i = 0; i < length; i++) values.add(stableValue(java.lang.reflect.Array.get(value, i)));
            return values.toString();
        }
        return value.getClass().getName() + ':' + String.valueOf(value);
    }

    private String normalizedSourcePath(String path) {
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

    private record SourceSpan(int startLine, int endLineExclusive, String filePath, int sourceStartLine) {
        private boolean contains(int line) { return line >= startLine && line < endLineExclusive; }
        private int sourceLine(int line) { return sourceStartLine + line - startLine; }
    }

    private record SourceBundle(String source, String filePath, String sourceHash, List<SourceSpan> spans) {
        private List<Diagnostic> remap(List<Diagnostic> diagnostics) {
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
    private record Compilation(CompiledModule module, List<Diagnostic> diagnostics, String sourceHash, String registryHash) {
        private static Compilation failure(List<Diagnostic> diagnostics) { return new Compilation(null, List.copyOf(diagnostics), null, null); }
    }

    private byte[] serializeBytecode(CompiledModule module) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("VLCB");
            out.writeInt(3);
            writeString(out, module.scriptId());
            writeString(out, module.scriptName());
            writeString(out, module.version());
            out.writeInt(module.languageVersion());
            writeString(out, module.sourceHash());
            writeString(out, module.registryHash());

            ConstantPool pool = module.constantPool();
            out.writeInt(pool.size());
            for (int i = 0; i < pool.size(); i++) {
                ConstantPool.Tag tag = pool.tag(i);
                out.writeInt(tag.ordinal());
                switch (tag) {
                    case INT -> out.writeInt(pool.intValue(i));
                    case LONG -> out.writeLong(pool.longValue(i));
                    case FLOAT -> out.writeFloat(pool.floatValue(i));
                    case DOUBLE -> out.writeDouble(pool.doubleValue(i));
                    case STRING -> writeString(out, pool.stringValue(i));
                    case BOOLEAN -> out.writeBoolean(pool.booleanValue(i));
                    case DURATION -> out.writeLong(pool.durationNanos(i));
                    case NULL -> {}
                }
            }

            out.writeInt(module.functions().size());
            for (CompiledFunction function : module.functions()) {
                writeString(out, function.name());
                out.writeInt(function.index());
                out.writeInt(function.parameterCount());
                out.writeInt(function.localCount());
                out.writeInt(function.maxStack());
                out.writeBoolean(function.suspending());
                out.writeBoolean(function.isLifecycle());
                out.writeInt(function.code().length);
                for (int value : function.code()) out.writeInt(value);
                out.writeInt(function.lineNumbers().length);
                for (int value : function.lineNumbers()) out.writeInt(value);
            }

            writeStrings(out, module.persistentFieldIds());
            writeStrings(out, module.persistentFieldTypes());
            out.writeInt(module.persistentFieldIndices().size());
            for (int value : module.persistentFieldIndices()) out.writeInt(value);
            out.writeInt(module.persistentFieldIsStatic().size());
            for (boolean value : module.persistentFieldIsStatic()) out.writeBoolean(value);
            writeStrings(out, module.lifecycleHooks());

            out.writeInt(module.eventHandlers().size());
            for (CompiledModule.EventHandlerInfo handler : module.eventHandlers()) {
                writeString(out, handler.eventReference());
                writeString(out, handler.functionName());
                out.writeInt(handler.functionIndex());
                out.writeBoolean(handler.suspending());
            }

            out.writeInt(module.fieldInitializers().size());
            for (CompiledModule.FieldInitializer initializer : module.fieldInitializers()) {
                out.writeInt(initializer.fieldIndex());
                out.writeBoolean(initializer.isStatic());
                writeValue(out, initializer.initialValue());
            }
            writeString(out, module.author() == null ? "" : module.author());
            writeString(out, module.description() == null ? "" : module.description());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot serialize bytecode", e);
        }
    }

    public static CompiledModule deserializeBytecode(byte[] data,
                                                      List<io.velora.api.setting.SettingDescriptor> settings) {
        if (data == null || data.length == 0 || data.length > 64 * 1024 * 1024) return null;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (!"VLCB".equals(in.readUTF())) return null;
            int format = in.readInt();
            if (format < 1 || format > 3) return null;
            String scriptId = readString(in, format);
            String scriptName = readString(in, format);
            String version = readString(in, format);
            int languageVersion = in.readInt();
            if (languageVersion != 2) return null;
            String sourceHash = readString(in, format);
            String registryHash = readString(in, format);

            int poolSize = readCount(in, 1_000_000);
            requireRemaining(in, poolSize, 4);
            ConstantPool pool = new ConstantPool();
            ConstantPool.Tag[] tags = ConstantPool.Tag.values();
            for (int i = 0; i < poolSize; i++) {
                int ordinal = in.readInt();
                if (ordinal < 0 || ordinal >= tags.length) return null;
                switch (tags[ordinal]) {
                    case INT -> pool.addInt(in.readInt());
                    case LONG -> pool.addLong(in.readLong());
                    case FLOAT -> pool.addFloat(in.readFloat());
                    case DOUBLE -> pool.addDouble(in.readDouble());
                    case STRING -> pool.addString(readString(in, format));
                    case BOOLEAN -> pool.addBoolean(in.readBoolean());
                    case DURATION -> pool.addDuration(in.readLong());
                    case NULL -> pool.addNull();
                }
            }

            int functionCount = readCount(in, 100_000);
            requireRemaining(in, functionCount, 28);
            List<CompiledFunction> functions = new ArrayList<>(functionCount);
            for (int i = 0; i < functionCount; i++) {
                String name = readString(in, format);
                int index = in.readInt();
                int parameterCount = readCount(in, 1_000_000);
                int localCount = readCount(in, 1_000_000);
                int maxStack = readCount(in, 1_000_000);
                boolean suspending = in.readBoolean();
                boolean lifecycle = in.readBoolean();
                int codeLength = readCount(in, 16_000_000);
                requireRemaining(in, codeLength, Integer.BYTES);
                int[] code = new int[codeLength];
                for (int j = 0; j < codeLength; j++) code[j] = in.readInt();
                int lineLength = readCount(in, 16_000_000);
                requireRemaining(in, lineLength, Integer.BYTES);
                int[] lines = new int[lineLength];
                for (int j = 0; j < lineLength; j++) lines[j] = in.readInt();
                functions.add(new CompiledFunction(name, index, parameterCount, localCount, maxStack, suspending, lifecycle, code, lines));
            }

            List<String> persistentFieldIds = readStrings(in, format, 1_000_000);
            List<String> persistentFieldTypes = readStrings(in, format, 1_000_000);
            int indexCount = readCount(in, 1_000_000);
            requireRemaining(in, indexCount, Integer.BYTES);
            List<Integer> persistentFieldIndices = new ArrayList<>(indexCount);
            for (int i = 0; i < indexCount; i++) persistentFieldIndices.add(in.readInt());
            int staticCount = readCount(in, 1_000_000);
            requireRemaining(in, staticCount, 1);
            List<Boolean> persistentFieldIsStatic = new ArrayList<>(staticCount);
            for (int i = 0; i < staticCount; i++) persistentFieldIsStatic.add(in.readBoolean());
            List<String> lifecycleHooks = readStrings(in, format, 100_000);

            int handlerCount = readCount(in, 1_000_000);
            requireRemaining(in, handlerCount, 9);
            List<CompiledModule.EventHandlerInfo> eventHandlers = new ArrayList<>(handlerCount);
            for (int i = 0; i < handlerCount; i++) {
                eventHandlers.add(new CompiledModule.EventHandlerInfo(readString(in, format), readString(in, format), in.readInt(), in.readBoolean()));
            }

            List<CompiledModule.FieldInitializer> initializers = new ArrayList<>();
            String author = null;
            String description = null;
            if (format >= 2) {
                int initializerCount = readCount(in, 1_000_000);
                requireRemaining(in, initializerCount, 6);
                for (int i = 0; i < initializerCount; i++) {
                    initializers.add(new CompiledModule.FieldInitializer(in.readInt(), in.readBoolean(), readValue(in, 0)));
                }
                author = emptyToNull(readString(in, format));
                description = emptyToNull(readString(in, format));
            }

            if (in.available() != 0) return null;
            CompiledModule module = new CompiledModule(scriptId, scriptName, version, languageVersion,
                    sourceHash, registryHash, pool, functions, settings,
                    persistentFieldIds, persistentFieldTypes, persistentFieldIndices, persistentFieldIsStatic,
                    lifecycleHooks, eventHandlers, initializers, author, description);
            return new BytecodeVerifier().verify(module).stream().anyMatch(Diagnostic::isError) ? null : module;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void writeStrings(DataOutputStream out, List<String> values) throws IOException {
        out.writeInt(values.size());
        for (String value : values) writeString(out, value);
    }

    private static List<String> readStrings(DataInputStream in, int format, int maxCount) throws IOException {
        int count = readCount(in, maxCount);
        requireRemaining(in, count, format == 1 ? 2 : Integer.BYTES);
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) values.add(readString(in, format));
        return values;
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = Objects.requireNonNull(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in, int format) throws IOException {
        if (format == 1) return in.readUTF();
        int length = readCount(in, 16_000_000);
        if (length > in.available()) throw new EOFException();
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException();
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int readCount(DataInputStream in, int max) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > max) throw new IOException("Invalid count: " + count);
        return count;
    }

    private static void requireRemaining(DataInputStream in, int count, int minimumBytes) throws IOException {
        if ((long) count * minimumBytes > in.available()) throw new EOFException();
    }

    private static void writeValue(DataOutputStream out, ScriptValue value) throws IOException {
        if (value == null || value.isNull()) { out.writeByte(0); return; }
        if (value instanceof PrimitiveValue.IntV v) { out.writeByte(1); out.writeInt(v.value()); return; }
        if (value instanceof PrimitiveValue.LongV v) { out.writeByte(2); out.writeLong(v.value()); return; }
        if (value instanceof PrimitiveValue.FloatV v) { out.writeByte(3); out.writeFloat(v.value()); return; }
        if (value instanceof PrimitiveValue.DoubleV v) { out.writeByte(4); out.writeDouble(v.value()); return; }
        if (value instanceof PrimitiveValue.BooleanV v) { out.writeByte(5); out.writeBoolean(v.value()); return; }
        if (value instanceof PrimitiveValue.ByteV v) { out.writeByte(6); out.writeByte(v.value()); return; }
        if (value instanceof PrimitiveValue.CharV v) { out.writeByte(7); out.writeChar(v.value()); return; }
        if (value instanceof StringValue v) { out.writeByte(8); writeString(out, v.value()); return; }
        if (value instanceof ListValue v) {
            out.writeByte(9);
            out.writeInt(v.elements().size());
            for (ScriptValue element : v.elements()) writeValue(out, element);
            return;
        }
        if (value instanceof SetValue v) {
            out.writeByte(10);
            out.writeInt(v.elements().size());
            for (ScriptValue element : v.elements()) writeValue(out, element);
            return;
        }
        if (value instanceof MapValue v) {
            out.writeByte(11);
            out.writeInt(v.entries().size());
            for (var entry : v.entries().entrySet()) { writeValue(out, entry.getKey()); writeValue(out, entry.getValue()); }
            return;
        }
        throw new IllegalArgumentException("Unsupported field initializer value: " + value.getClass().getSimpleName());
    }

    private static ScriptValue readValue(DataInputStream in, int depth) throws IOException {
        if (depth > 64) throw new IOException("Initializer nesting is too deep");
        return switch (in.readUnsignedByte()) {
            case 0 -> PrimitiveValue.nullValue();
            case 1 -> PrimitiveValue.of(in.readInt());
            case 2 -> PrimitiveValue.of(in.readLong());
            case 3 -> PrimitiveValue.of(in.readFloat());
            case 4 -> PrimitiveValue.of(in.readDouble());
            case 5 -> PrimitiveValue.of(in.readBoolean());
            case 6 -> PrimitiveValue.of(in.readByte());
            case 7 -> PrimitiveValue.of(in.readChar());
            case 8 -> new StringValue(readString(in, 2));
            case 9 -> {
                int count = readCount(in, 1_000_000);
                requireRemaining(in, count, 1);
                List<ScriptValue> values = new ArrayList<>(count);
                for (int i = 0; i < count; i++) values.add(readValue(in, depth + 1));
                yield new ListValue(values);
            }
            case 10 -> {
                int count = readCount(in, 1_000_000);
                requireRemaining(in, count, 1);
                Set<ScriptValue> values = new LinkedHashSet<>();
                for (int i = 0; i < count; i++) values.add(readValue(in, depth + 1));
                yield new SetValue(values);
            }
            case 11 -> {
                int count = readCount(in, 1_000_000);
                requireRemaining(in, count, 2);
                Map<ScriptValue, ScriptValue> values = new LinkedHashMap<>();
                for (int i = 0; i < count; i++) values.put(readValue(in, depth + 1), readValue(in, depth + 1));
                yield new MapValue(values);
            }
            default -> throw new IOException("Invalid initializer value tag");
        };
    }

    private static String emptyToNull(String value) { return value.isEmpty() ? null : value; }
}
