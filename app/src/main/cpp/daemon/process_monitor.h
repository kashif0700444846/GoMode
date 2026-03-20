#pragma once
#include <stdbool.h>
#include <sys/types.h>

typedef void (*process_callback_t)(pid_t pid, const char* package_name);

bool process_monitor_start(process_callback_t callback);
void process_monitor_stop();
const char* process_monitor_get_package(pid_t pid);
