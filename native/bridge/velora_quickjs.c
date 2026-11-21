#include "velora_quickjs.h"
#include "quickjs.h"
#include <stdio.h>
#include <string.h>

#define JS_MAGIC 0x564A5343 // "VJSC"

struct VeloraJsContext {
    uint32_t magic;
    JSRuntime *rt;
    JSContext *ctx;
    jobject host_global_ref;
    char script_id[256];
    char main_file[256];
    JSValue descriptor_val;
    JSValue events_obj;
    JSValue settings_obj;
    int64_t suspended_duration_nanos;
    bool is_suspended_yield;
    int64_t next_promise_id;
};

static JSValue js_val_from_java(JNIEnv *env, JSContext *ctx, jobject obj);
static jobject java_val_from_js(JNIEnv *env, JSContext *ctx, JSValue val);

// Global console functions
static JSValue js_console_log_internal(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv, int level) {
    VeloraJsContext *vctx = (VeloraJsContext*)JS_GetContextOpaque(ctx);
    if (!vctx) return JS_UNDEFINED;
    JNIEnv *env = NULL;
    // Format arguments
    char buf[4096];
    buf[0] = '\0';
    size_t pos = 0;
    for (int i = 0; i < argc; i++) {
        size_t len;
        const char *str = JS_ToCStringLen(ctx, &len, argv[i]);
        if (str) {
            if (i > 0 && pos < sizeof(buf) - 2) buf[pos++] = ' ';
            size_t copy_len = len < (sizeof(buf) - pos - 1) ? len : (sizeof(buf) - pos - 1);
            memcpy(buf + pos, str, copy_len);
            pos += copy_len;
            buf[pos] = '\0';
            JS_FreeCString(ctx, str);
        }
    }
    // Call host log
    JavaVM *jvm = NULL;
    // We can get env via JNI_OnLoad or thread attach
    extern JavaVM *g_jvm;
    if (g_jvm) {
        (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_8);
        if (env && vctx->host_global_ref) {
            call_foreign_host_log(env, vctx->host_global_ref, vctx->script_id, level, buf);
        }
    }
    return JS_UNDEFINED;
}

static JSValue js_console_print(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    return js_console_log_internal(ctx, this_val, argc, argv, 1); // INFO
}
static JSValue js_console_info(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    return js_console_log_internal(ctx, this_val, argc, argv, 1);
}
static JSValue js_console_warn(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    return js_console_log_internal(ctx, this_val, argc, argv, 2); // WARN
}
static JSValue js_console_error(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    return js_console_log_internal(ctx, this_val, argc, argv, 3); // ERROR
}
static JSValue js_console_debug(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    return js_console_log_internal(ctx, this_val, argc, argv, 0); // DEBUG
}

// velora.delay(ms)
static JSValue js_velora_delay(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    VeloraJsContext *vctx = (VeloraJsContext*)JS_GetContextOpaque(ctx);
    int64_t ms = 0;
    if (argc > 0) JS_ToInt64(ctx, &ms, argv[0]);
    if (ms < 0) ms = 0;
    if (vctx) {
        vctx->suspended_duration_nanos = ms * 1000000LL;
    }
    JSValue resolving_funcs[2];
    JSValue promise = JS_NewPromiseCapability(ctx, resolving_funcs);
    // Auto-resolve when resumed from Java
    JS_FreeValue(ctx, resolving_funcs[0]);
    JS_FreeValue(ctx, resolving_funcs[1]);
    return promise;
}

// velora.yield()
static JSValue js_velora_yield(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    VeloraJsContext *vctx = (VeloraJsContext*)JS_GetContextOpaque(ctx);
    if (vctx) vctx->is_suspended_yield = true;
    JSValue resolving_funcs[2];
    JSValue promise = JS_NewPromiseCapability(ctx, resolving_funcs);
    JS_FreeValue(ctx, resolving_funcs[0]);
    JS_FreeValue(ctx, resolving_funcs[1]);
    return promise;
}

// setting constructor helpers: setting.float(name, def, options)
static JSValue js_setting_create(JSContext *ctx, const char *type, int argc, JSValueConst *argv) {
    JSValue obj = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, obj, "__velora_setting", JS_NewBool(ctx, 1));
    JS_SetPropertyStr(ctx, obj, "type", JS_NewString(ctx, type));
    if (argc > 0) JS_SetPropertyStr(ctx, obj, "name", JS_DupValue(ctx, argv[0]));
    if (argc > 1) JS_SetPropertyStr(ctx, obj, "defaultValue", JS_DupValue(ctx, argv[1]));
    if (argc > 2 && JS_IsObject(argv[2])) {
        JS_SetPropertyStr(ctx, obj, "options", JS_DupValue(ctx, argv[2]));
    }
    return obj;
}

