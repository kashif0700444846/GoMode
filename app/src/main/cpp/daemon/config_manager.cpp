/**
 * GodMode - Config Manager
 *
 * Manages the SQLite database for:
 * - Per-app spoofing configurations
 * - Access logs (what app accessed what data and when)
 *
 * Uses a simple file-based JSON approach since SQLite NDK linking
 * requires additional setup. The Kotlin app manages the Room DB;
 * the daemon uses a lightweight flat-file approach.
 */

#include "config_manager.h"
#include "../ipc/socket_server.h"
#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <pthread.h>
#include <time.h>
#include <errno.h>

#define TAG "GodMode_ConfigMgr"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define MAX_LOG_ENTRIES 10000
#define LOG_FLUSH_INTERVAL 5  // seconds

static char g_db_path[256] = {0};
static char g_log_path[256] = {0};
static char g_config_path[256] = {0};
static pthread_mutex_t g_mutex = PTHREAD_MUTEX_INITIALIZER;

// In-memory log ring buffer
struct LogEntry {
    long timestamp_ms;
    char package[256];
    int uid;
    char property[64];
    char original_value[256];
    char spoofed_value[256];
    bool was_spoofed;
};

static LogEntry g_log_buffer[MAX_LOG_ENTRIES];
static int g_log_head = 0;
static int g_log_count = 0;
static time_t g_last_flush = 0;

bool config_manager_init(const char* db_path) {
    strncpy(g_db_path, db_path, sizeof(g_db_path) - 1);

    // Derive paths
    snprintf(g_log_path, sizeof(g_log_path), "%s.logs", db_path);
    snprintf(g_config_path, sizeof(g_config_path), "%s.config", db_path);

    // Create directory if needed
    char dir[256];
    strncpy(dir, db_path, sizeof(dir) - 1);
    char* last_slash = strrchr(dir, '/');
    if (last_slash) {
        *last_slash = '\0';
        mkdir(dir, 0755);
    }

    LOGI("Config manager initialized at %s", db_path);
    return true;
}

// Parse a log message: package|uid|timestamp|property|original|spoofed|was_spoofed
static bool parse_log_message(const char* msg, LogEntry* entry) {
    char buf[1024];
    strncpy(buf, msg, sizeof(buf) - 1);

    char* p = buf;
    char* token;
    int field = 0;

    while ((token = strsep(&p, "|")) != nullptr) {
        switch (field) {
            case 0: strncpy(entry->package, token, sizeof(entry->package)-1); break;
            case 1: entry->uid = atoi(token); break;
            case 2: entry->timestamp_ms = atol(token); break;
            case 3: strncpy(entry->property, token, sizeof(entry->property)-1); break;
            case 4: strncpy(entry->original_value, token, sizeof(entry->original_value)-1); break;
            case 5: strncpy(entry->spoofed_value, token, sizeof(entry->spoofed_value)-1); break;
            case 6: entry->was_spoofed = atoi(token) != 0; break;
        }
        field++;
    }
    return field >= 4;
}

void config_manager_store_log(const char* log_msg) {
    pthread_mutex_lock(&g_mutex);

    LogEntry entry;
    memset(&entry, 0, sizeof(entry));

    if (parse_log_message(log_msg, &entry)) {
        int idx = g_log_head % MAX_LOG_ENTRIES;
        g_log_buffer[idx] = entry;
        g_log_head++;
        if (g_log_count < MAX_LOG_ENTRIES) g_log_count++;
    }

    // Flush to disk periodically
    time_t now = time(nullptr);
    if (now - g_last_flush >= LOG_FLUSH_INTERVAL) {
        FILE* f = fopen(g_log_path, "a");
        if (f) {
            fprintf(f, "%s\n", log_msg);
            fclose(f);
        }
        g_last_flush = now;
    }

    pthread_mutex_unlock(&g_mutex);
}

void config_manager_get_config(const char* package, char* out, size_t max_len) {
    pthread_mutex_lock(&g_mutex);

    // Read config file and find entry for package
    FILE* f = fopen(g_config_path, "r");
    if (!f) {
        // No config - return disabled config
        snprintf(out, max_len, "package=%s|active=0", package);
        pthread_mutex_unlock(&g_mutex);
        return;
    }

    char line[4096];
    bool found = false;
    while (fgets(line, sizeof(line), f)) {
        // Remove newline
        line[strcspn(line, "\n")] = 0;
        // Check if this line is for our package
        char pkg_prefix[300];
        snprintf(pkg_prefix, sizeof(pkg_prefix), "package=%s|", package);
        if (strncmp(line, pkg_prefix, strlen(pkg_prefix)) == 0) {
            strncpy(out, line, max_len - 1);
            found = true;
            break;
        }
    }
    fclose(f);

    if (!found) {
        snprintf(out, max_len, "package=%s|active=0", package);
    }

    pthread_mutex_unlock(&g_mutex);
}

