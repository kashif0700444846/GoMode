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
#include <set>
#include <string>
#include <map>

#define TAG "GodMode_ProcMon"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static process_callback_t g_callback = nullptr;
static pthread_t g_monitor_thread;
static volatile bool g_running = false;

// Track known PIDs to detect new ones
static std::set<pid_t> g_known_pids;
static std::map<pid_t, std::string> g_pid_packages;
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
        if (out[i] == '\0') {
            out[i] = '\0';
            break;
        }
        if (out[i] == ':') {
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

// Scan /proc for all current PIDs
static void scan_proc(std::set<pid_t>& pids) {
    DIR* dir = opendir("/proc");
    if (!dir) return;

    struct dirent* entry;
    while ((entry = readdir(dir)) != nullptr) {
        if (entry->d_type != DT_DIR) continue;
        pid_t pid = atoi(entry->d_name);
        if (pid <= 0) continue;
        pids.insert(pid);
    }
    closedir(dir);
}

static void* monitor_thread_func(void* arg) {
    LOGI("Process monitor thread started");

    // Initialize known PIDs
    pthread_mutex_lock(&g_pids_mutex);
    scan_proc(g_known_pids);
    pthread_mutex_unlock(&g_pids_mutex);

    while (g_running) {
        usleep(500000); // Poll every 500ms

        std::set<pid_t> current_pids;
        scan_proc(current_pids);

        pthread_mutex_lock(&g_pids_mutex);

        // Find new PIDs
        for (pid_t pid : current_pids) {
            if (g_known_pids.find(pid) == g_known_pids.end()) {
                // New process!
                char pkg[256] = {0};
                if (get_package_name(pid, pkg, sizeof(pkg))) {
                    if (is_android_app(pkg)) {
                        // Only notify for app UIDs (>= 10000)
                        int uid = get_process_uid(pid);
                        if (uid >= 10000) {
                            g_pid_packages[pid] = std::string(pkg);
                            LOGI("New app process: pid=%d pkg=%s uid=%d", pid, pkg, uid);

                            if (g_callback) {
                                // Call outside mutex to avoid deadlock
                                pthread_mutex_unlock(&g_pids_mutex);
                                g_callback(pid, pkg);
                                pthread_mutex_lock(&g_pids_mutex);
                            }
                        }
                    }
                }
                g_known_pids.insert(pid);
            }
        }

        // Remove dead PIDs
        std::set<pid_t> dead_pids;
        for (pid_t pid : g_known_pids) {
            if (current_pids.find(pid) == current_pids.end()) {
                dead_pids.insert(pid);
                g_pid_packages.erase(pid);
            }
        }
        for (pid_t pid : dead_pids) {
            g_known_pids.erase(pid);
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

// Get package name for a given PID
const char* process_monitor_get_package(pid_t pid) {
    pthread_mutex_lock(&g_pids_mutex);
    auto it = g_pid_packages.find(pid);
    const char* result = nullptr;
    if (it != g_pid_packages.end()) {
        result = it->second.c_str();
    }
    pthread_mutex_unlock(&g_pids_mutex);
    return result;
}
