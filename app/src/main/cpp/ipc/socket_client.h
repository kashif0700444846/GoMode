#pragma once
#include <stdbool.h>
#include <stddef.h>

bool socket_client_connect(const char* socket_path);
bool socket_client_send(const char* message);
bool socket_client_receive(char* buffer, size_t max_len);
void socket_client_disconnect();
