package io.velora;

import io.velora.api.Velora;
import io.velora.api.VeloraEngine;
import io.velora.api.VeloraLimits;
import io.velora.api.script.ScriptCreateRequest;
import io.velora.api.script.ScriptTransaction;
import io.velora.api.script.ScriptTransactionResult;
import io.velora.host.FileRevision;
import io.velora.host.FileTransaction;
import io.velora.host.MainThreadExecutor;
import io.velora.host.ScriptFileEntry;
import io.velora.host.SourceSnapshot;
import io.velora.host.VeloraClock;
import io.velora.host.VeloraFileSystem;
import io.velora.host.VeloraHost;
import io.velora.host.VeloraLogger;
import io.velora.host.WorkerExecutor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class ScriptWorkflowV2Test {
    @Test
    void multiFileDiscoveryAndReloadTransactionAreProjectWide() {
        MemoryFileSystem fs = new MemoryFileSystem();
        fs.put("multi", "main.vls", "@Script(name=\"Multi\", version=\"1\")\nscript Multi { int answer() { return helper() } }");
        fs.put("multi", "helper.vls", "int helper() { return 1 }");
        VeloraEngine engine = Velora.builder().host(host(fs)).build();
        engine.freeze();
        engine.scripts().discover();

        var before = engine.scripts().list().stream().filter(s -> s.id().equals("multi")).findFirst().orElseThrow();
        assertEquals(List.of("helper.vls", "main.vls"), before.sourceFiles());
        assertEquals(1L, before.activeRevision().revisionNumber());

        String helperRevision = fs.readSource("multi", "helper.vls").revision().revisionHash();
        ScriptTransactionResult result = engine.scripts().beginTransaction("multi")
                .expectRevision(1)
                .write("helper.vls", "int helper() { return 2 }", helperRevision)
                .validateAndCommit(ScriptTransaction.CommitMode.RELOAD_IF_VALID);

        assertTrue(result.success(), result.message());
        assertEquals("int helper() { return 2 }", fs.readSource("multi", "helper.vls").content());
        var after = engine.scripts().list().stream().filter(s -> s.id().equals("multi")).findFirst().orElseThrow();
        assertEquals(2L, after.activeRevision().revisionNumber());
        engine.close();
    }

    @Test
    void synchronousLifecycleExecutionKeepsOtherScriptsRunnable() {
        MemoryFileSystem fs = new MemoryFileSystem();
        fs.put("first", "main.vls", "@Script(name=\"First\", version=\"1\")\nscript First { entry onRun() { console.print(\"first-run\") } }");
        fs.put("second", "main.vls", "@Script(name=\"Second\", version=\"1\")\nscript Second { entry onEnable() { console.print(\"second-enable\") } }");
        VeloraEngine engine = Velora.builder().host(host(fs)).build();
        engine.freeze();
        engine.scripts().discover();
        assertTrue(engine.scripts().enable("first").success());
        assertTrue(engine.scripts().enable("second").success());
        assertTrue(engine.debug().logs("first").stream().anyMatch(log -> log.message().equals("first-run")));
        assertTrue(engine.debug().logs("second").stream().anyMatch(log -> log.message().equals("second-enable")));
        assertTrue(engine.debug().errors("first").isEmpty());
        engine.close();
    }

    @Test
    void everyLifecycleHookExecutesInRuntimeOrder() {
        MemoryFileSystem fs = new MemoryFileSystem();
        fs.put("lifecycle", "main.vls", "@Script(name=\"Lifecycle\", version=\"1\")\nscript Lifecycle { entry onLoad() { console.print(\"load\") } entry onEnable() { console.print(\"enable\") } entry onRun() { console.print(\"run\") } entry onTick() { console.print(\"tick\") } entry onDisable() { console.print(\"disable\") } entry onUnload() { console.print(\"unload\") } }");
        VeloraEngine engine = Velora.builder().host(host(fs)).build();
        engine.freeze();
        engine.scripts().discover();
        assertTrue(engine.scripts().enable("lifecycle").success());
        engine.tick();
        List<String> beforeDisable = engine.debug().logs("lifecycle").stream().map(log -> log.message()).toList();
        assertTrue(beforeDisable.contains("load"));
        assertTrue(beforeDisable.contains("enable"));
        assertTrue(beforeDisable.contains("run"));
        assertTrue(beforeDisable.contains("tick"));
        assertTrue(engine.scripts().disable("lifecycle").success());
        assertTrue(engine.debug().logs("lifecycle").stream().anyMatch(log -> log.message().equals("disable")));
        assertTrue(engine.scripts().unload("lifecycle").success());
        assertTrue(engine.debug().logs("lifecycle").stream().anyMatch(log -> log.message().equals("unload")));
        engine.close();
    }

    @Test
    void enableFailsCleanlyWhenOnRunCannotBeScheduled() {
        MemoryFileSystem fs = new MemoryFileSystem();
        VeloraEngine engine = Velora.builder().host(host(fs)).limits(VeloraLimits.builder().memoryPerScript(64).build()).build();
        engine.freeze();
        String source = "@Script(name=\"Limited\", version=\"1\")\nscript Limited { entry onRun() { console.print(\"never\") } }";
        assertTrue(engine.scripts().create(ScriptCreateRequest.builder("limited", "Limited").file("main.vls", source).build()).success());
        var result = engine.scripts().enable("limited");
        assertFalse(result.success());
        assertEquals(io.velora.api.script.ScriptStatus.FAILED, engine.scripts().status("limited"));
        assertTrue(engine.debug().logs("limited").isEmpty());
        engine.close();
    }

    @Test
    void asyncOnTickDoesNotOverlapItself() throws Exception {
        MemoryFileSystem fs = new MemoryFileSystem();
        String source = "@Script(name=\"TickSerial\", version=\"1\")\nscript TickSerial { async entry onTick() { delay(20.milliseconds)\n console.print(\"tick-finished\") } }";
        fs.put("tick-serial", "main.vls", source);
        VeloraEngine engine = Velora.builder().host(host(fs)).build();
        engine.freeze();
        engine.scripts().discover();
        assertTrue(engine.scripts().enable("tick-serial").success());
        engine.tick();
        engine.tick();
        Thread.sleep(25);
        engine.tick();
        long completed = engine.debug().logs("tick-serial").stream().filter(log -> log.message().equals("tick-finished")).count();
        assertEquals(1L, completed);
        engine.close();
    }

    @Test
    void localStorageSupportsCreateEditRediscoverAndDelete() throws Exception {
        Path root = Files.createTempDirectory("velora-storage-test-");
        VeloraFileSystem fs = VeloraFileSystem.local(root);
        VeloraEngine first = Velora.builder().host(host(fs)).build();
        first.freeze();
        String source = "@Script(name=\"Disk\", version=\"1\")\nscript Disk { int value() { return 41 } }";
        assertTrue(first.scripts().create(ScriptCreateRequest.builder("disk", "Disk").file("main.vls", source).build()).success());
        SourceSnapshot before = fs.readSource("disk", "main.vls");
        assertNotNull(before);
        ScriptTransactionResult update = first.scripts().beginTransaction("disk")
                .write("main.vls", source.replace("41", "42"), before.revision().revisionHash())
                .validateAndCommit(ScriptTransaction.CommitMode.RELOAD_IF_VALID);
        assertTrue(update.success(), update.message());
        assertTrue(fs.readSource("disk", "main.vls").content().contains("42"));
        first.close();

        VeloraEngine second = Velora.builder().host(host(fs)).build();
        second.freeze();
        second.scripts().discover();
        assertTrue(second.scripts().find("disk").isPresent());
        assertTrue(second.scripts().delete("disk").success());
        assertFalse(fs.scriptExists("disk"));
        second.close();
    }

    @Test
    void clientServiceEventsReflectCompilePermissionsAndConsoleChanges() {
        MemoryFileSystem fs = new MemoryFileSystem();
        VeloraEngine engine = Velora.builder().host(host(fs)).build();
        engine.freeze();
        List<io.velora.api.script.ScriptServiceEvents.Type> events = new ArrayList<>();
        engine.scripts().events().subscribe(event -> events.add(event.type()));
        var created = engine.scripts().create(ScriptCreateRequest.builder("events", "Events")
                .file("main.vls", "@Script(name=\"Events\", version=\"1\")\nscript Events { entry onRun() { console.print(\"service-log\") } }")
                .build());
        assertTrue(created.success(), created.message());
        var localStorage = engine.permissions().find("LOCAL_STORAGE");
        engine.scripts().grantPermissions("events", io.velora.api.permission.PermissionSet.of(localStorage));
        assertTrue(engine.scripts().enable("events").success());
        engine.tick();
        assertTrue(events.contains(io.velora.api.script.ScriptServiceEvents.Type.COMPILE_STARTED));
        assertTrue(events.contains(io.velora.api.script.ScriptServiceEvents.Type.COMPILE_FINISHED));
        assertTrue(events.contains(io.velora.api.script.ScriptServiceEvents.Type.CREATED));
        assertTrue(events.contains(io.velora.api.script.ScriptServiceEvents.Type.PERMISSIONS_CHANGED));
        assertTrue(events.contains(io.velora.api.script.ScriptServiceEvents.Type.LOG_ADDED));
        engine.close();
    }

    @Test
    void clientCanCreateDiskScriptFromTemplateWithInitialSettings() {
        MemoryFileSystem fs = new MemoryFileSystem();
        VeloraEngine engine = Velora.builder().host(host(fs)).build();
        engine.extensions().register(new io.velora.api.VeloraExtension() {
            @Override public String id() { return "template-test"; }
            @Override public String version() { return "1"; }
            @Override public void register(io.velora.api.VeloraExtensionContext context) {
                context.templates().register(io.velora.api.script.ScriptTemplate.builder("base")
                        .name("Base")
                        .file("main.vls", "@Script(name=\"Templated\", version=\"1\")\nscript Templated { settings { @Number amount (\"Amount\", 0..100, 1, 10, @Number.Slider) } }")
                        .build());
            }
        });
        engine.freeze();
        var created = engine.scripts().create(ScriptCreateRequest.builder("templated", "Templated")
                .template("base")
                .setting("amount", 42)
                .build());
        assertTrue(created.success(), created.message());
        assertNotNull(fs.readSource("templated", "main.vls"));
        assertEquals(42, ((Number) engine.scripts().settingValues("templated").get("amount").value()).intValue());
        engine.close();
    }

    @Test
    void clientsCanReadUpdateAndReloadCurrentSettingValues() {
        MemoryFileSystem fs = new MemoryFileSystem();
        fs.put("settings", "main.vls", "@Script(name=\"Settings\", version=\"1\")\nscript Settings { settings { @Number amount (\"Amount\", 0..100, 1, 10, @Number.Slider) } entry onRun() { console.print(\"amount=\" + amount) } }");
        VeloraEngine first = Velora.builder().host(host(fs)).build();
        first.freeze();
        first.scripts().discover();
        assertEquals(10, ((Number) first.scripts().settingValues("settings").get("amount").value()).intValue());
        var descriptor = first.scripts().settings("settings").find("amount").orElseThrow();
        ScriptTransactionResult updated = first.scripts().beginTransaction("settings")
                .updateSetting("amount", io.velora.api.setting.SettingValue.of(descriptor.type(), 25))
                .validateAndCommit(ScriptTransaction.CommitMode.COMMIT_WITHOUT_RELOAD);
        assertTrue(updated.success(), updated.message());
        assertEquals(25, ((Number) first.scripts().settingValues("settings").get("amount").value()).intValue());
        assertEquals(25, ((Number) first.scripts().find("settings").orElseThrow().settingValues().get("amount").value()).intValue());
        first.close();

        VeloraEngine second = Velora.builder().host(host(fs)).build();
        second.freeze();
        second.scripts().discover();
        assertEquals(25, ((Number) second.scripts().settingValues("settings").get("amount").value()).intValue());
        second.close();
    }

    @Test
    void invalidOrStaleTransactionsNeverMutateSources() {
        MemoryFileSystem fs = new MemoryFileSystem();
        fs.put("safe", "main.vls", "@Script(name=\"Safe\", version=\"1\")\nscript Safe { int answer() { return helper() } }");
        fs.put("safe", "helper.vls", "int helper() { return 1 }");
        VeloraEngine engine = Velora.builder().host(host(fs)).build();
        engine.freeze();
        engine.scripts().discover();

        String original = fs.readSource("safe", "helper.vls").content();
        ScriptTransactionResult invalid = engine.scripts().beginTransaction("safe")
                .write("helper.vls", "int helper() { return \"wrong\" }", fs.readSource("safe", "helper.vls").revision().revisionHash())
                .validateAndCommit(ScriptTransaction.CommitMode.RELOAD_IF_VALID);
        assertFalse(invalid.success());
        assertEquals(ScriptTransaction.ConflictReason.COMPILE_ERROR, invalid.conflictReason());
        assertEquals(original, fs.readSource("safe", "helper.vls").content());

        ScriptTransactionResult staleFile = engine.scripts().beginTransaction("safe")
                .write("helper.vls", "int helper() { return 3 }", "stale")
                .validateAndCommit(ScriptTransaction.CommitMode.COMMIT_WITHOUT_RELOAD);
        assertFalse(staleFile.success());
        assertEquals(ScriptTransaction.ConflictReason.SOURCE_REVISION_CONFLICT, staleFile.conflictReason());
        assertEquals(original, fs.readSource("safe", "helper.vls").content());

        ScriptTransactionResult staleScript = engine.scripts().beginTransaction("safe")
                .expectRevision(99)
                .write("helper.vls", "int helper() { return 4 }", fs.readSource("safe", "helper.vls").revision().revisionHash())
                .validateAndCommit(ScriptTransaction.CommitMode.COMMIT_WITHOUT_RELOAD);
        assertFalse(staleScript.success());
        assertEquals(ScriptTransaction.ConflictReason.SOURCE_REVISION_CONFLICT, staleScript.conflictReason());
        assertEquals(original, fs.readSource("safe", "helper.vls").content());
        engine.close();
    }

    @Test
    void transactionDeleteParticipatesInWholeProjectValidationAndPathsAreSafe() {
        MemoryFileSystem fs = new MemoryFileSystem();
        fs.put("delete", "main.vls", "@Script(name=\"Delete\", version=\"1\")\nscript Delete { int answer() { return helper() } }");
        fs.put("delete", "helper.vls", "int helper() { return 1 }");
        VeloraEngine engine = Velora.builder().host(host(fs)).build();
        engine.freeze();
        engine.scripts().discover();

        ScriptTransactionResult delete = engine.scripts().beginTransaction("delete")
                .delete("helper.vls")
                .validateAndCommit(ScriptTransaction.CommitMode.COMMIT_WITHOUT_RELOAD);
        assertFalse(delete.success());
        assertEquals(ScriptTransaction.ConflictReason.COMPILE_ERROR, delete.conflictReason());
        assertNotNull(fs.readSource("delete", "helper.vls"));
        assertThrows(IllegalArgumentException.class, () -> engine.scripts().beginTransaction("delete").write("../escape.vls", "", null));
        assertThrows(IllegalArgumentException.class, () -> engine.scripts().beginTransaction("delete").delete("C:\\escape.vls"));
        engine.close();
    }

    private VeloraHost host(VeloraFileSystem fs) {
        return new VeloraHost() {
            @Override public String id() { return "workflow-test"; }
            @Override public String version() { return "1"; }
            @Override public MainThreadExecutor mainThread() { return new MainThreadExecutor() {
                @Override public boolean isMainThread() { return true; }
                @Override public void execute(Runnable action) { action.run(); }
            }; }
            @Override public WorkerExecutor workers() { return new WorkerExecutor() {
                @Override public void execute(Runnable action) { action.run(); }
                @Override public void shutdown() {}
            }; }
            @Override public VeloraClock clock() { return new VeloraClock() {
                @Override public long nanoTime() { return System.nanoTime(); }
                @Override public long currentTimeMillis() { return System.currentTimeMillis(); }
            }; }
            @Override public VeloraLogger logger() { return new VeloraLogger() {
                @Override public void debug(String message) {}
                @Override public void info(String message) {}
                @Override public void warn(String message) {}
                @Override public void error(String message, Throwable error) {}
            }; }
            @Override public VeloraFileSystem fileSystem() { return fs; }
        };
    }

    private static final class MemoryFileSystem implements VeloraFileSystem {
        private final Map<String, Map<String, Entry>> sources = new LinkedHashMap<>();
        private final Map<String, Map<String, byte[]>> data = new LinkedHashMap<>();

        void put(String scriptId, String path, String content) {
            sources.computeIfAbsent(scriptId, ignored -> new LinkedHashMap<>()).put(path, new Entry(content, 1));
        }

        @Override public List<ScriptFileEntry> listScripts() {
            List<ScriptFileEntry> result = new ArrayList<>();
            sources.forEach((scriptId, files) -> files.forEach((path, entry) ->
                    result.add(new ScriptFileEntry(scriptId, path, entry.content.length(), entry.revision))));
            return result;
        }

        @Override public SourceSnapshot readSource(String scriptId, String relativePath) {
            Entry entry = sources.getOrDefault(scriptId, Map.of()).get(relativePath);
            if (entry == null) return null;
            String hash = hash(entry.content, entry.revision);
            return new SourceSnapshot(entry.content, new FileRevision(scriptId, relativePath, hash, entry.revision), hash, entry.revision);
        }

        @Override public FileRevision writeAtomic(String scriptId, String relativePath, String content, FileRevision expectedRevision) {
            Tx tx = new Tx(scriptId);
            tx.write(relativePath, content, expectedRevision);
            if (!tx.commit()) throw new IllegalStateException("Revision conflict");
            return readSource(scriptId, relativePath).revision();
        }

        @Override public FileTransaction beginTransaction(String scriptId) { return new Tx(scriptId); }

        @Override public byte[] readData(String scriptId, String key) {
            byte[] value = data.getOrDefault(scriptId, Map.of()).get(key);
            return value != null ? Arrays.copyOf(value, value.length) : null;
        }

        @Override public void writeDataAtomic(String scriptId, String key, byte[] value) {
            data.computeIfAbsent(scriptId, ignored -> new LinkedHashMap<>()).put(key, Arrays.copyOf(value, value.length));
        }

        @Override public boolean scriptExists(String scriptId) { return sources.containsKey(scriptId); }
        @Override public void deleteScript(String scriptId) { sources.remove(scriptId); data.remove(scriptId); }

        private static String hash(String content, long revision) { return Integer.toHexString(content.hashCode()) + '-' + revision; }

        private record Entry(String content, long revision) {}

        private final class Tx implements FileTransaction {
            private final String scriptId;
            private final Map<String, Write> writes = new LinkedHashMap<>();
            private final Map<String, FileRevision> checks = new LinkedHashMap<>();
            private final List<String> deletes = new ArrayList<>();
            private boolean committed;

            Tx(String scriptId) { this.scriptId = scriptId; }
            @Override public String scriptId() { return scriptId; }
            @Override public FileTransaction write(String path, String content, FileRevision expected) {
                writes.put(path, new Write(content, expected));
                return this;
            }
            @Override public FileTransaction delete(String path) { deletes.add(path); return this; }
            @Override public FileTransaction validateExpectedRevision(String path, FileRevision expected) { checks.put(path, expected); return this; }
            @Override public boolean commit() {
                if (committed) return false;
                for (Map.Entry<String, FileRevision> check : checks.entrySet()) if (!matches(check.getKey(), check.getValue())) return false;
                for (Map.Entry<String, Write> write : writes.entrySet()) if (write.getValue().expected != null && !matches(write.getKey(), write.getValue().expected)) return false;
                Map<String, Entry> files = sources.computeIfAbsent(scriptId, ignored -> new LinkedHashMap<>());
                for (String path : deletes) files.remove(path);
                for (Map.Entry<String, Write> write : writes.entrySet()) {
                    Entry current = files.get(write.getKey());
                    long revision = current == null ? 1 : current.revision + 1;
                    files.put(write.getKey(), new Entry(write.getValue().content, revision));
                }
                committed = true;
                return true;
            }
            @Override public void rollback() { writes.clear(); deletes.clear(); checks.clear(); }
            @Override public boolean isCommitted() { return committed; }

            private boolean matches(String path, FileRevision expected) {
                SourceSnapshot current = readSource(scriptId, path);
                return current != null && Objects.equals(current.revision().revisionHash(), expected.revisionHash());
            }
            private record Write(String content, FileRevision expected) {}
        }
    }
}
