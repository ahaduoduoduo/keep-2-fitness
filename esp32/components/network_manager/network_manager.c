#include "network_manager.h"

#include <stdlib.h>
#include <string.h>

#include "bridge_config.h"
#include "bridge_state.h"
#include "esp_check.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_mac.h"
#include "esp_netif.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "mdns.h"

static const char *TAG = "network";
static char access_point_name[24];
static bool mdns_started;
static bool station_connected;
static bool station_reconfiguring;
static bool station_forgetting;
static SemaphoreHandle_t wifi_scan_mutex;
static network_wifi_scan_result_t wifi_scan_result;
static SemaphoreHandle_t wifi_connection_mutex;
static network_wifi_connection_t wifi_connection;

static void update_state(void) {
    bridge_state_set_network(station_connected, true);
}

static void start_mdns(void) {
    if (mdns_started) return;
    if (mdns_init() != ESP_OK) return;
    mdns_hostname_set("c1bridge");
    mdns_instance_name_set("C1 Bridge");
    mdns_service_add("C1 Bridge", "_http", "_tcp", 80, NULL, 0);
    mdns_started = true;
}

static void set_wifi_connection(network_wifi_connection_state_t state,
                                uint16_t reason, const char *ipv4) {
    if (!wifi_connection_mutex) return;
    xSemaphoreTake(wifi_connection_mutex, portMAX_DELAY);
    wifi_connection.state = state;
    wifi_connection.disconnect_reason = reason;
    if (ipv4) strlcpy(wifi_connection.ipv4, ipv4,
                      sizeof(wifi_connection.ipv4));
    else wifi_connection.ipv4[0] = '\0';
    xSemaphoreGive(wifi_connection_mutex);
}

static network_wifi_connection_state_t state_for_disconnect_reason(uint16_t reason) {
    switch (reason) {
        case WIFI_REASON_AUTH_EXPIRE:
        case WIFI_REASON_AUTH_FAIL:
        case WIFI_REASON_HANDSHAKE_TIMEOUT:
        case WIFI_REASON_4WAY_HANDSHAKE_TIMEOUT:
            return NETWORK_WIFI_PASSWORD_ERROR;
        case WIFI_REASON_NO_AP_FOUND:
        case WIFI_REASON_NO_AP_FOUND_IN_RSSI_THRESHOLD:
        case WIFI_REASON_NO_AP_FOUND_IN_AUTHMODE_THRESHOLD:
            return NETWORK_WIFI_NOT_FOUND;
        default:
            return NETWORK_WIFI_FAILED;
    }
}

static void finish_wifi_scan(esp_err_t error) {
    network_wifi_network_t networks[NETWORK_MANAGER_WIFI_SCAN_CAPACITY] = {0};
    size_t count = 0;

    if (error == ESP_OK) {
        uint16_t available = 0;
        error = esp_wifi_scan_get_ap_num(&available);
        if (error == ESP_OK && available > 0) {
            uint16_t requested = available > 32 ? 32 : available;
            wifi_ap_record_t *records = calloc(requested, sizeof(*records));
            if (!records) {
                esp_wifi_clear_ap_list();
                error = ESP_ERR_NO_MEM;
            } else {
                error = esp_wifi_scan_get_ap_records(&requested, records);
                if (error == ESP_OK) {
                    for (uint16_t index = 0;
                         index < requested && count < NETWORK_MANAGER_WIFI_SCAN_CAPACITY;
                         index++) {
                        const char *ssid = (const char *)records[index].ssid;
                        if (!ssid[0]) continue;

                        bool duplicate = false;
                        for (size_t existing = 0; existing < count; existing++) {
                            if (strcmp(networks[existing].ssid, ssid) == 0) {
                                duplicate = true;
                                break;
                            }
                        }
                        if (duplicate) continue;

                        network_wifi_network_t *network = &networks[count++];
                        strlcpy(network->ssid, ssid, sizeof(network->ssid));
                        network->rssi = records[index].rssi;
                        network->channel = records[index].primary;
                        network->secure = records[index].authmode != WIFI_AUTH_OPEN;
                    }
                }
                free(records);
            }
        }
    }

    xSemaphoreTake(wifi_scan_mutex, portMAX_DELAY);
    wifi_scan_result.scanning = false;
    wifi_scan_result.ready = true;
    wifi_scan_result.error = error;
    wifi_scan_result.count = error == ESP_OK ? count : 0;
    if (error == ESP_OK) {
        memcpy(wifi_scan_result.networks, networks,
               count * sizeof(network_wifi_network_t));
    }
    xSemaphoreGive(wifi_scan_mutex);
    ESP_LOGI(TAG, "Wi-Fi scan completed; count=%u error=%s",
             (unsigned)count, esp_err_to_name(error));
}

static void wifi_scan_task(void *argument) {
    (void)argument;
    // Let the HTTP response leave the shared Wi-Fi radio before scanning.
    // A scan temporarily pauses both STA and SoftAP traffic on ESP32-S3.
    vTaskDelay(pdMS_TO_TICKS(1200));
    wifi_scan_config_t scan = {
        .show_hidden = false,
        .scan_type = WIFI_SCAN_TYPE_ACTIVE,
    };
    esp_err_t result = esp_wifi_scan_start(&scan, true);
    finish_wifi_scan(result);
    vTaskDelete(NULL);
}

