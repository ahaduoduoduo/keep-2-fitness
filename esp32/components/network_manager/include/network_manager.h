#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "esp_err.h"

#define NETWORK_MANAGER_WIFI_SSID_LENGTH 32
#define NETWORK_MANAGER_WIFI_SCAN_CAPACITY 16

typedef struct {
    char ssid[NETWORK_MANAGER_WIFI_SSID_LENGTH + 1];
    int8_t rssi;
    uint8_t channel;
    bool secure;
} network_wifi_network_t;

typedef struct {
    bool scanning;
    bool ready;
    esp_err_t error;
    size_t count;
    network_wifi_network_t networks[NETWORK_MANAGER_WIFI_SCAN_CAPACITY];
} network_wifi_scan_result_t;

typedef enum {
    NETWORK_WIFI_IDLE = 0,
    NETWORK_WIFI_CONNECTING,
    NETWORK_WIFI_CONNECTED,
    NETWORK_WIFI_PASSWORD_ERROR,
    NETWORK_WIFI_NOT_FOUND,
    NETWORK_WIFI_FAILED,
} network_wifi_connection_state_t;

typedef struct {
    network_wifi_connection_state_t state;
    uint16_t disconnect_reason;
    char ipv4[16];
} network_wifi_connection_t;

esp_err_t network_manager_init(void);
esp_err_t network_manager_apply_wifi(const char *ssid, const char *password);
esp_err_t network_manager_forget_wifi(void);
esp_err_t network_manager_start_wifi_scan(void);
void network_manager_get_wifi_scan(network_wifi_scan_result_t *result);
void network_manager_get_wifi_connection(network_wifi_connection_t *connection);
const char *network_manager_wifi_state_name(network_wifi_connection_state_t state);
const char *network_manager_access_point_name(void);
