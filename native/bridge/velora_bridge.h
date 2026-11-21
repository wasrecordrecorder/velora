#ifndef VELORA_BRIDGE_H
#define VELORA_BRIDGE_H

#include <jni.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#ifdef __cplusplus
extern "C" {
#endif

// Status codes matching ForeignExecutionResult.java
#define STATUS_SUCCESS 0
#define STATUS_SUSPENDED_DELAY 1
#define STATUS_SUSPENDED_AWAIT 2
#define STATUS_SUSPENDED_YIELD 3
#define STATUS_ERROR 4

typedef struct ForeignExecResult {
    int status;
    jobject value;
    int64_t suspend_param;
    char *error_msg;
} ForeignExecResult;

// Shared JVM pointer
extern JavaVM *g_jvm;

// JNI Helper functions
jobject create_diagnostic_error(JNIEnv *env, const char *code_str, const char *msg, const char *file_path, int line, int col);
jobject create_execution_result_success(JNIEnv *env, jobject val);
jobject create_execution_result_delay(JNIEnv *env, int64_t duration_nanos);
jobject create_execution_result_await(JNIEnv *env, int64_t task_id);
jobject create_execution_result_yield(JNIEnv *env);
jobject create_execution_result_error(JNIEnv *env, const char *msg);

jobject call_foreign_host_read_file(JNIEnv *env, jobject host, const char *script_id, const char *rel_path);
void call_foreign_host_log(JNIEnv *env, jobject host, const char *script_id, int level, const char *msg);
jobject call_foreign_host_call_api(JNIEnv *env, jobject host, const char *script_id, const char *ns, const char *fn, jobjectArray args);
jobject call_foreign_host_invoke_native(JNIEnv *env, jobject host, jobject target, const char *method, jobjectArray args);
jobject call_foreign_host_get_native_field(JNIEnv *env, jobject host, jobject target, const char *field);
jboolean call_foreign_host_set_native_field(JNIEnv *env, jobject host, jobject target, const char *field, jobject val);

#ifdef __cplusplus
}
#endif

#endif
