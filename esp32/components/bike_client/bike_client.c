#include "bike_client.h"

#include <ctype.h>
#include <string.h>
#include <time.h>

#include "bridge_config.h"
#include "c1_protocol.h"
#include "esp_check.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "host/ble_hs.h"
#include "host/ble_uuid.h"
#include "os/os_mbuf.h"

#define KIRIN_SERVICE_UUID 0x00ff
#define KIRIN_WRITE_UUID 0xff01
#define KIRIN_NOTIFY_UUID 0xff02
#define CCCD_UUID 0x2902

typedef enum {
    SESSION_IDLE = 0,
    SESSION_DISCOVERING,
    SESSION_SUBSCRIBING,
    SESSION_HANDSHAKE,
    SESSION_AUTHORIZING,
    SESSION_CONFIG,
    SESSION_READY,
} session_stage_t;

typedef enum {
    REQUEST_NONE = 0,
    REQUEST_CONFIG,
    REQUEST_POLL,
    REQUEST_RESISTANCE,
    REQUEST_TRAINING,
} request_kind_t;

static const char *TAG = "bike_client";
static uint8_t own_addr_type;
static uint16_t bike_connection = BLE_HS_CONN_HANDLE_NONE;
static uint16_t service_start;
static uint16_t service_end;
static uint16_t write_handle;
static uint16_t notify_handle;
static uint16_t cccd_handle;
static session_stage_t stage;
static request_kind_t active_request;
static int64_t request_started_us;
static int64_t reconnect_after_us;
static int64_t next_request_us;
static uint16_t bcp_sequence;
static uint16_t message_id;
static uint32_t request_id;
static uint8_t queued_resistance;
static uint8_t queued_training_status;
static uint8_t max_resistance = 18;
static c1_kirin_stream_t notification_stream;
static esp_timer_handle_t request_timer;
static bike_metrics_callback_t metrics_callback;
static void *metrics_context;
static bool ble_ready;
static bool control_authorized;

static int gap_event(struct ble_gap_event *event, void *argument);
static void start_scan(void);
static void dispatch_request(void);

static void clear_session(void) {
    bike_connection = BLE_HS_CONN_HANDLE_NONE;
    service_start = service_end = write_handle = notify_handle = cccd_handle = 0;
    stage = SESSION_IDLE;
    active_request = REQUEST_NONE;
    queued_resistance = queued_training_status = 0;
    control_authorized = false;
    bcp_sequence = 0;
    request_id = 0;
    memset(&notification_stream, 0, sizeof(notification_stream));
}

static bool advertised_name_matches(const struct ble_hs_adv_fields *fields,
                                    const bridge_config_t *config) {
    if (!fields->name || fields->name_len < 8) return false;
    char name[32] = {0};
    size_t length = fields->name_len < sizeof(name) - 1 ? fields->name_len : sizeof(name) - 1;
    memcpy(name, fields->name, length);
    if (strncmp(name, "Keep_CC_", 8) != 0) return false;
    if (!config->device_bound) return true;
    const char *suffix = name + 8;
    size_t suffix_length = strlen(suffix);
    return suffix_length <= BRIDGE_SN_LENGTH &&
           strcmp(config->bound_sn + BRIDGE_SN_LENGTH - suffix_length, suffix) == 0;
}

static bool manufacturer_matches(const struct ble_hs_adv_fields *fields,
                                 const bridge_config_t *config) {
    if (!fields->mfg_data || fields->mfg_data_len < 19) return false;
    for (size_t offset = 2; offset + 1 + BRIDGE_SN_LENGTH <= fields->mfg_data_len; offset++) {
        const uint8_t *candidate = fields->mfg_data + offset + 1;
        if (candidate[0] != 'C' || candidate[1] != 'C') continue;
        bool valid = true;
        for (size_t index = 0; index < BRIDGE_SN_LENGTH; index++) {
            if (!isalnum(candidate[index])) valid = false;
        }
        if (valid && (!config->device_bound ||
            memcmp(candidate, config->bound_sn, BRIDGE_SN_LENGTH) == 0)) return true;
    }
    return false;
}

