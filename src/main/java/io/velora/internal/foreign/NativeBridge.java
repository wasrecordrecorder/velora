package io.velora.internal.foreign;

import io.velora.api.compiler.Diagnostic;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public final class NativeBridge {

    private static final Object LOAD_LOCK = new Object();
    private static volatile boolean loaded = false;
    private static volatile Throwable loadError = null;

    static {
        tryLoad();
    }

    private NativeBridge() {
    }

    public static boolean isAvailable() {
        if (!loaded) tryLoad();
        return loaded;
    }

    public static void ensureAvailable() {
        if (!isAvailable()) {
            throw new IllegalStateException("Native bridge for JavaScript and Lua is unavailable", loadError);
        }
    }

    public static Throwable loadError() {
        return loadError;
    }

    private static void tryLoad() {
        synchronized (LOAD_LOCK) {
            if (loaded) return;
            try {
                System.loadLibrary("velora_langs");
                loaded = true;
                return;
            } catch (Throwable ignored) {
            }

            try {
                String resourcePath = "/native/windows-x64/velora_langs.dll";
                InputStream rawStream = NativeBridge.class.getResourceAsStream(resourcePath);
                if (rawStream == null) {
                    resourcePath = "native/windows-x64/velora_langs.dll";
                    rawStream = NativeBridge.class.getClassLoader().getResourceAsStream(resourcePath);
                }
                if (rawStream != null) {
                    Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "velora_native_" + System.getProperty("user.name"));
                    Files.createDirectories(tempDir);
                    Path tempDll = tempDir.resolve("velora_langs.dll");
                    try (InputStream in = rawStream; OutputStream out = Files.newOutputStream(tempDll)) {
                        in.transferTo(out);
                    }
                    System.load(tempDll.toAbsolutePath().toString());
                    loaded = true;
                    return;
                }
                throw new IllegalStateException("Embedded native library not found in classpath: /native/windows-x64/velora_langs.dll");
            } catch (Throwable error) {
                loadError = error;
            }
        }
    }

    public static native long createJsContext(String scriptId, String mainFile, String mainSource, ForeignHost host);

    public static native long createLuaContext(String scriptId, String mainFile, String mainSource, ForeignHost host);

    public static native ForeignScriptDescriptor extractDescriptor(long contextHandle);

    public static native ForeignExecutionResult invokeHook(long contextHandle, String hookName);

    public static native ForeignExecutionResult dispatchEvent(long contextHandle, String eventName, Object eventPayload);

    public static native ForeignExecutionResult resume(long contextHandle, long fiberId, Object result, String error);

    public static native void updateSetting(long contextHandle, String key, Object value);

    public static native void closeContext(long contextHandle);

    public static native List<Diagnostic> checkSyntaxJs(String code, String filePath);

    public static native List<Diagnostic> checkSyntaxLua(String code, String filePath);
}
