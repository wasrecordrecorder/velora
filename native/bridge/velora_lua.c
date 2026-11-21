#include "velora_lua.h"
#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"
#include <stdio.h>
#include <string.h>

#define LUA_MAGIC 0x564C5541 // "VLUA"

struct VeloraLuaContext {
    uint32_t magic;
    lua_State *L;
    lua_State *coro;
    int coro_ref;
    jobject host_global_ref;
    char script_id[256];
    char main_file[256];
    int descriptor_ref;
    int events_ref;
    int settings_ref;
    uint64_t memory_used;
    uint64_t memory_limit;
    int64_t suspended_duration_nanos;
    bool is_suspended_yield;
};

static void *velora_lua_alloc(void *ud, void *ptr, size_t osize, size_t nsize) {
    VeloraLuaContext *ctx = (VeloraLuaContext*)ud;
    if (nsize == 0) {
        if (ptr) {
            if (ctx->memory_used >= osize) ctx->memory_used -= osize;
            free(ptr);
        }
        return NULL;
    }
    if (ctx->memory_limit > 0 && ctx->memory_used + (nsize > osize ? nsize - osize : 0) > ctx->memory_limit) {
        return NULL; // Out of memory
    }
    void *nptr = realloc(ptr, nsize);
    if (nptr) {
        if (nsize > osize) ctx->memory_used += (nsize - osize);
        else ctx->memory_used -= (osize - nsize);
    }
    return nptr;
}

static void lua_push_java_val(JNIEnv *env, lua_State *L, jobject obj);
static jobject java_val_from_lua(JNIEnv *env, lua_State *L, int idx);

// Console helpers
static int l_console_log_internal(lua_State *L, int level) {
    VeloraLuaContext *ctx;
    lua_getallocf(L, (void**)&ctx);
    if (!ctx) return 0;
    int n = lua_gettop(L);
    char buf[4096];
    buf[0] = '\0';
    size_t pos = 0;
    for (int i = 1; i <= n; i++) {
        size_t len;
        const char *s = luaL_tolstring(L, i, &len);
        if (s) {
            if (i > 1 && pos < sizeof(buf) - 2) buf[pos++] = ' ';
            size_t copy_len = len < (sizeof(buf) - pos - 1) ? len : (sizeof(buf) - pos - 1);
            memcpy(buf + pos, s, copy_len);
            pos += copy_len;
            buf[pos] = '\0';
            lua_pop(L, 1);
        }
    }
    extern JavaVM *g_jvm;
    if (g_jvm) {
        JNIEnv *env = NULL;
        (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_8);
        if (env && ctx->host_global_ref) {
            call_foreign_host_log(env, ctx->host_global_ref, ctx->script_id, level, buf);
        }
    }
    return 0;
}

static int l_console_print(lua_State *L) { return l_console_log_internal(L, 1); }
static int l_console_info(lua_State *L)  { return l_console_log_internal(L, 1); }
static int l_console_warn(lua_State *L)  { return l_console_log_internal(L, 2); }
static int l_console_error(lua_State *L) { return l_console_log_internal(L, 3); }
static int l_console_debug(lua_State *L) { return l_console_log_internal(L, 0); }

// velora.delay(ms)
static int l_velora_delay(lua_State *L) {
    VeloraLuaContext *ctx;
    lua_getallocf(L, (void**)&ctx);
    lua_Integer ms = luaL_optinteger(L, 1, 0);
    if (ms < 0) ms = 0;
    if (ctx) ctx->suspended_duration_nanos = (int64_t)ms * 1000000LL;
    lua_pushinteger(L, ms);
    return lua_yield(L, 1);
}

// velora.yield()
static int l_velora_yield(lua_State *L) {
    VeloraLuaContext *ctx;
    lua_getallocf(L, (void**)&ctx);
    if (ctx) ctx->is_suspended_yield = true;
    return lua_yield(L, 0);
}

