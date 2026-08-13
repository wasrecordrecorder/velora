package io.velora.api.compiler;

public interface ScriptCompiler {

    CompileResult compile(CompileRequest request);

    boolean isFrozen();
}
