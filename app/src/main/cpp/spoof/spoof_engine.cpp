/**
 * GodMode - Spoof Engine
 *
 * Manages spoofing configuration per app and provides spoofed values
 * for all intercepted data types:
 *   - IMEI / Device ID
 *   - IMSI / SIM Serial
 *   - Android ID
 *   - GPS Location (lat/lon/alt/accuracy)
 *   - IP Address (IPv4/IPv6)
 *   - MAC Address
 *   - Device Serial Number
 *   - Build Properties (model, brand, fingerprint)
 *   - Network Operator (MCC/MNC)
 *   - Phone Number
 */

#include "spoof_engine.h"
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <time.h>
#include <math.h>
#include <pthread.h>
#include <sys/types.h>
#include <linux/android/binder.h>

#define TAG "GodMode_Spoof"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Maximum number of apps we track
#define MAX_APP_CONFIGS 128

// Spoof mode
#define SPOOF_MODE_DISABLED  0
#define SPOOF_MODE_CUSTOM    1
#define SPOOF_MODE_RANDOM    2
#define SPOOF_MODE_EMPTY     3

struct AppSpoofConfig {
    char package_name[256];
    bool active;

    // IMEI spoofing
    int imei_mode;
    char custom_imei[20];

    // IMSI spoofing
    int imsi_mode;
    char custom_imsi[20];

    // Android ID spoofing
    int android_id_mode;
    char custom_android_id[32];

    // Serial number spoofing
    int serial_mode;
    char custom_serial[32];

    // Location spoofing
    int location_mode;
    double custom_lat;
    double custom_lon;
    double custom_alt;
    float custom_accuracy;

    // IP address spoofing
    int ip_mode;
    char custom_ip[64];

    // MAC address spoofing
    int mac_mode;
    char custom_mac[18];

    // Phone number spoofing
    int phone_mode;
    char custom_phone[20];

    // Build props spoofing
    int build_mode;
    char custom_model[64];
    char custom_brand[64];
    char custom_fingerprint[256];

    // Network operator spoofing
    int operator_mode;
    char custom_mcc_mnc[8];
    char custom_operator_name[64];

    // SIM serial spoofing
    int sim_serial_mode;
    char custom_sim_serial[32];

    // Advertising ID spoofing
    int adid_mode;
    char custom_adid[64];
};

static AppSpoofConfig g_configs[MAX_APP_CONFIGS];
static int g_config_count = 0;
static char g_current_package[256] = {0};
static AppSpoofConfig* g_current_config = nullptr;
static pthread_mutex_t g_mutex = PTHREAD_MUTEX_INITIALIZER;

// Random seed initialized once
static bool g_rand_seeded = false;

static void ensure_rand_seeded() {
    if (!g_rand_seeded) {
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        srand((unsigned int)(ts.tv_nsec ^ ts.tv_sec ^ getpid()));
        g_rand_seeded = true;
    }
}

// Generate a random IMEI (15 digits, Luhn-valid)
static void gen_random_imei(char* out) {
    ensure_rand_seeded();
    // TAC (8 digits) + random (6 digits) + check digit
    int digits[15];
    // Random TAC
    for (int i = 0; i < 14; i++) {
        digits[i] = rand() % 10;
    }
    // Luhn check digit
    int sum = 0;
    for (int i = 0; i < 14; i++) {
        int d = digits[i];
        if ((i % 2) == 1) {
            d *= 2;
            if (d > 9) d -= 9;
        }
        sum += d;
    }
    digits[14] = (10 - (sum % 10)) % 10;
    for (int i = 0; i < 15; i++) {
        out[i] = '0' + digits[i];
    }
    out[15] = '\0';
}

// Generate a random Android ID (16 hex chars)
static void gen_random_android_id(char* out) {
    ensure_rand_seeded();
    for (int i = 0; i < 16; i++) {
        int r = rand() % 16;
        out[i] = (r < 10) ? ('0' + r) : ('a' + r - 10);
    }
    out[16] = '\0';
}

// Generate a random serial number
static void gen_random_serial(char* out) {
    ensure_rand_seeded();
    const char* chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    for (int i = 0; i < 12; i++) {
        out[i] = chars[rand() % 36];
    }
    out[12] = '\0';
}