static JSValue js_setting_float(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    return js_setting_create(ctx, "Float", argc, argv);
}
static JSValue js_setting_int(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    return js_setting_create(ctx, "Int", argc, argv);
}
static JSValue js_setting_boolean(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    return js_setting_create(ctx, "Boolean", argc, argv);
}
static JSValue js_setting_string(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    return js_setting_create(ctx, "String", argc, argv);
}
static JSValue js_setting_mode(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    return js_setting_create(ctx, "Mode", argc, argv);
}
static JSValue js_setting_color(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    return js_setting_create(ctx, "Color", argc, argv);
}

// defineScript(descriptor)
static JSValue js_define_script(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    if (argc > 0 && JS_IsObject(argv[0])) {
        VeloraJsContext *vctx = (VeloraJsContext*)JS_GetContextOpaque(ctx);
        if (vctx) {
            JS_FreeValue(ctx, vctx->descriptor_val);
            vctx->descriptor_val = JS_DupValue(ctx, argv[0]);
        }
        return JS_DupValue(ctx, argv[0]);
    }
    return JS_UNDEFINED;
}

// Generic API call dispatch: api.namespace.func(args)
typedef struct ApiClosure {
    char ns[64];
    char fn[64];
} ApiClosure;

static JSValue js_api_call(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv, int magic, JSValue *func_data) {
    VeloraJsContext *vctx = (VeloraJsContext*)JS_GetContextOpaque(ctx);
    if (!vctx) return JS_UNDEFINED;
    const char *ns = JS_ToCString(ctx, func_data[0]);
    const char *fn = JS_ToCString(ctx, func_data[1]);
    extern JavaVM *g_jvm;
    JNIEnv *env = NULL;
    jobject result = NULL;
    if (g_jvm) {
        (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_8);
        if (env && vctx->host_global_ref) {
            jobjectArray args = (*env)->NewObjectArray(env, argc, (*env)->FindClass(env, "java/lang/Object"), NULL);
            for (int i = 0; i < argc; i++) {
                jobject j_arg = java_val_from_js(env, ctx, argv[i]);
                (*env)->SetObjectArrayElement(env, args, i, j_arg);
            }
            result = call_foreign_host_call_api(env, vctx->host_global_ref, vctx->script_id, ns, fn, args);
        }
    }
    JS_FreeCString(ctx, ns);
    JS_FreeCString(ctx, fn);
    return js_val_from_java(env, ctx, result);
}

static JSModuleDef *js_module_loader(JSContext *ctx, const char *module_name, void *opaque) {
    VeloraJsContext *vctx = (VeloraJsContext*)opaque;
    if (!vctx) return NULL;
    extern JavaVM *g_jvm;
    JNIEnv *env = NULL;
    if (!g_jvm) return NULL;
    (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_8);
    if (!env || !vctx->host_global_ref) return NULL;

    const char *path = module_name;
    if (path[0] == '.' && path[1] == '/') path += 2;
    jstring content_str = (jstring)call_foreign_host_read_file(env, vctx->host_global_ref, vctx->script_id, path);
    if (!content_str) return NULL;

    const char *source = (*env)->GetStringUTFChars(env, content_str, NULL);
    size_t len = strlen(source);
    JSValue func_val = JS_Eval(ctx, source, len, module_name, JS_EVAL_TYPE_MODULE | JS_EVAL_FLAG_COMPILE_ONLY);
    (*env)->ReleaseStringUTFChars(env, content_str, source);

    if (JS_IsException(func_val)) return NULL;
    JSModuleDef *m = (JSModuleDef*)JS_VALUE_GET_PTR(func_val);
    JS_FreeValue(ctx, func_val);
    return m;
}

static void register_namespace(JSContext *ctx, JSValue parent, const char *ns_name, const char **funcs, int count) {
    JSValue ns_obj = JS_NewObject(ctx);
    for (int i = 0; i < count; i++) {
        JSValue data[2];
        data[0] = JS_NewString(ctx, ns_name);
        data[1] = JS_NewString(ctx, funcs[i]);
        JSValue fn = JS_NewCFunctionData(ctx, (JSCFunctionData*)js_api_call, 0, 0, 2, data);
        JS_FreeValue(ctx, data[0]);
        JS_FreeValue(ctx, data[1]);
        JS_SetPropertyStr(ctx, ns_obj, funcs[i], fn);
    }
    JS_SetPropertyStr(ctx, parent, ns_name, ns_obj);
}