void config_manager_set_config(const char* config_str) {
    pthread_mutex_lock(&g_mutex);

    // Extract package name
    char pkg[256] = {0};
    const char* p = strstr(config_str, "package=");
    if (p) {
        p += 8;
        const char* end = strchr(p, '|');
        if (end) {
            strncpy(pkg, p, end - p);
        } else {
            strncpy(pkg, p, sizeof(pkg) - 1);
        }
    }

    if (pkg[0] == 0) {
        pthread_mutex_unlock(&g_mutex);
        return;
    }

    // Read existing config
    char all_config[65536] = {0};
    FILE* f = fopen(g_config_path, "r");
    if (f) {
        fread(all_config, 1, sizeof(all_config) - 1, f);
        fclose(f);
    }

    // Remove existing entry for this package
    char new_config[65536] = {0};
    char pkg_prefix[300];
    snprintf(pkg_prefix, sizeof(pkg_prefix), "package=%s|", pkg);

    char* line = all_config;
    char* next;
    while (line && *line) {
        next = strchr(line, '\n');
        if (next) *next = '\0';

        if (strncmp(line, pkg_prefix, strlen(pkg_prefix)) != 0 && line[0] != '\0') {
            strncat(new_config, line, sizeof(new_config) - strlen(new_config) - 2);
            strncat(new_config, "\n", 1);
        }

        if (next) line = next + 1;
        else break;
    }

    // Add new entry
    strncat(new_config, config_str, sizeof(new_config) - strlen(new_config) - 2);
    strncat(new_config, "\n", 1);

    // Write back
    f = fopen(g_config_path, "w");
    if (f) {
        fputs(new_config, f);
        fclose(f);
        LOGI("Config saved for %s", pkg);
    }

    pthread_mutex_unlock(&g_mutex);
}

void config_manager_send_logs(int client_fd, const char* package) {
    pthread_mutex_lock(&g_mutex);

    // Send logs for the specified package (or all if package is empty)
    bool all = (!package || package[0] == '\0' || strcmp(package, "*") == 0);

    int start = (g_log_count >= MAX_LOG_ENTRIES) ? g_log_head : 0;
    int count = g_log_count;

    for (int i = 0; i < count; i++) {
        int idx = (start + i) % MAX_LOG_ENTRIES;
        LogEntry* e = &g_log_buffer[idx];

        if (all || strcmp(e->package, package) == 0) {
            char msg[1024];
            snprintf(msg, sizeof(msg),
                     "LOG_ENTRY|%s|%d|%ld|%s|%s|%s|%d",
                     e->package, e->uid, e->timestamp_ms,
                     e->property, e->original_value, e->spoofed_value,
                     e->was_spoofed ? 1 : 0);
            socket_server_send_to(client_fd, msg);
        }
    }

    // Send end marker
    socket_server_send_to(client_fd, "LOG_END");

    pthread_mutex_unlock(&g_mutex);
}

void config_manager_clear_logs(const char* package) {
    pthread_mutex_lock(&g_mutex);

    bool all = (!package || package[0] == '\0' || strcmp(package, "*") == 0);

    if (all) {
        g_log_head = 0;
        g_log_count = 0;
        // Clear log file
        FILE* f = fopen(g_log_path, "w");
        if (f) fclose(f);
    } else {
        // Remove entries for specific package
        LogEntry temp[MAX_LOG_ENTRIES];
        int new_count = 0;
        int start = (g_log_count >= MAX_LOG_ENTRIES) ? g_log_head : 0;

        for (int i = 0; i < g_log_count; i++) {
            int idx = (start + i) % MAX_LOG_ENTRIES;
            if (strcmp(g_log_buffer[idx].package, package) != 0) {
                temp[new_count++] = g_log_buffer[idx];
            }
        }
        memcpy(g_log_buffer, temp, new_count * sizeof(LogEntry));
        g_log_head = new_count;
        g_log_count = new_count;
    }

    pthread_mutex_unlock(&g_mutex);
}

void config_manager_send_app_list(int client_fd) {
    pthread_mutex_lock(&g_mutex);

    // Collect unique package names from logs
    std::set<std::string> packages;
    // ... simplified: read from config file
    FILE* f = fopen(g_config_path, "r");
    if (f) {
        char line[4096];
        while (fgets(line, sizeof(line), f)) {
            const char* p = strstr(line, "package=");
            if (p) {
                p += 8;
                const char* end = strchr(p, '|');
                if (end) {
                    char pkg[256] = {0};
                    strncpy(pkg, p, end - p);
                    packages.insert(pkg);
                }
            }
        }
        fclose(f);
    }

    // Also add packages from log buffer
    for (int i = 0; i < g_log_count; i++) {
        packages.insert(g_log_buffer[i].package);
    }

    for (const auto& pkg : packages) {
        char msg[300];
        snprintf(msg, sizeof(msg), "APP|%s", pkg.c_str());
        socket_server_send_to(client_fd, msg);
    }
    socket_server_send_to(client_fd, "APP_END");

    pthread_mutex_unlock(&g_mutex);
}

void config_manager_cleanup() {
    // Flush remaining logs
    pthread_mutex_lock(&g_mutex);
    // Final flush would happen here
    pthread_mutex_unlock(&g_mutex);
}