static bool should_connect(const struct ble_gap_disc_desc *advertisement) {
    if (advertisement->event_type != BLE_HCI_ADV_RPT_EVTYPE_ADV_IND &&
        advertisement->event_type != BLE_HCI_ADV_RPT_EVTYPE_DIR_IND) return false;
    struct ble_hs_adv_fields fields;
    if (ble_hs_adv_parse_fields(&fields, advertisement->data, advertisement->length_data) != 0) {
        return false;
    }
    bridge_config_t config;
    bridge_config_get(&config);
    return manufacturer_matches(&fields, &config) || advertised_name_matches(&fields, &config);
}

static int on_subscribed(uint16_t connection, const struct ble_gatt_error *error,
                         struct ble_gatt_attr *attribute, void *argument);

static int on_descriptor_found(uint16_t connection, const struct ble_gatt_error *error,
                               uint16_t characteristic_handle,
                               const struct ble_gatt_dsc *descriptor, void *argument) {
    (void)characteristic_handle;
    (void)argument;
    if (connection != bike_connection) return 0;
    if (error->status == 0 && descriptor && ble_uuid_u16(&descriptor->uuid.u) == CCCD_UUID) {
        cccd_handle = descriptor->handle;
        return 0;
    }
    if (error->status == BLE_HS_EDONE && cccd_handle) {
        const uint8_t enable[] = {1, 0};
        stage = SESSION_SUBSCRIBING;
        int rc = ble_gattc_write_flat(connection, cccd_handle, enable, sizeof(enable),
                                      on_subscribed, NULL);
        if (rc != 0) ble_gap_terminate(connection, BLE_ERR_REM_USER_CONN_TERM);
    }
    return 0;
}

static int on_characteristic(uint16_t connection, const struct ble_gatt_error *error,
                             const struct ble_gatt_chr *characteristic, void *argument) {
    (void)argument;
    if (connection != bike_connection) return 0;
    if (error->status == 0 && characteristic) {
        uint16_t uuid = ble_uuid_u16(&characteristic->uuid.u);
        if (uuid == KIRIN_WRITE_UUID) write_handle = characteristic->val_handle;
        if (uuid == KIRIN_NOTIFY_UUID) notify_handle = characteristic->val_handle;
        return 0;
    }
    if (error->status == BLE_HS_EDONE && write_handle) {
        if (!notify_handle) notify_handle = write_handle;
        int rc = ble_gattc_disc_all_dscs(connection, notify_handle, service_end,
                                         on_descriptor_found, NULL);
        if (rc != 0) ble_gap_terminate(connection, BLE_ERR_REM_USER_CONN_TERM);
    }
    return 0;
}

static int on_service_found(uint16_t connection, const struct ble_gatt_error *error,
                            const struct ble_gatt_svc *service, void *argument) {
    (void)argument;
    if (connection != bike_connection) return 0;
    if (error->status == 0 && service) {
        service_start = service->start_handle;
        service_end = service->end_handle;
        return 0;
    }
    if (error->status == BLE_HS_EDONE && service_start) {
        int rc = ble_gattc_disc_all_chrs(connection, service_start, service_end,
                                         on_characteristic, NULL);
        if (rc != 0) ble_gap_terminate(connection, BLE_ERR_REM_USER_CONN_TERM);
    }
    return 0;
}

static bool send_frame(const uint8_t *frame, size_t length) {
    if (bike_connection == BLE_HS_CONN_HANDLE_NONE || !write_handle || !length) return false;
    int rc = ble_gattc_write_no_rsp_flat(bike_connection, write_handle, frame, length);
    return rc == 0;
}

static size_t handshake_payload(uint8_t *payload, size_t capacity) {
    bridge_config_t config;
    bridge_config_get(&config);
    size_t length = strlen(config.bridge_device_id);
    if (capacity < length + 1) return 0;
    memcpy(payload, config.bridge_device_id, length);
    payload[length] = 0;
    return length + 1;
}