VeloraJsContext *velora_js_create(JNIEnv *env, const char *script_id, const char *main_file, const char *main_source, jobject host) {
    VeloraJsContext *vctx = (VeloraJsContext*)calloc(1, sizeof(VeloraJsContext));
    if (!vctx) return NULL;
    vctx->magic = JS_MAGIC;
    vctx->rt = JS_NewRuntime();
    if (!vctx->rt) { free(vctx); return NULL; }

    JS_SetMemoryLimit(vctx->rt, 64 * 1024 * 1024);
    JS_SetMaxStackSize(vctx->rt, 1024 * 1024);

    vctx->ctx = JS_NewContext(vctx->rt);
    if (!vctx->ctx) {
        JS_FreeRuntime(vctx->rt);
        free(vctx);
        return NULL;
    }
    JS_SetContextOpaque(vctx->ctx, vctx);

    if (host) vctx->host_global_ref = (*env)->NewGlobalRef(env, host);
    strncpy(vctx->script_id, script_id, sizeof(vctx->script_id) - 1);
    strncpy(vctx->main_file, main_file, sizeof(vctx->main_file) - 1);
    vctx->descriptor_val = JS_UNDEFINED;
    vctx->events_obj = JS_UNDEFINED;
    vctx->settings_obj = JS_UNDEFINED;

    JS_SetModuleLoaderFunc(vctx->rt, NULL, js_module_loader, vctx);

    JSValue global = JS_GetGlobalObject(vctx->ctx);

    // console
    JSValue console_obj = JS_NewObject(vctx->ctx);
    JS_SetPropertyStr(vctx->ctx, console_obj, "print", JS_NewCFunction(vctx->ctx, js_console_print, "print", 1));
    JS_SetPropertyStr(vctx->ctx, console_obj, "info", JS_NewCFunction(vctx->ctx, js_console_info, "info", 1));
    JS_SetPropertyStr(vctx->ctx, console_obj, "warn", JS_NewCFunction(vctx->ctx, js_console_warn, "warn", 1));
    JS_SetPropertyStr(vctx->ctx, console_obj, "error", JS_NewCFunction(vctx->ctx, js_console_error, "error", 1));
    JS_SetPropertyStr(vctx->ctx, console_obj, "debug", JS_NewCFunction(vctx->ctx, js_console_debug, "debug", 1));
    JS_SetPropertyStr(vctx->ctx, global, "console", console_obj);

    // setting
    JSValue setting_obj = JS_NewObject(vctx->ctx);
    JS_SetPropertyStr(vctx->ctx, setting_obj, "float", JS_NewCFunction(vctx->ctx, js_setting_float, "float", 2));
    JS_SetPropertyStr(vctx->ctx, setting_obj, "int", JS_NewCFunction(vctx->ctx, js_setting_int, "int", 2));
    JS_SetPropertyStr(vctx->ctx, setting_obj, "boolean", JS_NewCFunction(vctx->ctx, js_setting_boolean, "boolean", 2));
    JS_SetPropertyStr(vctx->ctx, setting_obj, "string", JS_NewCFunction(vctx->ctx, js_setting_string, "string", 2));
    JS_SetPropertyStr(vctx->ctx, setting_obj, "mode", JS_NewCFunction(vctx->ctx, js_setting_mode, "mode", 3));
    JS_SetPropertyStr(vctx->ctx, setting_obj, "color", JS_NewCFunction(vctx->ctx, js_setting_color, "color", 2));
    JS_SetPropertyStr(vctx->ctx, global, "setting", JS_DupValue(vctx->ctx, setting_obj));

    // velora
    JSValue velora_obj = JS_NewObject(vctx->ctx);
    JS_SetPropertyStr(vctx->ctx, velora_obj, "defineScript", JS_NewCFunction(vctx->ctx, js_define_script, "defineScript", 1));
    JS_SetPropertyStr(vctx->ctx, velora_obj, "define", JS_NewCFunction(vctx->ctx, js_define_script, "define", 1));
    JS_SetPropertyStr(vctx->ctx, velora_obj, "delay", JS_NewCFunction(vctx->ctx, js_velora_delay, "delay", 1));
    JS_SetPropertyStr(vctx->ctx, velora_obj, "yield", JS_NewCFunction(vctx->ctx, js_velora_yield, "yield", 0));
    JS_SetPropertyStr(vctx->ctx, velora_obj, "setting", setting_obj);
    vctx->settings_obj = JS_NewObject(vctx->ctx);
    JS_SetPropertyStr(vctx->ctx, velora_obj, "settings", JS_DupValue(vctx->ctx, vctx->settings_obj));
    JS_SetPropertyStr(vctx->ctx, global, "velora", velora_obj);
    JS_SetPropertyStr(vctx->ctx, global, "defineScript", JS_NewCFunction(vctx->ctx, js_define_script, "defineScript", 1));

    // Standard API namespaces
    const char *time_fns[] = {"now", "uptime", "sleep"};
    register_namespace(vctx->ctx, global, "time", time_fns, 3);
    const char *bytes_fns[] = {"alloc", "base64Encode", "base64Decode"};
    register_namespace(vctx->ctx, global, "bytes", bytes_fns, 3);
    const char *assets_fns[] = {"read", "write", "exists", "delete"};
    register_namespace(vctx->ctx, global, "assets", assets_fns, 4);

    // Evaluate main source
    size_t src_len = strlen(main_source);
    int eval_flags = JS_EVAL_TYPE_MODULE;
    if (strstr(main_source, "export default") == NULL && strstr(main_source, "import ") == NULL) {
        eval_flags = JS_EVAL_TYPE_GLOBAL;
    }

    JSValue eval_res = JS_Eval(vctx->ctx, main_source, src_len, main_file, eval_flags | (eval_flags == JS_EVAL_TYPE_MODULE ? JS_EVAL_FLAG_COMPILE_ONLY : 0));
    if (JS_IsException(eval_res)) {
        JSValue exc = JS_GetException(vctx->ctx);
        const char *err = JS_ToCString(vctx->ctx, exc);
        if (host) call_foreign_host_log(env, host, script_id, 3, err);
        JS_FreeCString(vctx->ctx, err);
        JS_FreeValue(vctx->ctx, exc);
    } else if (eval_flags == JS_EVAL_TYPE_MODULE) {
        JSModuleDef *m = (JSModuleDef*)JS_VALUE_GET_PTR(eval_res);
        eval_res = JS_EvalFunction(vctx->ctx, eval_res);
        if (!JS_IsException(eval_res)) {
            JSValue ns = JS_GetModuleNamespace(vctx->ctx, m);
            if (JS_IsObject(ns)) {
                JSValue def_exp = JS_GetPropertyStr(vctx->ctx, ns, "default");
                if (JS_IsObject(def_exp)) {
                    JS_FreeValue(vctx->ctx, vctx->descriptor_val);
                    vctx->descriptor_val = def_exp;
                    JSValue ev_obj = JS_GetPropertyStr(vctx->ctx, def_exp, "events");
                    if (JS_IsObject(ev_obj)) {
                        JS_FreeValue(vctx->ctx, vctx->events_obj);
                        vctx->events_obj = ev_obj;
                    } else {
                        JS_FreeValue(vctx->ctx, ev_obj);
                    }
                } else {
                    JS_FreeValue(vctx->ctx, def_exp);
                }
                JS_FreeValue(vctx->ctx, ns);
            }
        } else {
            JSValue exc = JS_GetException(vctx->ctx);
            const char *err = JS_ToCString(vctx->ctx, exc);
            if (host) call_foreign_host_log(env, host, script_id, 3, err);
            JS_FreeCString(vctx->ctx, err);
            JS_FreeValue(vctx->ctx, exc);
        }
    }
    JS_FreeValue(vctx->ctx, eval_res);
    JS_FreeValue(vctx->ctx, global);

    // Ticking microtasks
    JSContext *pctx;
    while (JS_ExecutePendingJob(vctx->rt, &pctx) > 0);

    return vctx;
}

