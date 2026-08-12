#include "bridge_config.h"

#include <ctype.h>
#include <stdio.h>
#include <string.h>

#include "bridge_state.h"
#include "esp_check.h"
#include "esp_random.h"
#include "nvs.h"

#ifndef C1_DEFAULT_SN
#define C1_DEFAULT_SN ""
#endif

static const char *NAMESPACE = "c1_bridge";
static bridge_config_t current;

static void random_hex(char *output, size_t length) {
    static const char hex[] = "0123456789abcdef";
    for (size_t index = 0; index < length; index++) {
        output[index] = hex[esp_random() & 0x0f];
    }
    output[length] = '\0';
}

static bool read_string(nvs_handle_t nvs, const char *key, char *value, size_t capacity) {
    size_t length = capacity;
    return nvs_get_str(nvs, key, value, &length) == ESP_OK;
}

static void save_string(nvs_handle_t nvs, const char *key, const char *value) {
    ESP_ERROR_CHECK(nvs_set_str(nvs, key, value));
}

bool bridge_config_valid_serial(const char *serial) {
    if (!serial || strlen(serial) != BRIDGE_SN_LENGTH || serial[0] != 'C' || serial[1] != 'C') {
        return false;
    }
    for (size_t index = 0; index < BRIDGE_SN_LENGTH; index++) {
        if (!isalnum((unsigned char)serial[index])) return false;
    }
    return true;
}

void bridge_config_init(void) {
    memset(&current, 0, sizeof(current));
    current.user_weight_kg = 60.0f;

    nvs_handle_t nvs;
    ESP_ERROR_CHECK(nvs_open(NAMESPACE, NVS_READWRITE, &nvs));
    current.wifi_configured = read_string(
        nvs, "wifi_ssid", current.wifi_ssid, sizeof(current.wifi_ssid));
    read_string(nvs, "wifi_pass", current.wifi_password, sizeof(current.wifi_password));

    if (read_string(nvs, "bound_sn", current.bound_sn, sizeof(current.bound_sn)) &&
        bridge_config_valid_serial(current.bound_sn)) {
        current.device_bound = true;
    } else {
        current.bound_sn[0] = '\0';
    }

    if (!read_string(nvs, "user_id", current.bridge_user_id, sizeof(current.bridge_user_id)) ||
        strlen(current.bridge_user_id) != BRIDGE_USER_ID_LENGTH) {
        random_hex(current.bridge_user_id, BRIDGE_USER_ID_LENGTH);
        save_string(nvs, "user_id", current.bridge_user_id);
    }
    if (!read_string(nvs, "device_id", current.bridge_device_id, sizeof(current.bridge_device_id)) ||
        strlen(current.bridge_device_id) != BRIDGE_DEVICE_ID_LENGTH) {
        random_hex(current.bridge_device_id, BRIDGE_DEVICE_ID_LENGTH);
        save_string(nvs, "device_id", current.bridge_device_id);
    }
    size_t weight_size = sizeof(current.user_weight_kg);
    nvs_get_blob(nvs, "weight", &current.user_weight_kg, &weight_size);
    if (current.user_weight_kg < 25.0f || current.user_weight_kg > 250.0f) {
        current.user_weight_kg = 60.0f;
    }
    uint8_t auth_enabled = 0;
    nvs_get_u8(nvs, "api_auth", &auth_enabled);
    current.api_auth_enabled = auth_enabled != 0;
    read_string(nvs, "api_key", current.api_key, sizeof(current.api_key));
    if (current.api_auth_enabled && strlen(current.api_key) < 8) {
        current.api_auth_enabled = false;
    }
    ESP_ERROR_CHECK(nvs_commit(nvs));
    nvs_close(nvs);
    bridge_state_set_device_bound(current.device_bound);
}

void bridge_config_get(bridge_config_t *config) {
    if (config) *config = current;
}

esp_err_t bridge_config_set_wifi(const char *ssid, const char *password) {
    if (!ssid || !ssid[0] || strlen(ssid) > BRIDGE_WIFI_SSID_LENGTH ||
        !password || strlen(password) > BRIDGE_WIFI_PASSWORD_LENGTH) {
        return ESP_ERR_INVALID_ARG;
    }
    nvs_handle_t nvs;
    ESP_RETURN_ON_ERROR(nvs_open(NAMESPACE, NVS_READWRITE, &nvs), "bridge_config", "open");
    strlcpy(current.wifi_ssid, ssid, sizeof(current.wifi_ssid));
    strlcpy(current.wifi_password, password, sizeof(current.wifi_password));
    current.wifi_configured = true;
    esp_err_t err = nvs_set_str(nvs, "wifi_ssid", current.wifi_ssid);
    if (err == ESP_OK) err = nvs_set_str(nvs, "wifi_pass", current.wifi_password);
    if (err == ESP_OK) err = nvs_commit(nvs);
    nvs_close(nvs);
    return err;
}