static void send_handshake(void) {
    uint8_t payload[BRIDGE_DEVICE_ID_LENGTH + 1];
    uint8_t frame[96];
    size_t payload_length = handshake_payload(payload, sizeof(payload));
    size_t length = c1_kirin_build_get_payload(frame, sizeof(frame), bcp_sequence++, message_id++,
                                               request_id++, "1/1", payload, payload_length);
    stage = SESSION_HANDSHAKE;
    request_started_us = esp_timer_get_time();
    if (!send_frame(frame, length)) ble_gap_terminate(bike_connection, BLE_ERR_REM_USER_CONN_TERM);
}

static void send_authorization(void) {
    bridge_config_t config;
    bridge_config_get(&config);
    uint8_t payload[96];
    uint8_t frame[160];
    size_t payload_length = c1_payload_user_info(
        payload, sizeof(payload), config.bridge_user_id, config.bridge_device_id,
        config.user_weight_kg, time(NULL));
    size_t length = c1_kirin_build_put(frame, sizeof(frame), bcp_sequence++, message_id++,
                                       request_id++, "106/3", payload, payload_length);
    stage = SESSION_AUTHORIZING;
    request_started_us = esp_timer_get_time();
    if (!send_frame(frame, length)) ble_gap_terminate(bike_connection, BLE_ERR_REM_USER_CONN_TERM);
}

static void begin_config_read(void) {
    uint8_t frame[64];
    size_t length = c1_kirin_build_get(frame, sizeof(frame), bcp_sequence++, message_id++,
                                       request_id++, "106/5");
    stage = SESSION_CONFIG;
    active_request = REQUEST_CONFIG;
    request_started_us = esp_timer_get_time();
    bridge_state_set_bike_status(
        control_authorized ? BRIDGE_BIKE_AUTHORIZED : BRIDGE_BIKE_READABLE,
        control_authorized ? "已连接并获得控制权限" : "已连接单车");
    if (!send_frame(frame, length)) ble_gap_terminate(bike_connection, BLE_ERR_REM_USER_CONN_TERM);
}

static int on_subscribed(uint16_t connection, const struct ble_gatt_error *error,
                         struct ble_gatt_attr *attribute, void *argument) {
    (void)attribute;
    (void)argument;
    if (connection != bike_connection || error->status != 0) {
        ble_gap_terminate(connection, BLE_ERR_REM_USER_CONN_TERM);
        return 0;
    }
    send_handshake();
    return 0;
}

static void handle_result(const c1_parse_result_t *result) {
    bridge_config_t config;
    bridge_config_get(&config);
    if (result->kind == C1_PARSE_HANDSHAKE && stage == SESSION_HANDSHAKE) {
        if (config.device_bound) send_authorization();
        else begin_config_read();
        return;
    }
    if (result->kind == C1_PARSE_AUTHORIZATION && stage == SESSION_AUTHORIZING) {
        control_authorized = result->coap_code == 0x45;
        bridge_state_set_bike_status(control_authorized
            ? BRIDGE_BIKE_AUTHORIZED : BRIDGE_BIKE_READABLE,
            control_authorized ? "已连接并获得控制权限" : "控制权限被拒绝，继续只读");
        begin_config_read();
        return;
    }
    if (result->kind == C1_PARSE_CYCLE_CONFIG) {
        if (result->cycle_config.max_resistance) {
            max_resistance = result->cycle_config.max_resistance;
            bridge_state_set_max_resistance(max_resistance);
        }
        active_request = REQUEST_NONE;
        stage = SESSION_READY;
        next_request_us = 0;
        dispatch_request();
        return;
    }
    if (result->kind == C1_PARSE_METRICS) {
        bridge_metrics_t metrics = {
            .start_time_seconds = result->metrics.start_time_seconds,
            .distance_meters = result->metrics.distance_meters,
            .duration_seconds = result->metrics.duration_seconds,
            .calories = result->metrics.calories,
            .resistance = result->metrics.resistance,
            .cadence_rpm = result->metrics.cadence_rpm,
            .power_watts = result->metrics.power_watts,
            .training_status = result->metrics.status,
            .updated_at_ms = esp_timer_get_time() / 1000,
        };
        bridge_state_set_metrics(&metrics);
        if (metrics_callback) metrics_callback(&metrics, metrics_context);
        active_request = REQUEST_NONE;
        next_request_us = esp_timer_get_time() + 1000000;
    } else if (result->kind == C1_PARSE_RESISTANCE && active_request == REQUEST_RESISTANCE) {
        active_request = REQUEST_NONE;
        bridge_state_set_control_pending(false);
        next_request_us = esp_timer_get_time() + 250000;
    } else if (result->kind == C1_PARSE_TRAINING_STATUS && active_request == REQUEST_TRAINING) {
        active_request = REQUEST_NONE;
        bridge_state_set_control_pending(false);
        next_request_us = esp_timer_get_time() + 250000;
    }
}