jobject velora_js_extract_descriptor(JNIEnv *env, VeloraJsContext *ctx) {
    if (!ctx || !JS_IsObject(ctx->descriptor_val)) return NULL;
    JSContext *c = ctx->ctx;

    // name, version, author, description
    JSValue name_v = JS_GetPropertyStr(c, ctx->descriptor_val, "name");
    JSValue ver_v = JS_GetPropertyStr(c, ctx->descriptor_val, "version");
    JSValue auth_v = JS_GetPropertyStr(c, ctx->descriptor_val, "author");
    JSValue desc_v = JS_GetPropertyStr(c, ctx->descriptor_val, "description");

    const char *name_str = JS_ToCString(c, name_v);
    const char *ver_str = JS_ToCString(c, ver_v);
    const char *auth_str = JS_ToCString(c, auth_v);
    const char *desc_str = JS_ToCString(c, desc_v);

    jstring j_name = (*env)->NewStringUTF(env, name_str ? name_str : ctx->script_id);
    jstring j_ver = (*env)->NewStringUTF(env, ver_str ? ver_str : "1.0.0");
    jstring j_auth = (*env)->NewStringUTF(env, auth_str ? auth_str : "");
    jstring j_desc = (*env)->NewStringUTF(env, desc_str ? desc_str : "");

    if (name_str) JS_FreeCString(c, name_str);
    if (ver_str) JS_FreeCString(c, ver_str);
    if (auth_str) JS_FreeCString(c, auth_str);
    if (desc_str) JS_FreeCString(c, desc_str);
    JS_FreeValue(c, name_v);
    JS_FreeValue(c, ver_v);
    JS_FreeValue(c, auth_v);
    JS_FreeValue(c, desc_v);

    // Settings list
    jclass list_cls = (*env)->FindClass(env, "java/util/ArrayList");
    jmethodID list_init = (*env)->GetMethodID(env, list_cls, "<init>", "()V");
    jmethodID list_add = (*env)->GetMethodID(env, list_cls, "add", "(Ljava/lang/Object;)Z");
    jobject settings_list = (*env)->NewObject(env, list_cls, list_init);

    JSValue settings_obj = JS_GetPropertyStr(c, ctx->descriptor_val, "settings");
    if (JS_IsObject(settings_obj)) {
        JSPropertyEnum *ptab;
        uint32_t plen;
        if (JS_GetOwnPropertyNames(c, &ptab, &plen, settings_obj, JS_GPN_STRING_MASK) >= 0) {
            jclass setdef_cls = (*env)->FindClass(env, "io/velora/internal/foreign/ForeignSettingDef");
            jmethodID setdef_init = (*env)->GetMethodID(env, setdef_cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Double;Ljava/lang/Double;Ljava/lang/Double;[Ljava/lang/String;)V");

            for (uint32_t i = 0; i < plen; i++) {
                const char *prop_key = JS_AtomToCString(c, ptab[i].atom);
                JSValue item = JS_GetProperty(c, settings_obj, ptab[i].atom);
                if (JS_IsObject(item)) {
                    JSValue type_v = JS_GetPropertyStr(c, item, "type");
                    JSValue item_name_v = JS_GetPropertyStr(c, item, "name");
                    JSValue def_v = JS_GetPropertyStr(c, item, "defaultValue");
                    JSValue opts_v = JS_GetPropertyStr(c, item, "options");

                    const char *type_s = JS_ToCString(c, type_v);
                    const char *iname_s = JS_ToCString(c, item_name_v);

                    jstring j_id = (*env)->NewStringUTF(env, prop_key);
                    jstring j_sname = (*env)->NewStringUTF(env, iname_s ? iname_s : prop_key);
                    jstring j_stype = (*env)->NewStringUTF(env, type_s ? type_s : "String");
                    jobject j_sdef = java_val_from_js(env, c, def_v);

                    // Min/Max/Step/Options
                    jobject j_min = NULL, j_max = NULL, j_step = NULL;
                    jobjectArray j_options = NULL;
                    if (JS_IsObject(opts_v)) {
                        JSValue min_v = JS_GetPropertyStr(c, opts_v, "min");
                        JSValue max_v = JS_GetPropertyStr(c, opts_v, "max");
                        JSValue step_v = JS_GetPropertyStr(c, opts_v, "step");
                        jclass dbl_cls = (*env)->FindClass(env, "java/lang/Double");
                        jmethodID dbl_of = (*env)->GetStaticMethodID(env, dbl_cls, "valueOf", "(D)Ljava/lang/Double;");
                        double val;
                        if (JS_ToFloat64(c, &val, min_v) >= 0) j_min = (*env)->CallStaticObjectMethod(env, dbl_cls, dbl_of, val);
                        if (JS_ToFloat64(c, &val, max_v) >= 0) j_max = (*env)->CallStaticObjectMethod(env, dbl_cls, dbl_of, val);
                        if (JS_ToFloat64(c, &val, step_v) >= 0) j_step = (*env)->CallStaticObjectMethod(env, dbl_cls, dbl_of, val);
                        JS_FreeValue(c, min_v);
                        JS_FreeValue(c, max_v);
                        JS_FreeValue(c, step_v);
                    }

                    jobject setdef_obj = (*env)->NewObject(env, setdef_cls, setdef_init, j_id, j_sname, j_stype, j_sdef, j_min, j_max, j_step, j_options);
                    (*env)->CallBooleanMethod(env, settings_list, list_add, setdef_obj);

                    if (type_s) JS_FreeCString(c, type_s);
                    if (iname_s) JS_FreeCString(c, iname_s);
                    JS_FreeValue(c, type_v);
                    JS_FreeValue(c, item_name_v);
                    JS_FreeValue(c, def_v);
                    JS_FreeValue(c, opts_v);
                }
                JS_FreeValue(c, item);
                JS_FreeCString(c, prop_key);
                JS_FreeAtom(c, ptab[i].atom);
            }
            js_free(c, ptab);
        }
    }
    JS_FreeValue(c, settings_obj);

    // Lifecycle hooks
    jobject hooks_list = (*env)->NewObject(env, list_cls, list_init);
    const char *possible_hooks[] = {"onLoad", "onEnable", "onDisable", "onUnload", "onRun"};
    const char *norm_hooks[] = {"ON_LOAD", "ON_ENABLE", "ON_DISABLE", "ON_UNLOAD", "ON_RUN"};
    for (int i = 0; i < 5; i++) {
        JSValue fn = JS_GetPropertyStr(c, ctx->descriptor_val, possible_hooks[i]);
        if (JS_IsFunction(c, fn)) {
            jstring j_hook = (*env)->NewStringUTF(env, norm_hooks[i]);
            (*env)->CallBooleanMethod(env, hooks_list, list_add, j_hook);
        }
        JS_FreeValue(c, fn);
    }

    // Events list
    jobject events_list = (*env)->NewObject(env, list_cls, list_init);
    JSValue ev_obj = JS_GetPropertyStr(c, ctx->descriptor_val, "events");
    if (JS_IsObject(ev_obj)) {
        JS_FreeValue(c, ctx->events_obj);
        ctx->events_obj = JS_DupValue(c, ev_obj);
        JSPropertyEnum *ptab;
        uint32_t plen;
        if (JS_GetOwnPropertyNames(c, &ptab, &plen, ev_obj, JS_GPN_STRING_MASK) >= 0) {
            for (uint32_t i = 0; i < plen; i++) {
                const char *ev_name = JS_AtomToCString(c, ptab[i].atom);
                jstring j_ev = (*env)->NewStringUTF(env, ev_name);
                (*env)->CallBooleanMethod(env, events_list, list_add, j_ev);
                JS_FreeCString(c, ev_name);
                JS_FreeAtom(c, ptab[i].atom);
            }
            js_free(c, ptab);
        }
    }
    JS_FreeValue(c, ev_obj);

    // Construct ForeignScriptDescriptor
    jclass desc_cls = (*env)->FindClass(env, "io/velora/internal/foreign/ForeignScriptDescriptor");
    jmethodID desc_init = (*env)->GetMethodID(env, desc_cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/List;Ljava/util/List;Ljava/util/List;)V");
    return (*env)->NewObject(env, desc_cls, desc_init, j_name, j_ver, j_auth, j_desc, settings_list, hooks_list, events_list);
}

jobject velora_js_invoke_hook(JNIEnv *env, VeloraJsContext *ctx, const char *hook_name) {
    if (!ctx || !JS_IsObject(ctx->descriptor_val)) return create_execution_result_error(env, "No script descriptor");
    JSContext *c = ctx->ctx;

    // Map ON_LOAD -> onLoad, etc.
    const char *prop = "onRun";
    if (strcmp(hook_name, "ON_LOAD") == 0) prop = "onLoad";
    else if (strcmp(hook_name, "ON_ENABLE") == 0) prop = "onEnable";
    else if (strcmp(hook_name, "ON_DISABLE") == 0) prop = "onDisable";
    else if (strcmp(hook_name, "ON_UNLOAD") == 0) prop = "onUnload";

    JSValue fn = JS_GetPropertyStr(c, ctx->descriptor_val, prop);
    if (!JS_IsFunction(c, fn)) {
        JS_FreeValue(c, fn);
        return create_execution_result_success(env, NULL);
    }

    ctx->suspended_duration_nanos = 0;
    ctx->is_suspended_yield = false;

    JSValue res = JS_Call(c, fn, ctx->descriptor_val, 0, NULL);
    JS_FreeValue(c, fn);

    // Ticking microtasks
    JSContext *pctx;
    while (JS_ExecutePendingJob(ctx->rt, &pctx) > 0);

    if (JS_IsException(res)) {
        JSValue exc = JS_GetException(c);
        const char *err = JS_ToCString(c, exc);
        jobject r = create_execution_result_error(env, err ? err : "Exception in hook");
        if (err) JS_FreeCString(c, err);
        JS_FreeValue(c, exc);
        JS_FreeValue(c, res);
        return r;
    }

    if (ctx->suspended_duration_nanos > 0) {
        int64_t dur = ctx->suspended_duration_nanos;
        ctx->suspended_duration_nanos = 0;
        JS_FreeValue(c, res);
        return create_execution_result_delay(env, dur);
    }
    if (ctx->is_suspended_yield) {
        ctx->is_suspended_yield = false;
        JS_FreeValue(c, res);
        return create_execution_result_yield(env);
    }

    jobject j_res = java_val_from_js(env, c, res);
    JS_FreeValue(c, res);
    return create_execution_result_success(env, j_res);
}

jobject velora_js_dispatch_event(JNIEnv *env, VeloraJsContext *ctx, const char *event_name, jobject event_payload) {
    if (!ctx || !JS_IsObject(ctx->events_obj)) return create_execution_result_success(env, NULL);
    JSContext *c = ctx->ctx;
    JSValue fn = JS_GetPropertyStr(c, ctx->events_obj, event_name);
    if (!JS_IsFunction(c, fn)) {
        JS_FreeValue(c, fn);
        return create_execution_result_success(env, NULL);
    }

    JSValue arg = js_val_from_java(env, c, event_payload);
    JSValue res = JS_Call(c, fn, ctx->events_obj, 1, &arg);
    JS_FreeValue(c, arg);
    JS_FreeValue(c, fn);

    JSContext *pctx;
    while (JS_ExecutePendingJob(ctx->rt, &pctx) > 0);

    if (JS_IsException(res)) {
        JSValue exc = JS_GetException(c);
        const char *err = JS_ToCString(c, exc);
        jobject r = create_execution_result_error(env, err ? err : "Exception in event");
        if (err) JS_FreeCString(c, err);
        JS_FreeValue(c, exc);
        JS_FreeValue(c, res);
        return r;
    }

    jobject j_res = java_val_from_js(env, c, res);
    JS_FreeValue(c, res);
    return create_execution_result_success(env, j_res);
}

jobject velora_js_resume(JNIEnv *env, VeloraJsContext *ctx, int64_t fiber_id, jobject result, const char *error) {
    if (!ctx) return create_execution_result_error(env, "Invalid context");
    JSContext *c = ctx->ctx;
    ctx->suspended_duration_nanos = 0;
    ctx->is_suspended_yield = false;

    JSContext *pctx;
    while (JS_ExecutePendingJob(ctx->rt, &pctx) > 0);

    if (ctx->suspended_duration_nanos > 0) {
        int64_t dur = ctx->suspended_duration_nanos;
        ctx->suspended_duration_nanos = 0;
        return create_execution_result_delay(env, dur);
    }
    if (ctx->is_suspended_yield) {
        ctx->is_suspended_yield = false;
        return create_execution_result_yield(env);
    }
    return create_execution_result_success(env, NULL);
}

void velora_js_update_setting(JNIEnv *env, VeloraJsContext *ctx, const char *key, jobject value) {
    if (!ctx || !JS_IsObject(ctx->settings_obj)) return;
    JSValue val = js_val_from_java(env, ctx->ctx, value);
    JS_SetPropertyStr(ctx->ctx, ctx->settings_obj, key, val);
}

void velora_js_close(JNIEnv *env, VeloraJsContext *ctx) {
    if (!ctx) return;
    if (ctx->host_global_ref) {
        (*env)->DeleteGlobalRef(env, ctx->host_global_ref);
        ctx->host_global_ref = NULL;
    }
    JS_FreeValue(ctx->ctx, ctx->descriptor_val);
    JS_FreeValue(ctx->ctx, ctx->events_obj);
    JS_FreeValue(ctx->ctx, ctx->settings_obj);
    JS_FreeContext(ctx->ctx);
    JS_FreeRuntime(ctx->rt);
    free(ctx);
}

jobject velora_js_check_syntax(JNIEnv *env, const char *code, const char *file_path) {
    jclass list_cls = (*env)->FindClass(env, "java/util/ArrayList");
    jmethodID list_init = (*env)->GetMethodID(env, list_cls, "<init>", "()V");
    jmethodID list_add = (*env)->GetMethodID(env, list_cls, "add", "(Ljava/lang/Object;)Z");
    jobject list = (*env)->NewObject(env, list_cls, list_init);

    JSRuntime *rt = JS_NewRuntime();
    if (!rt) return list;
    JSContext *ctx = JS_NewContext(rt);
    if (!ctx) { JS_FreeRuntime(rt); return list; }

    size_t len = strlen(code);
    JSValue val = JS_Eval(ctx, code, len, file_path, JS_EVAL_TYPE_MODULE | JS_EVAL_FLAG_COMPILE_ONLY);
    if (JS_IsException(val)) {
        JSValue exc = JS_GetException(ctx);
        const char *msg = JS_ToCString(ctx, exc);
        int line = 1, col = 1;
        JSValue line_v = JS_GetPropertyStr(ctx, exc, "lineNumber");
        if (!JS_IsUndefined(line_v)) JS_ToInt32(ctx, &line, line_v);
        JS_FreeValue(ctx, line_v);
        JSValue col_v = JS_GetPropertyStr(ctx, exc, "columnNumber");
        if (!JS_IsUndefined(col_v)) JS_ToInt32(ctx, &col, col_v);
        JS_FreeValue(ctx, col_v);

        jobject diag = create_diagnostic_error(env, "PARSER_UNEXPECTED_TOKEN", msg ? msg : "JS Syntax Error", file_path, line, col);
        (*env)->CallBooleanMethod(env, list, list_add, diag);

        if (msg) JS_FreeCString(ctx, msg);
        JS_FreeValue(ctx, exc);
    }
    JS_FreeValue(ctx, val);
    JS_FreeContext(ctx);
    JS_FreeRuntime(rt);
    return list;
}

static JSValue js_val_from_java(JNIEnv *env, JSContext *ctx, jobject obj) {
    if (!obj || !env) return JS_NULL;
    jclass str_cls = (*env)->FindClass(env, "java/lang/String");
    if ((*env)->IsInstanceOf(env, obj, str_cls)) {
        const char *chars = (*env)->GetStringUTFChars(env, (jstring)obj, NULL);
        JSValue v = JS_NewString(ctx, chars);
        (*env)->ReleaseStringUTFChars(env, (jstring)obj, chars);
        return v;
    }
    jclass num_cls = (*env)->FindClass(env, "java/lang/Number");
    if ((*env)->IsInstanceOf(env, obj, num_cls)) {
        jmethodID dbl_mid = (*env)->GetMethodID(env, num_cls, "doubleValue", "()D");
        double d = (*env)->CallDoubleMethod(env, obj, dbl_mid);
        return JS_NewFloat64(ctx, d);
    }
    jclass bool_cls = (*env)->FindClass(env, "java/lang/Boolean");
    if ((*env)->IsInstanceOf(env, obj, bool_cls)) {
        jmethodID bool_mid = (*env)->GetMethodID(env, bool_cls, "booleanValue", "()Z");
        jboolean b = (*env)->CallBooleanMethod(env, obj, bool_mid);
        return JS_NewBool(ctx, b ? 1 : 0);
    }
    return JS_NULL;
}

static jobject java_val_from_js(JNIEnv *env, JSContext *ctx, JSValue val) {
    if (!env) return NULL;
    int tag = JS_VALUE_GET_TAG(val);
    if (tag == JS_TAG_NULL || tag == JS_TAG_UNDEFINED) return NULL;
    if (tag == JS_TAG_BOOL) {
        jclass cls = (*env)->FindClass(env, "java/lang/Boolean");
        jmethodID mid = (*env)->GetStaticMethodID(env, cls, "valueOf", "(Z)Ljava/lang/Boolean;");
        return (*env)->CallStaticObjectMethod(env, cls, mid, (jboolean)JS_VALUE_GET_BOOL(val));
    }
    if (tag == JS_TAG_INT) {
        jclass cls = (*env)->FindClass(env, "java/lang/Integer");
        jmethodID mid = (*env)->GetStaticMethodID(env, cls, "valueOf", "(I)Ljava/lang/Integer;");
        return (*env)->CallStaticObjectMethod(env, cls, mid, (jint)JS_VALUE_GET_INT(val));
    }
    if (JS_TAG_IS_FLOAT64(tag)) {
        jclass cls = (*env)->FindClass(env, "java/lang/Double");
        jmethodID mid = (*env)->GetStaticMethodID(env, cls, "valueOf", "(D)Ljava/lang/Double;");
        return (*env)->CallStaticObjectMethod(env, cls, mid, (jdouble)JS_VALUE_GET_FLOAT64(val));
    }
    if (tag == JS_TAG_STRING) {
        const char *s = JS_ToCString(ctx, val);
        jstring js = (*env)->NewStringUTF(env, s ? s : "");
        if (s) JS_FreeCString(ctx, s);
        return js;
    }
    return NULL;
}
