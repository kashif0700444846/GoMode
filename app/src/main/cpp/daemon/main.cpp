/**
 * GodMode Root Daemon (godmoded)
 *
 * Runs as root. Responsibilities:
 * 1. Monitor process creation via /proc polling or netlink
 * 2. Inject godmode_hook.so into new app processes
 * 3. Serve IPC socket for hook libraries and Android app
 * 4. Manage spoofing configuration database
 * 5. Relay access logs to the Android app
 * 6. Persist across reboots (started by boot script or app)
 */

#include "process_monitor.h"
#include "injector.h"
#include "config_manager.h"
#include "../ipc/socket_server.h"
#include <android/log.h>
#include <unistd.h>
#include <signal.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <errno.h>
#include <pthread.h>

#define TAG "GodMode_Daemon"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define DAEMON_VERSION "1.0.0"
#define PID_FILE "/data/local/tmp/godmoded.pid"
#define LOG_FILE "/data/local/tmp/godmoded.log"

static volatile bool g_running = true;

// IPC message handler - called for each message received from clients
static void on_ipc_message(int client_fd, const char* message) {
    LOGI("IPC msg from fd=%d: %.100s", client_fd, message);

    if (strncmp(message, "LOG|", 4) == 0) {
        // Access log from hook library - store and forward to app
        config_manager_store_log(message + 4);

        // Forward to Android app (app connects as a special client)
        char fwd[8192];
        snprintf(fwd, sizeof(fwd), "LOG|%s", message + 4);
        socket_server_broadcast(fwd);

    } else if (strncmp(message, "GET_CONFIG|", 11) == 0) {
        // Hook library requesting config for a package
        const char* pkg = message + 11;
        char config[4096] = {0};
        config_manager_get_config(pkg, config, sizeof(config));
        socket_server_send_to(client_fd, config);

    } else if (strncmp(message, "SET_CONFIG|", 11) == 0) {
        // Android app setting config for a package
        config_manager_set_config(message + 11);
        // Broadcast config update to all hook instances of this app
        char broadcast[4096];
        snprintf(broadcast, sizeof(broadcast), "CONFIG|%s", message + 11);
        socket_server_broadcast(broadcast);

    } else if (strncmp(message, "GET_LOGS|", 9) == 0) {
        // Android app requesting logs
        const char* pkg = message + 9;
        config_manager_send_logs(client_fd, pkg);

    } else if (strncmp(message, "CLEAR_LOGS|", 11) == 0) {
        const char* pkg = message + 11;
        config_manager_clear_logs(pkg);

    } else if (strncmp(message, "GET_APPS", 8) == 0) {
        // Android app requesting list of monitored apps
        config_manager_send_app_list(client_fd);

    } else if (strncmp(message, "STATUS", 6) == 0) {
        // Status ping from app
        char status[256];
        snprintf(status, sizeof(status), "STATUS_OK|version=%s|pid=%d", DAEMON_VERSION, getpid());
        socket_server_send_to(client_fd, status);

    } else if (strncmp(message, "INJECT|", 7) == 0) {
        // Manual inject request: INJECT|<pid>
        pid_t pid = atoi(message + 7);
        if (pid > 0) {
            injector_inject_process(pid);
        }

    } else if (strncmp(message, "SHUTDOWN", 8) == 0) {
        LOGI("Shutdown requested");
        g_running = false;
    }
}

static void signal_handler(int sig) {
    LOGI("Signal %d received, shutting down", sig);
    g_running = false;
}

static void write_pid_file() {
    FILE* f = fopen(PID_FILE, "w");
    if (f) {
        fprintf(f, "%d\n", getpid());
        fclose(f);
    }
}

static bool check_already_running() {
    FILE* f = fopen(PID_FILE, "r");
    if (!f) return false;
    int pid = 0;
    fscanf(f, "%d", &pid);
    fclose(f);
    if (pid <= 0) return false;
    // Check if process is still running
    char proc_path[64];
    snprintf(proc_path, sizeof(proc_path), "/proc/%d/status", pid);
    return access(proc_path, F_OK) == 0;
}

// Process creation callback - called when a new app process is detected
static void on_process_created(pid_t pid, const char* package_name) {
    LOGI("New process: pid=%d pkg=%s", pid, package_name);

    // Skip system processes and ourselves
    if (!package_name || package_name[0] == '\0') return;
    if (strncmp(package_name, "com.godmode.app", 15) == 0) return;
    if (strncmp(package_name, "system", 6) == 0) return;
    if (strncmp(package_name, "android", 7) == 0) return;

    // Small delay to let the process initialize
    usleep(200000); // 200ms

    // Inject our hook library
    if (injector_inject_process(pid)) {
        LOGI("Successfully injected into %s (pid=%d)", package_name, pid);
    } else {
        LOGE("Failed to inject into %s (pid=%d)", package_name, pid);
    }
}

int main(int argc, char* argv[]) {
    // Set process name
    prctl(PR_SET_NAME, "godmoded", 0, 0, 0);

    LOGI("GodMode Daemon v%s starting (pid=%d)", DAEMON_VERSION, getpid());

    // Check if already running
    if (check_already_running()) {
        LOGI("Daemon already running, exiting");
        return 0;
    }

    // Write PID file
    write_pid_file();

    // Set up signal handlers
    signal(SIGTERM, signal_handler);
    signal(SIGINT, signal_handler);
    signal(SIGPIPE, SIG_IGN);

    // Initialize config manager (SQLite database)
    if (!config_manager_init("/data/local/tmp/godmode.db")) {
        LOGE("Failed to initialize config manager");
        return 1;
    }

    // Start IPC socket server
    if (!socket_server_start(on_ipc_message)) {
        LOGE("Failed to start socket server");
        return 1;
    }

    // Start process monitor
    if (!process_monitor_start(on_process_created)) {
        LOGE("Failed to start process monitor");
        // Not fatal - we can still serve config
    }

    LOGI("GodMode Daemon ready");

    // Main loop
    while (g_running) {
        sleep(1);
    }

    LOGI("GodMode Daemon shutting down");

    process_monitor_stop();
    socket_server_stop();
    config_manager_cleanup();

    unlink(PID_FILE);

    return 0;
}