static void event_handler(void *argument, esp_event_base_t base, int32_t id, void *data) {
    (void)argument;
    (void)data;
    if (base == WIFI_EVENT && id == WIFI_EVENT_STA_START) {
        bridge_config_t config;
        bridge_config_get(&config);
        if (config.wifi_configured) {
            set_wifi_connection(NETWORK_WIFI_CONNECTING, 0, NULL);
            esp_wifi_connect();
        }
    } else if (base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED) {
        wifi_event_sta_disconnected_t *disconnected = data;
        uint16_t reason = disconnected ? disconnected->reason : 0;
        station_connected = false;
        update_state();
        bridge_config_t config;
        bridge_config_get(&config);
        if (!config.wifi_configured || station_forgetting) {
            station_forgetting = false;
            station_reconfiguring = false;
            set_wifi_connection(NETWORK_WIFI_IDLE, 0, NULL);
        } else if (station_reconfiguring && reason == WIFI_REASON_ASSOC_LEAVE) {
            set_wifi_connection(NETWORK_WIFI_CONNECTING, 0, NULL);
        } else {
            station_reconfiguring = false;
            set_wifi_connection(state_for_disconnect_reason(reason), reason, NULL);
        }
        if (config.wifi_configured) esp_wifi_connect();
    } else if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        ip_event_got_ip_t *got_ip = data;
        char ipv4[16] = {0};
        if (got_ip) esp_ip4addr_ntoa(&got_ip->ip_info.ip, ipv4, sizeof(ipv4));
        station_connected = true;
        station_reconfiguring = false;
        update_state();
        set_wifi_connection(NETWORK_WIFI_CONNECTED, 0, ipv4);
        start_mdns();
        ESP_LOGI(TAG, "Wi-Fi connected; dashboard: http://c1bridge.local");
    }
}

static esp_err_t configure_station(const bridge_config_t *config) {
    wifi_config_t station = {0};
    strlcpy((char *)station.sta.ssid, config->wifi_ssid, sizeof(station.sta.ssid));
    strlcpy((char *)station.sta.password, config->wifi_password, sizeof(station.sta.password));
    station.sta.threshold.authmode = config->wifi_password[0]
        ? WIFI_AUTH_WPA2_PSK : WIFI_AUTH_OPEN;
    station.sta.pmf_cfg.capable = true;
    station.sta.pmf_cfg.required = false;
    return esp_wifi_set_config(WIFI_IF_STA, &station);
}

static void wifi_connect_task(void *argument) {
    (void)argument;
    // Keep the configuration response reachable before the station changes
    // channel or starts WPA authentication.
    vTaskDelay(pdMS_TO_TICKS(1200));
    for (int attempt = 0; attempt < 5; attempt++) {
        esp_err_t result = esp_wifi_connect();
        if (result == ESP_OK) {
            ESP_LOGI(TAG, "Wi-Fi connection started");
            break;
        }
        ESP_LOGW(TAG, "Wi-Fi connect attempt %d: %s",
                 attempt + 1, esp_err_to_name(result));
        vTaskDelay(pdMS_TO_TICKS(500));
    }
    vTaskDelete(NULL);
}

esp_err_t network_manager_init(void) {
    wifi_scan_mutex = xSemaphoreCreateMutex();
    wifi_connection_mutex = xSemaphoreCreateMutex();
    if (!wifi_scan_mutex || !wifi_connection_mutex) return ESP_ERR_NO_MEM;
    ESP_RETURN_ON_ERROR(esp_netif_init(), TAG, "netif");
    esp_err_t event_result = esp_event_loop_create_default();
    if (event_result != ESP_OK && event_result != ESP_ERR_INVALID_STATE) return event_result;
    esp_netif_create_default_wifi_ap();
    esp_netif_create_default_wifi_sta();
    wifi_init_config_t init = WIFI_INIT_CONFIG_DEFAULT();
    ESP_RETURN_ON_ERROR(esp_wifi_init(&init), TAG, "wifi init");
    ESP_RETURN_ON_ERROR(esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID,
                                                    event_handler, NULL), TAG, "wifi handler");
    ESP_RETURN_ON_ERROR(esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP,
                                                    event_handler, NULL), TAG, "ip handler");

    uint8_t mac[6];
    esp_read_mac(mac, ESP_MAC_WIFI_SOFTAP);
    snprintf(access_point_name, sizeof(access_point_name), "C1-Bridge-%02X%02X", mac[4], mac[5]);
    wifi_config_t access_point = {0};
    strlcpy((char *)access_point.ap.ssid, access_point_name, sizeof(access_point.ap.ssid));
    access_point.ap.ssid_len = strlen(access_point_name);
    access_point.ap.channel = 1;
    access_point.ap.max_connection = 4;
    access_point.ap.authmode = WIFI_AUTH_OPEN;
    access_point.ap.pmf_cfg.required = false;

    ESP_RETURN_ON_ERROR(esp_wifi_set_mode(WIFI_MODE_APSTA), TAG, "mode");
    ESP_RETURN_ON_ERROR(esp_wifi_set_config(WIFI_IF_AP, &access_point), TAG, "AP config");
    bridge_config_t config;
    bridge_config_get(&config);
    set_wifi_connection(config.wifi_configured
        ? NETWORK_WIFI_CONNECTING : NETWORK_WIFI_IDLE, 0, NULL);
    if (config.wifi_configured) ESP_RETURN_ON_ERROR(configure_station(&config), TAG, "STA config");
    ESP_RETURN_ON_ERROR(esp_wifi_start(), TAG, "start");
    update_state();
    ESP_LOGI(TAG, "Configuration AP: %s", access_point_name);
    return ESP_OK;
}

