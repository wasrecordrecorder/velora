#ifndef VELORA_LUA_H
#define VELORA_LUA_H

#include "velora_bridge.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct VeloraLuaContext VeloraLuaContext;

VeloraLuaContext *velora_lua_create(JNIEnv *env, const char *script_id, const char *main_file, const char *main_source, jobject host);
jobject velora_lua_extract_descriptor(JNIEnv *env, VeloraLuaContext *ctx);
jobject velora_lua_invoke_hook(JNIEnv *env, VeloraLuaContext *ctx, const char *hook_name);
jobject velora_lua_dispatch_event(JNIEnv *env, VeloraLuaContext *ctx, const char *event_name, jobject event_payload);
jobject velora_lua_resume(JNIEnv *env, VeloraLuaContext *ctx, int64_t fiber_id, jobject result, const char *error);
void velora_lua_update_setting(JNIEnv *env, VeloraLuaContext *ctx, const char *key, jobject value);
void velora_lua_close(JNIEnv *env, VeloraLuaContext *ctx);
jobject velora_lua_check_syntax(JNIEnv *env, const char *code, const char *file_path);

#ifdef __cplusplus
}
#endif

#endif
