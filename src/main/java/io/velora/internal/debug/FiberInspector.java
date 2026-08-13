package io.velora.internal.debug;

import io.velora.api.debug.FiberSnapshot;
import io.velora.internal.scheduler.ScriptFiber;

import java.util.*;

public final class FiberInspector {
    public static FiberSnapshot inspect(ScriptFiber fiber) {
        return new FiberSnapshot(fiber.id(), fiber.parentId(), fiber.scriptId(), fiber.functionName(), fiber.state().name(),
                fiber.instructionPointer(), fiber.instructionsExecuted(), fiber.createdAtNanos());
    }

    public static List<FiberSnapshot> inspectAll(Collection<ScriptFiber> fibers) {
        List<FiberSnapshot> result = new ArrayList<>(fibers.size());
        for (ScriptFiber fiber : fibers) result.add(inspect(fiber));
        return List.copyOf(result);
    }
}
