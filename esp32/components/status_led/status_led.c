#include "status_led.h"

#include "bridge_state.h"
#include "driver/rmt_tx.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "led_strip.h"

static led_strip_handle_t strip;
static const char *TAG = "status_led";

static void update_led(void *argument) {
    (void)argument;
    bridge_snapshot_t state;
    bridge_state_get(&state);
    uint8_t red = 0, green = 0, blue = 0;
    if (state.bike_status == BRIDGE_BIKE_AUTHORIZED) {
        red = 112; green = 20;
    } else if (state.bike_status == BRIDGE_BIKE_READABLE) {
        red = 72; green = 32;
    } else if (state.bike_status == BRIDGE_BIKE_ERROR) {
        red = 112;
    } else if (state.wifi_connected) {
        green = 64; blue = 8;
    } else {
        blue = 64;
    }
    led_strip_set_pixel(strip, 0, red, green, blue);
    led_strip_refresh(strip);
}

void status_led_init(void) {
    const led_strip_config_t strip_config = {
        .strip_gpio_num = 21,
        .max_leds = 1,
        .led_model = LED_MODEL_WS2812,
        .color_component_format = LED_STRIP_COLOR_COMPONENT_FMT_GRB,
        .flags.invert_out = false,
    };
    const led_strip_rmt_config_t rmt_config = {
        .clk_src = RMT_CLK_SRC_DEFAULT,
        .resolution_hz = 10000000,
        .mem_block_symbols = 64,
        .flags.with_dma = false,
    };
    esp_err_t result = led_strip_new_rmt_device(&strip_config, &rmt_config, &strip);
    if (result != ESP_OK) {
        ESP_LOGE(TAG, "GPIO21 RGB initialization failed: %s",
                 esp_err_to_name(result));
        return;
    }
    const esp_timer_create_args_t args = {
        .callback = update_led,
        .name = "status_led",
    };
    esp_timer_handle_t timer;
    result = esp_timer_create(&args, &timer);
    if (result != ESP_OK) {
        ESP_LOGE(TAG, "Status timer creation failed: %s",
                 esp_err_to_name(result));
        return;
    }
    update_led(NULL);
    result = esp_timer_start_periodic(timer, 500000);
    if (result != ESP_OK) {
        ESP_LOGE(TAG, "Status timer start failed: %s",
                 esp_err_to_name(result));
        return;
    }
    ESP_LOGI(TAG, "GPIO21 WS2812 status LED enabled");
}
