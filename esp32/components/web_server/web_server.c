#include "web_server.h"

#include <ctype.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

#include "bike_client.h"
#include "bridge_config.h"
#include "bridge_state.h"
#include "cJSON.h"
#include "esp_check.h"
#include "esp_http_server.h"
#include "esp_log.h"
#include "network_manager.h"

static const char *TAG = "web_server";
static httpd_handle_t server;

extern const uint8_t index_html_start[] asm("_binary_index_html_start");
extern const uint8_t index_html_end[] asm("_binary_index_html_end");
extern const uint8_t app_css_start[] asm("_binary_app_css_start");
extern const uint8_t app_css_end[] asm("_binary_app_css_end");
extern const uint8_t app_js_start[] asm("_binary_app_js_start");
extern const uint8_t app_js_end[] asm("_binary_app_js_end");
extern const uint8_t qr_js_start[] asm("_binary_qr_js_start");
extern const uint8_t qr_js_end[] asm("_binary_qr_js_end");
extern const uint8_t jsqr_min_js_start[] asm("_binary_jsqr_min_js_start");
extern const uint8_t jsqr_min_js_end[] asm("_binary_jsqr_min_js_end");
extern const uint8_t bebas_start[] asm("_binary_bebas_neue_woff2_start");
extern const uint8_t bebas_end[] asm("_binary_bebas_neue_woff2_end");
extern const uint8_t barlow_start[] asm("_binary_barlow_condensed_woff2_start");
extern const uint8_t barlow_end[] asm("_binary_barlow_condensed_woff2_end");

typedef struct {
    const uint8_t *start;
    const uint8_t *end;
    const char *content_type;
    const char *cache_control;
} static_asset_t;

typedef struct {
    char *json;
} publish_work_t;

static void set_common_headers(httpd_req_t *request) {
    httpd_resp_set_hdr(request, "Access-Control-Allow-Origin", "*");
    httpd_resp_set_hdr(request, "Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    httpd_resp_set_hdr(request, "Access-Control-Allow-Headers",
                       "Authorization, X-C1-Bridge-Key, Content-Type");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
}

static esp_err_t send_text(httpd_req_t *request, const char *status,
                           const char *content_type, const char *text) {
    set_common_headers(request);
    httpd_resp_set_status(request, status);
    httpd_resp_set_type(request, content_type);
    return httpd_resp_sendstr(request, text);
}

static esp_err_t send_json(httpd_req_t *request, cJSON *json) {
    char *encoded = cJSON_PrintUnformatted(json);
    cJSON_Delete(json);
    if (!encoded) return send_text(request, "500 Internal Server Error", "text/plain", "JSON encode failed");
    set_common_headers(request);
    httpd_resp_set_type(request, "application/json");
    esp_err_t result = httpd_resp_sendstr(request, encoded);
    free(encoded);
    return result;
}

static int hex_value(char value) {
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    if (value >= 'A' && value <= 'F') return value - 'A' + 10;
    return -1;
}

static bool decode_query_value(const char *source, char *destination, size_t capacity) {
    size_t output = 0;
    while (*source && output + 1 < capacity) {
        if (*source == '%' && source[1] && source[2]) {
            int high = hex_value(source[1]);
            int low = hex_value(source[2]);
            if (high < 0 || low < 0) return false;
            destination[output++] = (char)((high << 4) | low);
            source += 3;
        } else {
            destination[output++] = *source == '+' ? ' ' : *source;
            source++;
        }
    }
    destination[output] = '\0';
    return *source == '\0';
}

static bool request_api_key(httpd_req_t *request, char *key, size_t capacity) {
    char header[BRIDGE_API_KEY_LENGTH + 8] = {0};
    if (httpd_req_get_hdr_value_str(request, "X-C1-Bridge-Key", header,
                                    sizeof(header)) == ESP_OK) {
        strlcpy(key, header, capacity);
        return true;
    }
    if (httpd_req_get_hdr_value_str(request, "Authorization", header,
                                    sizeof(header)) == ESP_OK &&
        strncasecmp(header, "Bearer ", 7) == 0) {
        strlcpy(key, header + 7, capacity);
        return true;
    }
    return false;
}

