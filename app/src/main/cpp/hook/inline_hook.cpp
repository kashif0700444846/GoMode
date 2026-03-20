/**
 * GodMode - Inline Hook Engine for ARM64
 * Hooks native functions by patching the first instruction with a branch
 * to our trampoline. Works on Android 16 / ARM64 (AArch64).
 */

#include "inline_hook.h"
#include <sys/mman.h>
#include <string.h>
#include <stdint.h>
#include <android/log.h>
#include <unistd.h>
#include <errno.h>

#define TAG "GodMode_Hook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ARM64 trampoline: LDR X17, #8; BR X17; <64-bit address>
// This is a position-independent absolute branch
static const uint8_t TRAMPOLINE_TEMPLATE[] = {
    0x51, 0x00, 0x00, 0x58,  // LDR X17, #8
    0x20, 0x02, 0x1F, 0xD6,  // BR X17
    0x00, 0x00, 0x00, 0x00,  // address low 32 bits
    0x00, 0x00, 0x00, 0x00   // address high 32 bits
};
#define TRAMPOLINE_SIZE 16
#define TRAMPOLINE_ALLOC_SIZE 128

// Saved hooks list
#define MAX_HOOKS 256
static HookEntry g_hooks[MAX_HOOKS];
static int g_hook_count = 0;

static bool make_writable(void* addr, size_t size) {
    uintptr_t page_start = (uintptr_t)addr & ~(getpagesize() - 1);
    size_t total = size + ((uintptr_t)addr - page_start);
    if (mprotect((void*)page_start, total, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        LOGE("mprotect failed: %s", strerror(errno));
        return false;
    }
    return true;
}

static bool make_executable(void* addr, size_t size) {
    uintptr_t page_start = (uintptr_t)addr & ~(getpagesize() - 1);
    size_t total = size + ((uintptr_t)addr - page_start);
    if (mprotect((void*)page_start, total, PROT_READ | PROT_EXEC) != 0) {
        LOGE("mprotect restore failed: %s", strerror(errno));
        return false;
    }
    return true;
}

bool inline_hook_install(void* target, void* replacement, void** original) {
    if (!target || !replacement) {
        LOGE("inline_hook_install: null pointer");
        return false;
    }

    if (g_hook_count >= MAX_HOOKS) {
        LOGE("Max hooks reached");
        return false;
    }

    HookEntry& entry = g_hooks[g_hook_count];
    entry.target = target;
    entry.replacement = replacement;

    // Save original bytes (enough for trampoline)
    memcpy(entry.original_bytes, target, TRAMPOLINE_SIZE);

    // Allocate trampoline for original function execution
    // The trampoline will execute the original bytes then jump back
    entry.trampoline = mmap(nullptr, TRAMPOLINE_ALLOC_SIZE, PROT_READ | PROT_WRITE | PROT_EXEC,
                            MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (entry.trampoline == MAP_FAILED) {
        LOGE("mmap trampoline failed: %s", strerror(errno));
        return false;
    }

    // Copy original bytes to trampoline
    memcpy(entry.trampoline, entry.original_bytes, TRAMPOLINE_SIZE);

    // Add jump back to original function + TRAMPOLINE_SIZE
    uint8_t* tramp = (uint8_t*)entry.trampoline + TRAMPOLINE_SIZE;
    memcpy(tramp, TRAMPOLINE_TEMPLATE, TRAMPOLINE_SIZE);
    uintptr_t ret_addr = (uintptr_t)target + TRAMPOLINE_SIZE;
    memcpy(tramp + 8, &ret_addr, 8);

    // Flush instruction cache for trampoline
    __builtin___clear_cache((char*)entry.trampoline, (char*)((uint8_t*)entry.trampoline + TRAMPOLINE_ALLOC_SIZE));

    if (original) {
        *original = entry.trampoline;
    }

    // Patch target function with branch to replacement
    if (!make_writable(target, TRAMPOLINE_SIZE)) {
        munmap(entry.trampoline, TRAMPOLINE_ALLOC_SIZE);
        return false;
    }

    uint8_t patch[TRAMPOLINE_SIZE];
    memcpy(patch, TRAMPOLINE_TEMPLATE, TRAMPOLINE_SIZE);
    uintptr_t repl_addr = (uintptr_t)replacement;
    memcpy(patch + 8, &repl_addr, 8);
    memcpy(target, patch, TRAMPOLINE_SIZE);

    // Flush instruction cache
    __builtin___clear_cache((char*)target, (char*)((uint8_t*)target + TRAMPOLINE_SIZE));

    make_executable(target, TRAMPOLINE_SIZE);

    entry.active = true;
    g_hook_count++;

    LOGI("Hook installed: %p -> %p (trampoline: %p)", target, replacement, entry.trampoline);
    return true;
}

bool inline_hook_remove(void* target) {
    for (int i = 0; i < g_hook_count; i++) {
        if (g_hooks[i].target == target && g_hooks[i].active) {
            if (!make_writable(target, TRAMPOLINE_SIZE)) return false;
            memcpy(target, g_hooks[i].original_bytes, TRAMPOLINE_SIZE);
            __builtin___clear_cache((char*)target, (char*)((uint8_t*)target + TRAMPOLINE_SIZE));
            make_executable(target, TRAMPOLINE_SIZE);
            munmap(g_hooks[i].trampoline, TRAMPOLINE_ALLOC_SIZE);
            g_hooks[i].active = false;
            LOGI("Hook removed: %p", target);
            return true;
        }
    }
    return false;
}

void inline_hook_remove_all() {
    for (int i = 0; i < g_hook_count; i++) {
        if (g_hooks[i].active) {
            inline_hook_remove(g_hooks[i].target);
        }
    }
    g_hook_count = 0;
}