esp_err_t network_manager_apply_wifi(const char *ssid, const char *password) {
    if (!ssid || !ssid[0] || strlen(ssid) > BRIDGE_WIFI_SSID_LENGTH ||
        !password || strlen(password) > 63 ||
        (password[0] && strlen(password) < 8)) {
        return ESP_ERR_INVALID_ARG;
    }

    bridge_config_t candidate = {0};
    strlcpy(candidate.wifi_ssid, ssid, sizeof(candidate.wifi_ssid));
    strlcpy(candidate.wifi_password, password, sizeof(candidate.wifi_password));

    esp_wifi_scan_stop();
    station_reconfiguring = true;
    esp_wifi_disconnect();
    ESP_RETURN_ON_ERROR(configure_station(&candidate), TAG, "apply wifi");
    ESP_RETURN_ON_ERROR(bridge_config_set_wifi(ssid, password), TAG, "save wifi");
    set_wifi_connection(NETWORK_WIFI_CONNECTING, 0, NULL);

    if (xTaskCreate(wifi_connect_task, "wifi_connect", 3072,
                    NULL, 5, NULL) != pdPASS) {
        station_reconfiguring = false;
        return ESP_ERR_NO_MEM;
    }
    return ESP_OK;
}

esp_err_t network_manager_forget_wifi(void) {
    esp_wifi_scan_stop();
    station_forgetting = true;
    station_reconfiguring = false;
    ESP_RETURN_ON_ERROR(bridge_config_clear_wifi(), TAG, "clear wifi");

    esp_err_t disconnect_result = esp_wifi_disconnect();
    if (disconnect_result != ESP_OK &&
        disconnect_result != ESP_ERR_WIFI_NOT_CONNECT) {
        station_forgetting = false;
        return disconnect_result;
    }

    wifi_config_t empty = {0};
    ESP_RETURN_ON_ERROR(esp_wifi_set_config(WIFI_IF_STA, &empty), TAG,
                        "clear station config");
    station_connected = false;
    update_state();
    set_wifi_connection(NETWORK_WIFI_IDLE, 0, NULL);
    return ESP_OK;
}

esp_err_t network_manager_start_wifi_scan(void) {
    if (!wifi_scan_mutex) return ESP_ERR_INVALID_STATE;
    xSemaphoreTake(wifi_scan_mutex, portMAX_DELAY);
    if (wifi_scan_result.scanning) {
        xSemaphoreGive(wifi_scan_mutex);
        return ESP_ERR_INVALID_STATE;
    }
    memset(&wifi_scan_result, 0, sizeof(wifi_scan_result));
    wifi_scan_result.scanning = true;
    xSemaphoreGive(wifi_scan_mutex);

    BaseType_t created = xTaskCreate(wifi_scan_task, "wifi_scan", 3072,
                                     NULL, 5, NULL);
    if (created != pdPASS) {
        finish_wifi_scan(ESP_ERR_NO_MEM);
        return ESP_ERR_NO_MEM;
    }
    return ESP_OK;
}

void network_manager_get_wifi_scan(network_wifi_scan_result_t *result) {
    if (!result || !wifi_scan_mutex) return;
    xSemaphoreTake(wifi_scan_mutex, portMAX_DELAY);
    *result = wifi_scan_result;
    xSemaphoreGive(wifi_scan_mutex);
}

void network_manager_get_wifi_connection(network_wifi_connection_t *connection) {
    if (!connection || !wifi_connection_mutex) return;
    xSemaphoreTake(wifi_connection_mutex, portMAX_DELAY);
    *connection = wifi_connection;
    xSemaphoreGive(wifi_connection_mutex);
}

const char *network_manager_wifi_state_name(network_wifi_connection_state_t state) {
    switch (state) {
        case NETWORK_WIFI_CONNECTING: return "connecting";
        case NETWORK_WIFI_CONNECTED: return "connected";
        case NETWORK_WIFI_PASSWORD_ERROR: return "password_error";
        case NETWORK_WIFI_NOT_FOUND: return "not_found";
        case NETWORK_WIFI_FAILED: return "failed";
        default: return "idle";
    }
}

const char *network_manager_access_point_name(void) {
    return access_point_name;
}
