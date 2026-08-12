#include "cycling_power.h"

#include <math.h>
#include <string.h>

#include "bridge_state.h"
#include "esp_check.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "os/os_mbuf.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

static const char *TAG = "cycling_power";
static uint8_t own_addr_type;
static uint16_t measurement_handle;
static uint16_t watch_connection = BLE_HS_CONN_HANDLE_NONE;
static int16_t power_watts;
static uint16_t cadence_rpm;
static uint16_t cumulative_crank_revolutions;
static uint16_t last_crank_event_time;
static double crank_fraction;
static double event_clock_ticks;
static int64_t previous_update_us;
static esp_timer_handle_t notification_timer;

static int gatt_access(uint16_t conn_handle, uint16_t attr_handle,
                       struct ble_gatt_access_ctxt *context, void *argument) {
    (void)conn_handle;
    (void)attr_handle;
    uint16_t uuid = ble_uuid_u16(context->chr->uuid);
    if (context->op != BLE_GATT_ACCESS_OP_READ_CHR) return BLE_ATT_ERR_READ_NOT_PERMITTED;
    if (uuid == 0x2a65) {
        const uint32_t feature = 1u << 3;
        return os_mbuf_append(context->om, &feature, sizeof(feature)) == 0
               ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    if (uuid == 0x2a5d) {
        const uint8_t left_crank = 5;
        return os_mbuf_append(context->om, &left_crank, sizeof(left_crank)) == 0
               ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    return BLE_ATT_ERR_READ_NOT_PERMITTED;
}

static const struct ble_gatt_svc_def services[] = {{
    .type = BLE_GATT_SVC_TYPE_PRIMARY,
    .uuid = BLE_UUID16_DECLARE(0x1818),
    .characteristics = (struct ble_gatt_chr_def[]) {{
        .uuid = BLE_UUID16_DECLARE(0x2a63),
        .access_cb = gatt_access,
        .flags = BLE_GATT_CHR_F_NOTIFY,
        .val_handle = &measurement_handle,
    }, {
        .uuid = BLE_UUID16_DECLARE(0x2a65),
        .access_cb = gatt_access,
        .flags = BLE_GATT_CHR_F_READ,
    }, {
        .uuid = BLE_UUID16_DECLARE(0x2a5d),
        .access_cb = gatt_access,
        .flags = BLE_GATT_CHR_F_READ,
    }, {0}}
}, {0}};

static void start_advertising(void);

static int gap_event(struct ble_gap_event *event, void *argument) {
    (void)argument;
    switch (event->type) {
        case BLE_GAP_EVENT_CONNECT:
            if (event->connect.status == 0) {
                struct ble_gap_conn_desc descriptor;
                if (ble_gap_conn_find(event->connect.conn_handle, &descriptor) == 0 &&
                    descriptor.role == BLE_GAP_ROLE_SLAVE) {
                    watch_connection = event->connect.conn_handle;
                    bridge_state_set_watch_connected(true);
                    ESP_LOGI(TAG, "Cycling power client connected");
                }
            } else start_advertising();
            return 0;
        case BLE_GAP_EVENT_DISCONNECT:
            if (event->disconnect.conn.conn_handle == watch_connection) {
                watch_connection = BLE_HS_CONN_HANDLE_NONE;
                bridge_state_set_watch_connected(false);
                start_advertising();
            }
            return 0;
        case BLE_GAP_EVENT_ADV_COMPLETE:
            start_advertising();
            return 0;
        case BLE_GAP_EVENT_SUBSCRIBE:
            if (event->subscribe.attr_handle == measurement_handle) {
                ESP_LOGI(TAG, "Measurement notifications %s",
                         event->subscribe.cur_notify ? "enabled" : "disabled");
            }
            return 0;
        default:
            return 0;
    }
}

static void start_advertising(void) {
    if (ble_gap_adv_active()) return;
    struct ble_hs_adv_fields fields = {0};
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.uuids16 = (ble_uuid16_t[]) {BLE_UUID16_INIT(0x1818)};
    fields.num_uuids16 = 1;
    fields.uuids16_is_complete = 1;
    int rc = ble_gap_adv_set_fields(&fields);
    if (rc != 0) {
        ESP_LOGE(TAG, "Advertising fields failed: %d", rc);
        return;
    }

    struct ble_hs_adv_fields response = {0};
    const char *name = ble_svc_gap_device_name();
    response.name = (uint8_t *)name;
    response.name_len = strlen(name);
    response.name_is_complete = 1;
    ble_gap_adv_rsp_set_fields(&response);

    struct ble_gap_adv_params params = {0};
    params.conn_mode = BLE_GAP_CONN_MODE_UND;
    params.disc_mode = BLE_GAP_DISC_MODE_GEN;
    params.itvl_min = BLE_GAP_ADV_FAST_INTERVAL1_MIN;
    params.itvl_max = BLE_GAP_ADV_FAST_INTERVAL1_MAX;
    rc = ble_gap_adv_start(own_addr_type, NULL, BLE_HS_FOREVER, &params, gap_event, NULL);
    if (rc != 0) ESP_LOGE(TAG, "Advertising start failed: %d", rc);
}

static void notify_measurement(void *argument) {
    (void)argument;
    int64_t now_us = esp_timer_get_time();
    double elapsed = previous_update_us ? (now_us - previous_update_us) / 1000000.0 : 0.25;
    previous_update_us = now_us;
    event_clock_ticks = fmod(event_clock_ticks + elapsed * 1024.0, 65536.0);
    if (cadence_rpm > 0) {
        double revolutions = crank_fraction + elapsed * cadence_rpm / 60.0;
        unsigned completed = floor(revolutions);
        crank_fraction = revolutions - completed;
        if (completed) {
            cumulative_crank_revolutions += completed;
            double period_ticks = 60.0 * 1024.0 / cadence_rpm;
            double last = fmod(event_clock_ticks - crank_fraction * period_ticks + 65536.0, 65536.0);
            last_crank_event_time = (uint16_t)lround(last);
        }
    }
    if (watch_connection == BLE_HS_CONN_HANDLE_NONE) return;
    uint8_t value[8] = {
        1u << 5, 0,
        power_watts & 0xff, (power_watts >> 8) & 0xff,
        cumulative_crank_revolutions & 0xff, cumulative_crank_revolutions >> 8,
        last_crank_event_time & 0xff, last_crank_event_time >> 8,
    };
    struct os_mbuf *packet = ble_hs_mbuf_from_flat(value, sizeof(value));
    if (packet) ble_gatts_notify_custom(watch_connection, measurement_handle, packet);
}

static void on_sync(void) {
    int rc = ble_hs_util_ensure_addr(0);
    if (rc == 0) rc = ble_hs_id_infer_auto(0, &own_addr_type);
    if (rc != 0) {
        ESP_LOGE(TAG, "BLE address setup failed: %d", rc);
        return;
    }
    start_advertising();
    extern void bike_client_on_ble_ready(uint8_t address_type);
    bike_client_on_ble_ready(own_addr_type);
}

static void host_task(void *argument) {
    (void)argument;
    nimble_port_run();
    nimble_port_freertos_deinit();
}

esp_err_t cycling_power_init(void) {
    esp_err_t err = nimble_port_init();
    if (err != ESP_OK) return err;
    ble_svc_gap_init();
    ble_svc_gatt_init();
    ble_svc_gap_device_name_set("C1 Bridge");
    int rc = ble_gatts_count_cfg(services);
    if (rc == 0) rc = ble_gatts_add_svcs(services);
    if (rc != 0) return ESP_FAIL;
    ble_hs_cfg.sync_cb = on_sync;
    ble_hs_cfg.store_status_cb = ble_store_util_status_rr;
    nimble_port_freertos_init(host_task);
    const esp_timer_create_args_t timer_args = {
        .callback = notify_measurement,
        .name = "power_notify",
    };
    ESP_RETURN_ON_ERROR(esp_timer_create(&timer_args, &notification_timer), TAG, "timer create");
    return esp_timer_start_periodic(notification_timer, 250000);
}

void cycling_power_set_metrics(int16_t watts, uint16_t rpm) {
    power_watts = watts;
    cadence_rpm = rpm > 300 ? 300 : rpm;
}
