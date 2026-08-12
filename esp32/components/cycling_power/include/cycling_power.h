#pragma once

#include <stdint.h>
#include "esp_err.h"

esp_err_t cycling_power_init(void);
void cycling_power_set_metrics(int16_t power_watts, uint16_t cadence_rpm);