// velora.define(t)
static int l_velora_define(lua_State *L) {
    luaL_checktype(L, 1, LUA_TTABLE);
    return 1;
}

// setting helpers: velora.setting.float(name, def, options)
static int l_setting_create(lua_State *L, const char *type) {
    const char *name = luaL_checkstring(L, 1);
    lua_newtable(L);
    lua_pushboolean(L, 1);
    lua_setfield(L, -2, "__velora_setting");
    lua_pushstring(L, type);
    lua_setfield(L, -2, "type");
    lua_pushstring(L, name);
    lua_setfield(L, -2, "name");
    if (lua_gettop(L) >= 2) {
        lua_pushvalue(L, 2);
        lua_setfield(L, -2, "defaultValue");
    }
    if (lua_gettop(L) >= 3 && lua_istable(L, 3)) {
        lua_pushvalue(L, 3);
        lua_setfield(L, -2, "options");
    }
    return 1;
}

static int l_setting_float(lua_State *L)   { return l_setting_create(L, "Float"); }
static int l_setting_int(lua_State *L)     { return l_setting_create(L, "Int"); }
static int l_setting_boolean(lua_State *L) { return l_setting_create(L, "Boolean"); }
static int l_setting_string(lua_State *L)  { return l_setting_create(L, "String"); }
static int l_setting_mode(lua_State *L)    { return l_setting_create(L, "Mode"); }
static int l_setting_color(lua_State *L)   { return l_setting_create(L, "Color"); }

// Module searcher for require("module")
static int l_package_searcher(lua_State *L) {
    const char *modname = luaL_checkstring(L, 1);
    VeloraLuaContext *ctx;
    lua_getallocf(L, (void**)&ctx);
    if (!ctx) return 1;

    char rel_path[512];
    size_t i = 0;
    while (modname[i] && i < sizeof(rel_path) - 6) {
        rel_path[i] = (modname[i] == '.') ? '/' : modname[i];
        i++;
    }
    rel_path[i] = '\0';
    strcat(rel_path, ".lua");

    extern JavaVM *g_jvm;
    if (!g_jvm) return 1;
    JNIEnv *env = NULL;
    (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_8);
    if (!env || !ctx->host_global_ref) return 1;

    jstring content_str = (jstring)call_foreign_host_read_file(env, ctx->host_global_ref, ctx->script_id, rel_path);
    if (!content_str) {
        lua_pushfstring(L, "\n\tno file in script storage '%s'", rel_path);
        return 1;
    }

    const char *src = (*env)->GetStringUTFChars(env, content_str, NULL);
    int status = luaL_loadbuffer(L, src, strlen(src), rel_path);
    (*env)->ReleaseStringUTFChars(env, content_str, src);

    if (status != LUA_OK) {
        return lua_error(L);
    }
    lua_pushstring(L, rel_path);
    return 2;
}

// Generic API call dispatcher for Lua: namespace.func(...)
static int l_api_call(lua_State *L) {
    VeloraLuaContext *ctx;
    lua_getallocf(L, (void**)&ctx);
    if (!ctx) return 0;
    const char *ns = lua_tostring(L, lua_upvalueindex(1));
    const char *fn = lua_tostring(L, lua_upvalueindex(2));
    int argc = lua_gettop(L);

    extern JavaVM *g_jvm;
    if (!g_jvm) return 0;
    JNIEnv *env = NULL;
    (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_8);
    if (!env || !ctx->host_global_ref) return 0;

    jobjectArray args = (*env)->NewObjectArray(env, argc, (*env)->FindClass(env, "java/lang/Object"), NULL);
    for (int i = 1; i <= argc; i++) {
        jobject j_arg = java_val_from_lua(env, L, i);
        (*env)->SetObjectArrayElement(env, args, i - 1, j_arg);
    }
    jobject res = call_foreign_host_call_api(env, ctx->host_global_ref, ctx->script_id, ns, fn, args);
    lua_push_java_val(env, L, res);
    return 1;
}

