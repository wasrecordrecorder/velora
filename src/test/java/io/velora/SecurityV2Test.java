package io.velora;

import io.velora.internal.security.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

class SecurityV2Test {

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

}