static bool request_authorized(httpd_req_t *request) {
    bridge_config_t config;
    bridge_config_get(&config);
    if (!config.api_auth_enabled) return true;
    char key[BRIDGE_API_KEY_LENGTH + 1] = {0};
    return request_api_key(request, key, sizeof(key)) &&
           bridge_config_api_key_matches(key);
}

static esp_err_t require_authorized(httpd_req_t *request) {
    if (request_authorized(request)) return ESP_OK;
    httpd_resp_set_hdr(request, "WWW-Authenticate", "Bearer realm=\"C1 Bridge API\"");
    send_text(request, "401 Unauthorized", "text/plain", "API key required");
    return ESP_ERR_INVALID_STATE;
}

static cJSON *state_json(void) {
    bridge_snapshot_t snapshot;
    bridge_state_get(&snapshot);
    cJSON *root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "apiVersion", "v1");
    cJSON_AddBoolToObject(root, "bikeConnected",
        snapshot.bike_status == BRIDGE_BIKE_READABLE ||
        snapshot.bike_status == BRIDGE_BIKE_AUTHORIZED);
    cJSON_AddBoolToObject(root, "authorized",
                         snapshot.bike_status == BRIDGE_BIKE_AUTHORIZED);
    cJSON_AddBoolToObject(root, "watchConnected", snapshot.watch_connected);
    cJSON_AddBoolToObject(root, "wifiConnected", snapshot.wifi_connected);
    network_wifi_connection_t wifi = {0};
    network_manager_get_wifi_connection(&wifi);
    cJSON_AddStringToObject(root, "wifiState",
                           network_manager_wifi_state_name(wifi.state));
    cJSON_AddStringToObject(root, "wifiIp", wifi.ipv4);
    cJSON_AddNumberToObject(root, "wifiDisconnectReason",
                            wifi.disconnect_reason);
    cJSON_AddBoolToObject(root, "accessPointActive", snapshot.access_point_active);
    cJSON_AddBoolToObject(root, "deviceBound", snapshot.device_bound);
    cJSON_AddBoolToObject(root, "controlPending", snapshot.control_pending);
    cJSON_AddNumberToObject(root, "maxResistance", snapshot.max_resistance);
    cJSON_AddStringToObject(root, "message", snapshot.status_message);
    cJSON *metrics = cJSON_AddObjectToObject(root, "metrics");
    cJSON_AddNumberToObject(metrics, "startTime", snapshot.metrics.start_time_seconds);
    cJSON_AddNumberToObject(metrics, "distance", snapshot.metrics.distance_meters);
    cJSON_AddNumberToObject(metrics, "duration", snapshot.metrics.duration_seconds);
    cJSON_AddNumberToObject(metrics, "calories", snapshot.metrics.calories);
    cJSON_AddNumberToObject(metrics, "resistance", snapshot.metrics.resistance);
    cJSON_AddNumberToObject(metrics, "cadence", snapshot.metrics.cadence_rpm);
    cJSON_AddNumberToObject(metrics, "power", snapshot.metrics.power_watts);
    cJSON_AddNumberToObject(metrics, "trainingStatus", snapshot.metrics.training_status);
    cJSON_AddNumberToObject(metrics, "updatedAt", (double)snapshot.metrics.updated_at_ms);
    return root;
}

