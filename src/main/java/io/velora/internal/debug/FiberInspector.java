package io.velora.internal.debug;

import io.velora.api.debug.FiberSnapshot;
import io.velora.internal.scheduler.ScriptFiber;

import java.util.*;

public final class FiberInspector {

    public static FiberSnapshot inspect(ScriptFiber fiber) {
        return new FiberSnapshot(fiber.id(), fiber.parentId(), fiber.scriptId(),
                "fn" + fiber.functionIndex(), fiber.state().name(),
                0, fiber.instructionsThisTick(), 0);
    }

    public static List<FiberSnapshot> inspectAll(Collection<ScriptFiber> fibers) {
        List<FiberSnapshot> result = new ArrayList<>();
        for (ScriptFiber f : fibers) {
            result.add(inspect(f));
        }
        return result;
    }
}
