/**
 * GodMode - Process Injector
 *
 * Injects the godmode_hook.so shared library into a target process using ptrace.
 * This works by:
 * 1. Attaching to the target process with ptrace
 * 2. Finding the address of dlopen in the target process
 * 3. Allocating memory in the target process for the library path
 * 4. Calling dlopen remotely to load our hook library
 * 5. Detaching from the process
 *
 * This technique works on Android 16 with root privileges.
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

#define TAG "GodMode_Injector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Path to our hook library (installed to system)
#define HOOK_LIB_PATH "/system/lib64/libgodmode_hook.so"
#define HOOK_LIB_PATH_ALT "/data/local/tmp/libgodmode_hook.so"

// ARM64 registers structure
struct pt_regs_arm64 {
    uint64_t regs[31];
    uint64_t sp;
    uint64_t pc;
    uint64_t pstate;
};

// Read memory from target process using process_vm_readv
static bool read_remote_mem(pid_t pid, uintptr_t addr, void* buf, size_t len) {
    struct iovec local = { buf, len };
    struct iovec remote = { (void*)addr, len };
    ssize_t n = process_vm_readv(pid, &local, 1, &remote, 1, 0);
    return n == (ssize_t)len;
}

// Write memory to target process using process_vm_writev
static bool write_remote_mem(pid_t pid, uintptr_t addr, const void* buf, size_t len) {
    struct iovec local = { (void*)buf, len };
    struct iovec remote = { (void*)addr, len };
    ssize_t n = process_vm_writev(pid, &local, 1, &remote, 1, 0);
    return n == (ssize_t)len;
}

// Find the base address of a library in a remote process by parsing /proc/<pid>/maps
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

// Find the offset of a symbol in a local library
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

    // Get base address of this library in our process
    Dl_info info;
    if (!dladdr(sym, &info)) {
        dlclose(handle);
        return 0;
    }

    uintptr_t offset = (uintptr_t)sym - (uintptr_t)info.dli_fbase;
    dlclose(handle);
    return offset;
}

// Allocate memory in remote process using mmap syscall via ptrace
static uintptr_t remote_mmap(pid_t pid, size_t size) {
    // We need to call mmap in the remote process
    // This is done by:
    // 1. Save registers
    // 2. Find a syscall instruction in the remote process
    // 3. Set registers for mmap syscall
    // 4. Execute syscall
    // 5. Read return value
    // 6. Restore registers

    struct pt_regs_arm64 regs, saved_regs;

    if (ptrace(PTRACE_GETREGSET, pid, (void*)NT_PRSTATUS, nullptr) < 0) {
        // Use PTRACE_GETREGS fallback
        if (ptrace(PTRACE_GETREGS, pid, nullptr, &regs) < 0) {
            LOGE("PTRACE_GETREGS failed: %s", strerror(errno));
            return 0;
        }
    }

    // For simplicity, use /proc/<pid>/mem to write a syscall instruction
    // and execute it. This is a well-known injection technique.

    // ARM64 mmap syscall number = 222
    // syscall(mmap, NULL, size, PROT_READ|PROT_WRITE|PROT_EXEC, MAP_PRIVATE|MAP_ANONYMOUS, -1, 0)

    // Save current registers
    struct iovec iov = { &regs, sizeof(regs) };
    if (ptrace(PTRACE_GETREGSET, pid, (void*)NT_PRSTATUS, &iov) < 0) {
        LOGE("PTRACE_GETREGSET failed: %s", strerror(errno));
        return 0;
    }
    memcpy(&saved_regs, &regs, sizeof(regs));

    // Find a suitable location to inject our syscall stub
    // Use the current PC location (we're stopped)
    uintptr_t inject_addr = regs.pc;

    // ARM64 syscall instruction: SVC #0 = 0xD4000001
    // Followed by BRK #0 to stop after syscall = 0xD4200000
    uint32_t stub[] = {
        0xD4000001,  // SVC #0
        0xD4200000   // BRK #0
    };

    // Save original bytes
    uint32_t orig_bytes[2];
    read_remote_mem(pid, inject_addr, orig_bytes, sizeof(orig_bytes));

    // Write syscall stub
    write_remote_mem(pid, inject_addr, stub, sizeof(stub));

    // Set up registers for mmap syscall
    regs.regs[8] = 222;                          // syscall number (mmap)
    regs.regs[0] = 0;                            // addr = NULL
    regs.regs[1] = size;                         // length
    regs.regs[2] = PROT_READ | PROT_WRITE | PROT_EXEC; // prot
    regs.regs[3] = MAP_PRIVATE | MAP_ANONYMOUS;  // flags
    regs.regs[4] = (uint64_t)-1;                 // fd = -1
    regs.regs[5] = 0;                            // offset

    iov.iov_base = &regs;
    iov.iov_len = sizeof(regs);
    ptrace(PTRACE_SETREGSET, pid, (void*)NT_PRSTATUS, &iov);

    // Execute until BRK
    ptrace(PTRACE_CONT, pid, nullptr, nullptr);
    int status;
    waitpid(pid, &status, 0);

    // Read return value
    iov.iov_base = &regs;
    iov.iov_len = sizeof(regs);
    ptrace(PTRACE_GETREGSET, pid, (void*)NT_PRSTATUS, &iov);
    uintptr_t mmap_result = regs.regs[0];

    // Restore original bytes and registers
    write_remote_mem(pid, inject_addr, orig_bytes, sizeof(orig_bytes));
    iov.iov_base = &saved_regs;
    iov.iov_len = sizeof(saved_regs);
    ptrace(PTRACE_SETREGSET, pid, (void*)NT_PRSTATUS, &iov);

    if (mmap_result == (uintptr_t)-1 || mmap_result == 0) {
        LOGE("Remote mmap failed");
        return 0;
    }

    return mmap_result;
}

// Call dlopen in remote process
static bool remote_dlopen(pid_t pid, const char* lib_path) {
    // Find dlopen in the remote process
    uintptr_t remote_linker_base = find_remote_lib_base(pid, "linker64");
    if (!remote_linker_base) {
        remote_linker_base = find_remote_lib_base(pid, "/apex/com.android.runtime/bin/linker64");
    }

    uintptr_t remote_libdl_base = find_remote_lib_base(pid, "libdl.so");
    if (!remote_libdl_base) {
        remote_libdl_base = find_remote_lib_base(pid, "/apex/com.android.art/lib64/libdl.so");
    }

    if (!remote_libdl_base) {
        LOGE("Cannot find libdl.so in remote process %d", pid);
        return false;
    }

    // Find dlopen offset in local libdl
    uintptr_t dlopen_offset = find_local_symbol_offset("libdl.so", "dlopen");
    if (!dlopen_offset) {
        dlopen_offset = find_local_symbol_offset("/apex/com.android.art/lib64/libdl.so", "dlopen");
    }
    if (!dlopen_offset) {
        LOGE("Cannot find dlopen offset");
        return false;
    }

    uintptr_t remote_dlopen_addr = remote_libdl_base + dlopen_offset;
    LOGI("Remote dlopen at: 0x%lx", remote_dlopen_addr);

    // Allocate memory for library path string
    size_t path_len = strlen(lib_path) + 1;
    uintptr_t remote_path = remote_mmap(pid, 4096);
    if (!remote_path) {
        LOGE("Failed to allocate remote memory for path");
        return false;
    }

    // Write library path to remote memory
    write_remote_mem(pid, remote_path, lib_path, path_len);

    // Allocate memory for our call stub
    uintptr_t stub_addr = remote_mmap(pid, 4096);
    if (!stub_addr) {
        LOGE("Failed to allocate remote memory for stub");
        return false;
    }

    // Build ARM64 stub that calls dlopen(path, RTLD_NOW) and then BRK
    // LDR X0, #24      ; load path address
    // MOV X1, #2       ; RTLD_NOW = 2
    // LDR X17, #16     ; load dlopen address
    // BLR X17          ; call dlopen
    // BRK #0           ; breakpoint
    // .quad path_addr  ; path address
    // .quad dlopen_addr; dlopen address
    uint32_t stub[8];
    stub[0] = 0x58000180;  // LDR X0, #48 (relative to PC)
    stub[1] = 0xD2800041;  // MOV X1, #2
    stub[2] = 0x58000151;  // LDR X17, #40
    stub[3] = 0xD63F0220;  // BLR X17
    stub[4] = 0xD4200000;  // BRK #0
    stub[5] = 0xD503201F;  // NOP (padding)

    // Write stub
    write_remote_mem(pid, stub_addr, stub, sizeof(stub));

    // Write addresses after stub
    write_remote_mem(pid, stub_addr + sizeof(stub), &remote_dlopen_addr, 8);
    write_remote_mem(pid, stub_addr + sizeof(stub) + 8, &remote_path, 8);

    // Set PC to our stub and execute
    struct pt_regs_arm64 regs, saved_regs;
    struct iovec iov = { &regs, sizeof(regs) };
    ptrace(PTRACE_GETREGSET, pid, (void*)NT_PRSTATUS, &iov);
    memcpy(&saved_regs, &regs, sizeof(regs));

    regs.pc = stub_addr;
    iov.iov_base = &regs;
    ptrace(PTRACE_SETREGSET, pid, (void*)NT_PRSTATUS, &iov);

    ptrace(PTRACE_CONT, pid, nullptr, nullptr);
    int status;
    waitpid(pid, &status, 0);

    // Read result
    iov.iov_base = &regs;
    iov.iov_len = sizeof(regs);
    ptrace(PTRACE_GETREGSET, pid, (void*)NT_PRSTATUS, &iov);
    uintptr_t handle = regs.regs[0];

    // Restore registers
    iov.iov_base = &saved_regs;
    iov.iov_len = sizeof(saved_regs);
    ptrace(PTRACE_SETREGSET, pid, (void*)NT_PRSTATUS, &iov);

    // Free stub memory (munmap)
    // For simplicity, we leave it - it's a small amount

    LOGI("dlopen result: 0x%lx for %s in pid=%d", handle, lib_path, pid);
    return handle != 0;
}

bool injector_inject_process(pid_t pid) {
    LOGI("Injecting into pid=%d", pid);

    // Determine library path
    const char* lib_path = HOOK_LIB_PATH;
    if (access(lib_path, F_OK) != 0) {
        lib_path = HOOK_LIB_PATH_ALT;
        if (access(lib_path, F_OK) != 0) {
            LOGE("Hook library not found at %s or %s", HOOK_LIB_PATH, HOOK_LIB_PATH_ALT);
            return false;
        }
    }

    // Check if already injected
    uintptr_t existing = find_remote_lib_base(pid, "libgodmode_hook.so");
    if (existing) {
        LOGI("Already injected into pid=%d", pid);
        return true;
    }

    // Attach to process
    if (ptrace(PTRACE_ATTACH, pid, nullptr, nullptr) < 0) {
        LOGE("PTRACE_ATTACH failed for pid=%d: %s", pid, strerror(errno));
        return false;
    }

    int status;
    if (waitpid(pid, &status, 0) < 0) {
        LOGE("waitpid failed: %s", strerror(errno));
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return false;
    }

    if (!WIFSTOPPED(status)) {
        LOGE("Process not stopped after attach");
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return false;
    }

    LOGI("Attached to pid=%d, injecting %s", pid, lib_path);

    bool success = remote_dlopen(pid, lib_path);

    // Detach
    ptrace(PTRACE_DETACH, pid, nullptr, nullptr);

    if (success) {
        LOGI("Successfully injected %s into pid=%d", lib_path, pid);
    } else {
        LOGE("Failed to inject %s into pid=%d", lib_path, pid);
    }

    return success;
}
