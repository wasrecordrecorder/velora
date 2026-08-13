package io.velora.internal.debug;

import io.velora.api.debug.TaskSnapshot;
import io.velora.api.task.TaskState;

import java.util.*;

public final class TaskInspector {

    public static TaskSnapshot inspect(long taskId, TaskState state, String scriptId) {
        return new TaskSnapshot(taskId, scriptId, state.name(), null, 0);
    }

    public static List<TaskSnapshot> inspectAll(Map<Long, TaskState> tasks, String scriptId) {
        List<TaskSnapshot> result = new ArrayList<>();
        for (var e : tasks.entrySet()) {
            result.add(inspect(e.getKey(), e.getValue(), scriptId));
        }
        return result;
    }
}
