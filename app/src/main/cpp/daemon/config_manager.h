#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <set>
#include <string>

bool config_manager_init(const char* db_path);
void config_manager_store_log(const char* log_msg);
void config_manager_get_config(const char* package, char* out, size_t max_len);
void config_manager_set_config(const char* config_str);
void config_manager_send_logs(int client_fd, const char* package);
void config_manager_clear_logs(const char* package);
void config_manager_send_app_list(int client_fd);
void config_manager_cleanup();
