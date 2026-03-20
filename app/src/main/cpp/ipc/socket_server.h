#pragma once
#include <stdbool.h>

typedef void (*socket_message_callback_t)(int client_fd, const char* message);

bool socket_server_start(socket_message_callback_t callback);
void socket_server_broadcast(const char* message);
void socket_server_send_to(int client_fd, const char* message);
void socket_server_stop();
