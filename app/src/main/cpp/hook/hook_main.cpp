/**
 * GodMode - Hook Library Entry Point
 *
 * This shared library is injected into target app processes by the
 * GodMode root daemon. When loaded, it installs all hooks and connects
 * to the daemon's IPC socket for configuration and logging.
 *
 * Entry point: godmode_init() called via constructor attribute.
 */

#include "hook_main.h"
#include "binder_hook.h"
#include "property_hook.h"
#include "../ipc/socket_client.h"
#include "../spoof/spoof_engine.h"
#include <android/log.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <sys/prctl.h>

#define TAG "GodMode_Main"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static bool g_initialized = false;
static pthread_t g_config_thread;

// Background thread to receive config updates from daemon
static void* config_update_thread(void* arg) {
    prctl(PR_SET_NAME, "godmode_cfg", 0, 0, 0);

    while (true) {
        char msg[4096] = {0};
        if (socket_client_receive(msg, sizeof(msg))) {
            if (strncmp(msg, "CONFIG|", 7) == 0) {
                spoof_engine_update_config(msg + 7);
                LOGI("Config updated: %s", msg + 7);
            } else if (strncmp(msg, "SHUTDOWN", 8) == 0) {
                LOGI("Shutdown requested");
                break;
            }
        }
        usleep(100000); // 100ms poll
    }
    return nullptr;
}

// Called automatically when library is loaded into a process
__attribute__((constructor))
static void godmode_init() {
    if (g_initialized) return;
    g_initialized = true;

    LOGI("GodMode hook library loading...");

    // Connect to daemon IPC socket
    if (!socket_client_connect("/dev/socket/godmoded")) {
        LOGE("Failed to connect to GodMode daemon socket");
        // Continue anyway - will work in log-only mode
    }

    // Load spoofing configuration for this process
    char pkg[256] = {0};
    FILE* f = fopen("/proc/self/cmdline", "r");
    if (f) {
        fgets(pkg, sizeof(pkg) - 1, f);
        fclose(f);
        for (int i = 0; i < (int)sizeof(pkg); i++) {
            if (pkg[i] == ':' || pkg[i] == '\0') { pkg[i] = '\0'; break; }
        }
    }

    spoof_engine_init(pkg);

    // Install hooks
    bool binder_ok = binder_hook_install();
    bool prop_ok = property_hook_install();

    LOGI("GodMode hooks installed for %s: binder=%d props=%d",
         pkg, binder_ok, prop_ok);

    // Start config update thread
    pthread_create(&g_config_thread, nullptr, config_update_thread, nullptr);
    pthread_detach(g_config_thread);
}

// Called when library is unloaded
__attribute__((destructor))
static void godmode_cleanup() {
    if (!g_initialized) return;
    LOGI("GodMode hook library unloading...");
    binder_hook_remove();
    property_hook_remove();
    socket_client_disconnect();
    g_initialized = false;
}
