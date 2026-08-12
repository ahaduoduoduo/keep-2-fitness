#pragma once

#include <stdint.h>
#include "bridge_state.h"
#include "esp_err.h"

typedef void (*bike_metrics_callback_t)(const bridge_metrics_t *metrics, void *context);

esp_err_t bike_client_init(bike_metrics_callback_t callback, void *context);
void bike_client_on_ble_ready(uint8_t address_type);
esp_err_t bike_client_set_resistance(uint8_t resistance);
esp_err_t bike_client_set_training_status(uint8_t status);
void bike_client_restart(void);
