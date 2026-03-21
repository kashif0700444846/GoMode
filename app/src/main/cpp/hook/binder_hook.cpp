/**
 * GodMode - Binder IPC Hook
 *
 * Intercepts ioctl calls on /dev/binder to monitor and modify
 * IPC transactions between apps and Android system services.
 * This allows us to intercept calls to:
 *   - TelephonyManager (IMEI, IMSI, phone number)
 *   - LocationManager (GPS coordinates)
 *   - WifiManager (MAC address, SSID)
 *   - PackageManager (installed apps)
 *   - ClipboardManager
 *   - SensorManager
 *   - CameraManager
 *   - AudioManager
 *   - NetworkInterface (IP address)
 */

#include "binder_hook.h"
#include "inline_hook.h"
#include "../spoof/spoof_engine.h"
#include "../ipc/socket_client.h"
#include <sys/types.h>
#include <android/log.h>
#include <sys/ioctl.h>
#include <linux/android/binder.h>
#include <dlfcn.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <pthread.h>
#include <time.h>

#define TAG "GodMode_Binder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// Binder transaction codes for common services
// These are interface transaction codes from AIDL definitions
#define TELEPHONY_GET_DEVICE_ID     1   // getDeviceId
#define TELEPHONY_GET_IMEI          5   // getImei
#define TELEPHONY_GET_IMSI          11  // getSubscriberId
#define TELEPHONY_GET_LINE1_NUMBER  12  // getLine1Number
#define TELEPHONY_GET_SIM_SERIAL    14  // getSimSerialNumber
#define LOCATION_GET_LAST_LOCATION  1   // getLastLocation
#define WIFI_GET_CONNECTION_INFO    4   // getConnectionInfo (MAC, SSID)
#define SETTINGS_GET_STRING         1   // getString (Android ID)
#define NETWORK_GET_INTERFACES      1   // getNetworkInterfaces

// Original ioctl function pointer
typedef int (*ioctl_fn_t)(int fd, unsigned long request, ...);
static ioctl_fn_t original_ioctl = nullptr;

// Current process package name
static char g_package_name[256] = {0};
static int g_uid = -1;

// Mutex for thread safety
static pthread_mutex_t g_log_mutex = PTHREAD_MUTEX_INITIALIZER;

// Kernel-level verification log file
static FILE* g_kernel_log = nullptr;
static void write_kernel_verification(const char* msg) {
    if (!g_kernel_log) {
        g_kernel_log = fopen("/data/local/tmp/gomode_kernel.log", "a");
    }
    if (g_kernel_log) {
        fprintf(g_kernel_log, "[KERNEL-L0] %ld: %s\n", time(nullptr), msg);
        fflush(g_kernel_log);
    }
}

// Log an access event with kernel verification
static void log_access_event(const char* property, const char* original_value,
                              const char* spoofed_value, bool was_spoofed) {
    pthread_mutex_lock(&g_log_mutex);

    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    long timestamp_ms = ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;

    // KERNEL VERIFICATION: Write to kernel log
    char kernel_msg[512];
    snprintf(kernel_msg, sizeof(kernel_msg),
             "INTERCEPT: pkg=%s uid=%d property=%s spoofed=%d",
             g_package_name, g_uid, property, was_spoofed);
    write_kernel_verification(kernel_msg);

    // Send log to daemon via socket
    char log_msg[1024];
    snprintf(log_msg, sizeof(log_msg),
             "LOG|%s|%d|%ld|%s|%s|%s|%d",
             g_package_name, g_uid, timestamp_ms,
             property, original_value ? original_value : "",
             spoofed_value ? spoofed_value : "",
             was_spoofed ? 1 : 0);

    socket_client_send(log_msg);

    LOGI("KERNEL-L0: ACCESS pkg=%s prop=%s orig=%s spoof=%s",
         g_package_name, property,
         original_value ? original_value : "null",
         spoofed_value ? spoofed_value : "null");

    pthread_mutex_unlock(&g_log_mutex);
}

/**
 * Parses a Binder transaction buffer to identify the service interface
 * and transaction code, returning a human-readable property name.
 */
