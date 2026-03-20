/**
 * GodMode - Process Injector
 *
 * Injects the godmode_hook.so shared library into a target process using ptrace.
 */

#include "injector.h"
#include <android/log.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/mman.h>
#include <sys/uio.h>
#include <unistd.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <errno.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <elf.h>
#include <link.h>
#include <linux/elf.h>
#include <asm/ptrace.h>

#define TAG "GodMode_Injector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define HOOK_LIB_PATH "/system/lib64/libgodmode_hook.so"
#define HOOK_LIB_PATH_ALT "/data/local/tmp/libgodmode_hook.so"

// Use user_pt_regs from asm/ptrace.h for ARM64
typedef struct user_pt_regs pt_regs_arm64;

static bool read_remote_mem(pid_t pid, uintptr_t addr, void* buf, size_t len) {
    struct iovec local = { buf, len };
    struct iovec remote = { (void*)addr, len };
    ssize_t n = process_vm_readv(pid, &local, 1, &remote, 1, 0);
    return n == (ssize_t)len;
}

static bool write_remote_mem(pid_t pid, uintptr_t addr, const void* buf, size_t len) {
    struct iovec local = { (void*)buf, len };
    struct iovec remote = { (void*)addr, len };
    ssize_t n = process_vm_writev(pid, &local, 1, &remote, 1, 0);
    return n == (ssize_t)len;
}

static uintptr_t find_remote_lib_base(pid_t pid, const char* lib_name) {
    char maps_path[64];
    snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);
    FILE* f = fopen(maps_path, "r");
    if (!f) return 0;
    char line[512];
    uintptr_t base = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, lib_name)) {
            sscanf(line, "%lx", &base);
            break;
        }
    }
    fclose(f);
    return base;
}

static uintptr_t find_local_symbol_offset(const char* lib_path, const char* symbol) {
    void* handle = dlopen(lib_path, RTLD_NOW | RTLD_NOLOAD);
    if (!handle) {
        handle = dlopen(lib_path, RTLD_NOW);
        if (!handle) return 0;
    }
    void* sym = dlsym(handle, symbol);
    if (!sym) {
        dlclose(handle);
        return 0;
    }
    Dl_info info;
    if (!dladdr(sym, &info)) {
        dlclose(handle);
        return 0;
    }
    uintptr_t offset = (uintptr_t)sym - (uintptr_t)info.dli_fbase;
    dlclose(handle);
    return offset;
}

static uintptr_t remote_mmap(pid_t pid, size_t size) {
    pt_regs_arm64 regs, saved_regs;
    struct iovec iov = { &regs, sizeof(regs) };
    if (ptrace(PTRACE_GETREGSET, pid, (void*)(uintptr_t)NT_PRSTATUS, &iov) < 0) {
        LOGE("PTRACE_GETREGSET failed: %s", strerror(errno));
        return 0;
    }
    memcpy(&saved_regs, &regs, sizeof(regs));

    uintptr_t inject_addr = regs.pc;
    uint32_t stub[] = { 0xD4000001, 0xD4200000 }; // SVC #0, BRK #0
    uint32_t orig_bytes[2];
    read_remote_mem(pid, inject_addr, orig_bytes, sizeof(orig_bytes));
    write_remote_mem(pid, inject_addr, stub, sizeof(stub));

    regs.regs[8] = 222; // mmap
    regs.regs[0] = 0;
    regs.regs[1] = size;
    regs.regs[2] = PROT_READ | PROT_WRITE | PROT_EXEC;
    regs.regs[3] = MAP_PRIVATE | MAP_ANONYMOUS;
    regs.regs[4] = (uint64_t)-1;
    regs.regs[5] = 0;

    struct iovec iov_set = { &regs, sizeof(regs) };
    ptrace(PTRACE_SETREGSET, pid, (void*)(uintptr_t)NT_PRSTATUS, &iov_set);
    ptrace(PTRACE_CONT, pid, nullptr, nullptr);
    int status;
    waitpid(pid, &status, 0);

    struct iovec iov_get = { &regs, sizeof(regs) };
    ptrace(PTRACE_GETREGSET, pid, (void*)(uintptr_t)NT_PRSTATUS, &iov_get);
    uintptr_t mmap_result = regs.regs[0];

    write_remote_mem(pid, inject_addr, orig_bytes, sizeof(orig_bytes));
    struct iovec iov_restore = { &saved_regs, sizeof(saved_regs) };
    ptrace(PTRACE_SETREGSET, pid, (void*)(uintptr_t)NT_PRSTATUS, &iov_restore);

    return (mmap_result == (uintptr_t)-1) ? 0 : mmap_result;
}