static bool receive_json(httpd_req_t *request, cJSON **result) {
    if (request->content_len <= 0 || request->content_len > 512) {
        send_text(request, "400 Bad Request", "text/plain", "Invalid JSON body length");
        return false;
    }
    char body[513];
    size_t received = 0;
    while (received < request->content_len) {
        int count = httpd_req_recv(request, body + received,
                                   request->content_len - received);
        if (count == HTTPD_SOCK_ERR_TIMEOUT) continue;
        if (count <= 0) {
            send_text(request, "400 Bad Request", "text/plain", "JSON body receive failed");
            return false;
        }
        received += (size_t)count;
    }
    body[received] = '\0';
    *result = cJSON_ParseWithLength(body, received);
    if (!*result) {
        send_text(request, "400 Bad Request", "text/plain", "Invalid JSON");
        return false;
    }
    return true;
}

static esp_err_t static_handler(httpd_req_t *request) {
    const static_asset_t *asset = request->user_ctx;
    httpd_resp_set_type(request, asset->content_type);
    httpd_resp_set_hdr(request, "Cache-Control", asset->cache_control);
    return httpd_resp_send(request, (const char *)asset->start,
                           asset->end - asset->start);
}

static esp_err_t root_handler(httpd_req_t *request) {
    httpd_resp_set_type(request, "text/html; charset=utf-8");
    httpd_resp_set_hdr(request, "Cache-Control", "no-cache");
    return httpd_resp_send(request, (const char *)index_html_start,
                           index_html_end - index_html_start);
}

static esp_err_t options_handler(httpd_req_t *request) {
    set_common_headers(request);
    return httpd_resp_send(request, NULL, 0);
}

static esp_err_t api_root_handler(httpd_req_t *request) {
    if (require_authorized(request) != ESP_OK) return ESP_OK;
    cJSON *root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "name", "C1 Bridge LAN API");
    cJSON_AddStringToObject(root, "version", "v1");
    cJSON *authentication = cJSON_AddObjectToObject(root, "authentication");
    bridge_config_t config;
    bridge_config_get(&config);
    cJSON_AddBoolToObject(authentication, "enabled", config.api_auth_enabled);
    cJSON_AddStringToObject(authentication, "headers",
                           "Authorization: Bearer <key> or X-C1-Bridge-Key: <key>");
    return send_json(request, root);
}

static esp_err_t state_handler(httpd_req_t *request) {
    if (require_authorized(request) != ESP_OK) return ESP_OK;
    return send_json(request, state_json());
}

static esp_err_t config_handler(httpd_req_t *request) {
    if (require_authorized(request) != ESP_OK) return ESP_OK;
    bridge_config_t config;
    bridge_config_get(&config);
    cJSON *root = cJSON_CreateObject();
    cJSON_AddBoolToObject(root, "deviceBound", config.device_bound);
    cJSON_AddStringToObject(root, "boundSn", config.device_bound ? config.bound_sn : "");
    cJSON_AddStringToObject(root, "suggestedSn", bridge_config_suggested_serial());
    cJSON_AddBoolToObject(root, "wifiConfigured", config.wifi_configured);
    cJSON_AddStringToObject(root, "wifiSsid", config.wifi_configured ? config.wifi_ssid : "");
    cJSON_AddStringToObject(root, "accessPoint", network_manager_access_point_name());
    cJSON_AddBoolToObject(root, "apiAuthEnabled", config.api_auth_enabled);
    return send_json(request, root);
}

static esp_err_t wifi_handler(httpd_req_t *request) {
    if (require_authorized(request) != ESP_OK) return ESP_OK;
    cJSON *body = NULL;
    if (!receive_json(request, &body)) return ESP_OK;
    esp_err_t result;
    cJSON *ssid = cJSON_GetObjectItemCaseSensitive(body, "ssid");
    cJSON *password = cJSON_GetObjectItemCaseSensitive(body, "password");
    if (!cJSON_IsString(ssid) || !cJSON_IsString(password)) result = ESP_ERR_INVALID_ARG;
    else result = network_manager_apply_wifi(ssid->valuestring, password->valuestring);
    cJSON_Delete(body);
    if (result == ESP_ERR_INVALID_ARG) {
        return send_text(request, "400 Bad Request", "text/plain",
                         "网络名称不能为空；加密网络密码需要 8–63 个字符");
    }
    if (result != ESP_OK) {
        ESP_LOGE(TAG, "Apply Wi-Fi failed: %s", esp_err_to_name(result));
        return send_text(request, "503 Service Unavailable", "text/plain",
                         "Wi-Fi 配置暂时无法应用，请稍后重试");
    }
    return send_text(request, "200 OK", "application/json", "{\"saved\":true}");
}

