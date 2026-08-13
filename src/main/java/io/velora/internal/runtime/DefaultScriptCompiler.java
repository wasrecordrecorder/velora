package io.velora.internal.runtime;

import io.velora.api.compiler.*;
import io.velora.api.function.ApiRegistry;
import io.velora.api.registry.*;
import io.velora.internal.bytecode.*;
import io.velora.internal.ir.*;
import io.velora.internal.lexer.Lexer;
import io.velora.internal.lexer.LexerResult;
import io.velora.internal.parser.ParseResult;
import io.velora.internal.parser.Parser;
import io.velora.internal.semantic.ResolvedScript;
import io.velora.internal.semantic.SemanticAnalyzer;
import io.velora.internal.source.SourceHash;

import java.io.*;
import java.util.*;

public final class DefaultScriptCompiler implements ScriptCompiler {

    private final TypeRegistry typeRegistry;
    private final SettingRegistry settingRegistry;
    private final ApiRegistry apiRegistry;
    private final ConstantRegistry constantRegistry;
    private final PermissionRegistry permissionRegistry;
    private boolean frozen;

    public DefaultScriptCompiler(TypeRegistry typeRegistry, SettingRegistry settingRegistry,
                                 ApiRegistry apiRegistry, ConstantRegistry constantRegistry,
                                 PermissionRegistry permissionRegistry) {
        this.typeRegistry = typeRegistry;
        this.settingRegistry = settingRegistry;
        this.apiRegistry = apiRegistry;
        this.constantRegistry = constantRegistry;
        this.permissionRegistry = permissionRegistry;
    }

