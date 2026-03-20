/**
 * GodMode - System Property Hook
 *
 * Hooks __system_property_get and __system_property_find to intercept
 * and spoof system properties like:
 *   - ro.serialno (device serial)
 *   - ro.product.model (device model)
 *   - ro.product.brand (device brand)
 *   - ro.product.manufacturer
 *   - ro.build.fingerprint
 *   - net.hostname
 *   - persist.sys.timezone
 *   - gsm.sim.operator.numeric (MCC/MNC)
 */

#include "property_hook.h"
#include "inline_hook.h"
#include "../spoof/spoof_engine.h"
#include <android/log.h>
#include <sys/system_properties.h>
#include <dlfcn.h>
#include <string.h>
#include <stdlib.h>

#define TAG "GodMode_PropHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef int (*prop_get_fn_t)(const char* name, char* value, const char* default_value);
typedef const prop_info* (*prop_find_fn_t)(const char* name);
typedef void (*prop_read_fn_t)(const prop_info* pi, char* name, char* value);

static prop_get_fn_t original_prop_get = nullptr;
static prop_find_fn_t original_prop_find = nullptr;
static prop_read_fn_t original_prop_read = nullptr;

// Properties we care about spoofing
static const char* SPOOFABLE_PROPS[] = {
    "ro.serialno",
    "ro.boot.serialno",
    "ro.product.model",
    "ro.product.brand",
    "ro.product.name",
    "ro.product.device",
    "ro.product.manufacturer",
    "ro.build.fingerprint",
    "ro.build.id",
    "ro.build.display.id",
    "net.hostname",
    "gsm.sim.operator.numeric",
    "gsm.sim.operator.alpha",
    "gsm.sim.imsi",
    "gsm.imei",
    nullptr
};

static bool is_spoofable_prop(const char* name) {
    for (int i = 0; SPOOFABLE_PROPS[i] != nullptr; i++) {
        if (strcmp(name, SPOOFABLE_PROPS[i]) == 0) return true;
    }
    return false;
}

// Hooked __system_property_get
int hooked_prop_get(const char* name, char* value, const char* default_value) {
    int result = original_prop_get(name, value, default_value);

    if (name && value && is_spoofable_prop(name)) {
        char spoofed[PROP_VALUE_MAX] = {0};
        if (spoof_engine_get_property(name, value, spoofed)) {
            strncpy(value, spoofed, PROP_VALUE_MAX - 1);
            LOGI("Spoofed prop %s: %s -> %s", name, value, spoofed);
        }
    }

    return result;
}

bool property_hook_install() {
    void* libc = dlopen("libc.so", RTLD_NOW);
    if (!libc) {
        LOGE("Failed to open libc.so");
        return false;
    }

    void* prop_get_addr = dlsym(libc, "__system_property_get");
    dlclose(libc);

    if (!prop_get_addr) {
        LOGE("Failed to find __system_property_get");
        return false;
    }

    bool ok = inline_hook_install(prop_get_addr, (void*)hooked_prop_get,
                                   (void**)&original_prop_get);
    if (!ok) {
        LOGE("Failed to install property hook");
        return false;
    }

    LOGI("Property hook installed");
    return true;
}

void property_hook_remove() {
    if (original_prop_get) {
        void* libc = dlopen("libc.so", RTLD_NOW);
        if (libc) {
            void* addr = dlsym(libc, "__system_property_get");
            dlclose(libc);
            if (addr) inline_hook_remove(addr);
        }
        original_prop_get = nullptr;
    }
}