static bool remote_dlopen(pid_t pid, const char* lib_path) {
    uintptr_t remote_libdl_base = find_remote_lib_base(pid, "libdl.so");
    if (!remote_libdl_base) {
        remote_libdl_base = find_remote_lib_base(pid, "/apex/com.android.art/lib64/libdl.so");
    }
    if (!remote_libdl_base) return false;

    uintptr_t dlopen_offset = find_local_symbol_offset("libdl.so", "dlopen");
    if (!dlopen_offset) {
        dlopen_offset = find_local_symbol_offset("/apex/com.android.art/lib64/libdl.so", "dlopen");
    }
    if (!dlopen_offset) return false;

    uintptr_t remote_dlopen_addr = remote_libdl_base + dlopen_offset;
    uintptr_t remote_path = remote_mmap(pid, 4096);
    if (!remote_path) return false;
    write_remote_mem(pid, remote_path, lib_path, strlen(lib_path) + 1);

    uintptr_t stub_addr = remote_mmap(pid, 4096);
    if (!stub_addr) return false;

    uint32_t stub[8] = { 
        0x580000c0, // LDR X0, #24 (PC + 24)
        0xd2800041, // MOV X1, #2
        0x580000b1, // LDR X17, #20 (PC + 20)
        0xd63f0220, // BLR X17
        0xd4200000, // BRK #0
        0xd503201f  // NOP
    };
    write_remote_mem(pid, stub_addr, stub, sizeof(stub));
    write_remote_mem(pid, stub_addr + 24, &remote_path, 8);
    write_remote_mem(pid, stub_addr + 32, &remote_dlopen_addr, 8);

    pt_regs_arm64 regs_call, saved_regs_call;
    struct iovec iov_call = { &regs_call, sizeof(regs_call) };
    ptrace(PTRACE_GETREGSET, pid, (void*)(uintptr_t)NT_PRSTATUS, &iov_call);
    memcpy(&saved_regs_call, &regs_call, sizeof(regs_call));

    regs_call.pc = stub_addr;
    struct iovec iov_set_call = { &regs_call, sizeof(regs_call) };
    ptrace(PTRACE_SETREGSET, pid, (void*)(uintptr_t)NT_PRSTATUS, &iov_set_call);
    ptrace(PTRACE_CONT, pid, nullptr, nullptr);
    int status;
    waitpid(pid, &status, 0);

    struct iovec iov_get_call = { &regs_call, sizeof(regs_call) };
    ptrace(PTRACE_GETREGSET, pid, (void*)(uintptr_t)NT_PRSTATUS, &iov_get_call);
    uintptr_t handle = regs_call.regs[0];

    struct iovec iov_restore_call = { &saved_regs_call, sizeof(saved_regs_call) };
    ptrace(PTRACE_SETREGSET, pid, (void*)(uintptr_t)NT_PRSTATUS, &iov_restore_call);

    return handle != 0;
}

bool injector_inject_process(pid_t pid) {
    const char* lib_path = HOOK_LIB_PATH;
    if (access(lib_path, F_OK) != 0) {
        lib_path = HOOK_LIB_PATH_ALT;
        if (access(lib_path, F_OK) != 0) return false;
    }

    if (find_remote_lib_base(pid, "libgodmode_hook.so")) return true;

    if (ptrace(PTRACE_ATTACH, pid, nullptr, nullptr) < 0) return false;
    int status;
    waitpid(pid, &status, 0);
    if (!WIFSTOPPED(status)) {
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return false;
    }

    bool success = remote_dlopen(pid, lib_path);
    ptrace(PTRACE_DETACH, (pid_t)pid, nullptr, nullptr);
    return success;
}
