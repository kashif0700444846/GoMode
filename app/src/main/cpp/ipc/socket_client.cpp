/**
 * GodMode - IPC Socket Client
 * Used by the injected hook library to communicate with the root daemon.
 * Sends access logs and receives configuration updates.
 */

#include "socket_client.h"
#include <android/log.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <string.h>
#include <errno.h>
#include <pthread.h>
#include <fcntl.h>

#define TAG "GodMode_SockClient"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static int g_sock_fd = -1;
static pthread_mutex_t g_sock_mutex = PTHREAD_MUTEX_INITIALIZER;

bool socket_client_connect(const char* socket_path) {
    pthread_mutex_lock(&g_sock_mutex);

    if (g_sock_fd >= 0) {
        close(g_sock_fd);
        g_sock_fd = -1;
    }

    g_sock_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (g_sock_fd < 0) {
        LOGE("socket() failed: %s", strerror(errno));
        pthread_mutex_unlock(&g_sock_mutex);
        return false;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, socket_path, sizeof(addr.sun_path) - 1);

    // Set non-blocking for connect attempt
    int flags = fcntl(g_sock_fd, F_GETFL, 0);
    fcntl(g_sock_fd, F_SETFL, flags | O_NONBLOCK);

    int ret = connect(g_sock_fd, (struct sockaddr*)&addr, sizeof(addr));
    if (ret < 0 && errno != EINPROGRESS) {
        LOGE("connect() failed: %s", strerror(errno));
        close(g_sock_fd);
        g_sock_fd = -1;
        pthread_mutex_unlock(&g_sock_mutex);
        return false;
    }

    // Restore blocking mode
    fcntl(g_sock_fd, F_SETFL, flags);

    LOGI("Connected to daemon socket: %s", socket_path);
    pthread_mutex_unlock(&g_sock_mutex);
    return true;
}

bool socket_client_send(const char* message) {
    pthread_mutex_lock(&g_sock_mutex);

    if (g_sock_fd < 0) {
        pthread_mutex_unlock(&g_sock_mutex);
        return false;
    }

    size_t len = strlen(message);
    uint32_t msg_len = (uint32_t)len;

    // Send length prefix then message
    ssize_t sent = send(g_sock_fd, &msg_len, sizeof(msg_len), MSG_NOSIGNAL);
    if (sent != sizeof(msg_len)) {
        LOGE("send length failed: %s", strerror(errno));
        close(g_sock_fd);
        g_sock_fd = -1;
        pthread_mutex_unlock(&g_sock_mutex);
        return false;
    }

    sent = send(g_sock_fd, message, len, MSG_NOSIGNAL);
    if (sent != (ssize_t)len) {
        LOGE("send message failed: %s", strerror(errno));
        close(g_sock_fd);
        g_sock_fd = -1;
        pthread_mutex_unlock(&g_sock_mutex);
        return false;
    }

    pthread_mutex_unlock(&g_sock_mutex);
    return true;
}

bool socket_client_receive(char* buffer, size_t max_len) {
    pthread_mutex_lock(&g_sock_mutex);

    if (g_sock_fd < 0) {
        pthread_mutex_unlock(&g_sock_mutex);
        return false;
    }

    // Set receive timeout
    struct timeval tv;
    tv.tv_sec = 0;
    tv.tv_usec = 50000; // 50ms
    setsockopt(g_sock_fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    uint32_t msg_len = 0;
    ssize_t rcvd = recv(g_sock_fd, &msg_len, sizeof(msg_len), 0);
    if (rcvd != sizeof(msg_len)) {
        pthread_mutex_unlock(&g_sock_mutex);
        return false;
    }

    if (msg_len == 0 || msg_len >= max_len) {
        pthread_mutex_unlock(&g_sock_mutex);
        return false;
    }

    rcvd = recv(g_sock_fd, buffer, msg_len, 0);
    if (rcvd != (ssize_t)msg_len) {
        pthread_mutex_unlock(&g_sock_mutex);
        return false;
    }
    buffer[msg_len] = '\0';

    pthread_mutex_unlock(&g_sock_mutex);
    return true;
}

void socket_client_disconnect() {
    pthread_mutex_lock(&g_sock_mutex);
    if (g_sock_fd >= 0) {
        close(g_sock_fd);
        g_sock_fd = -1;
    }
    pthread_mutex_unlock(&g_sock_mutex);
}
