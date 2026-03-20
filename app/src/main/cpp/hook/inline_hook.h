#pragma once
#include <stdint.h>
#include <stdbool.h>

#define TRAMPOLINE_SAVE_SIZE 16

struct HookEntry {
    void* target;
    void* replacement;
    void* trampoline;
    uint8_t original_bytes[TRAMPOLINE_SAVE_SIZE];
    bool active;
};

bool inline_hook_install(void* target, void* replacement, void** original);
bool inline_hook_remove(void* target);
void inline_hook_remove_all();
