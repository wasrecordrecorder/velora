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
        fs.put("multi", "main.vls", "@Script(\"Multi\")\n@Version(\"1\")\nscript Multi { int answer() { return helper() } }");
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
        fs.put("first", "main.vls", "@Script(\"First\")\n@Version(\"1\")\nscript First { @Run run() { console.print(\"first-run\") } }");
        fs.put("second", "main.vls", "@Script(\"Second\")\n@Version(\"1\")\nscript Second { @Enable enable() { console.print(\"second-enable\") } }");
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
        fs.put("lifecycle", "main.vls", "@Script(\"Lifecycle\")\n@Version(\"1\")\nscript Lifecycle { @Load load() { console.print(\"load\") } @Enable enable() { console.print(\"enable\") } @Run run() { console.print(\"run\") } @Tick tick() { console.print(\"tick\") } @Disable disable() { console.print(\"disable\") } @Unload unload() { console.print(\"unload\") } }");
        VeloraEngine engine = Velora.builder().host(host(fs)).build();
        engine.events().register(io.velora.api.event.EventDescriptor.builder("client.tick").scriptName("Tick").payloadType(io.velora.api.type.VeloraTypes.UNIT).build());
        engine.freeze();
        engine.scripts().discover();
        assertTrue(engine.scripts().enable("lifecycle").success());
        engine.events().emitSafe(io.velora.api.event.EventKey.of("client.tick", Void.class), null);
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
    void unloadOnlyRunsAfterTheLoadPhaseCompleted() {
        MemoryFileSystem fs = new MemoryFileSystem();
        fs.put("never-enabled", "main.vls", "@Script(\"NeverEnabled\")\nscript NeverEnabled { @Unload unload() { console.print(\"unload\") } }");
        VeloraEngine engine = Velora.builder().host(host(fs)).build();
        engine.freeze();
        engine.scripts().discover();
        assertTrue(engine.scripts().unload("never-enabled").success());
        assertTrue(engine.debug().logs("never-enabled").isEmpty());
        engine.close();

        MemoryFileSystem enabledFs = new MemoryFileSystem();
        enabledFs.put("enabled", "main.vls", "@Script(\"Enabled\")\nscript Enabled { @Unload unload() { console.print(\"unload\") } }");
        VeloraEngine enabledEngine = Velora.builder().host(host(enabledFs)).build();
        enabledEngine.freeze();
        enabledEngine.scripts().discover();
        assertTrue(enabledEngine.scripts().enable("enabled").success());
        assertTrue(enabledEngine.scripts().disable("enabled").success());
        assertTrue(enabledEngine.scripts().unload("enabled").success());
        assertTrue(enabledEngine.debug().logs("enabled").stream().anyMatch(log -> log.message().equals("unload")));
        enabledEngine.close();
    }

    @Test
    void loadRunsOnceWhileEnableRunsForEveryActivation() {
        MemoryFileSystem fs = new MemoryFileSystem();
        fs.put("lifecycle-repeat", "main.vls", "@Script(\"LifecycleRepeat\")\n@Version(\"1\")\nscript LifecycleRepeat { @Load load() { console.print(\"load\") } @Enable enable() { console.print(\"enable\") } @Disable disable() {} }");
        VeloraEngine engine = Velora.builder().host(host(fs)).build();
        engine.freeze();
        engine.scripts().discover();
        assertTrue(engine.scripts().enable("lifecycle-repeat").success());
        assertTrue(engine.scripts().disable("lifecycle-repeat").success());
        assertTrue(engine.scripts().enable("lifecycle-repeat").success());
        List<String> logs = engine.debug().logs("lifecycle-repeat").stream().map(log -> log.message()).toList();
        assertEquals(1L, logs.stream().filter("load"::equals).count());
        assertEquals(2L, logs.stream().filter("enable"::equals).count());
        engine.close();
    }

    @Test
    void scriptHandleDescriptorTracksLiveStatus() {
        MemoryFileSystem fs = new MemoryFileSystem();
        fs.put("descriptor", "main.vls", "@Script(\"Descriptor\")\n@Version(\"1\")\nscript Descriptor {}");
        VeloraEngine engine = Velora.builder().host(host(fs)).build();
        engine.freeze();
        engine.scripts().discover();
        var handle = engine.scripts().find("descriptor").orElseThrow();
        assertEquals(io.velora.api.script.ScriptStatus.LOADED, handle.descriptor().status());
        assertFalse(handle.descriptor().enabled());
        assertTrue(handle.enable().success());
        assertEquals(io.velora.api.script.ScriptStatus.ENABLED, handle.descriptor().status());
        assertTrue(handle.descriptor().enabled());
        assertTrue(handle.disable().success());
        assertEquals(io.velora.api.script.ScriptStatus.DISABLED, handle.descriptor().status());
        assertFalse(handle.descriptor().enabled());
        engine.close();
    }

    @Test
    void lifecycleFailuresAreReturnedAndRuntimeIsCleaned() {
        MemoryFileSystem fs = new MemoryFileSystem();
        fs.put("enable-failure", "main.vls", "@Script(\"EnableFailure\")\n@Version(\"1\")\nscript EnableFailure { @Enable enable() { int zero = 0\n int value = 1 / zero } }");
        fs.put("disable-failure", "main.vls", "@Script(\"DisableFailure\")\n@Version(\"1\")\nscript DisableFailure { @Disable disable() { int zero = 0\n int value = 1 / zero } }");
        VeloraEngine engine = Velora.builder().host(host(fs)).build();
        engine.freeze();
        engine.scripts().discover();
        var enableFailure = engine.scripts().enable("enable-failure");
        assertFalse(enableFailure.success());
        assertEquals(io.velora.api.script.ScriptStatus.FAILED, engine.scripts().status("enable-failure"));
        assertTrue(engine.debug().fibers("enable-failure").isEmpty());
        assertTrue(engine.scripts().enable("disable-failure").success());
        var disableFailure = engine.scripts().disable("disable-failure");
        assertFalse(disableFailure.success());
        assertEquals(io.velora.api.script.ScriptStatus.DISABLED, engine.scripts().status("disable-failure"));
        assertTrue(engine.debug().fibers("disable-failure").isEmpty());
        engine.close();
    }

    @Test
    void enableFailsCleanlyWhenOnRunCannotBeScheduled() {
        MemoryFileSystem fs = new MemoryFileSystem();
        VeloraEngine engine = Velora.builder().host(host(fs)).limits(VeloraLimits.builder().memoryPerScript(64).build()).build();
        engine.freeze();
        String source = "@Script(\"Limited\")\n@Version(\"1\")\nscript Limited { @Run run() { console.print(\"never\") } }";
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
        String source = "@Script(\"TickSerial\")\n@Version(\"1\")\nscript TickSerial { @Tick async tick() { delay(20.milliseconds)\n console.print(\"tick-finished\") } }";
        fs.put("tick-serial", "main.vls", source);
        VeloraEngine engine = Velora.builder().host(host(fs)).build();
        engine.events().register(io.velora.api.event.EventDescriptor.builder("client.tick").scriptName("Tick").payloadType(io.velora.api.type.VeloraTypes.UNIT).build());
        engine.freeze();
        engine.scripts().discover();
        assertTrue(engine.scripts().enable("tick-serial").success());
        var tick = io.velora.api.event.EventKey.of("client.tick", Void.class);
        engine.events().emitSafe(tick, null);
        engine.tick();
        engine.events().emitSafe(tick, null);
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
        String source = "@Script(\"Disk\")\n@Version(\"1\")\nscript Disk { int value() { return 41 } }";
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
    void clientServiceEventsReflectCompileAndConsoleChanges() {
        MemoryFileSystem fs = new MemoryFileSystem();
        VeloraEngine engine = Velora.builder().host(host(fs)).build();
        engine.freeze();
        List<io.velora.api.script.ScriptServiceEvents.Type> events = new ArrayList<>();
        engine.scripts().events().subscribe(event -> events.add(event.type()));
        var created = engine.scripts().create(ScriptCreateRequest.builder("events", "Events")
                .file("main.vls", "@Script(\"Events\")\n@Version(\"1\")\nscript Events { @Run run() { console.print(\"service-log\") } }")
                .build());
        assertTrue(created.success(), created.message());
        assertTrue(engine.scripts().enable("events").success());
        engine.tick();
        assertTrue(events.contains(io.velora.api.script.ScriptServiceEvents.Type.COMPILE_STARTED));
        assertTrue(events.contains(io.velora.api.script.ScriptServiceEvents.Type.COMPILE_FINISHED));
        assertTrue(events.contains(io.velora.api.script.ScriptServiceEvents.Type.CREATED));
        assertTrue(events.contains(io.velora.api.script.ScriptServiceEvents.Type.LOG_ADDED));
        engine.close();
    }

    @Test
    void scriptServiceListenerFailuresAreIsolated() {
        MemoryFileSystem fs = new MemoryFileSystem();
        VeloraEngine engine = Velora.builder().host(host(fs)).build();
        engine.freeze();
        List<io.velora.api.script.ScriptServiceEvents.Type> events = new ArrayList<>();
        engine.scripts().events().subscribe(event -> { throw new IllegalStateException("listener failed"); });
        engine.scripts().events().subscribe(event -> events.add(event.type()));
        var created = engine.scripts().create(ScriptCreateRequest.builder("listener-isolation", "ListenerIsolation")
                .file("main.vls", "@Script(\"ListenerIsolation\")\n@Version(\"1\")\nscript ListenerIsolation { @Run run() {} }")
                .build());
        assertTrue(created.success(), created.message());
        assertTrue(events.contains(io.velora.api.script.ScriptServiceEvents.Type.CREATED));
        assertThrows(NullPointerException.class, () -> engine.scripts().events().subscribe(null));
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
                        .file("main.vls", "@Script(\"Templated\")\n@Version(\"1\")\nscript Templated { @Setting(\"Amount\", min=0, max=100, step=1, editor=\"slider\") amount = 10 }")
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
        fs.put("settings", "main.vls", "@Script(\"Settings\")\n@Version(\"1\")\nscript Settings { @Setting(\"Amount\", min=0, max=100, step=1, editor=\"slider\") amount = 10 @Run run() { console.print(\"amount=\" + amount) } }");
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
    void sourceAndNewSettingCanBeCommittedAndReloadedAtomically() {
        MemoryFileSystem fs = new MemoryFileSystem();
        String before = "@Script(\"AtomicSettings\")\nscript AtomicSettings { @Setting(\"Amount\") amount = 1 }";
        String after = "@Script(\"AtomicSettings\")\nscript AtomicSettings { @Setting(\"Amount\") amount = 1 @Setting(\"Speed\", min=0, max=10) speed = 2 }";
        fs.put("atomic-settings", "main.vls", before);
        VeloraEngine engine = Velora.builder().host(host(fs)).build();
        engine.freeze();
        engine.scripts().discover();

        String revision = fs.readSource("atomic-settings", "main.vls").revision().revisionHash();
        ScriptTransactionResult result = engine.scripts().beginTransaction("atomic-settings")
                .write("main.vls", after, revision)
                .updateSetting("speed", io.velora.api.setting.SettingValue.ofInt(7))
                .validateAndCommit(ScriptTransaction.CommitMode.RELOAD_IF_VALID);

        assertTrue(result.success(), result.message());
        assertEquals(7, engine.scripts().settingValues("atomic-settings").get("speed").asInt());
        assertEquals(7, io.velora.internal.persistence.SettingsFileCodec.decode(new String(fs.readData("atomic-settings", "settings.velora"), StandardCharsets.UTF_8)).get("speed").asInt());
        engine.close();
    }

    @Test
    void validateOnlyUsesCandidateSettingSchemaWithoutMutatingState() {
        MemoryFileSystem fs = new MemoryFileSystem();
        String before = "@Script(\"ValidateSettings\")\nscript ValidateSettings { @Setting(\"Amount\") amount = 1 }";
        String after = "@Script(\"ValidateSettings\")\nscript ValidateSettings { @Setting(\"Amount\") amount = 1 @Setting(\"Speed\", min=0, max=10) speed = 2 }";
        fs.put("validate-settings", "main.vls", before);
        VeloraEngine engine = Velora.builder().host(host(fs)).build();
        engine.freeze();
        engine.scripts().discover();

        String revision = fs.readSource("validate-settings", "main.vls").revision().revisionHash();
        ScriptTransactionResult result = engine.scripts().beginTransaction("validate-settings")
                .write("main.vls", after, revision)
                .updateSetting("speed", io.velora.api.setting.SettingValue.ofInt(7))
                .validateAndCommit(ScriptTransaction.CommitMode.VALIDATE_ONLY);

        assertTrue(result.success(), result.message());
        assertEquals(before, fs.readSource("validate-settings", "main.vls").content());
        assertFalse(engine.scripts().settingValues("validate-settings").containsKey("speed"));
        assertNull(fs.readData("validate-settings", "settings.velora"));
        engine.close();
    }

    @Test
    void settingIdAliasMigratesPersistedValuesAndAcceptsOldIdUpdates() {
        MemoryFileSystem fs = new MemoryFileSystem();
        String before = "@Script(\"Alias\")\nscript Alias { @Setting(\"Amount\") amount = 1 }";
        String after = "@Script(\"Alias\")\nscript Alias { @Setting(\"Speed\", idAlias=\"amount\") speed = 2 }";
        fs.put("alias", "main.vls", before);
        VeloraEngine engine = Velora.builder().host(host(fs)).build();
        engine.freeze();
        engine.scripts().discover();
        assertTrue(engine.scripts().beginTransaction("alias")
                .updateSetting("amount", io.velora.api.setting.SettingValue.ofInt(6))
                .validateAndCommit(ScriptTransaction.CommitMode.COMMIT_WITHOUT_RELOAD).success());

        String revision = fs.readSource("alias", "main.vls").revision().revisionHash();
        ScriptTransactionResult renamed = engine.scripts().beginTransaction("alias")
                .write("main.vls", after, revision)
                .validateAndCommit(ScriptTransaction.CommitMode.RELOAD_IF_VALID);
        assertTrue(renamed.success(), renamed.message());
        assertEquals(6, engine.scripts().settingValues("alias").get("speed").asInt());

        ScriptTransactionResult oldId = engine.scripts().beginTransaction("alias")
                .updateSetting("amount", io.velora.api.setting.SettingValue.ofInt(9))
                .validateAndCommit(ScriptTransaction.CommitMode.COMMIT_WITHOUT_RELOAD);
        assertTrue(oldId.success(), oldId.message());
        assertEquals(9, engine.scripts().settingValues("alias").get("speed").asInt());
        engine.close();
    }

    @Test
    void invalidOrStaleTransactionsNeverMutateSources() {
        MemoryFileSystem fs = new MemoryFileSystem();
        fs.put("safe", "main.vls", "@Script(\"Safe\")\n@Version(\"1\")\nscript Safe { int answer() { return helper() } }");
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
    void fileRevisionIsRecheckedInsideFileTransactionCommit() {
        MemoryFileSystem fs = new MemoryFileSystem();
        fs.put("race", "main.vls", "@Script(\"Race\")\nscript Race { int answer() { return 1 } }");
        VeloraEngine engine = Velora.builder().host(host(fs)).build();
        engine.freeze();
        engine.scripts().discover();
        String revision = fs.readSource("race", "main.vls").revision().revisionHash();
        ScriptTransaction transaction = engine.scripts().beginTransaction("race")
                .write("main.vls", "@Script(\"Race\")\nscript Race { int answer() { return 2 } }", revision);
        fs.onNextTransaction(() -> fs.put("race", "main.vls", "@Script(\"Race\")\nscript Race { int answer() { return 3 } }"));
        ScriptTransactionResult result = transaction.validateAndCommit(ScriptTransaction.CommitMode.COMMIT_WITHOUT_RELOAD);
        assertFalse(result.success());
        assertEquals(ScriptTransaction.ConflictReason.SOURCE_REVISION_CONFLICT, result.conflictReason());
        assertTrue(fs.readSource("race", "main.vls").content().contains("return 3"));
        engine.close();
    }

    @Test
    void failedReloadReloadsOldLifecycleAfterUnload() {
        MemoryFileSystem fs = new MemoryFileSystem();
        fs.put("lifecycle-rollback", "main.vls", "@Script(\"Old\")\nscript Old { @Load load() { console.print(\"old-load\") } @Unload unload() { console.print(\"old-unload\") } }");
        VeloraEngine engine = Velora.builder().host(host(fs)).build();
        engine.freeze();
        engine.scripts().discover();
        assertTrue(engine.scripts().enable("lifecycle-rollback").success());
        fs.put("lifecycle-rollback", "main.vls", "@Script(\"New\")\nscript New { @Load load() { console.print(\"new-load\") } @Enable enable() { int zero = 0 int broken = 1 / zero } }");
        assertFalse(engine.scripts().reload("lifecycle-rollback").success());
        assertEquals(io.velora.api.script.ScriptStatus.ENABLED, engine.scripts().status("lifecycle-rollback"));
        List<String> logs = engine.debug().logs("lifecycle-rollback").stream().map(io.velora.api.debug.ScriptLogEntry::message).toList();
        assertEquals(2L, logs.stream().filter("old-load"::equals).count());
        assertEquals(1L, logs.stream().filter("old-unload"::equals).count());
        engine.close();
    }

    @Test
    void failedReloadRollbackNeverOverwritesExternalSourceChanges() {
        MemoryFileSystem fs = new MemoryFileSystem();
        String original = "@Script(\"Rollback\")\nscript Rollback { int answer() { return 1 } }";
        String committed = original.replace("return 1", "return 2");
        String external = original.replace("return 1", "return 3");
        fs.put("rollback", "main.vls", original);
        VeloraEngine engine = Velora.builder().host(host(fs)).build();
        engine.freeze();
        String revision = fs.readSource("rollback", "main.vls").revision().revisionHash();
        var transaction = new io.velora.internal.script.ScriptTransactionImpl("rollback", engine.compiler(), host(fs), null, null, null, -1, () -> {}, () -> {
            fs.put("rollback", "main.vls", external);
            fs.put("rollback", "external.vls", "int external() { return 9 }");
            return io.velora.api.script.ScriptOperationResult.failure("rollback", "activation failed");
        });
        ScriptTransactionResult result = transaction.write("main.vls", committed, revision).validateAndCommit(ScriptTransaction.CommitMode.RELOAD_IF_VALID);
        assertFalse(result.success());
        assertEquals(ScriptTransaction.ConflictReason.ACTIVATION_ERROR, result.conflictReason());
        assertEquals(external, fs.readSource("rollback", "main.vls").content());
        assertNotNull(fs.readSource("rollback", "external.vls"));
        engine.close();
    }

    @Test
    void failedReloadRestoresOwnSourceWhenNoOneChangedIt() {
        MemoryFileSystem fs = new MemoryFileSystem();
        String original = "@Script(\"RollbackOwn\")\nscript RollbackOwn { int answer() { return 1 } }";
        fs.put("rollback-own", "main.vls", original);
        VeloraEngine engine = Velora.builder().host(host(fs)).build();
        engine.freeze();
        String revision = fs.readSource("rollback-own", "main.vls").revision().revisionHash();
        var transaction = new io.velora.internal.script.ScriptTransactionImpl("rollback-own", engine.compiler(), host(fs), null, null, null, -1, () -> {}, () ->
                io.velora.api.script.ScriptOperationResult.failure("rollback-own", "activation failed"));
        ScriptTransactionResult result = transaction.write("main.vls", original.replace("return 1", "return 2"), revision)
                .validateAndCommit(ScriptTransaction.CommitMode.RELOAD_IF_VALID);
        assertFalse(result.success());
        assertEquals(original, fs.readSource("rollback-own", "main.vls").content());
        engine.close();
    }

    @Test
    void transactionDeleteParticipatesInWholeProjectValidationAndPathsAreSafe() {
        MemoryFileSystem fs = new MemoryFileSystem();
        fs.put("delete", "main.vls", "@Script(\"Delete\")\n@Version(\"1\")\nscript Delete { int answer() { return helper() } }");
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


    @Test
    void discoveryExposesCompilerDiagnosticsAndFailedStatus() {
        MemoryFileSystem fs = new MemoryFileSystem();
        fs.put("broken", "main.vls", "@Script(\"Broken\")\nscript Broken { answer() { return missing } }");
        VeloraEngine engine = Velora.builder().host(host(fs)).build();
        engine.freeze();
        engine.scripts().discover();
        var descriptor = engine.scripts().find("broken").orElseThrow().descriptor();
        assertEquals(io.velora.api.script.ScriptStatus.FAILED, descriptor.status());
        assertTrue(descriptor.errorCount() > 0);
        assertTrue(engine.scripts().diagnostics("broken").stream().anyMatch(d -> d.code() == io.velora.api.compiler.DiagnosticCode.SEMANTIC_UNRESOLVED_SYMBOL));
        assertTrue(engine.scripts().find("broken").orElseThrow().diagnostics().stream().anyMatch(d -> d.code() == io.velora.api.compiler.DiagnosticCode.SEMANTIC_UNRESOLVED_SYMBOL));
        engine.close();
    }

    @Test
    void scriptsWithoutSettingsDoNotReceiveSyntheticSettings() {
        MemoryFileSystem fs = new MemoryFileSystem();
        fs.put("plain", "main.vls", "@Script(\"Plain\")\nscript Plain { answer() { return 1 } }");
        VeloraEngine engine = Velora.builder().host(host(fs)).build();
        engine.freeze();
        engine.scripts().discover();
        assertTrue(engine.scripts().settings("plain").settings().isEmpty());
        assertTrue(engine.scripts().settingValues("plain").isEmpty());
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
        private Runnable nextTransactionHook;

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

        @Override public FileTransaction beginTransaction(String scriptId) {
            Runnable hook = nextTransactionHook;
            nextTransactionHook = null;
            if (hook != null) hook.run();
            return new Tx(scriptId);
        }

        void onNextTransaction(Runnable hook) {
            nextTransactionHook = hook;
        }

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