static esp_err_t wifi_forget_handler(httpd_req_t *request) {
    if (require_authorized(request) != ESP_OK) return ESP_OK;
    esp_err_t result = network_manager_forget_wifi();
    if (result != ESP_OK) {
        ESP_LOGE(TAG, "Forget Wi-Fi failed: %s", esp_err_to_name(result));
        return send_text(request, "503 Service Unavailable", "text/plain",
                         "暂时无法忘记 Wi-Fi，请稍后重试");
    }
    return send_text(request, "200 OK", "application/json",
                     "{\"forgotten\":true}");
}

static esp_err_t wifi_scan_start_handler(httpd_req_t *request) {
    if (require_authorized(request) != ESP_OK) return ESP_OK;
    esp_err_t result = network_manager_start_wifi_scan();
    if (result == ESP_ERR_INVALID_STATE) {
        return send_text(request, "202 Accepted", "application/json",
                         "{\"scanning\":true}");
    }
    if (result != ESP_OK) {
        return send_text(request, "503 Service Unavailable", "text/plain",
                         "Wi-Fi scan could not start");
    }
    return send_text(request, "202 Accepted", "application/json",
                     "{\"scanning\":true}");
}

static esp_err_t wifi_scan_result_handler(httpd_req_t *request) {
    if (require_authorized(request) != ESP_OK) return ESP_OK;

    network_wifi_scan_result_t result = {0};
    network_manager_get_wifi_scan(&result);

    cJSON *root = cJSON_CreateObject();
    cJSON_AddBoolToObject(root, "scanning", result.scanning);
    cJSON_AddBoolToObject(root, "ready", result.ready);
    cJSON_AddStringToObject(root, "error",
                           result.error == ESP_OK ? "" : esp_err_to_name(result.error));
    cJSON *items = cJSON_AddArrayToObject(root, "networks");
    for (size_t index = 0; index < result.count; index++) {
        cJSON *item = cJSON_CreateObject();
        cJSON_AddStringToObject(item, "ssid", result.networks[index].ssid);
        cJSON_AddNumberToObject(item, "rssi", result.networks[index].rssi);
        cJSON_AddNumberToObject(item, "channel", result.networks[index].channel);
        cJSON_AddBoolToObject(item, "secure", result.networks[index].secure);
        cJSON_AddItemToArray(items, item);
    }
    return send_json(request, root);
}

static esp_err_t device_handler(httpd_req_t *request) {
    if (require_authorized(request) != ESP_OK) return ESP_OK;
    cJSON *body = NULL;
    if (!receive_json(request, &body)) return ESP_OK;
    esp_err_t result;
    cJSON *serial = cJSON_GetObjectItemCaseSensitive(body, "sn");
    if (cJSON_IsNull(serial)) result = bridge_config_clear_device_binding();
    else if (cJSON_IsString(serial)) result = bridge_config_bind_device(serial->valuestring);
    else result = ESP_ERR_INVALID_ARG;
    cJSON_Delete(body);
    if (result != ESP_OK) return send_text(request, "400 Bad Request", "text/plain", "SN must be 16 characters and start with CC");
    bike_client_restart();
    return send_text(request, "200 OK", "application/json", "{\"saved\":true}");
}

