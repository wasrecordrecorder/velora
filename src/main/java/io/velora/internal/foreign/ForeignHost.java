package io.velora.internal.foreign;

public interface ForeignHost {

    String readFile(String scriptId, String relativePath);

    void log(String scriptId, int level, String message);

    Object callApi(String scriptId, String namespace, String function, Object[] args);

    Object invokeNative(Object target, String method, Object[] args);

    Object getNativeField(Object target, String field);

    boolean setNativeField(Object target, String field, Object value);

    long scheduleDelay(String scriptId, long durationNanos);

    void scheduleYield(String scriptId);
}