    @Override
    public CompileResult compile(CompileRequest request) {
        List<Diagnostic> diagnostics = new ArrayList<>();

        if (request.languageVersion() > 1) {
            diagnostics.add(Diagnostic.error(DiagnosticCode.COMPILER_UNSUPPORTED_VERSION,
                    "Unsupported language version: " + request.languageVersion(), SourceRange.of("main.vls", 0, 0)));
            return CompileResult.failure(request.scriptId(), diagnostics);
        }

        if (request.mode() == CompileMode.CACHE_ONLY) {
            return CompileResult.failure(request.scriptId(), List.of(
                    Diagnostic.error(DiagnosticCode.COMPILER_CACHE_MISS,
                            "No cached bytecode available", SourceRange.of("main.vls", 0, 0))));
        }

        for (SourceFile sf : request.sources()) {
            if (sf.relativePath().contains("..")) {
                diagnostics.add(Diagnostic.error(DiagnosticCode.COMPILER_PATH_TRAVERSAL,
                        "Path traversal not allowed: " + sf.relativePath(), SourceRange.of(sf.relativePath(), 0, 0)));
                return CompileResult.failure(request.scriptId(), diagnostics);
            }
        }

        StringBuilder combined = new StringBuilder();
        StringBuilder hashInput = new StringBuilder();
        String mainFileContent = null;
        String mainFileHash = null;
        List<String> helperContents = new ArrayList<>();
        for (SourceFile sf : request.sources()) {
            if (sf.relativePath().endsWith(".vls")) {
                hashInput.append(sf.contentHash());
                if (sf.content().contains("script ") || sf.content().contains("@Script")) {
                    if (mainFileContent == null) {
                        mainFileContent = sf.content();
                        mainFileHash = sf.contentHash();
                    } else {
                        diagnostics.add(Diagnostic.error(DiagnosticCode.COMPILER_MULTIPLE_SCRIPTS,
                                "Multiple script declarations across files", SourceRange.of(sf.relativePath(), 0, 0)));
                        return CompileResult.failure(request.scriptId(), diagnostics);
                    }
                } else {
                    helperContents.add(sf.content());
                }
            }
        }
        if (mainFileContent == null && !request.sources().isEmpty()) {
            diagnostics.add(Diagnostic.error(DiagnosticCode.COMPILER_NO_SCRIPT,
                    "No script declaration found", SourceRange.of("main.vls", 0, 0)));
            return CompileResult.failure(request.scriptId(), diagnostics);
        }
        if (mainFileContent == null) {
            diagnostics.add(Diagnostic.error(DiagnosticCode.COMPILER_NO_SCRIPT,
                    "No .vls source files provided", SourceRange.of("main.vls", 0, 0)));
            return CompileResult.failure(request.scriptId(), diagnostics);
        }
        if (helperContents.isEmpty()) {
            combined.append(mainFileContent);
        } else {
            int lastBrace = mainFileContent.lastIndexOf('}');
            if (lastBrace >= 0) {
                combined.append(mainFileContent, 0, lastBrace);
                for (String helper : helperContents) {
                    combined.append("\n").append(helper).append("\n");
                }
                combined.append("}");
            } else {
                combined.append(mainFileContent);
                for (String helper : helperContents) {
                    combined.append("\n").append(helper);
                }
            }
        }
        String mainSource = combined.toString();
        String sourceHash = hashInput.toString();

        LexerResult lexerResult = new Lexer(mainSource, "main.vls").lex();
        diagnostics.addAll(lexerResult.diagnostics());
        if (hasErrors(diagnostics)) {
            return CompileResult.failure(request.scriptId(), diagnostics);
        }

        ParseResult parseResult = Parser.parse(mainSource, "main.vls");
        diagnostics.addAll(parseResult.diagnostics());
        if (hasErrors(diagnostics) || parseResult.scriptNode() == null) {
            return CompileResult.failure(request.scriptId(), diagnostics);
        }

        SemanticAnalyzer analyzer = new SemanticAnalyzer(
                typeRegistry, settingRegistry, apiRegistry, constantRegistry, permissionRegistry);
        ResolvedScript resolved = analyzer.analyze(parseResult.scriptNode());
        diagnostics.addAll(analyzer.diagnostics());
        if (hasErrors(diagnostics)) {
            return CompileResult.failure(request.scriptId(), diagnostics);
        }

        IrBuilder irBuilder = new IrBuilder(resolved, apiRegistry);
        IrModule irModule = irBuilder.build();

        IrVerifier irVerifier = new IrVerifier();
        diagnostics.addAll(irVerifier.verify(irModule));
        if (hasErrors(diagnostics)) {
            return CompileResult.failure(request.scriptId(), diagnostics);
        }

        BytecodeWriter writer = new BytecodeWriter();
        CompiledModule compiledModule = writer.write(irModule);

        BytecodeVerifier bcVerifier = new BytecodeVerifier();
        diagnostics.addAll(bcVerifier.verify(compiledModule));
        if (hasErrors(diagnostics)) {
            return CompileResult.failure(request.scriptId(), diagnostics);
        }

        String registryHash = computeRegistryHash();
        byte[] bytecode = serializeBytecode(compiledModule);

        return new CompileResult(true, request.scriptId(), diagnostics, bytecode, registryHash, sourceHash);
    }