static esp_err_t resistance_handler(httpd_req_t *request) {
    if (require_authorized(request) != ESP_OK) return ESP_OK;
    cJSON *body = NULL;
    if (!receive_json(request, &body)) return ESP_OK;
    esp_err_t result;
    cJSON *value = cJSON_GetObjectItemCaseSensitive(body, "value");
    bridge_snapshot_t snapshot;
    bridge_state_get(&snapshot);
    if (!cJSON_IsNumber(value) || value->valuedouble != value->valueint ||
        value->valueint < 1 || value->valueint > snapshot.max_resistance) {
        result = ESP_ERR_INVALID_ARG;
    } else {
        result = bike_client_set_resistance((uint8_t)value->valueint);
    }
    cJSON_Delete(body);
    if (result == ESP_ERR_INVALID_STATE) return send_text(request, "409 Conflict", "text/plain", "Bike control is not authorized");
    if (result != ESP_OK) return send_text(request, "400 Bad Request", "text/plain", "Invalid resistance");
    return send_text(request, "202 Accepted", "application/json", "{\"accepted\":true}");
}

static esp_err_t training_handler(httpd_req_t *request) {
    if (require_authorized(request) != ESP_OK) return ESP_OK;
    cJSON *body = NULL;
    if (!receive_json(request, &body)) return ESP_OK;
    esp_err_t result;
    cJSON *status = cJSON_GetObjectItemCaseSensitive(body, "status");
    if (!cJSON_IsNumber(status) ||
        (status->valueint != 1 && status->valueint != 3 && status->valueint != 4)) {
        result = ESP_ERR_INVALID_ARG;
    } else {
        result = bike_client_set_training_status((uint8_t)status->valueint);
    }
    cJSON_Delete(body);
    if (result == ESP_ERR_INVALID_STATE) return send_text(request, "409 Conflict", "text/plain", "Bike control is not authorized");
    if (result != ESP_OK) return send_text(request, "400 Bad Request", "text/plain", "Training status must be 1, 3, or 4");
    return send_text(request, "202 Accepted", "application/json", "{\"accepted\":true}");
}

static void close_websockets(void) {
    if (!server) return;
    int clients[CONFIG_LWIP_MAX_SOCKETS];
    size_t count = CONFIG_LWIP_MAX_SOCKETS;
    if (httpd_get_client_list(server, &count, clients) != ESP_OK) return;
    for (size_t index = 0; index < count; index++) {
        if (httpd_ws_get_fd_info(server, clients[index]) == HTTPD_WS_CLIENT_WEBSOCKET) {
            httpd_sess_trigger_close(server, clients[index]);
        }
    }
}

static esp_err_t auth_handler(httpd_req_t *request) {
    if (require_authorized(request) != ESP_OK) return ESP_OK;
    cJSON *body = NULL;
    if (!receive_json(request, &body)) return ESP_OK;
    esp_err_t result;
    cJSON *enabled = cJSON_GetObjectItemCaseSensitive(body, "enabled");
    cJSON *key = cJSON_GetObjectItemCaseSensitive(body, "key");
    if (!cJSON_IsBool(enabled) || !cJSON_IsString(key)) result = ESP_ERR_INVALID_ARG;
    else result = bridge_config_set_api_auth(cJSON_IsTrue(enabled), key->valuestring);
    cJSON_Delete(body);
    if (result != ESP_OK) return send_text(request, "400 Bad Request", "text/plain", "Enabled API keys must contain 8 to 48 characters");
    result = send_text(request, "200 OK", "application/json", "{\"saved\":true}");
    close_websockets();
    return result;
}

