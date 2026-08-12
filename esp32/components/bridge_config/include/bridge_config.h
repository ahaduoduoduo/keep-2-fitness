#pragma once

#include <stdbool.h>
#include <stddef.h>
#include "esp_err.h"

#define BRIDGE_SN_LENGTH 16
#define BRIDGE_USER_ID_LENGTH 24
#define BRIDGE_DEVICE_ID_LENGTH 16
#define BRIDGE_WIFI_SSID_LENGTH 32
#define BRIDGE_WIFI_PASSWORD_LENGTH 64
#define BRIDGE_API_KEY_LENGTH 48

typedef struct {
    char wifi_ssid[BRIDGE_WIFI_SSID_LENGTH + 1];
    char wifi_password[BRIDGE_WIFI_PASSWORD_LENGTH + 1];
    bool wifi_configured;
    char bound_sn[BRIDGE_SN_LENGTH + 1];
    bool device_bound;
    char bridge_user_id[BRIDGE_USER_ID_LENGTH + 1];
    char bridge_device_id[BRIDGE_DEVICE_ID_LENGTH + 1];
    float user_weight_kg;
    bool api_auth_enabled;
    char api_key[BRIDGE_API_KEY_LENGTH + 1];
} bridge_config_t;

void bridge_config_init(void);
void bridge_config_get(bridge_config_t *config);
esp_err_t bridge_config_set_wifi(const char *ssid, const char *password);
esp_err_t bridge_config_clear_wifi(void);
esp_err_t bridge_config_bind_device(const char *serial);
esp_err_t bridge_config_clear_device_binding(void);
esp_err_t bridge_config_set_weight(float kilograms);
esp_err_t bridge_config_set_api_auth(bool enabled, const char *api_key);
bool bridge_config_api_key_matches(const char *api_key);
bool bridge_config_valid_serial(const char *serial);
const char *bridge_config_suggested_serial(void);
