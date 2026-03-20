/**
 * GodMode - JNI Bridge
 * Provides native methods callable from Kotlin/Java:
 * - Root detection
 * - Daemon management
 * - Direct IPC with daemon
 */

#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <errno.h>

#define TAG "GodMode_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define DAEMON_SOCKET "/dev/socket/godmoded"
#define DAEMON_BINARY "/data/local/tmp/godmoded"

// Simple socket send/receive for JNI
static int g_jni_sock = -1;

static bool jni_connect_daemon() {
    if (g_jni_sock >= 0) return true;

    g_jni_sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (g_jni_sock < 0) return false;

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, DAEMON_SOCKET, sizeof(addr.sun_path) - 1);

    if (connect(g_jni_sock, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        close(g_jni_sock);
        g_jni_sock = -1;
        return false;
    }
    return true;
}

static bool jni_send_message(const char* msg) {
    if (!jni_connect_daemon()) return false;
    uint32_t len = strlen(msg);
    if (send(g_jni_sock, &len, sizeof(len), MSG_NOSIGNAL) != sizeof(len)) {
        close(g_jni_sock); g_jni_sock = -1; return false;
    }
    if (send(g_jni_sock, msg, len, MSG_NOSIGNAL) != (ssize_t)len) {
        close(g_jni_sock); g_jni_sock = -1; return false;
    }
    return true;
}

