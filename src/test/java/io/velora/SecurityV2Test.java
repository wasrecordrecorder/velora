package io.velora;

import io.velora.api.VeloraLimits;
import io.velora.api.permission.*;
import io.velora.internal.security.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

class SecurityV2Test {

    // === PermissionController ===

    @Test
    @DisplayName("PermissionController: set and check permissions")
    void permissionController_setCheck() {
        PermissionController pc = new PermissionController();
        ScriptPermission worldRead = ScriptPermission.of("WORLD_READ", "World Read", "");
        ScriptPermission playerControl = ScriptPermission.of("PLAYER_CONTROL", "Player Control", "");

        pc.setPermissions("script1", PermissionSet.of(worldRead));
        assertTrue(pc.hasPermission("script1", worldRead));
        assertFalse(pc.hasPermission("script1", playerControl));
    }

    @Test
    @DisplayName("PermissionController: empty permissions for unknown script")
    void permissionController_unknownScript() {
        PermissionController pc = new PermissionController();
        ScriptPermission perm = ScriptPermission.of("TEST", "Test", "");
        assertFalse(pc.hasPermission("unknown", perm));
        assertEquals(PermissionSet.empty(), pc.getPermissions("unknown"));
    }

    @Test
    @DisplayName("PermissionController: clear permissions")
    void permissionController_clear() {
        PermissionController pc = new PermissionController();
        ScriptPermission perm = ScriptPermission.of("TEST", "Test", "");
        pc.setPermissions("s1", PermissionSet.of(perm));
        assertTrue(pc.hasPermission("s1", perm));
        pc.clear("s1");
        assertFalse(pc.hasPermission("s1", perm));
    }

    @Test
    @DisplayName("PermissionController: multiple scripts isolated")
    void permissionController_isolation() {
        PermissionController pc = new PermissionController();
        ScriptPermission p1 = ScriptPermission.of("P1", "P1", "");
        ScriptPermission p2 = ScriptPermission.of("P2", "P2", "");
        pc.setPermissions("s1", PermissionSet.of(p1));
        pc.setPermissions("s2", PermissionSet.of(p2));
        assertTrue(pc.hasPermission("s1", p1));
        assertFalse(pc.hasPermission("s1", p2));
        assertTrue(pc.hasPermission("s2", p2));
        assertFalse(pc.hasPermission("s2", p1));
    }

    // === HandleValidator ===

    @Test
    @DisplayName("HandleValidator: register and validate")
    void handleValidator_registerValidate() {
        HandleValidator hv = new HandleValidator();
        hv.registerHandleType("PlayerRef");
        assertTrue(hv.isValid("PlayerRef"));
        assertFalse(hv.isValid("UnknownType"));
    }

    @Test
    @DisplayName("HandleValidator: validate throws on invalid")
    void handleValidator_throws() {
        HandleValidator hv = new HandleValidator();
        hv.registerHandleType("Valid");
        assertDoesNotThrow(() -> hv.validate("Valid"));
        assertThrows(SecurityException.class, () -> hv.validate("Invalid"));
    }

    @Test
    @DisplayName("HandleValidator: empty validator rejects all")
    void handleValidator_empty() {
        HandleValidator hv = new HandleValidator();
        assertFalse(hv.isValid("Anything"));
        assertThrows(SecurityException.class, () -> hv.validate("Anything"));
    }

    // === ApiCostController ===

    @Test
    @DisplayName("ApiCostController: within budget")
    void apiCostController_withinBudget() {
        ApiCostController ac = new ApiCostController(100);
        assertTrue(ac.canCall(50));
        ac.recordCall(50);
        assertTrue(ac.canCall(50));
        ac.recordCall(50);
        assertFalse(ac.canCall(1));
    }

    @Test
    @DisplayName("ApiCostController: reset tick")
    void apiCostController_reset() {
        ApiCostController ac = new ApiCostController(100);
        ac.recordCall(100);
        assertFalse(ac.canCall(1));
        ac.resetTick();
        assertTrue(ac.canCall(100));
    }

    @Test
    @DisplayName("ApiCostController: exact budget boundary")
    void apiCostController_boundary() {
        ApiCostController ac = new ApiCostController(100);
        assertTrue(ac.canCall(100));
        ac.recordCall(100);
        assertFalse(ac.canCall(1));
        assertEquals(100, ac.costThisTick());
    }

    @Test
    @DisplayName("ApiCostController: zero cost always allowed")
    void apiCostController_zeroCost() {
        ApiCostController ac = new ApiCostController(0);
        assertTrue(ac.canCall(0));
        ac.recordCall(0);
        assertTrue(ac.canCall(0));
    }

    // === ResourceCounter ===

