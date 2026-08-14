package io.velora;

import io.velora.api.*;
import io.velora.api.function.*;
import io.velora.api.setting.*;
import io.velora.api.type.*;
import io.velora.binding.annotation.*;
import io.velora.host.*;
import io.velora.internal.runtime.*;
import io.velora.internal.registry.*;
import io.velora.internal.compiler.*;
import io.velora.internal.script.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

class EngineV2Test {

    private VeloraHost simpleHost() {
        return new VeloraHost() {
            public String id() { return "test"; }
            public String version() { return "2"; }
            public MainThreadExecutor mainThread() { return new MainThreadExecutor() {
                public boolean isMainThread() { return true; }
                public void execute(Runnable action) { action.run(); }
            }; }
            public WorkerExecutor workers() { return new WorkerExecutor() {
                public void execute(Runnable action) { action.run(); }
                public void shutdown() { }
            }; }
            public VeloraClock clock() { return new VeloraClock() {
                public long nanoTime() { return System.nanoTime(); }
                public long currentTimeMillis() { return System.currentTimeMillis(); }
            }; }
            public VeloraLogger logger() { return new VeloraLogger() {
                public void debug(String m) { }
                public void info(String m) { }
                public void warn(String m) { }
                public void error(String m, Throwable t) { }
            }; }
            public VeloraFileSystem fileSystem() { return null; }
        };
    }

    // === Builder ===

    @Test
    @DisplayName("Builder requires host")
    void builderRequiresHost() {
        assertThrows(VeloraException.class, () -> Velora.builder().build());
    }

    @Test
    @DisplayName("Builder with host creates engine")
    void builderWithHost() {
        VeloraEngine engine = Velora.builder().host(simpleHost()).build();
        assertNotNull(engine);
        assertEquals(VeloraState.CONFIGURING, engine.state());
    }

    @Test
    @DisplayName("Builder with custom limits")
    void builderWithLimits() {
        VeloraLimits limits = VeloraLimits.builder().maxFibersPerScript(10).build();
        VeloraEngine engine = Velora.builder().host(simpleHost()).limits(limits).build();
        assertEquals(10, engine.limits().maxFibersPerScript());
    }

    @Test
    @DisplayName("Velora.version returns version string")
    void versionString() {
        assertNotNull(Velora.version());
        assertFalse(Velora.version().isEmpty());
    }

    // === Registries ===

    @Test
    @DisplayName("Engine has generic core types without client bindings")
    void typeRegistry() {
        VeloraEngine engine = Velora.builder().host(simpleHost()).build();
        assertNotNull(engine.types().find("Int"));
        assertNotNull(engine.types().find("String"));
        assertNotNull(engine.types().find("Vec3"));
        assertNull(engine.types().find("BlockPos"));
        assertNull(engine.types().find("PlayerRef"));
        assertNull(engine.types().find("BlockRef"));
    }

    @Test
    @DisplayName("Engine has generic built-in API only")
    void builtInApi() {
        VeloraEngine engine = Velora.builder().host(simpleHost()).build();
        assertNotNull(engine.api().find("console", "print"));
        assertFalse(engine.api().namespaces().contains("player"));
        assertFalse(engine.api().namespaces().contains("world"));
        assertFalse(engine.api().namespaces().contains("bot"));
    }

    @Test
    @DisplayName("Engine has built-in settings")
    void builtInSettings() {
        VeloraEngine engine = Velora.builder().host(simpleHost()).build();
        assertTrue(engine.settings().all().size() > 0, "Should have built-in setting kinds");
    }


    @Test
    @DisplayName("Engine has constant registry")
    void constantRegistry() {
        VeloraEngine engine = Velora.builder().host(simpleHost()).build();
        assertNotNull(engine.constants());
    }

    @Test
    @DisplayName("Engine has event registry")
    void eventRegistry() {
        VeloraEngine engine = Velora.builder().host(simpleHost()).build();
        assertNotNull(engine.events());
    }