static void handle_notification(const uint8_t *data, size_t length) {
    uint8_t frame[C1_KIRIN_MAX_FRAME];
    size_t frame_length = 0;
    if (!c1_kirin_stream_feed(&notification_stream, data, length,
                              frame, sizeof(frame), &frame_length)) return;
    c1_parse_result_t result;
    if (c1_kirin_parse(frame, frame_length, &result)) handle_result(&result);
}

static int gap_event(struct ble_gap_event *event, void *argument) {
    (void)argument;
    switch (event->type) {
        case BLE_GAP_EVENT_DISC:
            if (!should_connect(&event->disc)) return 0;
            ble_gap_disc_cancel();
            bridge_state_set_bike_status(BRIDGE_BIKE_CONNECTING, "正在连接单车");
            if (ble_gap_connect(own_addr_type, &event->disc.addr, 20000, NULL,
                                gap_event, NULL) != 0) start_scan();
            return 0;
        case BLE_GAP_EVENT_CONNECT:
            if (event->connect.status != 0) {
                reconnect_after_us = esp_timer_get_time() + 1000000;
                return 0;
            }
            bike_connection = event->connect.conn_handle;
            stage = SESSION_DISCOVERING;
            ble_gattc_exchange_mtu(bike_connection, NULL, NULL);
            if (ble_gattc_disc_svc_by_uuid(bike_connection,
                BLE_UUID16_DECLARE(KIRIN_SERVICE_UUID), on_service_found, NULL) != 0) {
                ble_gap_terminate(bike_connection, BLE_ERR_REM_USER_CONN_TERM);
            }
            return 0;
        case BLE_GAP_EVENT_DISCONNECT:
            if (event->disconnect.conn.conn_handle == bike_connection) {
                clear_session();
                bridge_state_set_bike_status(BRIDGE_BIKE_SCANNING, "单车已断开，等待重新出现");
                reconnect_after_us = esp_timer_get_time() + 1000000;
            }
            return 0;
        case BLE_GAP_EVENT_NOTIFY_RX:
            if (event->notify_rx.conn_handle == bike_connection &&
                (event->notify_rx.attr_handle == notify_handle ||
                 event->notify_rx.attr_handle == write_handle)) {
                uint8_t buffer[C1_KIRIN_MAX_FRAME];
                int length = OS_MBUF_PKTLEN(event->notify_rx.om);
                if (length > 0 && length <= sizeof(buffer) &&
                    os_mbuf_copydata(event->notify_rx.om, 0, length, buffer) == 0) {
                    handle_notification(buffer, length);
                }
            }
            return 0;
        case BLE_GAP_EVENT_DISC_COMPLETE:
            reconnect_after_us = esp_timer_get_time() + 1000000;
            return 0;
        default:
            return 0;
    }
}

static void start_scan(void) {
    if (!ble_ready || bike_connection != BLE_HS_CONN_HANDLE_NONE || ble_gap_disc_active()) return;
    struct ble_gap_disc_params params = {0};
    params.passive = 0;
    params.filter_duplicates = 1;
    params.itvl = BLE_GAP_SCAN_FAST_INTERVAL_MIN;
    params.window = BLE_GAP_SCAN_FAST_WINDOW;
    bridge_state_set_bike_status(BRIDGE_BIKE_SCANNING, "等待单车广播");
    int rc = ble_gap_disc(own_addr_type, BLE_HS_FOREVER, &params, gap_event, NULL);
    if (rc != 0) ESP_LOGW(TAG, "Scan start failed: %d", rc);
}

