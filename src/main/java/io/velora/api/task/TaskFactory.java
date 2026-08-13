package io.velora.api.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class TaskFactory {
    private TaskFactory() {}

    public static <T> VeloraTaskSource<T> create() {
        return new Source<>();
    }

    private static final class Source<T> implements VeloraTaskSource<T> {
        private final Task<T> task = new Task<>(this);
        private final List<Runnable> cancelCallbacks = new ArrayList<>();

        @Override public VeloraTask<T> task() { return task; }
        @Override public boolean succeed(T value) { return task.complete(TaskState.SUCCEEDED, value, null); }
        @Override public boolean fail(Throwable error) { return task.complete(TaskState.FAILED, null, Objects.requireNonNull(error)); }

        @Override
        public boolean cancel() {
            if (!task.complete(TaskState.CANCELLED, null, null)) return false;
            List<Runnable> callbacks;
            synchronized (this) {
                callbacks = new ArrayList<>(cancelCallbacks);
                cancelCallbacks.clear();
            }
            for (Runnable callback : callbacks) callback.run();
            return true;
        }

        @Override
        public void onCancel(Runnable callback) {
            Objects.requireNonNull(callback);
            synchronized (this) {
                if (task.state() == TaskState.PENDING) {
                    cancelCallbacks.add(callback);
                    return;
                }
                if (task.state() != TaskState.CANCELLED) return;
            }
            callback.run();
        }
    }

    private static final class Task<T> implements VeloraTask<T> {
        private final Source<T> source;
        private final List<TaskListener<T>> listeners = new ArrayList<>();
        private TaskState state = TaskState.PENDING;
        private T result;
        private Throwable failure;

        private Task(Source<T> source) {
            this.source = source;
        }

        @Override public synchronized TaskState state() { return state; }

        @Override
        public synchronized T result() {
            if (state != TaskState.SUCCEEDED) throw new IllegalStateException("Task is " + state);
            return result;
        }

        @Override
        public synchronized Throwable failure() {
            if (state != TaskState.FAILED) throw new IllegalStateException("Task is " + state);
            return failure;
        }

        @Override public boolean cancel() { return source.cancel(); }

        @Override
        public void onComplete(TaskListener<T> listener) {
            Objects.requireNonNull(listener);
            synchronized (this) {
                if (state == TaskState.PENDING) {
                    listeners.add(listener);
                    return;
                }
            }
            listener.onComplete(this);
        }

        private boolean complete(TaskState terminalState, T value, Throwable error) {
            List<TaskListener<T>> callbacks;
            synchronized (this) {
                if (state != TaskState.PENDING) return false;
                state = terminalState;
                result = value;
                failure = error;
                callbacks = new ArrayList<>(listeners);
                listeners.clear();
            }
            for (TaskListener<T> listener : callbacks) listener.onComplete(this);
            return true;
        }
    }
}
