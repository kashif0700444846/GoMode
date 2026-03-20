/**
 * GodMode - Process Monitor
 *
 * Monitors /proc for new processes and identifies Android app launches.
 * Uses a combination of:
 * 1. /proc polling (reliable, works on all Android versions)
 * 2. inotify on /proc (faster notification)
 *
 * When a new app process is detected, calls the registered callback.
 */

#include "process_monitor.h"
#include <android/log.h>
#include <dirent.h>
#include <unistd.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <fcntl.h>
#include <errno.h>
#include <pthread.h>
#include <sys/inotify.h>
#include <sys/stat.h>
#include <time.h>

#define TAG "GodMode_ProcMon"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static process_callback_t g_callback = nullptr;
static pthread_t g_monitor_thread;
static volatile bool g_running = false;

// Track known PIDs to detect new ones
#define MAX_TRACKED_PIDS 1024
static pid_t g_known_pids[MAX_TRACKED_PIDS];
static char g_pid_packages[MAX_TRACKED_PIDS][256];
static int g_tracked_count = 0;
static pthread_mutex_t g_pids_mutex = PTHREAD_MUTEX_INITIALIZER;

// Read the package name from /proc/<pid>/cmdline
static bool get_package_name(pid_t pid, char* out, size_t max_len) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);

    int fd = open(path, O_RDONLY);
    if (fd < 0) return false;

    ssize_t n = read(fd, out, max_len - 1);
    close(fd);

    if (n <= 0) return false;
    out[n] = '\0';

    // cmdline uses null bytes as separators
    // First token is the process name (usually package name)
    for (int i = 0; i < n; i++) {
        if (out[i] == '\0' || out[i] == ':') {
            out[i] = '\0';
            break;
        }
    }

    return out[0] != '\0';
}

// Check if a PID looks like an Android app (has a package name with dots)
static bool is_android_app(const char* name) {
    if (!name || name[0] == '\0') return false;
    // Android packages have at least one dot
    bool has_dot = false;
    for (const char* p = name; *p; p++) {
        if (*p == '.') { has_dot = true; break; }
    }
    if (!has_dot) return false;

    // Skip system processes
    if (strcmp(name, "android.process.acore") == 0) return false;
    if (strncmp(name, "system_server", 13) == 0) return false;
    if (strncmp(name, "zygote", 6) == 0) return false;
    if (strncmp(name, "webview_zygote", 14) == 0) return false;
    if (strncmp(name, "app_zygote", 10) == 0) return false;

    return true;
}

// Get UID for a process
static int get_process_uid(pid_t pid) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/status", pid);

    FILE* f = fopen(path, "r");
    if (!f) return -1;

    char line[256];
    int uid = -1;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "Uid:", 4) == 0) {
            sscanf(line + 4, "%d", &uid);
            break;
        }
    }
    fclose(f);
    return uid;
}

static bool is_known(pid_t pid) {
    for (int i = 0; i < g_tracked_count; i++) {
        if (g_known_pids[i] == pid) return true;
    }
    return false;
}

static void add_known(pid_t pid, const char* pkg) {
    if (g_tracked_count < MAX_TRACKED_PIDS) {
        g_known_pids[g_tracked_count] = pid;
        if (pkg) strncpy(g_pid_packages[g_tracked_count], pkg, 255);
        else g_pid_packages[g_tracked_count][0] = '\0';
        g_tracked_count++;
    }
}

static void* monitor_thread_func(void* arg) {
    LOGI("Process monitor thread started");

    while (g_running) {
        usleep(500000); // Poll every 500ms

        DIR* dir = opendir("/proc");
        if (!dir) continue;

        struct dirent* entry;
        pid_t current_pids[MAX_TRACKED_PIDS];
        int current_count = 0;

        while ((entry = readdir(dir)) != nullptr && current_count < MAX_TRACKED_PIDS) {
            if (entry->d_type != DT_DIR) continue;
            pid_t pid = atoi(entry->d_name);
            if (pid <= 0) continue;
            current_pids[current_count++] = pid;
        }
        closedir(dir);

        pthread_mutex_lock(&g_pids_mutex);

        // Find new PIDs
        for (int i = 0; i < current_count; i++) {
            pid_t pid = current_pids[i];
            if (!is_known(pid)) {
                char pkg[256] = {0};
                if (get_package_name(pid, pkg, sizeof(pkg))) {
                    if (is_android_app(pkg)) {
                        int uid = get_process_uid(pid);
                        if (uid >= 10000) {
                            LOGI("New app process: pid=%d pkg=%s uid=%d", pid, pkg, uid);
                            if (g_callback) {
                                pthread_mutex_unlock(&g_pids_mutex);
                                g_callback(pid, pkg);
                                pthread_mutex_lock(&g_pids_mutex);
                            }
                        }
                    }
                }
                add_known(pid, pkg);
            }
        }

        // Remove dead PIDs
        for (int i = 0; i < g_tracked_count; i++) {
            bool found = false;
            for (int j = 0; j < current_count; j++) {
                if (g_known_pids[i] == current_pids[j]) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                // Remove this one
                g_known_pids[i] = g_known_pids[g_tracked_count - 1];
                memcpy(g_pid_packages[i], g_pid_packages[g_tracked_count - 1], 256);
                g_tracked_count--;
                i--; // Check the swapped one
            }
        }

        pthread_mutex_unlock(&g_pids_mutex);
    }

    LOGI("Process monitor thread stopped");
    return nullptr;
}

bool process_monitor_start(process_callback_t callback) {
    g_callback = callback;
    g_running = true;

    if (pthread_create(&g_monitor_thread, nullptr, monitor_thread_func, nullptr) != 0) {
        LOGE("Failed to create monitor thread: %s", strerror(errno));
        return false;
    }
    pthread_detach(g_monitor_thread);

    LOGI("Process monitor started");
    return true;
}

void process_monitor_stop() {
    g_running = false;
    LOGI("Process monitor stopped");
}

const char* process_monitor_get_package(pid_t pid) {
    pthread_mutex_lock(&g_pids_mutex);
    for (int i = 0; i < g_tracked_count; i++) {
        if (g_known_pids[i] == pid) {
            const char* res = g_pid_packages[i];
            pthread_mutex_unlock(&g_pids_mutex);
            return res;
        }
    }
    pthread_mutex_unlock(&g_pids_mutex);
    return nullptr;
}