    public CompiledModule compileToModule(CompileRequest request) {
        List<Diagnostic> diagnostics = new ArrayList<>();

        if (request.languageVersion() > 1) {
            return null;
        }

        if (request.mode() == CompileMode.CACHE_ONLY) {
            return null;
        }

        for (SourceFile sf : request.sources()) {
            if (sf.relativePath().contains("..")) {
                return null;
            }
        }

        StringBuilder combined = new StringBuilder();
        String mainFileContent = null;
        List<String> helperContents = new ArrayList<>();
        for (SourceFile sf : request.sources()) {
            if (sf.relativePath().endsWith(".vls")) {
                if (sf.content().contains("script ") || sf.content().contains("@Script")) {
                    if (mainFileContent == null) {
                        mainFileContent = sf.content();
                    } else {
                        return null;
                    }
                } else {
                    helperContents.add(sf.content());
                }
            }
        }
        if (mainFileContent == null && !request.sources().isEmpty()) {
            mainFileContent = request.sources().get(0).content();
        }
        if (mainFileContent == null) return null;
        if (helperContents.isEmpty()) {
            combined.append(mainFileContent);
        } else {
            int lastBrace = mainFileContent.lastIndexOf('}');
            if (lastBrace >= 0) {
                combined.append(mainFileContent, 0, lastBrace);
                for (String helper : helperContents) {
                    combined.append("\n").append(helper).append("\n");
                }
                combined.append("}");
            } else {
                combined.append(mainFileContent);
                for (String helper : helperContents) {
                    combined.append("\n").append(helper);
                }
            }
        }
        String mainSource = combined.toString();

        LexerResult lexerResult = new Lexer(mainSource, "main.vls").lex();
        diagnostics.addAll(lexerResult.diagnostics());
        if (hasErrors(diagnostics)) return null;

        ParseResult parseResult = Parser.parse(mainSource, "main.vls");
        diagnostics.addAll(parseResult.diagnostics());
        if (hasErrors(diagnostics) || parseResult.scriptNode() == null) return null;

        SemanticAnalyzer analyzer = new SemanticAnalyzer(
                typeRegistry, settingRegistry, apiRegistry, constantRegistry, permissionRegistry);
        ResolvedScript resolved = analyzer.analyze(parseResult.scriptNode());
        diagnostics.addAll(analyzer.diagnostics());
        if (hasErrors(diagnostics)) return null;

        IrBuilder irBuilder = new IrBuilder(resolved, apiRegistry);
        IrModule irModule = irBuilder.build();

        IrVerifier irVerifier = new IrVerifier();
        diagnostics.addAll(irVerifier.verify(irModule));
        if (hasErrors(diagnostics)) return null;

        BytecodeWriter writer = new BytecodeWriter();
        String sourceHash = io.velora.internal.source.SourceHash.compute(mainSource);
        return writer.write(irModule, sourceHash);
    }

    @Override
    public boolean isFrozen() { return frozen; }

    void freeze() { frozen = true; }

    private boolean hasErrors(List<Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(Diagnostic::isError);
    }

    private String computeRegistryHash() {
        return io.velora.internal.source.SourceHash.compute(
                typeRegistry.all().toString() + apiRegistry.all().toString());
    }

