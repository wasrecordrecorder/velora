package io.velora;

import io.velora.cli.VeloraCli;
import io.velora.internal.compiler.DefaultScriptCompiler;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CliV2Test {
    @Test
    void checkCompileAndRunUseTheNormalCompilerPipeline() throws Exception {
        Path root = Files.createTempDirectory("velora-cli-test-");
        Path source = root.resolve("main.vls");
        Files.writeString(source, "@Script(\"Cli\")\n@Version(\"1\")\nscript Cli { @Run run() { console.print(\"cli-ok\") } }");
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        assertEquals(0, VeloraCli.run(new String[]{"check", source.toString()}, out, err));
        assertTrue(outBytes.toString(StandardCharsets.UTF_8).contains("OK"));
        Path bytecode = root.resolve("cli.vlcb");
        assertEquals(0, VeloraCli.run(new String[]{"compile", source.toString(), "-o", bytecode.toString()}, out, err));
        assertTrue(Files.size(bytecode) > 0);
        assertNotNull(DefaultScriptCompiler.deserializeBytecode(Files.readAllBytes(bytecode), List.of()));
        outBytes.reset();
        errBytes.reset();
        assertEquals(0, VeloraCli.run(new String[]{"run", source.toString(), "--timeout", "1000"}, out, err));
        assertTrue(outBytes.toString(StandardCharsets.UTF_8).contains("cli-ok"));
        assertEquals("", errBytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void cliRuntimeErrorsKeepSourceLines() throws Exception {
        Path root = Files.createTempDirectory("velora-cli-runtime-");
        Path source = root.resolve("main.vls");
        Files.writeString(source, "@Script(\"RuntimeError\")\n@Version(\"1\")\nscript RuntimeError {\n    @Run run() {\n        int zero = 0\n        int value = 1 / zero\n    }\n}");
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        assertEquals(1, VeloraCli.run(new String[]{"run", source.toString(), "--timeout", "1000"}, out, err));
        String error = errBytes.toString(StandardCharsets.UTF_8);
        assertTrue(error.contains("RUNTIME_DIVISION_BY_ZERO"), error);
        assertTrue(error.contains("at line 6"), error);
    }

    @Test
    void cliReturnsDistinctUsageAndCompilationFailures() throws Exception {
        Path root = Files.createTempDirectory("velora-cli-errors-");
        Path source = root.resolve("main.vls");
        Files.writeString(source, "@Script(\"Broken\")\nscript Broken { int answer() { return \"bad\" } }");
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        assertEquals(1, VeloraCli.run(new String[]{"check", source.toString()}, out, err));
        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("error"));
        errBytes.reset();
        assertEquals(2, VeloraCli.run(new String[]{"check", source.toString(), "--unknown"}, out, err));
        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("Unknown option"));
    }
    @Test
    void cliRejectsCommandSpecificOptionsEvenWhenTheyUseDefaultValues() throws Exception {
        Path root = Files.createTempDirectory("velora-cli-options-");
        Path source = root.resolve("main.vls");
        Files.writeString(source, "@Script(\"Options\")\nscript Options { }");
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        assertEquals(2, VeloraCli.run(new String[]{"check", source.toString(), "--timeout", "30000"}, out, err));
        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("Timeout is only valid for run"));
    }

    @Test
    void cliRejectsMalformedAndSymbolicLinkInputs() throws Exception {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        assertEquals(2, VeloraCli.run(new String[]{"check", "bad\0path"}, out, err));
        Path root = Files.createTempDirectory("velora-cli-symlink-");
        Path source = root.resolve("main.vls");
        Path link = root.resolve("link.vls");
        Files.writeString(source, "@Script(\"Link\")\n@Version(\"1\")\nscript Link { }");
        try {
            Files.createSymbolicLink(link, source);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException ignored) {
            return;
        }
        errBytes.reset();
        assertEquals(2, VeloraCli.run(new String[]{"check", link.toString()}, out, err));
        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("Symbolic link input is not allowed"));
    }

}