static esp_err_t websocket_auth(httpd_req_t *request) {
    bridge_config_t config;
    bridge_config_get(&config);
    if (!config.api_auth_enabled) return ESP_OK;
    char query[192] = {0};
    char encoded[BRIDGE_API_KEY_LENGTH * 3 + 1] = {0};
    char decoded[BRIDGE_API_KEY_LENGTH + 1] = {0};
    if (httpd_req_get_url_query_str(request, query, sizeof(query)) != ESP_OK ||
        httpd_query_key_value(query, "key", encoded, sizeof(encoded)) != ESP_OK ||
        !decode_query_value(encoded, decoded, sizeof(decoded)) ||
        !bridge_config_api_key_matches(decoded)) {
        httpd_resp_set_status(request, "401 Unauthorized");
        return ESP_FAIL;
    }
    return ESP_OK;
}

static esp_err_t websocket_handler(httpd_req_t *request) {
    if (request->method == HTTP_GET) {
        cJSON *json = state_json();
        char *encoded = cJSON_PrintUnformatted(json);
        cJSON_Delete(json);
        if (!encoded) return ESP_ERR_NO_MEM;
        httpd_ws_frame_t frame = {
            .final = true,
            .type = HTTPD_WS_TYPE_TEXT,
            .payload = (uint8_t *)encoded,
            .len = strlen(encoded),
        };
        esp_err_t result = httpd_ws_send_frame(request, &frame);
        free(encoded);
        return result;
    }
    httpd_ws_frame_t frame = {0};
    if (httpd_ws_recv_frame(request, &frame, 0) != ESP_OK) return ESP_FAIL;
    if (frame.len > 0) {
        uint8_t *discard = malloc(frame.len);
        if (!discard) return ESP_ERR_NO_MEM;
        frame.payload = discard;
        esp_err_t result = httpd_ws_recv_frame(request, &frame, frame.len);
        free(discard);
        return result;
    }
    return ESP_OK;
}

static void publish_work(void *argument) {
    publish_work_t *work = argument;
    int clients[CONFIG_LWIP_MAX_SOCKETS];
    size_t count = CONFIG_LWIP_MAX_SOCKETS;
    if (httpd_get_client_list(server, &count, clients) == ESP_OK) {
        httpd_ws_frame_t frame = {
            .final = true,
            .type = HTTPD_WS_TYPE_TEXT,
            .payload = (uint8_t *)work->json,
            .len = strlen(work->json),
        };
        for (size_t index = 0; index < count; index++) {
            if (httpd_ws_get_fd_info(server, clients[index]) == HTTPD_WS_CLIENT_WEBSOCKET) {
                httpd_ws_send_frame_async(server, clients[index], &frame);
            }
        }
    }
    free(work->json);
    free(work);
}

void web_server_publish_state(void) {
    if (!server) return;
    cJSON *json = state_json();
    char *encoded = cJSON_PrintUnformatted(json);
    cJSON_Delete(json);
    if (!encoded) return;
    publish_work_t *work = calloc(1, sizeof(*work));
    if (!work) {
        free(encoded);
        return;
    }
    work->json = encoded;
    if (httpd_queue_work(server, publish_work, work) != ESP_OK) {
        free(encoded);
        free(work);
    }
}

static esp_err_t register_handler(const char *uri, httpd_method_t method,
                                  esp_err_t (*handler)(httpd_req_t *), void *context) {
    httpd_uri_t definition = {
        .uri = uri,
        .method = method,
        .handler = handler,
        .user_ctx = context,
    };
    return httpd_register_uri_handler(server, &definition);
}

