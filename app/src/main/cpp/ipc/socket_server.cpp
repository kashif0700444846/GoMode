/**
 * GodMode - IPC Socket Server
 * Runs in the root daemon process.
 * Accepts connections from injected hook libraries and the Android app.
 * Relays logs to the app and sends config updates to hooks.
 */

#include "socket_server.h"
#include <android/log.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <unistd.h>
#include <string.h>
#include <errno.h>
#include <pthread.h>
#include <stdlib.h>
#include <fcntl.h>
#include <poll.h>

#define TAG "GodMode_SockServer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define SOCKET_PATH "/dev/socket/godmoded"
#define MAX_CLIENTS 64

static int g_server_fd = -1;
static int g_client_fds[MAX_CLIENTS];
static int g_client_count = 0;
static pthread_mutex_t g_clients_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_t g_server_thread;
static volatile bool g_running = false;

// Callback for received messages
static socket_message_callback_t g_callback = nullptr;

static void add_client(int fd) {
    pthread_mutex_lock(&g_clients_mutex);
    if (g_client_count < MAX_CLIENTS) {
        g_client_fds[g_client_count++] = fd;
        LOGI("Client connected: fd=%d (total=%d)", fd, g_client_count);
    } else {
        close(fd);
        LOGE("Max clients reached, rejecting connection");
    }
    pthread_mutex_unlock(&g_clients_mutex);
}

static void remove_client(int fd) {
    pthread_mutex_lock(&g_clients_mutex);
    for (int i = 0; i < g_client_count; i++) {
        if (g_client_fds[i] == fd) {
            close(fd);
            g_client_fds[i] = g_client_fds[--g_client_count];
            LOGI("Client disconnected: fd=%d (total=%d)", fd, g_client_count);
            break;
        }
    }
    pthread_mutex_unlock(&g_clients_mutex);
}

static bool read_message(int fd, char* buffer, size_t max_len) {
    uint32_t msg_len = 0;
    ssize_t rcvd = recv(fd, &msg_len, sizeof(msg_len), MSG_WAITALL);
    if (rcvd != sizeof(msg_len)) return false;
    if (msg_len == 0 || msg_len >= max_len) return false;

    rcvd = recv(fd, buffer, msg_len, MSG_WAITALL);
    if (rcvd != (ssize_t)msg_len) return false;
    buffer[msg_len] = '\0';
    return true;
}

static void* server_thread_func(void* arg) {
    struct pollfd pfds[MAX_CLIENTS + 1];
    char msg_buf[8192];

    while (g_running) {
        // Build poll set
        pfds[0].fd = g_server_fd;
        pfds[0].events = POLLIN;
        pfds[0].revents = 0;

        pthread_mutex_lock(&g_clients_mutex);
        int nclients = g_client_count;
        for (int i = 0; i < nclients; i++) {
            pfds[i + 1].fd = g_client_fds[i];
            pfds[i + 1].events = POLLIN;
            pfds[i + 1].revents = 0;
        }
        pthread_mutex_unlock(&g_clients_mutex);

        int ret = poll(pfds, nclients + 1, 1000);
        if (ret < 0) {
            if (errno == EINTR) continue;
            LOGE("poll failed: %s", strerror(errno));
            break;
        }
        if (ret == 0) continue;

        // New connection
        if (pfds[0].revents & POLLIN) {
            int client_fd = accept(g_server_fd, nullptr, nullptr);
            if (client_fd >= 0) {
                add_client(client_fd);
            }
        }

        // Messages from clients
        for (int i = 0; i < nclients; i++) {
            if (pfds[i + 1].revents & (POLLIN | POLLHUP | POLLERR)) {
                int fd = pfds[i + 1].fd;
                if (pfds[i + 1].revents & POLLIN) {
                    if (read_message(fd, msg_buf, sizeof(msg_buf))) {
                        if (g_callback) {
                            g_callback(fd, msg_buf);
                        }
                    } else {
                        remove_client(fd);
                    }
                } else {
                    remove_client(fd);
                }
            }
        }
    }

    return nullptr;
}

bool socket_server_start(socket_message_callback_t callback) {
    g_callback = callback;

    // Remove old socket
    unlink(SOCKET_PATH);

    g_server_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (g_server_fd < 0) {
        LOGE("socket() failed: %s", strerror(errno));
        return false;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, SOCKET_PATH, sizeof(addr.sun_path) - 1);

    if (bind(g_server_fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        LOGE("bind() failed: %s", strerror(errno));
        close(g_server_fd);
        return false;
    }

    // Allow all processes to connect
    chmod(SOCKET_PATH, 0777);

    if (listen(g_server_fd, 32) < 0) {
        LOGE("listen() failed: %s", strerror(errno));
        close(g_server_fd);
        return false;
    }

    g_running = true;
    pthread_create(&g_server_thread, nullptr, server_thread_func, nullptr);
    pthread_detach(g_server_thread);

    LOGI("Socket server started at %s", SOCKET_PATH);
    return true;
}

void socket_server_broadcast(const char* message) {
    pthread_mutex_lock(&g_clients_mutex);
    uint32_t msg_len = strlen(message);
    for (int i = 0; i < g_client_count; i++) {
        send(g_client_fds[i], &msg_len, sizeof(msg_len), MSG_NOSIGNAL);
        send(g_client_fds[i], message, msg_len, MSG_NOSIGNAL);
    }
    pthread_mutex_unlock(&g_clients_mutex);
}

void socket_server_send_to(int client_fd, const char* message) {
    uint32_t msg_len = strlen(message);
    send(client_fd, &msg_len, sizeof(msg_len), MSG_NOSIGNAL);
    send(client_fd, message, msg_len, MSG_NOSIGNAL);
}

void socket_server_stop() {
    g_running = false;
    if (g_server_fd >= 0) {
        close(g_server_fd);
        g_server_fd = -1;
    }
    unlink(SOCKET_PATH);
}