    @Test
    @DisplayName("ResourceCounter: memory tracking")
    void resourceCounter_memory() {
        ResourceCounter rc = new ResourceCounter();
        rc.reserveMemory(1024);
        assertEquals(1024, rc.memoryUsed());
        rc.releaseMemory(512);
        assertEquals(512, rc.memoryUsed());
    }

    @Test
    @DisplayName("ResourceCounter: fiber tracking")
    void resourceCounter_fibers() {
        ResourceCounter rc = new ResourceCounter();
        rc.reserveFiber();
        rc.reserveFiber();
        assertEquals(2, rc.fibers());
        rc.releaseFiber();
        assertEquals(1, rc.fibers());
    }

    @Test
    @DisplayName("ResourceCounter: task tracking")
    void resourceCounter_tasks() {
        ResourceCounter rc = new ResourceCounter();
        rc.reserveTask();
        assertEquals(1, rc.tasks());
        rc.releaseTask();
        assertEquals(0, rc.tasks());
    }

    @Test
    @DisplayName("ResourceCounter: event queue tracking")
    void resourceCounter_events() {
        ResourceCounter rc = new ResourceCounter();
        rc.reserveEvent();
        rc.reserveEvent();
        assertEquals(2, rc.eventQueueSize());
        rc.releaseEvent();
        assertEquals(1, rc.eventQueueSize());
    }

    @Test
    @DisplayName("ResourceCounter: reset clears all")
    void resourceCounter_reset() {
        ResourceCounter rc = new ResourceCounter();
        rc.reserveMemory(100);
        rc.reserveFiber();
        rc.reserveTask();
        rc.reserveEvent();
        rc.reset();
        assertEquals(0, rc.memoryUsed());
        assertEquals(0, rc.fibers());
        assertEquals(0, rc.tasks());
        assertEquals(0, rc.eventQueueSize());
    }

    // === ResourceLimits ===

    @Test
    @DisplayName("ResourceLimits: memory exceeded")
    void resourceLimits_memory() {
        VeloraLimits limits = VeloraLimits.builder().memoryPerScript(1024).build();
        ResourceLimits rl = new ResourceLimits(limits);
        ResourceCounter rc = new ResourceCounter();
        rc.reserveMemory(512);
        assertFalse(rl.memoryExceeded(rc));
        rc.reserveMemory(1024);
        assertTrue(rl.memoryExceeded(rc));
    }

    @Test
    @DisplayName("ResourceLimits: fibers exceeded")
    void resourceLimits_fibers() {
        VeloraLimits limits = VeloraLimits.builder().maxFibersPerScript(2).build();
        ResourceLimits rl = new ResourceLimits(limits);
        ResourceCounter rc = new ResourceCounter();
        rc.reserveFiber();
        rc.reserveFiber();
        assertFalse(rl.fibersExceeded(rc));
        rc.reserveFiber();
        assertTrue(rl.fibersExceeded(rc));
    }

    @Test
    @DisplayName("ResourceLimits: tasks exceeded")
    void resourceLimits_tasks() {
        VeloraLimits limits = VeloraLimits.builder().maxTasksPerScript(1).build();
        ResourceLimits rl = new ResourceLimits(limits);
        ResourceCounter rc = new ResourceCounter();
        rc.reserveTask();
        assertFalse(rl.tasksExceeded(rc));
        rc.reserveTask();
        assertTrue(rl.tasksExceeded(rc));
    }

    @Test
    @DisplayName("ResourceLimits: string length exceeded")
    void resourceLimits_stringLength() {
        VeloraLimits limits = VeloraLimits.builder().maxStringLength(10).build();
        ResourceLimits rl = new ResourceLimits(limits);
        assertFalse(rl.stringLengthExceeded(10));
        assertTrue(rl.stringLengthExceeded(11));
    }

    @Test
    @DisplayName("ResourceLimits: collection elements exceeded")
    void resourceLimits_collection() {
        VeloraLimits limits = VeloraLimits.builder().maxCollectionElements(100).build();
        ResourceLimits rl = new ResourceLimits(limits);
        assertFalse(rl.collectionElementsExceeded(100));
        assertTrue(rl.collectionElementsExceeded(101));
    }

    @Test
    @DisplayName("ResourceLimits: call depth exceeded")
    void resourceLimits_callDepth() {
        VeloraLimits limits = VeloraLimits.builder().maxCallDepth(10).build();
        ResourceLimits rl = new ResourceLimits(limits);
        assertFalse(rl.callDepthExceeded(10));
        assertTrue(rl.callDepthExceeded(11));
    }

    // === ScriptThrottleController ===