esp_err_t bridge_config_clear_wifi(void) {
    nvs_handle_t nvs;
    esp_err_t err = nvs_open(NAMESPACE, NVS_READWRITE, &nvs);
    if (err != ESP_OK) return err;

    current.wifi_ssid[0] = '\0';
    current.wifi_password[0] = '\0';
    current.wifi_configured = false;

    err = nvs_erase_key(nvs, "wifi_ssid");
    if (err == ESP_ERR_NVS_NOT_FOUND) err = ESP_OK;
    esp_err_t password_err = nvs_erase_key(nvs, "wifi_pass");
    if (password_err == ESP_ERR_NVS_NOT_FOUND) password_err = ESP_OK;
    if (err == ESP_OK) err = password_err;
    if (err == ESP_OK) err = nvs_commit(nvs);
    nvs_close(nvs);
    return err;
}

esp_err_t bridge_config_bind_device(const char *serial) {
    if (!bridge_config_valid_serial(serial)) return ESP_ERR_INVALID_ARG;
    nvs_handle_t nvs;
    esp_err_t err = nvs_open(NAMESPACE, NVS_READWRITE, &nvs);
    if (err != ESP_OK) return err;
    strlcpy(current.bound_sn, serial, sizeof(current.bound_sn));
    current.device_bound = true;
    err = nvs_set_str(nvs, "bound_sn", current.bound_sn);
    if (err == ESP_OK) err = nvs_commit(nvs);
    nvs_close(nvs);
    bridge_state_set_device_bound(true);
    return err;
}

esp_err_t bridge_config_clear_device_binding(void) {
    nvs_handle_t nvs;
    esp_err_t err = nvs_open(NAMESPACE, NVS_READWRITE, &nvs);
    if (err != ESP_OK) return err;
    current.bound_sn[0] = '\0';
    current.device_bound = false;
    err = nvs_erase_key(nvs, "bound_sn");
    if (err == ESP_ERR_NVS_NOT_FOUND) err = ESP_OK;
    if (err == ESP_OK) err = nvs_commit(nvs);
    nvs_close(nvs);
    bridge_state_set_device_bound(false);
    return err;
}

esp_err_t bridge_config_set_weight(float kilograms) {
    if (kilograms < 25.0f || kilograms > 250.0f) return ESP_ERR_INVALID_ARG;
    nvs_handle_t nvs;
    esp_err_t err = nvs_open(NAMESPACE, NVS_READWRITE, &nvs);
    if (err != ESP_OK) return err;
    current.user_weight_kg = kilograms;
    err = nvs_set_blob(nvs, "weight", &kilograms, sizeof(kilograms));
    if (err == ESP_OK) err = nvs_commit(nvs);
    nvs_close(nvs);
    return err;
}

esp_err_t bridge_config_set_api_auth(bool enabled, const char *api_key) {
    if (enabled && (!api_key || strlen(api_key) < 8 ||
                    strlen(api_key) > BRIDGE_API_KEY_LENGTH)) {
        return ESP_ERR_INVALID_ARG;
    }
    nvs_handle_t nvs;
    esp_err_t err = nvs_open(NAMESPACE, NVS_READWRITE, &nvs);
    if (err != ESP_OK) return err;
    current.api_auth_enabled = enabled;
    if (enabled) strlcpy(current.api_key, api_key, sizeof(current.api_key));
    else current.api_key[0] = '\0';
    err = nvs_set_u8(nvs, "api_auth", enabled ? 1 : 0);
    if (err == ESP_OK) err = nvs_set_str(nvs, "api_key", current.api_key);
    if (err == ESP_OK) err = nvs_commit(nvs);
    nvs_close(nvs);
    return err;
}

bool bridge_config_api_key_matches(const char *api_key) {
    if (!current.api_auth_enabled) return true;
    if (!api_key) return false;
    size_t left_length = strlen(current.api_key);
    size_t right_length = strlen(api_key);
    if (left_length != right_length) return false;
    unsigned difference = 0;
    for (size_t index = 0; index < left_length; index++) {
        difference |= (unsigned)(current.api_key[index] ^ api_key[index]);
    }
    return difference == 0;
}

const char *bridge_config_suggested_serial(void) {
    return current.device_bound ? current.bound_sn : C1_DEFAULT_SN;
}
