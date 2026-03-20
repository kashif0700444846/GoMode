#pragma once
#include <sys/types.h>
#include <stdbool.h>
#include <stddef.h>
#include <linux/android/binder.h>

#ifdef __cplusplus
extern "C" {
#endif

void spoof_engine_init(const char* package_name);
void spoof_engine_update_config(const char* config_str);
bool spoof_engine_get_property(const char* prop_name, const char* original, char* spoofed_out);
void spoof_engine_process_reply(const char* package, binder_transaction_data* txn);

bool spoof_engine_get_imei(char* out, size_t max_len);
bool spoof_engine_get_android_id(char* out, size_t max_len);
bool spoof_engine_get_location(double* lat, double* lon, double* alt, float* accuracy);
bool spoof_engine_get_ip(char* out, size_t max_len);
bool spoof_engine_get_mac(char* out, size_t max_len);

#ifdef __cplusplus
}
#endif