// Generate a random MAC address
static void gen_random_mac(char* out) {
    ensure_rand_seeded();
    uint8_t mac[6];
    for (int i = 0; i < 6; i++) mac[i] = rand() % 256;
    mac[0] &= 0xFE; // unicast
    mac[0] |= 0x02; // locally administered
    snprintf(out, 18, "%02X:%02X:%02X:%02X:%02X:%02X",
             mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
}

// Generate a random IPv4 address (private range)
static void gen_random_ip(char* out) {
    ensure_rand_seeded();
    snprintf(out, 16, "192.168.%d.%d", rand() % 255, 1 + rand() % 254);
}

void spoof_engine_init(const char* package_name) {
    pthread_mutex_lock(&g_mutex);
    strncpy(g_current_package, package_name, sizeof(g_current_package) - 1);

    // Find config for this package
    g_current_config = nullptr;
    for (int i = 0; i < g_config_count; i++) {
        if (strcmp(g_configs[i].package_name, package_name) == 0) {
            g_current_config = &g_configs[i];
            break;
        }
    }

    if (!g_current_config) {
        LOGI("No spoof config for %s - monitoring only", package_name);
    } else {
        LOGI("Spoof config loaded for %s", package_name);
    }
    pthread_mutex_unlock(&g_mutex);
}

void spoof_engine_update_config(const char* json_config) {
    // Parse simple key=value config string
    // Format: package=com.example|imei_mode=2|custom_imei=123456789012345|...
    pthread_mutex_lock(&g_mutex);

    char buf[4096];
    strncpy(buf, json_config, sizeof(buf) - 1);

    AppSpoofConfig* cfg = nullptr;
    char pkg[256] = {0};

    // Find package name first
    char* p = buf;
    char* token;
    while ((token = strsep(&p, "|")) != nullptr) {
        if (strncmp(token, "package=", 8) == 0) {
            strncpy(pkg, token + 8, sizeof(pkg) - 1);
            break;
        }
    }

    if (pkg[0] == 0) {
        pthread_mutex_unlock(&g_mutex);
        return;
    }

    // Find or create config entry
    for (int i = 0; i < g_config_count; i++) {
        if (strcmp(g_configs[i].package_name, pkg) == 0) {
            cfg = &g_configs[i];
            break;
        }
    }
    if (!cfg && g_config_count < MAX_APP_CONFIGS) {
        cfg = &g_configs[g_config_count++];
        memset(cfg, 0, sizeof(*cfg));
        strncpy(cfg->package_name, pkg, sizeof(cfg->package_name) - 1);
    }

    if (!cfg) {
        pthread_mutex_unlock(&g_mutex);
        return;
    }

    // Parse remaining fields
    strncpy(buf, json_config, sizeof(buf) - 1);
    p = buf;
    while ((token = strsep(&p, "|")) != nullptr) {
        char* eq = strchr(token, '=');
        if (!eq) continue;
        *eq = '\0';
        const char* key = token;
        const char* val = eq + 1;

        if (strcmp(key, "imei_mode") == 0) cfg->imei_mode = atoi(val);
        else if (strcmp(key, "custom_imei") == 0) strncpy(cfg->custom_imei, val, sizeof(cfg->custom_imei)-1);
        else if (strcmp(key, "imsi_mode") == 0) cfg->imsi_mode = atoi(val);
        else if (strcmp(key, "custom_imsi") == 0) strncpy(cfg->custom_imsi, val, sizeof(cfg->custom_imsi)-1);
        else if (strcmp(key, "android_id_mode") == 0) cfg->android_id_mode = atoi(val);
        else if (strcmp(key, "custom_android_id") == 0) strncpy(cfg->custom_android_id, val, sizeof(cfg->custom_android_id)-1);
        else if (strcmp(key, "serial_mode") == 0) cfg->serial_mode = atoi(val);
        else if (strcmp(key, "custom_serial") == 0) strncpy(cfg->custom_serial, val, sizeof(cfg->custom_serial)-1);
        else if (strcmp(key, "location_mode") == 0) cfg->location_mode = atoi(val);
        else if (strcmp(key, "custom_lat") == 0) cfg->custom_lat = atof(val);
        else if (strcmp(key, "custom_lon") == 0) cfg->custom_lon = atof(val);
        else if (strcmp(key, "custom_alt") == 0) cfg->custom_alt = atof(val);
        else if (strcmp(key, "ip_mode") == 0) cfg->ip_mode = atoi(val);
        else if (strcmp(key, "custom_ip") == 0) strncpy(cfg->custom_ip, val, sizeof(cfg->custom_ip)-1);
        else if (strcmp(key, "mac_mode") == 0) cfg->mac_mode = atoi(val);
        else if (strcmp(key, "custom_mac") == 0) strncpy(cfg->custom_mac, val, sizeof(cfg->custom_mac)-1);
        else if (strcmp(key, "phone_mode") == 0) cfg->phone_mode = atoi(val);
        else if (strcmp(key, "custom_phone") == 0) strncpy(cfg->custom_phone, val, sizeof(cfg->custom_phone)-1);
        else if (strcmp(key, "build_mode") == 0) cfg->build_mode = atoi(val);
        else if (strcmp(key, "custom_model") == 0) strncpy(cfg->custom_model, val, sizeof(cfg->custom_model)-1);
        else if (strcmp(key, "custom_brand") == 0) strncpy(cfg->custom_brand, val, sizeof(cfg->custom_brand)-1);
        else if (strcmp(key, "operator_mode") == 0) cfg->operator_mode = atoi(val);
        else if (strcmp(key, "custom_mcc_mnc") == 0) strncpy(cfg->custom_mcc_mnc, val, sizeof(cfg->custom_mcc_mnc)-1);
        else if (strcmp(key, "adid_mode") == 0) cfg->adid_mode = atoi(val);
        else if (strcmp(key, "custom_adid") == 0) strncpy(cfg->custom_adid, val, sizeof(cfg->custom_adid)-1);
        else if (strcmp(key, "active") == 0) cfg->active = atoi(val) != 0;
    }

    // Update current config pointer if this is our package
    if (strcmp(pkg, g_current_package) == 0) {
        g_current_config = cfg;
    }

    pthread_mutex_unlock(&g_mutex);
}

bool spoof_engine_get_property(const char* prop_name, const char* original,
                                char* spoofed_out) {
    pthread_mutex_lock(&g_mutex);
    AppSpoofConfig* cfg = g_current_config;

    if (!cfg || !cfg->active) {
        pthread_mutex_unlock(&g_mutex);
        return false;
    }

    bool spoofed = false;

    if (strcmp(prop_name, "ro.serialno") == 0 || strcmp(prop_name, "ro.boot.serialno") == 0) {
        if (cfg->serial_mode == SPOOF_MODE_CUSTOM) {
            strncpy(spoofed_out, cfg->custom_serial, PROP_VALUE_MAX - 1);
            spoofed = true;
        } else if (cfg->serial_mode == SPOOF_MODE_RANDOM) {
            gen_random_serial(spoofed_out);
            spoofed = true;
        } else if (cfg->serial_mode == SPOOF_MODE_EMPTY) {
            spoofed_out[0] = '\0';
            spoofed = true;
        }
    } else if (strcmp(prop_name, "ro.product.model") == 0) {
        if (cfg->build_mode == SPOOF_MODE_CUSTOM && cfg->custom_model[0]) {
            strncpy(spoofed_out, cfg->custom_model, PROP_VALUE_MAX - 1);
            spoofed = true;
        }
    } else if (strcmp(prop_name, "ro.product.brand") == 0) {
        if (cfg->build_mode == SPOOF_MODE_CUSTOM && cfg->custom_brand[0]) {
            strncpy(spoofed_out, cfg->custom_brand, PROP_VALUE_MAX - 1);
            spoofed = true;
        }
    }

    pthread_mutex_unlock(&g_mutex);
    return spoofed;
}

// Process a Binder reply and potentially modify it
void spoof_engine_process_reply(const char* package, binder_transaction_data* txn) {
    // This function modifies Binder reply data in-place
    // The actual implementation depends on the specific transaction
    // For now, we log the access and apply spoofing if configured
    // Full implementation requires parsing the Parcel format

    pthread_mutex_lock(&g_mutex);
    AppSpoofConfig* cfg = g_current_config;
    if (!cfg || !cfg->active) {
        pthread_mutex_unlock(&g_mutex);
        return;
    }
    // TODO: Parse Parcel data and apply spoofing based on transaction type
    // This requires knowledge of the specific Parcel layout for each service
    pthread_mutex_unlock(&g_mutex);
}

// Get spoofed IMEI
bool spoof_engine_get_imei(char* out, size_t max_len) {
    pthread_mutex_lock(&g_mutex);
    AppSpoofConfig* cfg = g_current_config;
    if (!cfg || !cfg->active || cfg->imei_mode == SPOOF_MODE_DISABLED) {
        pthread_mutex_unlock(&g_mutex);
        return false;
    }

    if (cfg->imei_mode == SPOOF_MODE_CUSTOM) {
        strncpy(out, cfg->custom_imei, max_len - 1);
    } else if (cfg->imei_mode == SPOOF_MODE_RANDOM) {
        gen_random_imei(out);
    } else if (cfg->imei_mode == SPOOF_MODE_EMPTY) {
        out[0] = '\0';
    }

    pthread_mutex_unlock(&g_mutex);
    return true;
}

// Get spoofed Android ID
bool spoof_engine_get_android_id(char* out, size_t max_len) {
    pthread_mutex_lock(&g_mutex);
    AppSpoofConfig* cfg = g_current_config;
    if (!cfg || !cfg->active || cfg->android_id_mode == SPOOF_MODE_DISABLED) {
        pthread_mutex_unlock(&g_mutex);
        return false;
    }

    if (cfg->android_id_mode == SPOOF_MODE_CUSTOM) {
        strncpy(out, cfg->custom_android_id, max_len - 1);
    } else if (cfg->android_id_mode == SPOOF_MODE_RANDOM) {
        gen_random_android_id(out);
    } else if (cfg->android_id_mode == SPOOF_MODE_EMPTY) {
        out[0] = '\0';
    }

    pthread_mutex_unlock(&g_mutex);
    return true;
}

// Get spoofed location
bool spoof_engine_get_location(double* lat, double* lon, double* alt, float* accuracy) {
    pthread_mutex_lock(&g_mutex);
    AppSpoofConfig* cfg = g_current_config;
    if (!cfg || !cfg->active || cfg->location_mode == SPOOF_MODE_DISABLED) {
        pthread_mutex_unlock(&g_mutex);
        return false;
    }

    if (cfg->location_mode == SPOOF_MODE_CUSTOM) {
        *lat = cfg->custom_lat;
        *lon = cfg->custom_lon;
        *alt = cfg->custom_alt;
        *accuracy = cfg->custom_accuracy;
    } else if (cfg->location_mode == SPOOF_MODE_RANDOM) {
        ensure_rand_seeded();
        *lat = -90.0 + (rand() % 18000) / 100.0;
        *lon = -180.0 + (rand() % 36000) / 100.0;
        *alt = rand() % 1000;
        *accuracy = 5.0f + (rand() % 20);
    } else if (cfg->location_mode == SPOOF_MODE_EMPTY) {
        *lat = 0.0; *lon = 0.0; *alt = 0.0; *accuracy = 0.0f;
    }

    pthread_mutex_unlock(&g_mutex);
    return true;
}

// Get spoofed IP
bool spoof_engine_get_ip(char* out, size_t max_len) {
    pthread_mutex_lock(&g_mutex);
    AppSpoofConfig* cfg = g_current_config;
    if (!cfg || !cfg->active || cfg->ip_mode == SPOOF_MODE_DISABLED) {
        pthread_mutex_unlock(&g_mutex);
        return false;
    }

    if (cfg->ip_mode == SPOOF_MODE_CUSTOM) {
        strncpy(out, cfg->custom_ip, max_len - 1);
    } else if (cfg->ip_mode == SPOOF_MODE_RANDOM) {
        gen_random_ip(out);
    }

    pthread_mutex_unlock(&g_mutex);
    return true;
}

// Get spoofed MAC
bool spoof_engine_get_mac(char* out, size_t max_len) {
    pthread_mutex_lock(&g_mutex);
    AppSpoofConfig* cfg = g_current_config;
    if (!cfg || !cfg->active || cfg->mac_mode == SPOOF_MODE_DISABLED) {
        pthread_mutex_unlock(&g_mutex);
        return false;
    }

    if (cfg->mac_mode == SPOOF_MODE_CUSTOM) {
        strncpy(out, cfg->custom_mac, max_len - 1);
    } else if (cfg->mac_mode == SPOOF_MODE_RANDOM) {
        gen_random_mac(out);
    }

    pthread_mutex_unlock(&g_mutex);
    return true;
}