static void lua_register_namespace(lua_State *L, const char *ns_name, const char **funcs, int count) {
    lua_newtable(L);
    for (int i = 0; i < count; i++) {
        lua_pushstring(L, ns_name);
        lua_pushstring(L, funcs[i]);
        lua_pushcclosure(L, l_api_call, 2);
        lua_setfield(L, -2, funcs[i]);
    }
    lua_setglobal(L, ns_name);
}

VeloraLuaContext *velora_lua_create(JNIEnv *env, const char *script_id, const char *main_file, const char *main_source, jobject host) {
    VeloraLuaContext *ctx = (VeloraLuaContext*)calloc(1, sizeof(VeloraLuaContext));
    if (!ctx) return NULL;
    ctx->magic = LUA_MAGIC;
    ctx->memory_limit = 64 * 1024 * 1024;
    ctx->L = lua_newstate(velora_lua_alloc, ctx);
    if (!ctx->L) { free(ctx); return NULL; }

    luaL_openlibs(ctx->L);

    if (host) ctx->host_global_ref = (*env)->NewGlobalRef(env, host);
    strncpy(ctx->script_id, script_id, sizeof(ctx->script_id) - 1);
    strncpy(ctx->main_file, main_file, sizeof(ctx->main_file) - 1);
    ctx->descriptor_ref = LUA_NOREF;
    ctx->events_ref = LUA_NOREF;
    ctx->settings_ref = LUA_NOREF;
    ctx->coro_ref = LUA_NOREF;

    // Install package.searchers loader
    lua_getglobal(ctx->L, "package");
    if (lua_istable(ctx->L, -1)) {
        lua_getfield(ctx->L, -1, "searchers");
        if (lua_istable(ctx->L, -1)) {
            // Insert at index 2
            int len = (int)luaL_len(ctx->L, -1);
            for (int i = len; i >= 2; i--) {
                lua_rawgeti(ctx->L, -1, i);
                lua_rawseti(ctx->L, -2, i + 1);
            }
            lua_pushcfunction(ctx->L, l_package_searcher);
            lua_rawseti(ctx->L, -2, 2);
        }
        lua_pop(ctx->L, 1);
    }
    lua_pop(ctx->L, 1);

    // console
    lua_newtable(ctx->L);
    lua_pushcfunction(ctx->L, l_console_print); lua_setfield(ctx->L, -2, "print");
    lua_pushcfunction(ctx->L, l_console_info);  lua_setfield(ctx->L, -2, "info");
    lua_pushcfunction(ctx->L, l_console_warn);  lua_setfield(ctx->L, -2, "warn");
    lua_pushcfunction(ctx->L, l_console_error); lua_setfield(ctx->L, -2, "error");
    lua_pushcfunction(ctx->L, l_console_debug); lua_setfield(ctx->L, -2, "debug");
    lua_setglobal(ctx->L, "console");

    // velora
    lua_newtable(ctx->L);
    lua_pushcfunction(ctx->L, l_velora_define); lua_setfield(ctx->L, -2, "define");
    lua_pushcfunction(ctx->L, l_velora_define); lua_setfield(ctx->L, -2, "defineScript");
    lua_pushcfunction(ctx->L, l_velora_delay);  lua_setfield(ctx->L, -2, "delay");
    lua_pushcfunction(ctx->L, l_velora_yield);  lua_setfield(ctx->L, -2, "yield");

    // velora.setting
    lua_newtable(ctx->L);
    lua_pushcfunction(ctx->L, l_setting_float);   lua_setfield(ctx->L, -2, "float");
    lua_pushcfunction(ctx->L, l_setting_int);     lua_setfield(ctx->L, -2, "int");
    lua_pushcfunction(ctx->L, l_setting_boolean); lua_setfield(ctx->L, -2, "boolean");
    lua_pushcfunction(ctx->L, l_setting_string);  lua_setfield(ctx->L, -2, "string");
    lua_pushcfunction(ctx->L, l_setting_mode);    lua_setfield(ctx->L, -2, "mode");
    lua_pushcfunction(ctx->L, l_setting_color);   lua_setfield(ctx->L, -2, "color");
    lua_setfield(ctx->L, -2, "setting");

    // velora.settings (live values table)
    lua_newtable(ctx->L);
    lua_pushvalue(ctx->L, -1);
    ctx->settings_ref = luaL_ref(ctx->L, LUA_REGISTRYINDEX);
    lua_setfield(ctx->L, -2, "settings");

    lua_setglobal(ctx->L, "velora");

    // Namespaces
    const char *time_fns[] = {"now", "uptime", "sleep"};
    lua_register_namespace(ctx->L, "time", time_fns, 3);
    const char *bytes_fns[] = {"alloc", "base64Encode", "base64Decode"};
    lua_register_namespace(ctx->L, "bytes", bytes_fns, 3);
    const char *assets_fns[] = {"read", "write", "exists", "delete"};
    lua_register_namespace(ctx->L, "assets", assets_fns, 4);

    // Load main source
    size_t src_len = strlen(main_source);
    int status = luaL_loadbuffer(ctx->L, main_source, src_len, main_file);
    if (status != LUA_OK) {
        const char *err = lua_tostring(ctx->L, -1);
        if (host) call_foreign_host_log(env, host, script_id, 3, err);
        lua_pop(ctx->L, 1);
        return ctx;
    }

    // Call chunk; if it returns a table, store it as descriptor
    status = lua_pcall(ctx->L, 0, 1, 0);
    if (status == LUA_OK && lua_istable(ctx->L, -1)) {
        lua_getfield(ctx->L, -1, "events");
        if (lua_istable(ctx->L, -1)) {
            if (ctx->events_ref != LUA_NOREF) {
                luaL_unref(ctx->L, LUA_REGISTRYINDEX, ctx->events_ref);
            }
            ctx->events_ref = luaL_ref(ctx->L, LUA_REGISTRYINDEX);
        } else {
            lua_pop(ctx->L, 1);
        }
        ctx->descriptor_ref = luaL_ref(ctx->L, LUA_REGISTRYINDEX);
    } else {
        if (status != LUA_OK) {
            const char *err = lua_tostring(ctx->L, -1);
            if (host) call_foreign_host_log(env, host, script_id, 3, err);
            lua_pop(ctx->L, 1);
        }
    }

    return ctx;
}