esp_err_t web_server_start(void) {
    if (server) return ESP_OK;
    httpd_config_t config = HTTPD_DEFAULT_CONFIG();
    config.max_uri_handlers = 20;
    config.max_open_sockets = 7;
    config.uri_match_fn = httpd_uri_match_wildcard;
    ESP_RETURN_ON_ERROR(httpd_start(&server, &config), TAG, "start");

    static const static_asset_t css = {app_css_start, app_css_end, "text/css; charset=utf-8", "no-cache"};
    static const static_asset_t js = {app_js_start, app_js_end, "text/javascript; charset=utf-8", "no-cache"};
    static const static_asset_t qr = {qr_js_start, qr_js_end, "text/javascript; charset=utf-8", "no-cache"};
    static const static_asset_t jsqr = {jsqr_min_js_start, jsqr_min_js_end, "text/javascript; charset=utf-8", "public, max-age=604800, immutable"};
    static const static_asset_t bebas = {bebas_start, bebas_end, "font/woff2", "public, max-age=604800, immutable"};
    static const static_asset_t barlow = {barlow_start, barlow_end, "font/woff2", "public, max-age=604800, immutable"};

    ESP_RETURN_ON_ERROR(register_handler("/app-v6.css", HTTP_GET, static_handler, (void *)&css), TAG, "css");
    ESP_RETURN_ON_ERROR(register_handler("/app-v6.js", HTTP_GET, static_handler, (void *)&js), TAG, "js");
    ESP_RETURN_ON_ERROR(register_handler("/qr-v2.js", HTTP_GET, static_handler, (void *)&qr), TAG, "qr");
    ESP_RETURN_ON_ERROR(register_handler("/jsqr-1.4.0.min.js", HTTP_GET, static_handler, (void *)&jsqr), TAG, "jsqr");
    ESP_RETURN_ON_ERROR(register_handler("/bebas-neue.woff2", HTTP_GET, static_handler, (void *)&bebas), TAG, "bebas");
    ESP_RETURN_ON_ERROR(register_handler("/barlow-condensed.woff2", HTTP_GET, static_handler, (void *)&barlow), TAG, "barlow");
    ESP_RETURN_ON_ERROR(register_handler("/api/v1", HTTP_GET, api_root_handler, NULL), TAG, "api root");
    ESP_RETURN_ON_ERROR(register_handler("/api/v1/state", HTTP_GET, state_handler, NULL), TAG, "state");
    ESP_RETURN_ON_ERROR(register_handler("/api/v1/config", HTTP_GET, config_handler, NULL), TAG, "config");
    ESP_RETURN_ON_ERROR(register_handler("/api/v1/wifi/scan", HTTP_POST, wifi_scan_start_handler, NULL), TAG, "wifi scan start");
    ESP_RETURN_ON_ERROR(register_handler("/api/v1/wifi/scan", HTTP_GET, wifi_scan_result_handler, NULL), TAG, "wifi scan result");
    ESP_RETURN_ON_ERROR(register_handler("/api/v1/wifi", HTTP_POST, wifi_handler, NULL), TAG, "wifi");
    ESP_RETURN_ON_ERROR(register_handler("/api/v1/wifi/forget", HTTP_POST, wifi_forget_handler, NULL), TAG, "wifi forget");
    ESP_RETURN_ON_ERROR(register_handler("/api/v1/device", HTTP_POST, device_handler, NULL), TAG, "device");
    ESP_RETURN_ON_ERROR(register_handler("/api/v1/resistance", HTTP_POST, resistance_handler, NULL), TAG, "resistance");
    ESP_RETURN_ON_ERROR(register_handler("/api/v1/training", HTTP_POST, training_handler, NULL), TAG, "training");
    ESP_RETURN_ON_ERROR(register_handler("/api/v1/auth", HTTP_POST, auth_handler, NULL), TAG, "auth");
    ESP_RETURN_ON_ERROR(register_handler("/api/v1/*", HTTP_OPTIONS, options_handler, NULL), TAG, "options");

    httpd_uri_t websocket = {
        .uri = "/api/v1/events",
        .method = HTTP_GET,
        .handler = websocket_handler,
        .is_websocket = true,
        .ws_pre_handshake_cb = websocket_auth,
    };
    ESP_RETURN_ON_ERROR(httpd_register_uri_handler(server, &websocket), TAG, "websocket");
    ESP_RETURN_ON_ERROR(register_handler("/*", HTTP_GET, root_handler, NULL), TAG, "root");
    ESP_LOGI(TAG, "Dashboard and LAN API started on port 80");
    return ESP_OK;
}