    @Test
    @DisplayName("ScriptThrottleController: throttle after max violations")
    void throttleController_throttle() {
        ScriptThrottleController stc = new ScriptThrottleController(3);
        stc.recordViolation("s1");
        stc.recordViolation("s1");
        assertFalse(stc.isThrottled("s1"));
        stc.recordViolation("s1");
        assertTrue(stc.isThrottled("s1"));
    }

    @Test
    @DisplayName("ScriptThrottleController: unthrottle clears state")
    void throttleController_unthrottle() {
        ScriptThrottleController stc = new ScriptThrottleController(1);
        stc.recordViolation("s1");
        assertTrue(stc.isThrottled("s1"));
        stc.unthrottle("s1");
        assertFalse(stc.isThrottled("s1"));
    }

    @Test
    @DisplayName("ScriptThrottleController: different scripts isolated")
    void throttleController_isolation() {
        ScriptThrottleController stc = new ScriptThrottleController(2);
        stc.recordViolation("s1");
        stc.recordViolation("s1");
        assertTrue(stc.isThrottled("s1"));
        assertFalse(stc.isThrottled("s2"));
    }

    @Test
    @DisplayName("ScriptThrottleController: clear removes all")
    void throttleController_clear() {
        ScriptThrottleController stc = new ScriptThrottleController(1);
        stc.recordViolation("s1");
        stc.clear("s1");
        assertFalse(stc.isThrottled("s1"));
    }

    @Test
    @DisplayName("ScriptThrottleController: throttledScripts returns set")
    void throttleController_throttledSet() {
        ScriptThrottleController stc = new ScriptThrottleController(1);
        stc.recordViolation("s1");
        stc.recordViolation("s2");
        assertEquals(2, stc.throttledScripts().size());
    }

    // === LogRateLimiter ===

    @Test
    @DisplayName("LogRateLimiter: within limit")
    void logRateLimiter_withinLimit() {
        LogRateLimiter lrl = new LogRateLimiter(5);
        for (int i = 0; i < 5; i++) {
            assertTrue(lrl.canLog("s1"));
            lrl.recordLog("s1");
        }
        assertFalse(lrl.canLog("s1"));
    }

    @Test
    @DisplayName("LogRateLimiter: reset tick clears counts")
    void logRateLimiter_reset() {
        LogRateLimiter lrl = new LogRateLimiter(1);
        lrl.recordLog("s1");
        assertFalse(lrl.canLog("s1"));
        lrl.resetTick();
        assertTrue(lrl.canLog("s1"));
    }

    @Test
    @DisplayName("LogRateLimiter: different scripts isolated")
    void logRateLimiter_isolation() {
        LogRateLimiter lrl = new LogRateLimiter(1);
        lrl.recordLog("s1");
        assertFalse(lrl.canLog("s1"));
        assertTrue(lrl.canLog("s2"));
    }

    @Test
    @DisplayName("LogRateLimiter: zero limit blocks all")
    void logRateLimiter_zero() {
        LogRateLimiter lrl = new LogRateLimiter(0);
        assertFalse(lrl.canLog("s1"));
    }

    // === PermissionSet ===

    @Test
    @DisplayName("PermissionSet: contains and containsAll")
    void permissionSet_contains() {
        ScriptPermission p1 = ScriptPermission.of("P1", "P1", "");
        ScriptPermission p2 = ScriptPermission.of("P2", "P2", "");
        PermissionSet set = PermissionSet.of(p1, p2);
        assertTrue(set.contains(p1));
        assertTrue(set.contains(p2));
        assertTrue(set.containsAll(PermissionSet.of(p1)));
        assertFalse(set.containsAll(PermissionSet.of(ScriptPermission.of("P3", "P3", ""))));
    }

    @Test
    @DisplayName("PermissionSet: union")
    void permissionSet_union() {
        ScriptPermission p1 = ScriptPermission.of("P1", "P1", "");
        ScriptPermission p2 = ScriptPermission.of("P2", "P2", "");
        PermissionSet s1 = PermissionSet.of(p1);
        PermissionSet s2 = PermissionSet.of(p2);
        PermissionSet union = s1.union(s2);
        assertTrue(union.contains(p1));
        assertTrue(union.contains(p2));
    }

    @Test
    @DisplayName("PermissionSet: empty set")
    void permissionSet_empty() {
        PermissionSet empty = PermissionSet.empty();
        assertTrue(empty.isEmpty());
        assertFalse(empty.contains(ScriptPermission.of("X", "X", "")));
    }

    @Test
    @DisplayName("PermissionSet: equality")
    void permissionSet_equality() {
        ScriptPermission p1 = ScriptPermission.of("P1", "P1", "");
        PermissionSet s1 = PermissionSet.of(p1);
        PermissionSet s2 = PermissionSet.of(p1);
        assertEquals(s1, s2);
        assertEquals(s1.hashCode(), s2.hashCode());
    }
}
