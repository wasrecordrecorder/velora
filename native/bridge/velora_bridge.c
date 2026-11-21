#include "velora_bridge.h"
#include "velora_quickjs.h"
#include "velora_lua.h"
#include "io_velora_internal_foreign_NativeBridge.h"

JavaVM *g_jvm = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

jobject create_diagnostic_error(JNIEnv *env, const char *code_str, const char *msg, const char *file_path, int line, int col) {
    jclass diag_cls = (*env)->FindClass(env, "io/velora/api/compiler/Diagnostic");
    jclass code_cls = (*env)->FindClass(env, "io/velora/api/compiler/DiagnosticCode");
    jfieldID code_fid = (*env)->GetStaticFieldID(env, code_cls, code_str, "Lio/velora/api/compiler/DiagnosticCode;");
    if (!code_fid) {
        (*env)->ExceptionClear(env);
        code_fid = (*env)->GetStaticFieldID(env, code_cls, "PARSER_UNEXPECTED_TOKEN", "Lio/velora/api/compiler/DiagnosticCode;");
    }
    jobject code_obj = (*env)->GetStaticObjectField(env, code_cls, code_fid);

    jclass range_cls = (*env)->FindClass(env, "io/velora/api/compiler/SourceRange");
    jmethodID range_of = (*env)->GetStaticMethodID(env, range_cls, "of", "(Ljava/lang/String;IIII)Lio/velora/api/compiler/SourceRange;");
    jstring jpath = (*env)->NewStringUTF(env, file_path ? file_path : "script");
    jobject range_obj = (*env)->CallStaticObjectMethod(env, range_cls, range_of, jpath, (jint)line, (jint)col, (jint)line, (jint)(col > 0 ? col + 1 : 1));

    jmethodID diag_err = (*env)->GetStaticMethodID(env, diag_cls, "error", "(Lio/velora/api/compiler/DiagnosticCode;Ljava/lang/String;Lio/velora/api/compiler/SourceRange;)Lio/velora/api/compiler/Diagnostic;");
    jstring jmsg = (*env)->NewStringUTF(env, msg ? msg : "Syntax error");
    return (*env)->CallStaticObjectMethod(env, diag_cls, diag_err, code_obj, jmsg, range_obj);
}

jobject create_execution_result_success(JNIEnv *env, jobject val) {
    jclass cls = (*env)->FindClass(env, "io/velora/internal/foreign/ForeignExecutionResult");
    jmethodID mid = (*env)->GetStaticMethodID(env, cls, "success", "(Ljava/lang/Object;)Lio/velora/internal/foreign/ForeignExecutionResult;");
    return (*env)->CallStaticObjectMethod(env, cls, mid, val);
}

jobject create_execution_result_delay(JNIEnv *env, int64_t duration_nanos) {
    jclass cls = (*env)->FindClass(env, "io/velora/internal/foreign/ForeignExecutionResult");
    jmethodID mid = (*env)->GetStaticMethodID(env, cls, "delay", "(J)Lio/velora/internal/foreign/ForeignExecutionResult;");
    return (*env)->CallStaticObjectMethod(env, cls, mid, (jlong)duration_nanos);
}

jobject create_execution_result_await(JNIEnv *env, int64_t task_id) {
    jclass cls = (*env)->FindClass(env, "io/velora/internal/foreign/ForeignExecutionResult");
    jmethodID mid = (*env)->GetStaticMethodID(env, cls, "awaitTask", "(J)Lio/velora/internal/foreign/ForeignExecutionResult;");
    return (*env)->CallStaticObjectMethod(env, cls, mid, (jlong)task_id);
}

jobject create_execution_result_yield(JNIEnv *env) {
    jclass cls = (*env)->FindClass(env, "io/velora/internal/foreign/ForeignExecutionResult");
    jmethodID mid = (*env)->GetStaticMethodID(env, cls, "yieldFiber", "()Lio/velora/internal/foreign/ForeignExecutionResult;");
    return (*env)->CallStaticObjectMethod(env, cls, mid);
}