    private byte[] serializeBytecode(CompiledModule module) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeUTF("VLCB");
            out.writeInt(1);
            out.writeUTF(module.scriptId());
            out.writeUTF(module.scriptName());
            out.writeUTF(module.version());
            out.writeInt(module.languageVersion());
            out.writeUTF(module.sourceHash());
            out.writeUTF(module.registryHash());

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
                    case STRING -> out.writeUTF(pool.stringValue(i));
                    case BOOLEAN -> out.writeBoolean(pool.booleanValue(i));
                    case DURATION -> out.writeLong(pool.durationNanos(i));
                    case NULL -> {}
                }
            }

            out.writeInt(module.functions().size());
            for (CompiledFunction fn : module.functions()) {
                out.writeUTF(fn.name());
                out.writeInt(fn.index());
                out.writeInt(fn.parameterCount());
                out.writeInt(fn.localCount());
                out.writeInt(fn.maxStack());
                out.writeBoolean(fn.suspending());
                out.writeBoolean(fn.isLifecycle());
                out.writeInt(fn.code().length);
                for (int c : fn.code()) out.writeInt(c);
                out.writeInt(fn.lineNumbers().length);
                for (int l : fn.lineNumbers()) out.writeInt(l);
            }

            out.writeInt(module.persistentFieldIds().size());
            for (String id : module.persistentFieldIds()) out.writeUTF(id);
            out.writeInt(module.persistentFieldTypes().size());
            for (String t : module.persistentFieldTypes()) out.writeUTF(t);
            out.writeInt(module.persistentFieldIndices().size());
            for (int idx : module.persistentFieldIndices()) out.writeInt(idx);
            out.writeInt(module.persistentFieldIsStatic().size());
            for (boolean isStatic : module.persistentFieldIsStatic()) out.writeBoolean(isStatic);

            out.writeInt(module.lifecycleHooks().size());
            for (String hook : module.lifecycleHooks()) out.writeUTF(hook);

            out.writeInt(module.eventHandlers().size());
            for (CompiledModule.EventHandlerInfo eh : module.eventHandlers()) {
                out.writeUTF(eh.eventReference());
                out.writeUTF(eh.functionName());
                out.writeInt(eh.functionIndex());
                out.writeBoolean(eh.suspending());
            }

            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    public static CompiledModule deserializeBytecode(byte[] data,
                                                      List<io.velora.api.setting.SettingDescriptor> settings,
                                                      io.velora.api.permission.PermissionSet requiredPermissions,
                                                      io.velora.api.permission.PermissionSet maximumPermissions) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            String magic = in.readUTF();
            if (!magic.equals("VLCB")) throw new IOException("Invalid magic");
            in.readInt();
            String scriptId = in.readUTF();
            String scriptName = in.readUTF();
            String version = in.readUTF();
            int languageVersion = in.readInt();
            String sourceHash = in.readUTF();
            String registryHash = in.readUTF();

            int poolSize = in.readInt();
            ConstantPool pool = new ConstantPool();
            for (int i = 0; i < poolSize; i++) {
                int tagOrd = in.readInt();
                ConstantPool.Tag tag = ConstantPool.Tag.values()[tagOrd];
                switch (tag) {
                    case INT -> pool.addInt(in.readInt());
                    case LONG -> pool.addLong(in.readLong());
                    case FLOAT -> pool.addFloat(in.readFloat());
                    case DOUBLE -> pool.addDouble(in.readDouble());
                    case STRING -> pool.addString(in.readUTF());
                    case BOOLEAN -> pool.addBoolean(in.readBoolean());
                    case DURATION -> pool.addDuration(in.readLong());
                    case NULL -> pool.addNull();
                }
            }

            int fnCount = in.readInt();
            List<CompiledFunction> functions = new ArrayList<>();
            for (int i = 0; i < fnCount; i++) {
                String name = in.readUTF();
                int index = in.readInt();
                int paramCount = in.readInt();
                int localCount = in.readInt();
                int maxStack = in.readInt();
                boolean suspending = in.readBoolean();
                boolean isLifecycle = in.readBoolean();
                int codeLen = in.readInt();
                int[] code = new int[codeLen];
                for (int j = 0; j < codeLen; j++) code[j] = in.readInt();
                int lineLen = in.readInt();
                int[] lineNumbers = new int[lineLen];
                for (int j = 0; j < lineLen; j++) lineNumbers[j] = in.readInt();
                functions.add(new CompiledFunction(name, index, paramCount, localCount, maxStack,
                        suspending, isLifecycle, code, lineNumbers));
            }

            int pfIdCount = in.readInt();
            List<String> persistentFieldIds = new ArrayList<>();
            for (int i = 0; i < pfIdCount; i++) persistentFieldIds.add(in.readUTF());
            int pfTypeCount = in.readInt();
            List<String> persistentFieldTypes = new ArrayList<>();
            for (int i = 0; i < pfTypeCount; i++) persistentFieldTypes.add(in.readUTF());
            int pfIdxCount = in.readInt();
            List<Integer> persistentFieldIndices = new ArrayList<>();
            for (int i = 0; i < pfIdxCount; i++) persistentFieldIndices.add(in.readInt());
            int pfStaticCount = in.readInt();
            List<Boolean> persistentFieldIsStatic = new ArrayList<>();
            for (int i = 0; i < pfStaticCount; i++) persistentFieldIsStatic.add(in.readBoolean());

            int hookCount = in.readInt();
            List<String> lifecycleHooks = new ArrayList<>();
            for (int i = 0; i < hookCount; i++) lifecycleHooks.add(in.readUTF());

            int ehCount = in.readInt();
            List<CompiledModule.EventHandlerInfo> eventHandlers = new ArrayList<>();
            for (int i = 0; i < ehCount; i++) {
                String eventRef = in.readUTF();
                String funcName = in.readUTF();
                int funcIdx = in.readInt();
                boolean susp = in.readBoolean();
                eventHandlers.add(new CompiledModule.EventHandlerInfo(eventRef, funcName, funcIdx, susp));
            }

            return new CompiledModule(scriptId, scriptName, version, languageVersion,
                    sourceHash, registryHash, pool, functions, settings,
                    persistentFieldIds, persistentFieldTypes,
                    persistentFieldIndices, persistentFieldIsStatic,
                    requiredPermissions, maximumPermissions,
                    lifecycleHooks, eventHandlers);
        } catch (IOException e) {
            return null;
        }
    }
}