jobject velora_lua_extract_descriptor(JNIEnv *env, VeloraLuaContext *ctx) {
    if (!ctx || ctx->descriptor_ref == LUA_NOREF) return NULL;
    lua_State *L = ctx->L;
    lua_rawgeti(L, LUA_REGISTRYINDEX, ctx->descriptor_ref);
    if (!lua_istable(L, -1)) {
        lua_pop(L, 1);
        return NULL;
    }

    lua_getfield(L, -1, "name");
    const char *name_s = luaL_optstring(L, -1, ctx->script_id);
    jstring j_name = (*env)->NewStringUTF(env, name_s);
    lua_pop(L, 1);

    lua_getfield(L, -1, "version");
    const char *ver_s = luaL_optstring(L, -1, "1.0.0");
    jstring j_ver = (*env)->NewStringUTF(env, ver_s);
    lua_pop(L, 1);

    lua_getfield(L, -1, "author");
    const char *auth_s = luaL_optstring(L, -1, "");
    jstring j_auth = (*env)->NewStringUTF(env, auth_s);
    lua_pop(L, 1);

    lua_getfield(L, -1, "description");
    const char *desc_s = luaL_optstring(L, -1, "");
    jstring j_desc = (*env)->NewStringUTF(env, desc_s);
    lua_pop(L, 1);

    jclass list_cls = (*env)->FindClass(env, "java/util/ArrayList");
    jmethodID list_init = (*env)->GetMethodID(env, list_cls, "<init>", "()V");
    jmethodID list_add = (*env)->GetMethodID(env, list_cls, "add", "(Ljava/lang/Object;)Z");

    // Settings
    jobject settings_list = (*env)->NewObject(env, list_cls, list_init);
    lua_getfield(L, -1, "settings");
    if (lua_istable(L, -1)) {
        jclass setdef_cls = (*env)->FindClass(env, "io/velora/internal/foreign/ForeignSettingDef");
        jmethodID setdef_init = (*env)->GetMethodID(env, setdef_cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Double;Ljava/lang/Double;Ljava/lang/Double;[Ljava/lang/String;)V");

        lua_pushnil(L);
        while (lua_next(L, -2) != 0) {
            const char *key = luaL_checkstring(L, -2);
            if (lua_istable(L, -1)) {
                lua_getfield(L, -1, "type");
                const char *type_s = luaL_optstring(L, -1, "String");
                lua_pop(L, 1);

                lua_getfield(L, -1, "name");
                const char *sname_s = luaL_optstring(L, -1, key);
                lua_pop(L, 1);

                lua_getfield(L, -1, "defaultValue");
                jobject j_sdef = java_val_from_lua(env, L, -1);
                lua_pop(L, 1);

                jobject j_min = NULL, j_max = NULL, j_step = NULL;
                lua_getfield(L, -1, "options");
                if (lua_istable(L, -1)) {
                    jclass dbl_cls = (*env)->FindClass(env, "java/lang/Double");
                    jmethodID dbl_of = (*env)->GetStaticMethodID(env, dbl_cls, "valueOf", "(D)Ljava/lang/Double;");

                    lua_getfield(L, -1, "min");
                    if (lua_isnumber(L, -1)) j_min = (*env)->CallStaticObjectMethod(env, dbl_cls, dbl_of, lua_tonumber(L, -1));
                    lua_pop(L, 1);

                    lua_getfield(L, -1, "max");
                    if (lua_isnumber(L, -1)) j_max = (*env)->CallStaticObjectMethod(env, dbl_cls, dbl_of, lua_tonumber(L, -1));
                    lua_pop(L, 1);

                    lua_getfield(L, -1, "step");
                    if (lua_isnumber(L, -1)) j_step = (*env)->CallStaticObjectMethod(env, dbl_cls, dbl_of, lua_tonumber(L, -1));
                    lua_pop(L, 1);
                }
                lua_pop(L, 1);

                jstring j_id = (*env)->NewStringUTF(env, key);
                jstring j_sname = (*env)->NewStringUTF(env, sname_s);
                jstring j_stype = (*env)->NewStringUTF(env, type_s);

                jobject setdef_obj = (*env)->NewObject(env, setdef_cls, setdef_init, j_id, j_sname, j_stype, j_sdef, j_min, j_max, j_step, NULL);
                (*env)->CallBooleanMethod(env, settings_list, list_add, setdef_obj);
            }
            lua_pop(L, 1);
        }
    }
    lua_pop(L, 1);

    // Lifecycle hooks
    jobject hooks_list = (*env)->NewObject(env, list_cls, list_init);
    const char *possible_hooks[] = {"on_load", "on_enable", "on_disable", "on_unload", "on_run"};
    const char *norm_hooks[] = {"ON_LOAD", "ON_ENABLE", "ON_DISABLE", "ON_UNLOAD", "ON_RUN"};
    for (int i = 0; i < 5; i++) {
        lua_getfield(L, -1, possible_hooks[i]);
        if (lua_isfunction(L, -1)) {
            jstring j_hook = (*env)->NewStringUTF(env, norm_hooks[i]);
            (*env)->CallBooleanMethod(env, hooks_list, list_add, j_hook);
        }
        lua_pop(L, 1);
    }

    // Events
    jobject events_list = (*env)->NewObject(env, list_cls, list_init);
    lua_getfield(L, -1, "events");
    if (lua_istable(L, -1)) {
        if (ctx->events_ref != LUA_NOREF) {
            luaL_unref(L, LUA_REGISTRYINDEX, ctx->events_ref);
        }
        lua_pushvalue(L, -1);
        ctx->events_ref = luaL_ref(L, LUA_REGISTRYINDEX);
        lua_pushnil(L);
        while (lua_next(L, -2) != 0) {
            if (lua_isstring(L, -2) && lua_isfunction(L, -1)) {
                const char *ev_name = lua_tostring(L, -2);
                jstring j_ev = (*env)->NewStringUTF(env, ev_name);
                (*env)->CallBooleanMethod(env, events_list, list_add, j_ev);
            }
            lua_pop(L, 1);
        }
    }
    lua_pop(L, 1);
    lua_pop(L, 1); // pop descriptor table

    jclass desc_cls = (*env)->FindClass(env, "io/velora/internal/foreign/ForeignScriptDescriptor");
    jmethodID desc_init = (*env)->GetMethodID(env, desc_cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/List;Ljava/util/List;Ljava/util/List;)V");
    return (*env)->NewObject(env, desc_cls, desc_init, j_name, j_ver, j_auth, j_desc, settings_list, hooks_list, events_list);
}

jobject velora_lua_invoke_hook(JNIEnv *env, VeloraLuaContext *ctx, const char *hook_name) {
    if (!ctx || ctx->descriptor_ref == LUA_NOREF) return create_execution_result_error(env, "No script descriptor");
    lua_State *L = ctx->L;
    lua_rawgeti(L, LUA_REGISTRYINDEX, ctx->descriptor_ref);
    if (!lua_istable(L, -1)) {
        lua_pop(L, 1);
        return create_execution_result_success(env, NULL);
    }

    const char *fn_name = "on_run";
    if (strcmp(hook_name, "ON_LOAD") == 0) fn_name = "on_load";
    else if (strcmp(hook_name, "ON_ENABLE") == 0) fn_name = "on_enable";
    else if (strcmp(hook_name, "ON_DISABLE") == 0) fn_name = "on_disable";
    else if (strcmp(hook_name, "ON_UNLOAD") == 0) fn_name = "on_unload";

    lua_getfield(L, -1, fn_name);
    if (!lua_isfunction(L, -1)) {
        lua_pop(L, 2);
        return create_execution_result_success(env, NULL);
    }

    ctx->suspended_duration_nanos = 0;
    ctx->is_suspended_yield = false;

    if (strcmp(hook_name, "ON_RUN") == 0) {
        // Run on_run inside a coroutine thread
        ctx->coro = lua_newthread(L);
        ctx->coro_ref = luaL_ref(L, LUA_REGISTRYINDEX);
        lua_pushvalue(L, -2); // push function
        lua_xmove(L, ctx->coro, 1);
        lua_pop(L, 2); // pop func and descriptor table

        int nresults = 0;
        int status = lua_resume(ctx->coro, NULL, 0, &nresults);
        if (status == LUA_YIELD) {
            if (ctx->suspended_duration_nanos > 0) {
                int64_t dur = ctx->suspended_duration_nanos;
                ctx->suspended_duration_nanos = 0;
                return create_execution_result_delay(env, dur);
            }
            if (ctx->is_suspended_yield) {
                ctx->is_suspended_yield = false;
                return create_execution_result_yield(env);
            }
            return create_execution_result_yield(env);
        } else if (status != LUA_OK) {
            const char *err = lua_tostring(ctx->coro, -1);
            return create_execution_result_error(env, err ? err : "Lua error in on_run");
        }
        return create_execution_result_success(env, NULL);
    }

    // Normal synchronous lifecycle hook
    int status = lua_pcall(L, 0, 1, 0);
    if (status != LUA_OK) {
        const char *err = lua_tostring(L, -1);
        jobject r = create_execution_result_error(env, err ? err : "Lua lifecycle error");
        lua_pop(L, 2); // pop error and descriptor
        return r;
    }
    jobject j_res = java_val_from_lua(env, L, -1);
    lua_pop(L, 2); // pop result and descriptor
    return create_execution_result_success(env, j_res);
}

jobject velora_lua_dispatch_event(JNIEnv *env, VeloraLuaContext *ctx, const char *event_name, jobject event_payload) {
    if (!ctx || ctx->events_ref == LUA_NOREF) return create_execution_result_success(env, NULL);
    lua_State *L = ctx->L;
    lua_rawgeti(L, LUA_REGISTRYINDEX, ctx->events_ref);
    if (!lua_istable(L, -1)) {
        lua_pop(L, 1);
        return create_execution_result_success(env, NULL);
    }

    lua_getfield(L, -1, event_name);
    if (!lua_isfunction(L, -1)) {
        lua_pop(L, 2);
        return create_execution_result_success(env, NULL);
    }

    lua_push_java_val(env, L, event_payload);
    int status = lua_pcall(L, 1, 1, 0);
    if (status != LUA_OK) {
        const char *err = lua_tostring(L, -1);
        jobject r = create_execution_result_error(env, err ? err : "Lua event error");
        lua_pop(L, 2);
        return r;
    }
    jobject j_res = java_val_from_lua(env, L, -1);
    lua_pop(L, 2);
    return create_execution_result_success(env, j_res);
}

jobject velora_lua_resume(JNIEnv *env, VeloraLuaContext *ctx, int64_t fiber_id, jobject result, const char *error) {
    if (!ctx || !ctx->coro) return create_execution_result_error(env, "No coroutine to resume");
    ctx->suspended_duration_nanos = 0;
    ctx->is_suspended_yield = false;

    int narg = 0;
    if (result) {
        lua_push_java_val(env, ctx->coro, result);
        narg = 1;
    }

    int nresults = 0;
    int status = lua_resume(ctx->coro, NULL, narg, &nresults);
    if (status == LUA_YIELD) {
        if (ctx->suspended_duration_nanos > 0) {
            int64_t dur = ctx->suspended_duration_nanos;
            ctx->suspended_duration_nanos = 0;
            return create_execution_result_delay(env, dur);
        }
        if (ctx->is_suspended_yield) {
            ctx->is_suspended_yield = false;
            return create_execution_result_yield(env);
        }
        return create_execution_result_yield(env);
    } else if (status != LUA_OK) {
        const char *err = lua_tostring(ctx->coro, -1);
        return create_execution_result_error(env, err ? err : "Lua resume error");
    }
    return create_execution_result_success(env, NULL);
}

void velora_lua_update_setting(JNIEnv *env, VeloraLuaContext *ctx, const char *key, jobject value) {
    if (!ctx || ctx->settings_ref == LUA_NOREF) return;
    lua_State *L = ctx->L;
    lua_rawgeti(L, LUA_REGISTRYINDEX, ctx->settings_ref);
    if (lua_istable(L, -1)) {
        lua_push_java_val(env, L, value);
        lua_setfield(L, -2, key);
    }
    lua_pop(L, 1);
}

void velora_lua_close(JNIEnv *env, VeloraLuaContext *ctx) {
    if (!ctx) return;
    if (ctx->host_global_ref) {
        (*env)->DeleteGlobalRef(env, ctx->host_global_ref);
        ctx->host_global_ref = NULL;
    }
    if (ctx->L) {
        lua_close(ctx->L);
        ctx->L = NULL;
    }
    free(ctx);
}

jobject velora_lua_check_syntax(JNIEnv *env, const char *code, const char *file_path) {
    jclass list_cls = (*env)->FindClass(env, "java/util/ArrayList");
    jmethodID list_init = (*env)->GetMethodID(env, list_cls, "<init>", "()V");
    jmethodID list_add = (*env)->GetMethodID(env, list_cls, "add", "(Ljava/lang/Object;)Z");
    jobject list = (*env)->NewObject(env, list_cls, list_init);

    lua_State *L = luaL_newstate();
    if (!L) return list;

    size_t len = strlen(code);
    int status = luaL_loadbuffer(L, code, len, file_path);
    if (status != LUA_OK) {
        const char *err = lua_tostring(L, -1);
        // Error format is typically: [string "main.lua"]:12: syntax error near 'x'
        int line = 1;
        const char *p = strstr(err, ":");
        if (p) {
            p++;
            while (*p && *p != ':') {
                if (*p >= '0' && *p <= '9') {
                    line = atoi(p);
                    break;
                }
                p++;
            }
        }
        jobject diag = create_diagnostic_error(env, "PARSER_UNEXPECTED_TOKEN", err ? err : "Lua Syntax Error", file_path, line, 1);
        (*env)->CallBooleanMethod(env, list, list_add, diag);
    }
    lua_close(L);
    return list;
}

static void lua_push_java_val(JNIEnv *env, lua_State *L, jobject obj) {
    if (!obj || !env) {
        lua_pushnil(L);
        return;
    }
    jclass str_cls = (*env)->FindClass(env, "java/lang/String");
    if ((*env)->IsInstanceOf(env, obj, str_cls)) {
        const char *chars = (*env)->GetStringUTFChars(env, (jstring)obj, NULL);
        lua_pushstring(L, chars);
        (*env)->ReleaseStringUTFChars(env, (jstring)obj, chars);
        return;
    }
    jclass num_cls = (*env)->FindClass(env, "java/lang/Number");
    if ((*env)->IsInstanceOf(env, obj, num_cls)) {
        jmethodID dbl_mid = (*env)->GetMethodID(env, num_cls, "doubleValue", "()D");
        double d = (*env)->CallDoubleMethod(env, obj, dbl_mid);
        lua_pushnumber(L, d);
        return;
    }
    jclass bool_cls = (*env)->FindClass(env, "java/lang/Boolean");
    if ((*env)->IsInstanceOf(env, obj, bool_cls)) {
        jmethodID bool_mid = (*env)->GetMethodID(env, bool_cls, "booleanValue", "()Z");
        jboolean b = (*env)->CallBooleanMethod(env, obj, bool_mid);
        lua_pushboolean(L, b ? 1 : 0);
        return;
    }
    lua_pushnil(L);
}

static jobject java_val_from_lua(JNIEnv *env, lua_State *L, int idx) {
    if (!env) return NULL;
    int type = lua_type(L, idx);
    if (type == LUA_TNIL) return NULL;
    if (type == LUA_TBOOLEAN) {
        jclass cls = (*env)->FindClass(env, "java/lang/Boolean");
        jmethodID mid = (*env)->GetStaticMethodID(env, cls, "valueOf", "(Z)Ljava/lang/Boolean;");
        return (*env)->CallStaticObjectMethod(env, cls, mid, (jboolean)lua_toboolean(L, idx));
    }
    if (type == LUA_TNUMBER) {
        if (lua_isinteger(L, idx)) {
            jclass cls = (*env)->FindClass(env, "java/lang/Long");
            jmethodID mid = (*env)->GetStaticMethodID(env, cls, "valueOf", "(J)Ljava/lang/Long;");
            return (*env)->CallStaticObjectMethod(env, cls, mid, (jlong)lua_tointeger(L, idx));
        } else {
            jclass cls = (*env)->FindClass(env, "java/lang/Double");
            jmethodID mid = (*env)->GetStaticMethodID(env, cls, "valueOf", "(D)Ljava/lang/Double;");
            return (*env)->CallStaticObjectMethod(env, cls, mid, (jdouble)lua_tonumber(L, idx));
        }
    }
    if (type == LUA_TSTRING) {
        const char *s = lua_tostring(L, idx);
        return (*env)->NewStringUTF(env, s ? s : "");
    }
    return NULL;
}