jobject create_execution_result_error(JNIEnv *env, const char *msg) {
    jclass cls = (*env)->FindClass(env, "io/velora/internal/foreign/ForeignExecutionResult");
    jmethodID mid = (*env)->GetStaticMethodID(env, cls, "error", "(Ljava/lang/String;)Lio/velora/internal/foreign/ForeignExecutionResult;");
    jstring jmsg = (*env)->NewStringUTF(env, msg ? msg : "Execution error");
    return (*env)->CallStaticObjectMethod(env, cls, mid, jmsg);
}

jobject call_foreign_host_read_file(JNIEnv *env, jobject host, const char *script_id, const char *rel_path) {
    if (!host) return NULL;
    jclass cls = (*env)->GetObjectClass(env, host);
    jmethodID mid = (*env)->GetMethodID(env, cls, "readFile", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    if (!mid) return NULL;
    jstring j_sid = (*env)->NewStringUTF(env, script_id);
    jstring j_path = (*env)->NewStringUTF(env, rel_path);
    return (*env)->CallObjectMethod(env, host, mid, j_sid, j_path);
}

void call_foreign_host_log(JNIEnv *env, jobject host, const char *script_id, int level, const char *msg) {
    if (!host) return;
    jclass cls = (*env)->GetObjectClass(env, host);
    jmethodID mid = (*env)->GetMethodID(env, cls, "log", "(Ljava/lang/String;ILjava/lang/String;)V");
    if (!mid) return;
    jstring j_sid = (*env)->NewStringUTF(env, script_id);
    jstring j_msg = (*env)->NewStringUTF(env, msg ? msg : "");
    (*env)->CallVoidMethod(env, host, mid, j_sid, (jint)level, j_msg);
}

jobject call_foreign_host_call_api(JNIEnv *env, jobject host, const char *script_id, const char *ns, const char *fn, jobjectArray args) {
    if (!host) return NULL;
    jclass cls = (*env)->GetObjectClass(env, host);
    jmethodID mid = (*env)->GetMethodID(env, cls, "callApi", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;");
    if (!mid) return NULL;
    jstring j_sid = (*env)->NewStringUTF(env, script_id);
    jstring j_ns = (*env)->NewStringUTF(env, ns);
    jstring j_fn = (*env)->NewStringUTF(env, fn);
    return (*env)->CallObjectMethod(env, host, mid, j_sid, j_ns, j_fn, args);
}

jobject call_foreign_host_invoke_native(JNIEnv *env, jobject host, jobject target, const char *method, jobjectArray args) {
    if (!host) return NULL;
    jclass cls = (*env)->GetObjectClass(env, host);
    jmethodID mid = (*env)->GetMethodID(env, cls, "invokeNative", "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;");
    if (!mid) return NULL;
    jstring j_method = (*env)->NewStringUTF(env, method);
    return (*env)->CallObjectMethod(env, host, mid, target, j_method, args);
}

jobject call_foreign_host_get_native_field(JNIEnv *env, jobject host, jobject target, const char *field) {
    if (!host) return NULL;
    jclass cls = (*env)->GetObjectClass(env, host);
    jmethodID mid = (*env)->GetMethodID(env, cls, "getNativeField", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;");
    if (!mid) return NULL;
    jstring j_field = (*env)->NewStringUTF(env, field);
    return (*env)->CallObjectMethod(env, host, mid, target, j_field);
}

jboolean call_foreign_host_set_native_field(JNIEnv *env, jobject host, jobject target, const char *field, jobject val) {
    if (!host) return JNI_FALSE;
    jclass cls = (*env)->GetObjectClass(env, host);
    jmethodID mid = (*env)->GetMethodID(env, cls, "setNativeField", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;)Z");
    if (!mid) return JNI_FALSE;
    jstring j_field = (*env)->NewStringUTF(env, field);
    return (*env)->CallBooleanMethod(env, host, mid, target, j_field, val);
}

// JNI Entry points from io_velora_internal_foreign_NativeBridge.h

JNIEXPORT jlong JNICALL Java_io_velora_internal_foreign_NativeBridge_createJsContext
  (JNIEnv *env, jclass cls, jstring script_id, jstring main_file, jstring main_source, jobject host) {
    const char *sid = script_id ? (*env)->GetStringUTFChars(env, script_id, NULL) : "script";
    const char *mf = main_file ? (*env)->GetStringUTFChars(env, main_file, NULL) : "main.js";
    const char *ms = main_source ? (*env)->GetStringUTFChars(env, main_source, NULL) : "";
    VeloraJsContext *ctx = velora_js_create(env, sid, mf, ms, host);
    if (script_id) (*env)->ReleaseStringUTFChars(env, script_id, sid);
    if (main_file) (*env)->ReleaseStringUTFChars(env, main_file, mf);
    if (main_source) (*env)->ReleaseStringUTFChars(env, main_source, ms);
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT jlong JNICALL Java_io_velora_internal_foreign_NativeBridge_createLuaContext
  (JNIEnv *env, jclass cls, jstring script_id, jstring main_file, jstring main_source, jobject host) {
    const char *sid = script_id ? (*env)->GetStringUTFChars(env, script_id, NULL) : "script";
    const char *mf = main_file ? (*env)->GetStringUTFChars(env, main_file, NULL) : "main.lua";
    const char *ms = main_source ? (*env)->GetStringUTFChars(env, main_source, NULL) : "";
    VeloraLuaContext *ctx = velora_lua_create(env, sid, mf, ms, host);
    if (script_id) (*env)->ReleaseStringUTFChars(env, script_id, sid);
    if (main_file) (*env)->ReleaseStringUTFChars(env, main_file, mf);
    if (main_source) (*env)->ReleaseStringUTFChars(env, main_source, ms);
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT jobject JNICALL Java_io_velora_internal_foreign_NativeBridge_extractDescriptor
  (JNIEnv *env, jclass cls, jlong handle) {
    if (!handle) return NULL;
    // Lower 1-bit or context marker: if odd/even or check signature
    // We differentiate JS and Lua handles by a magic tag in their context struct
    uint32_t *magic = (uint32_t*)(intptr_t)handle;
    if (*magic == 0x564A5343) { // "VJSC" = Velora JS Context
        return velora_js_extract_descriptor(env, (VeloraJsContext*)(intptr_t)handle);
    } else if (*magic == 0x564C5541) { // "VLUA" = Velora Lua Context
        return velora_lua_extract_descriptor(env, (VeloraLuaContext*)(intptr_t)handle);
    }
    return NULL;
}

JNIEXPORT jobject JNICALL Java_io_velora_internal_foreign_NativeBridge_invokeHook
  (JNIEnv *env, jclass cls, jlong handle, jstring hook_name) {
    if (!handle || !hook_name) return create_execution_result_error(env, "Invalid handle or hook name");
    const char *hook = (*env)->GetStringUTFChars(env, hook_name, NULL);
    uint32_t *magic = (uint32_t*)(intptr_t)handle;
    jobject res = NULL;
    if (*magic == 0x564A5343) {
        res = velora_js_invoke_hook(env, (VeloraJsContext*)(intptr_t)handle, hook);
    } else if (*magic == 0x564C5541) {
        res = velora_lua_invoke_hook(env, (VeloraLuaContext*)(intptr_t)handle, hook);
    } else {
        res = create_execution_result_error(env, "Unknown context handle");
    }
    (*env)->ReleaseStringUTFChars(env, hook_name, hook);
    return res;
}

JNIEXPORT jobject JNICALL Java_io_velora_internal_foreign_NativeBridge_dispatchEvent
  (JNIEnv *env, jclass cls, jlong handle, jstring event_name, jobject event_payload) {
    if (!handle || !event_name) return create_execution_result_error(env, "Invalid handle or event name");
    const char *evt = (*env)->GetStringUTFChars(env, event_name, NULL);
    uint32_t *magic = (uint32_t*)(intptr_t)handle;
    jobject res = NULL;
    if (*magic == 0x564A5343) {
        res = velora_js_dispatch_event(env, (VeloraJsContext*)(intptr_t)handle, evt, event_payload);
    } else if (*magic == 0x564C5541) {
        res = velora_lua_dispatch_event(env, (VeloraLuaContext*)(intptr_t)handle, evt, event_payload);
    } else {
        res = create_execution_result_error(env, "Unknown context handle");
    }
    (*env)->ReleaseStringUTFChars(env, event_name, evt);
    return res;
}

JNIEXPORT jobject JNICALL Java_io_velora_internal_foreign_NativeBridge_resume
  (JNIEnv *env, jclass cls, jlong handle, jlong fiber_id, jobject result, jstring error) {
    if (!handle) return create_execution_result_error(env, "Invalid handle");
    const char *err = error ? (*env)->GetStringUTFChars(env, error, NULL) : NULL;
    uint32_t *magic = (uint32_t*)(intptr_t)handle;
    jobject res = NULL;
    if (*magic == 0x564A5343) {
        res = velora_js_resume(env, (VeloraJsContext*)(intptr_t)handle, (int64_t)fiber_id, result, err);
    } else if (*magic == 0x564C5541) {
        res = velora_lua_resume(env, (VeloraLuaContext*)(intptr_t)handle, (int64_t)fiber_id, result, err);
    } else {
        res = create_execution_result_error(env, "Unknown context handle");
    }
    if (error && err) (*env)->ReleaseStringUTFChars(env, error, err);
    return res;
}

JNIEXPORT void JNICALL Java_io_velora_internal_foreign_NativeBridge_updateSetting
  (JNIEnv *env, jclass cls, jlong handle, jstring key, jobject value) {
    if (!handle || !key) return;
    const char *k = (*env)->GetStringUTFChars(env, key, NULL);
    uint32_t *magic = (uint32_t*)(intptr_t)handle;
    if (*magic == 0x564A5343) {
        velora_js_update_setting(env, (VeloraJsContext*)(intptr_t)handle, k, value);
    } else if (*magic == 0x564C5541) {
        velora_lua_update_setting(env, (VeloraLuaContext*)(intptr_t)handle, k, value);
    }
    (*env)->ReleaseStringUTFChars(env, key, k);
}

JNIEXPORT void JNICALL Java_io_velora_internal_foreign_NativeBridge_closeContext
  (JNIEnv *env, jclass cls, jlong handle) {
    if (!handle) return;
    uint32_t *magic = (uint32_t*)(intptr_t)handle;
    if (*magic == 0x564A5343) {
        velora_js_close(env, (VeloraJsContext*)(intptr_t)handle);
    } else if (*magic == 0x564C5541) {
        velora_lua_close(env, (VeloraLuaContext*)(intptr_t)handle);
    }
}

JNIEXPORT jobject JNICALL Java_io_velora_internal_foreign_NativeBridge_checkSyntaxJs
  (JNIEnv *env, jclass cls, jstring code, jstring file_path) {
    const char *c = code ? (*env)->GetStringUTFChars(env, code, NULL) : "";
    const char *fp = file_path ? (*env)->GetStringUTFChars(env, file_path, NULL) : "main.js";
    jobject res = velora_js_check_syntax(env, c, fp);
    if (code) (*env)->ReleaseStringUTFChars(env, code, c);
    if (file_path) (*env)->ReleaseStringUTFChars(env, file_path, fp);
    return res;
}

JNIEXPORT jobject JNICALL Java_io_velora_internal_foreign_NativeBridge_checkSyntaxLua
  (JNIEnv *env, jclass cls, jstring code, jstring file_path) {
    const char *c = code ? (*env)->GetStringUTFChars(env, code, NULL) : "";
    const char *fp = file_path ? (*env)->GetStringUTFChars(env, file_path, NULL) : "main.lua";
    jobject res = velora_lua_check_syntax(env, c, fp);
    if (code) (*env)->ReleaseStringUTFChars(env, code, c);
    if (file_path) (*env)->ReleaseStringUTFChars(env, file_path, fp);
    return res;
}