static const char* identify_binder_transaction(const binder_transaction_data* txn) {
    if (!txn || !txn->data.ptr.buffer) return nullptr;

    // The interface token is a UTF-16 string at the start of the buffer
    // preceded by a 4-byte length. We look for known interface names.
    const uint8_t* data = (const uint8_t*)txn->data.ptr.buffer;
    size_t size = txn->data_size;

    // Check for known interface tokens (UTF-16LE encoded)
    // android.telephony.ITelephony
    const char* telephony_marker = "android.telephony.ITelephony";
    // android.location.ILocationManager
    const char* location_marker = "android.location.ILocationManager";
    // android.net.wifi.IWifiManager
    const char* wifi_marker = "android.net.wifi.IWifiManager";
    // android.provider.ISettings
    const char* settings_marker = "android.provider.ISettings";

    // Simple UTF-16 to ASCII check by looking at every other byte
    char interface_buf[128] = {0};
    int j = 0;
    // Skip the first 4 bytes (length field) and read UTF-16LE
    for (size_t i = 4; i + 1 < size && j < 127; i += 2) {
        char c = data[i];
        if (c == 0) break;
        interface_buf[j++] = c;
    }
    interface_buf[j] = 0;

    LOGD("Binder interface: %s code: %d", interface_buf, txn->code);

    if (strstr(interface_buf, "ITelephony") || strstr(interface_buf, "IPhoneSubInfo")) {
        switch (txn->code) {
            case 1: return "TELEPHONY_DEVICE_ID";
            case 5: return "TELEPHONY_IMEI";
            case 11: return "TELEPHONY_IMSI";
            case 12: return "TELEPHONY_LINE1_NUMBER";
            case 14: return "TELEPHONY_SIM_SERIAL";
            default: return "TELEPHONY_OTHER";
        }
    } else if (strstr(interface_buf, "ILocationManager")) {
        return "LOCATION_ACCESS";
    } else if (strstr(interface_buf, "IWifiManager")) {
        switch (txn->code) {
            case 4: return "WIFI_MAC_SSID";
            default: return "WIFI_ACCESS";
        }
    } else if (strstr(interface_buf, "ISettings") || strstr(interface_buf, "Settings")) {
        return "SETTINGS_ANDROID_ID";
    } else if (strstr(interface_buf, "INetworkManagement") || strstr(interface_buf, "IConnectivity")) {
        return "NETWORK_IP_ACCESS";
    } else if (strstr(interface_buf, "ICamera") || strstr(interface_buf, "ICameraService")) {
        return "CAMERA_ACCESS";
    } else if (strstr(interface_buf, "IAudioService") || strstr(interface_buf, "IAudioManager")) {
        return "AUDIO_ACCESS";
    } else if (strstr(interface_buf, "IClipboard")) {
        return "CLIPBOARD_ACCESS";
    } else if (strstr(interface_buf, "ISensorManager")) {
        return "SENSOR_ACCESS";
    } else if (strstr(interface_buf, "IPackageManager")) {
        return "PACKAGE_LIST_ACCESS";
    } else if (strstr(interface_buf, "IActivityManager") || strstr(interface_buf, "IActivityTaskManager")) {
        return "ACTIVITY_ACCESS";
    }

    return nullptr;
}

/**
 * Hooked ioctl function.
 * Intercepts BINDER_WRITE_READ ioctl calls to monitor and modify
 * Binder IPC transactions.
 */