static bool jni_receive_message(char* buf, size_t max_len) {
    if (g_jni_sock < 0) return false;
    struct timeval tv = {5, 0};
    setsockopt(g_jni_sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    uint32_t len = 0;
    if (recv(g_jni_sock, &len, sizeof(len), MSG_WAITALL) != sizeof(len)) return false;
    if (len == 0 || len >= max_len) return false;
    if (recv(g_jni_sock, buf, len, MSG_WAITALL) != (ssize_t)len) return false;
    buf[len] = '\0';
    return true;
}

extern "C" {

// Check if device is rooted
JNIEXPORT jboolean JNICALL
Java_com_godmode_app_daemon_RootManager_nativeIsRooted(JNIEnv* env, jobject thiz) {
    // Check for su binary in common locations
    const char* su_paths[] = {
        "/sbin/su", "/system/bin/su", "/system/xbin/su",
        "/data/local/xbin/su", "/data/local/bin/su",
        "/system/sd/xbin/su", "/system/bin/failsafe/su",
        "/data/local/su", "/su/bin/su",
        "/magisk/.core/bin/su",
        nullptr
    };
    for (int i = 0; su_paths[i]; i++) {
        if (access(su_paths[i], F_OK) == 0) return JNI_TRUE;
    }
    // Check for Magisk
    if (access("/data/adb/magisk", F_OK) == 0) return JNI_TRUE;
    if (access("/sbin/.magisk", F_OK) == 0) return JNI_TRUE;
    // Check for KernelSU
    if (access("/data/adb/ksu", F_OK) == 0) return JNI_TRUE;
    return JNI_FALSE;
}

// Check if Magisk is installed
JNIEXPORT jboolean JNICALL
Java_com_godmode_app_daemon_RootManager_nativeIsMagiskInstalled(JNIEnv* env, jobject thiz) {
    return (access("/data/adb/magisk", F_OK) == 0 ||
            access("/sbin/.magisk", F_OK) == 0 ||
            access("/data/adb/magisk.db", F_OK) == 0) ? JNI_TRUE : JNI_FALSE;
}

// Check if KernelSU is installed
JNIEXPORT jboolean JNICALL
Java_com_godmode_app_daemon_RootManager_nativeIsKernelSUInstalled(JNIEnv* env, jobject thiz) {
    return (access("/data/adb/ksu", F_OK) == 0 ||
            access("/data/adb/ksud", F_OK) == 0) ? JNI_TRUE : JNI_FALSE;
}

// Check if LSPosed is installed
JNIEXPORT jboolean JNICALL
Java_com_godmode_app_daemon_RootManager_nativeIsLSPosedInstalled(JNIEnv* env, jobject thiz) {
    return (access("/data/adb/modules/lsposed", F_OK) == 0 ||
            access("/data/adb/modules/zygisk_lsposed", F_OK) == 0 ||
            access("/data/misc/lspd", F_OK) == 0) ? JNI_TRUE : JNI_FALSE;
}

// Check if daemon is running
JNIEXPORT jboolean JNICALL
Java_com_godmode_app_daemon_RootManager_nativeIsDaemonRunning(JNIEnv* env, jobject thiz) {
    return (access(DAEMON_SOCKET, F_OK) == 0) ? JNI_TRUE : JNI_FALSE;
}

// Get daemon status string
JNIEXPORT jstring JNICALL
Java_com_godmode_app_daemon_RootManager_nativeGetDaemonStatus(JNIEnv* env, jobject thiz) {
    if (!jni_connect_daemon()) {
        return env->NewStringUTF("DISCONNECTED");
    }
    if (!jni_send_message("STATUS")) {
        return env->NewStringUTF("ERROR");
    }
    char buf[512] = {0};
    if (!jni_receive_message(buf, sizeof(buf))) {
        return env->NewStringUTF("TIMEOUT");
    }
    return env->NewStringUTF(buf);
}

// Send config to daemon
JNIEXPORT jboolean JNICALL
Java_com_godmode_app_daemon_RootManager_nativeSendConfig(JNIEnv* env, jobject thiz, jstring config) {
    const char* cfg = env->GetStringUTFChars(config, nullptr);
    char msg[8192];
    snprintf(msg, sizeof(msg), "SET_CONFIG|%s", cfg);
    bool ok = jni_send_message(msg);
    env->ReleaseStringUTFChars(config, cfg);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Request logs from daemon
JNIEXPORT jstring JNICALL
Java_com_godmode_app_daemon_RootManager_nativeGetLogs(JNIEnv* env, jobject thiz, jstring package) {
    const char* pkg = env->GetStringUTFChars(package, nullptr);
    char msg[512];
    snprintf(msg, sizeof(msg), "GET_LOGS|%s", pkg);
    env->ReleaseStringUTFChars(package, pkg);

    if (!jni_send_message(msg)) {
        return env->NewStringUTF("");
    }

    // Collect all log entries
    char all_logs[65536] = {0};
    char buf[2048];
    while (jni_receive_message(buf, sizeof(buf))) {
        if (strcmp(buf, "LOG_END") == 0) break;
        strncat(all_logs, buf, sizeof(all_logs) - strlen(all_logs) - 2);
        strncat(all_logs, "\n", 1);
    }

    return env->NewStringUTF(all_logs);
}

// Install daemon binary (called after copying binary to path)
JNIEXPORT jboolean JNICALL
Java_com_godmode_app_daemon_RootManager_nativeInstallDaemon(JNIEnv* env, jobject thiz,
                                                              jstring binary_path) {
    const char* path = env->GetStringUTFChars(binary_path, nullptr);
    // Make executable
    int result = chmod(path, 0755);
    env->ReleaseStringUTFChars(binary_path, path);
    return (result == 0) ? JNI_TRUE : JNI_FALSE;
}

// Execute a root command
JNIEXPORT jstring JNICALL
Java_com_godmode_app_daemon_RootManager_nativeExecRoot(JNIEnv* env, jobject thiz, jstring cmd) {
    const char* command = env->GetStringUTFChars(cmd, nullptr);

    char full_cmd[2048];
    snprintf(full_cmd, sizeof(full_cmd), "su -c '%s' 2>&1", command);
    env->ReleaseStringUTFChars(cmd, command);

    FILE* pipe = popen(full_cmd, "r");
    if (!pipe) {
        return env->NewStringUTF("ERROR: popen failed");
    }

    char result[8192] = {0};
    char buf[256];
    while (fgets(buf, sizeof(buf), pipe)) {
        strncat(result, buf, sizeof(result) - strlen(result) - 1);
    }
    pclose(pipe);

    return env->NewStringUTF(result);
}

} // extern "C"