    @Test
    @DisplayName("Engine has compiler")
    void compiler() {
        VeloraEngine engine = Velora.builder().host(simpleHost()).build();
        assertNotNull(engine.compiler());
    }

    @Test
    @DisplayName("Engine has language service")
    void languageService() {
        VeloraEngine engine = Velora.builder().host(simpleHost()).build();
        assertNotNull(engine.language());
    }

    @Test
    @DisplayName("Engine has debug service")
    void debugService() {
        VeloraEngine engine = Velora.builder().host(simpleHost()).build();
        assertNotNull(engine.debug());
    }

    @Test
    @DisplayName("Engine has script manager")
    void scriptManager() {
        VeloraEngine engine = Velora.builder().host(simpleHost()).build();
        assertNotNull(engine.scripts());
    }

    // === API Registration ===

    @Test
    @DisplayName("Register custom API namespace and function")
    void registerApi() {
        VeloraEngine engine = Velora.builder().host(simpleHost()).build();
        engine.api().namespace("custom", ns -> ns.function("answer", VeloraTypes.INT, ctx -> 42));
        assertNotNull(engine.api().find("custom", "answer"));
    }

    @Test
    @DisplayName("Duplicate API registration is rejected")
    void duplicateApiRejected() {
        VeloraEngine engine = Velora.builder().host(simpleHost()).build();
        engine.api().namespace("dup", ns -> ns.function("value", VeloraTypes.INT, ctx -> 1));
        assertThrows(Throwable.class, () ->
            engine.api().namespace("dup", ns -> ns.function("value", VeloraTypes.INT, ctx -> 2)));
    }


    @Test
    @DisplayName("Register custom setting kind")
    void registerSettingKind() {
        VeloraEngine engine = Velora.builder().host(simpleHost()).build();
        engine.settings().register(
            SettingKind.named("CustomKind")
                .identifierParameter()
                .positional("name", SettingKind.Parameter.ParameterRole.DISPLAY_NAME, VeloraTypes.STRING, true)
                .positional("defaultValue", SettingKind.Parameter.ParameterRole.DEFAULT_VALUE, VeloraTypes.STRING, true)
                .resultType(VeloraTypes.STRING)
                .editor("custom")
                .build()
        );
        assertNotNull(engine.settings().find("CustomKind"));
    }

    // === Annotated Bindings ===

    @VeloraNamespace("ann")
    public static final class TestBindings {
        @VeloraFunction(name = "sum")
        public int sum(int a, int b) { return a + b; }

        @VeloraProperty(name = "answer")
        public int answer() { return 42; }
    }

    @Test
    @DisplayName("Annotated bindings register correctly")
    void annotatedBindings() {
        VeloraEngine engine = Velora.builder().host(simpleHost()).build();
        engine.api().registerAnnotated(new TestBindings());
        assertNotNull(engine.api().find("ann", "sum"));
        assertNotNull(engine.api().find("ann", "answer"));
    }

    // === Freeze ===

    @Test
    @DisplayName("Freeze transitions to FROZEN state")
    void freezeState() {
        VeloraEngine engine = Velora.builder().host(simpleHost()).build();
        engine.freeze();
        assertEquals(VeloraState.FROZEN, engine.state());
    }

    @Test
    @DisplayName("Freeze assigns API indices")
    void freezeAssignsIndices() {
        VeloraEngine engine = Velora.builder().host(simpleHost()).build();
        engine.api().namespace("test", ns -> ns.function("f1", VeloraTypes.INT, ctx -> 1));
        engine.freeze();
        FunctionDescriptor fd = engine.api().findByIndex(0);
        assertNotNull(fd);
        assertTrue(fd.index() >= 0);
    }

    @Test
    @DisplayName("Freeze is idempotent")
    void freezeIdempotent() {
        VeloraEngine engine = Velora.builder().host(simpleHost()).build();
        engine.freeze();
        assertDoesNotThrow(() -> engine.freeze());
        assertEquals(VeloraState.FROZEN, engine.state());
    }

