package io.velora.cli;

import io.velora.api.Velora;
import io.velora.api.VeloraEngine;
import io.velora.api.compiler.CompileRequest;
import io.velora.api.compiler.CompileResult;
import io.velora.api.compiler.Diagnostic;
import io.velora.api.compiler.SourceFile;
import io.velora.api.script.ScriptCreateRequest;
import io.velora.host.MainThreadExecutor;
import io.velora.host.VeloraClock;
import io.velora.host.VeloraFileSystem;
import io.velora.host.VeloraHost;
import io.velora.host.VeloraLogger;
import io.velora.host.WorkerExecutor;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class VeloraCli {
    private VeloraCli() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args == null || args.length == 0 || isHelp(args[0])) {
            usage(out);
            return args == null || args.length == 0 ? 2 : 0;
        }
        if (args.length == 1 && (args[0].equals("--version") || args[0].equals("-v"))) {
            out.println(Velora.version());
            return 0;
        }
        String command = args[0].toLowerCase(Locale.ROOT);
        if (!command.equals("check") && !command.equals("compile") && !command.equals("run")) return usageError(err, "Unknown command: " + args[0]);
        if (args.length < 2) {
            usage(err);
            return 2;
        }
        if (isHelp(args[1])) {
            usage(out);
            return 0;
        }

        try {
            Path input = Path.of(args[1]).toAbsolutePath().normalize();
            List<Path> javaImports = new ArrayList<>();
            Path output = null;
            long timeoutMillis = 30_000;
            boolean timeoutSpecified = false;
            for (int i = 2; i < args.length; i++) {
                switch (args[i]) {
                    case "--java" -> {
                        if (++i >= args.length) return usageError(err, "Missing path after --java");
                        javaImports.add(Path.of(args[i]).toAbsolutePath().normalize());
                    }
                    case "-o", "--output" -> {
                        if (++i >= args.length) return usageError(err, "Missing path after " + args[i - 1]);
                        output = Path.of(args[i]).toAbsolutePath().normalize();
                    }
                    case "--timeout" -> {
                        timeoutSpecified = true;
                        if (++i >= args.length) return usageError(err, "Missing milliseconds after --timeout");
                        try {
                            timeoutMillis = Long.parseLong(args[i]);
                        } catch (NumberFormatException error) {
                            return usageError(err, "Invalid timeout: " + args[i]);
                        }
                        if (timeoutMillis <= 0) return usageError(err, "Timeout must be positive");
                    }
                    default -> {
                        return usageError(err, "Unknown option: " + args[i]);
                    }
                }
            }
            if (!command.equals("compile") && output != null) return usageError(err, "Output is only valid for compile");
            if (!command.equals("run") && timeoutSpecified) return usageError(err, "Timeout is only valid for run");

            List<SourceFile> sources = sources(input);
            if (sources.isEmpty()) {
                err.println("No .vls files found in " + input);
                return 2;
            }
            try (CliHost host = new CliHost(out, err); VeloraEngine engine = Velora.builder().host(host).build()) {
                for (Path javaImport : javaImports) engine.javaImports().register(javaImport);
                engine.freeze();
                return switch (command) {
                    case "check" -> check(engine, sources, out, err);
                    case "compile" -> compile(engine, input, sources, output, out, err);
                    case "run" -> runScript(engine, sources, timeoutMillis, err);
                    default -> usageError(err, "Unknown command: " + command);
                };
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            err.println("Execution interrupted");
            return 2;
        } catch (IOException | RuntimeException error) {
            err.println(error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName());
            return 2;
        }
    }

    private static int check(VeloraEngine engine, List<SourceFile> sources, PrintStream out, PrintStream err) {
        CompileResult result = engine.compiler().compile(CompileRequest.builder("cli").sources(sources).build());
        printDiagnostics(result, out, err);
        if (result.success()) out.println("OK");
        return result.success() ? 0 : 1;
    }

    private static int compile(VeloraEngine engine, Path input, List<SourceFile> sources, Path output, PrintStream out, PrintStream err) throws IOException {
        CompileResult result = engine.compiler().compile(CompileRequest.builder("cli").sources(sources).build());
        printDiagnostics(result, out, err);
        if (!result.success()) return 1;
        Path target = output != null ? output : defaultOutput(input);
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        writeAtomic(target, result.bytecode());
        out.println(target);
        return 0;
    }

    private static int runScript(VeloraEngine engine, List<SourceFile> sources, long timeoutMillis, PrintStream err) throws InterruptedException {
        ScriptCreateRequest.Builder request = ScriptCreateRequest.builder("cli", "CLI");
        for (SourceFile source : sources) request.file(source.relativePath(), source.content());
        var created = engine.scripts().create(request.build());
        if (!created.success()) {
            err.println(created.message());
            for (Diagnostic diagnostic : engine.scripts().diagnostics("cli")) err.println(format(diagnostic));
            return 1;
        }
        var enabled = engine.scripts().enable("cli");
        if (!enabled.success()) {
            err.println(enabled.message());
            return 1;
        }
        long started = System.nanoTime();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        do {
            engine.tick();
            if (engine.debug().fibers("cli").isEmpty()) break;
            if (System.nanoTime() - started >= timeoutNanos) {
                err.println("Execution timed out after " + timeoutMillis + " ms");
                return 1;
            }
            Thread.sleep(1);
        } while (true);
        var errors = engine.debug().errors("cli");
        for (var error : errors) err.println(error.errorType() + (error.line() > 0 ? " at line " + error.line() : "") + ": " + error.message());
        return errors.isEmpty() ? 0 : 1;
    }

    private static List<SourceFile> sources(Path input) throws IOException {
        if (Files.isSymbolicLink(input)) throw new IllegalArgumentException("Symbolic link input is not allowed: " + input);
        if (Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) {
            if (!input.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".vls")) return List.of();
            return List.of(SourceFile.of(input.getFileName().toString(), Files.readString(input, StandardCharsets.UTF_8)));
        }
        if (!Files.isDirectory(input)) throw new IllegalArgumentException("Input does not exist: " + input);
        try (var stream = Files.walk(input)) {
            return stream.filter(path -> Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".vls"))
                    .sorted(Comparator.comparing(Path::toString))
                    .map(path -> {
                        try {
                            return SourceFile.of(input.relativize(path).toString().replace('\\', '/'), Files.readString(path, StandardCharsets.UTF_8));
                        } catch (IOException error) {
                            throw new UncheckedIOException(error);
                        }
                    })
                    .toList();
        } catch (UncheckedIOException error) {
            throw error.getCause();
        }
    }

    private static void writeAtomic(Path target, byte[] data) throws IOException {
        Path parent = target.getParent();
        Path directory = parent != null ? parent : Path.of(".").toAbsolutePath().normalize();
        Path temp = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, data);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Path defaultOutput(Path input) {
        String name = input.getFileName().toString();
        if (Files.isRegularFile(input)) {
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
        }
        Path parent = input.getParent();
        return (parent != null ? parent : Path.of(".")).resolve(name + ".vlcb").toAbsolutePath().normalize();
    }

    private static void printDiagnostics(CompileResult result, PrintStream out, PrintStream err) {
        for (Diagnostic diagnostic : result.diagnostics()) (diagnostic.isError() ? err : out).println(format(diagnostic));
    }

    private static String format(Diagnostic diagnostic) {
        String range = diagnostic.range() != null ? diagnostic.range().format() + ": " : "";
        return range + diagnostic.severity().name().toLowerCase(Locale.ROOT) + " " + diagnostic.code() + ": " + diagnostic.message();
    }

    private static boolean isHelp(String value) {
        return value.equals("help") || value.equals("--help") || value.equals("-h");
    }

    private static int usageError(PrintStream err, String message) {
        err.println(message);
        usage(err);
        return 2;
    }

    private static void usage(PrintStream out) {
        out.println("Velora " + Velora.version());
        out.println("  velora check <file|dir> [--java <class-file|jar|dir>]...");
        out.println("  velora compile <file|dir> [-o <file>] [--java <class-file|jar|dir>]...");
        out.println("  velora run <file|dir> [--timeout <ms>] [--java <class-file|jar|dir>]...");
        out.println("  velora --version");
    }

    private static final class CliHost implements VeloraHost, AutoCloseable {
        private final Path root;
        private final VeloraFileSystem fileSystem;
        private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        private final PrintStream out;
        private final PrintStream err;

        private CliHost(PrintStream out, PrintStream err) throws IOException {
            this.out = out;
            this.err = err;
            this.root = Files.createTempDirectory("velora-cli-");
            this.fileSystem = VeloraFileSystem.local(root);
        }

        @Override public String id() { return "velora-cli"; }
        @Override public String version() { return Velora.version(); }
        @Override public MainThreadExecutor mainThread() { return new MainThreadExecutor() {
            @Override public boolean isMainThread() { return true; }
            @Override public void execute(Runnable action) { action.run(); }
        }; }
        @Override public WorkerExecutor workers() { return new WorkerExecutor() {
            @Override public void execute(Runnable action) { workers.execute(action); }
            @Override public void shutdown() { workers.shutdownNow(); }
        }; }
        @Override public VeloraClock clock() { return new VeloraClock() {
            @Override public long nanoTime() { return System.nanoTime(); }
            @Override public long currentTimeMillis() { return System.currentTimeMillis(); }
        }; }
        @Override public VeloraLogger logger() { return new VeloraLogger() {
            @Override public void debug(String message) { out.println(message); }
            @Override public void info(String message) { out.println(message); }
            @Override public void warn(String message) { err.println(message); }
            @Override public void error(String message, Throwable throwable) { err.println(message); }
        }; }
        @Override public VeloraFileSystem fileSystem() { return fileSystem; }

        @Override
        public void close() {
            workers.shutdownNow();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                }
            } catch (IOException ignored) { }
        }
    }
}