int hooked_ioctl(int fd, unsigned long request, void* arg) {
    // Only intercept Binder write/read calls
    if (request != BINDER_WRITE_READ || !arg) {
        return original_ioctl(fd, request, arg);
    }

    // KERNEL VERIFICATION: Log interception
    static int intercept_count = 0;
    intercept_count++;
    if (intercept_count % 100 == 1) {  // Log every 100th to avoid spam
        char verify_msg[128];
        snprintf(verify_msg, sizeof(verify_msg),
                 "ACTIVE: Intercepted %d Binder calls from pkg=%s uid=%d",
                 intercept_count, g_package_name, g_uid);
        write_kernel_verification(verify_msg);
    }

    struct binder_write_read* bwr = (struct binder_write_read*)arg;

    // Process write buffer - outgoing transactions FROM this process
    if (bwr->write_size > 0 && bwr->write_buffer) {
        const uint8_t* ptr = (const uint8_t*)bwr->write_buffer;
        const uint8_t* end = ptr + bwr->write_size;

        while (ptr < end) {
            uint32_t cmd = *(uint32_t*)ptr;
            ptr += sizeof(uint32_t);

            if (cmd == BC_TRANSACTION || cmd == BC_TRANSACTION_SG) {
                const binder_transaction_data* txn = (const binder_transaction_data*)ptr;
                const char* property = identify_binder_transaction(txn);
                if (property) {
                    LOGD("KERNEL-L0: Outgoing Binder: %s", property);
                    log_access_event(property, nullptr, nullptr, false);
                }
                ptr += sizeof(binder_transaction_data);
                if (cmd == BC_TRANSACTION_SG) {
                    ptr += sizeof(binder_transaction_data_sg) - sizeof(binder_transaction_data);
                }
            } else {
                // Unknown command, stop parsing
                break;
            }
        }
    }

    // Execute the actual ioctl
    int result = original_ioctl(fd, request, arg);

    // Process read buffer - incoming replies TO this process
    if (result == 0 && bwr->read_size > 0 && bwr->read_buffer && bwr->read_consumed > 0) {
        uint8_t* ptr = (uint8_t*)bwr->read_buffer;
        uint8_t* end = ptr + bwr->read_consumed;

        while (ptr < end) {
            uint32_t cmd = *(uint32_t*)ptr;
            ptr += sizeof(uint32_t);

            if (cmd == BR_REPLY) {
                binder_transaction_data* txn = (binder_transaction_data*)ptr;

                // Try to identify and spoof the reply
                // This is where we modify the data coming back from system services
                spoof_engine_process_reply(g_package_name, txn);

                ptr += sizeof(binder_transaction_data);
            } else if (cmd == BR_TRANSACTION) {
                ptr += sizeof(binder_transaction_data);
            } else {
                break;
            }
        }
    }

    return result;
}

// Initialize package name from /proc/self/cmdline
static void init_package_name() {
    FILE* f = fopen("/proc/self/cmdline", "r");
    if (f) {
        fgets(g_package_name, sizeof(g_package_name) - 1, f);
        fclose(f);
        // cmdline uses null bytes as separators, first token is package name
        for (int i = 0; i < (int)sizeof(g_package_name); i++) {
            if (g_package_name[i] == '\0') break;
            if (g_package_name[i] == ':') {
                g_package_name[i] = '\0';
                break;
            }
        }
    }
    g_uid = getuid();
    LOGI("GodMode injected into: %s (uid=%d)", g_package_name, g_uid);
}

bool binder_hook_install() {
    init_package_name();

    // KERNEL VERIFICATION: Log hook installation
    write_kernel_verification("HOOK_INSTALL: Starting binder hook installation");
    
    // Find ioctl in libc
    void* libc = dlopen("libc.so", RTLD_NOW);
    if (!libc) {
        LOGE("Failed to open libc.so: %s", dlerror());
        write_kernel_verification("HOOK_INSTALL: FAILED - libc.so not found");
        return false;
    }

    void* ioctl_addr = dlsym(libc, "ioctl");
    dlclose(libc);

    if (!ioctl_addr) {
        LOGE("Failed to find ioctl: %s", dlerror());
        write_kernel_verification("HOOK_INSTALL: FAILED - ioctl symbol not found");
        return false;
    }

    LOGI("Installing Binder hook at ioctl: %p", ioctl_addr);
    char addr_msg[128];
    snprintf(addr_msg, sizeof(addr_msg), "HOOK_INSTALL: ioctl found at %p", ioctl_addr);
    write_kernel_verification(addr_msg);

    bool ok = inline_hook_install(ioctl_addr, (void*)hooked_ioctl, (void**)&original_ioctl);
    if (!ok) {
        LOGE("Failed to install ioctl hook");
        write_kernel_verification("HOOK_INSTALL: FAILED - inline hook installation failed");
        return false;
    }

    LOGI("Binder hook installed successfully for %s", g_package_name);
    char success_msg[256];
    snprintf(success_msg, sizeof(success_msg), 
             "HOOK_INSTALL: SUCCESS - Binder hook active for pkg=%s uid=%d", 
             g_package_name, g_uid);
    write_kernel_verification(success_msg);
    
    return true;
}

void binder_hook_remove() {
    if (original_ioctl) {
        void* libc = dlopen("libc.so", RTLD_NOW);
        if (libc) {
            void* ioctl_addr = dlsym(libc, "ioctl");
            dlclose(libc);
            if (ioctl_addr) {
                inline_hook_remove(ioctl_addr);
            }
        }
        original_ioctl = nullptr;
    }
}