    // === Close ===

    @Test
    @DisplayName("Close transitions to CLOSED state")
    void closeState() {
        VeloraEngine engine = Velora.builder().host(simpleHost()).build();
        engine.freeze();
        engine.close();
        assertEquals(VeloraState.CLOSED, engine.state());
    }

    // === VeloraState enum ===

    @Test
    @DisplayName("VeloraState has all expected states")
    void veloraStates() {
        assertNotNull(VeloraState.valueOf("CREATED"));
        assertNotNull(VeloraState.valueOf("CONFIGURING"));
        assertNotNull(VeloraState.valueOf("FROZEN"));
        assertNotNull(VeloraState.valueOf("RUNNING"));
        assertNotNull(VeloraState.valueOf("CLOSING"));
        assertNotNull(VeloraState.valueOf("CLOSED"));
        assertNotNull(VeloraState.valueOf("FAILED"));
    }

    // === VeloraLimits defaults ===

    @Test
    @DisplayName("Default limits have reasonable values")
    void defaultLimits() {
        VeloraLimits defaults = VeloraLimits.defaults();
        assertTrue(defaults.instructionsPerFiberTick() > 0);
        assertTrue(defaults.maxFibersPerScript() > 0);
        assertTrue(defaults.maxCallDepth() > 0);
        assertTrue(defaults.maxStringLength() > 0);
        assertTrue(defaults.maxCollectionElements() > 0);
        assertTrue(defaults.memoryPerScript() > 0);
    }

    @Test
    @DisplayName("Custom limits override defaults")
    void customLimits() {
        VeloraLimits limits = VeloraLimits.builder()
            .instructionsPerFiberTick(1000)
            .maxFibersPerScript(10)
            .maxCallDepth(50)
            .maxStringLength(10000)
            .maxCollectionElements(5000)
            .memoryPerScript(1024 * 1024)
            .build();
        assertEquals(1000, limits.instructionsPerFiberTick());
        assertEquals(10, limits.maxFibersPerScript());
        assertEquals(50, limits.maxCallDepth());
        assertEquals(10000, limits.maxStringLength());
        assertEquals(5000, limits.maxCollectionElements());
        assertEquals(1024 * 1024, limits.memoryPerScript());
    }

    // === VeloraTypes ===

    @Test
    @DisplayName("Built-in types are available")
    void builtInTypes() {
        assertNotNull(VeloraTypes.INT);
        assertNotNull(VeloraTypes.LONG);
        assertNotNull(VeloraTypes.DOUBLE);
        assertNotNull(VeloraTypes.FLOAT);
        assertNotNull(VeloraTypes.BOOLEAN);
        assertNotNull(VeloraTypes.STRING);
        assertNotNull(VeloraTypes.UNIT);
        assertNotNull(VeloraTypes.NOTHING);
    }

    @Test
    @DisplayName("Type widening: int to long")
    void typeWidening() {
        assertTrue(VeloraTypes.isWidening(VeloraTypes.INT, VeloraTypes.LONG));
        assertTrue(VeloraTypes.isWidening(VeloraTypes.INT, VeloraTypes.DOUBLE));
        assertTrue(VeloraTypes.isWidening(VeloraTypes.FLOAT, VeloraTypes.DOUBLE));
        assertFalse(VeloraTypes.isWidening(VeloraTypes.LONG, VeloraTypes.INT));
    }

    @Test
    @DisplayName("Type compatibility")
    void typeCompatibility() {
        assertTrue(VeloraTypes.isCompatible(VeloraTypes.INT, VeloraTypes.INT));
        assertTrue(VeloraTypes.isCompatible(VeloraTypes.INT, VeloraTypes.LONG));
        assertFalse(VeloraTypes.isCompatible(VeloraTypes.STRING, VeloraTypes.INT));
    }
}
