#ifndef VELORA_QUICKJS_H
#define VELORA_QUICKJS_H

#include "velora_bridge.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct VeloraJsContext VeloraJsContext;

VeloraJsContext *velora_js_create(JNIEnv *env, const char *script_id, const char *main_file, const char *main_source, jobject host);
jobject velora_js_extract_descriptor(JNIEnv *env, VeloraJsContext *ctx);
jobject velora_js_invoke_hook(JNIEnv *env, VeloraJsContext *ctx, const char *hook_name);
jobject velora_js_dispatch_event(JNIEnv *env, VeloraJsContext *ctx, const char *event_name, jobject event_payload);
jobject velora_js_resume(JNIEnv *env, VeloraJsContext *ctx, int64_t fiber_id, jobject result, const char *error);
void velora_js_update_setting(JNIEnv *env, VeloraJsContext *ctx, const char *key, jobject value);
void velora_js_close(JNIEnv *env, VeloraJsContext *ctx);
jobject velora_js_check_syntax(JNIEnv *env, const char *code, const char *file_path);

#ifdef __cplusplus
}
#endif

#endif