static void dispatch_request(void) {
    if (stage != SESSION_READY || active_request != REQUEST_NONE) return;
    uint8_t payload[8];
    uint8_t frame[72];
    size_t length = 0;
    if (queued_training_status) {
        uint8_t target = queued_training_status;
        queued_training_status = 0;
        size_t payload_length = c1_payload_training_status(payload, sizeof(payload), target);
        length = c1_kirin_build_put(frame, sizeof(frame), bcp_sequence++, message_id++,
                                    request_id++, "106/4", payload, payload_length);
        active_request = REQUEST_TRAINING;
    } else if (queued_resistance) {
        uint8_t target = queued_resistance;
        queued_resistance = 0;
        size_t payload_length = c1_payload_resistance(payload, sizeof(payload), target);
        length = c1_kirin_build_put(frame, sizeof(frame), bcp_sequence++, message_id++,
                                    request_id++, "106/6", payload, payload_length);
        active_request = REQUEST_RESISTANCE;
    } else {
        length = c1_kirin_build_get(frame, sizeof(frame), bcp_sequence++, message_id++,
                                    request_id++, "106/7");
        active_request = REQUEST_POLL;
    }
    request_started_us = esp_timer_get_time();
    if (!send_frame(frame, length)) active_request = REQUEST_NONE;
}

static void request_tick(void *argument) {
    (void)argument;
    int64_t now = esp_timer_get_time();
    if (reconnect_after_us && now >= reconnect_after_us) {
        reconnect_after_us = 0;
        start_scan();
    }
    if (bike_connection == BLE_HS_CONN_HANDLE_NONE) return;
    if ((stage == SESSION_HANDSHAKE && now - request_started_us > 5000000) ||
        (stage == SESSION_AUTHORIZING && now - request_started_us > 3000000) ||
        (active_request != REQUEST_NONE && now - request_started_us > 4000000)) {
        ESP_LOGW(TAG, "Kirin request timeout; reconnecting");
        ble_gap_terminate(bike_connection, BLE_ERR_REM_USER_CONN_TERM);
        return;
    }
    if (stage == SESSION_READY && active_request == REQUEST_NONE &&
        (queued_resistance || queued_training_status || now >= next_request_us)) {
        dispatch_request();
    }
}

esp_err_t bike_client_init(bike_metrics_callback_t callback, void *context) {
    metrics_callback = callback;
    metrics_context = context;
    clear_session();
    message_id = esp_timer_get_time() & 0xffff;
    const esp_timer_create_args_t args = {
        .callback = request_tick,
        .name = "bike_tick",
    };
    ESP_RETURN_ON_ERROR(esp_timer_create(&args, &request_timer), TAG, "timer create");
    return esp_timer_start_periodic(request_timer, 250000);
}

void bike_client_on_ble_ready(uint8_t address_type) {
    own_addr_type = address_type;
    ble_ready = true;
    start_scan();
}

esp_err_t bike_client_set_resistance(uint8_t resistance) {
    bridge_config_t config;
    bridge_config_get(&config);
    bridge_snapshot_t state;
    bridge_state_get(&state);
    if (!config.device_bound || state.bike_status != BRIDGE_BIKE_AUTHORIZED ||
        resistance < 1 || resistance > max_resistance) return ESP_ERR_INVALID_STATE;
    queued_resistance = resistance;
    bridge_state_set_control_pending(true);
    next_request_us = 0;
    return ESP_OK;
}

esp_err_t bike_client_set_training_status(uint8_t status) {
    bridge_snapshot_t state;
    bridge_state_get(&state);
    if (state.bike_status != BRIDGE_BIKE_AUTHORIZED ||
        (status != 1 && status != 3 && status != 4)) return ESP_ERR_INVALID_STATE;
    queued_training_status = status;
    bridge_state_set_control_pending(true);
    next_request_us = 0;
    return ESP_OK;
}

void bike_client_restart(void) {
    if (bike_connection != BLE_HS_CONN_HANDLE_NONE) {
        ble_gap_terminate(bike_connection, BLE_ERR_REM_USER_CONN_TERM);
    } else {
        ble_gap_disc_cancel();
        reconnect_after_us = esp_timer_get_time() + 250000;
    }
}
