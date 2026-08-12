#include "bike_client.h"
#include "bridge_config.h"
#include "bridge_state.h"
#include "cycling_power.h"
#include "esp_log.h"
#include "esp_system.h"
#include "network_manager.h"
#include "nvs_flash.h"
#include "status_led.h"
#include "web_server.h"

static const char *TAG = "c1_bridge";

static void init_nvs(void) {
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);
}

static void on_metrics(const bridge_metrics_t *metrics, void *context) {
    (void)context;
    cycling_power_set_metrics(metrics->power_watts, metrics->cadence_rpm);
    web_server_publish_state();
}

void app_main(void) {
    init_nvs();
    bridge_state_init();
    bridge_config_init();
    status_led_init();

    bridge_config_t config;
    bridge_config_get(&config);
    ESP_LOGI(TAG, "C1 Bridge ESP32 starting; configured=%d bound=%d",
             config.wifi_configured, config.device_bound);

    ESP_ERROR_CHECK(network_manager_init());
    ESP_ERROR_CHECK(web_server_start());
    ESP_ERROR_CHECK(cycling_power_init());
    ESP_ERROR_CHECK(bike_client_init(on_metrics, NULL));
}
