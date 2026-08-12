#include "bridge_state.h"

#include <string.h>

#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"

static SemaphoreHandle_t state_mutex;
static bridge_snapshot_t state;

void bridge_state_init(void) {
    state_mutex = xSemaphoreCreateMutex();
    memset(&state, 0, sizeof(state));
    state.max_resistance = 18;
    state.bike_status = BRIDGE_BIKE_STOPPED;
    strncpy(state.status_message, "等待单车", sizeof(state.status_message) - 1);
}

void bridge_state_get(bridge_snapshot_t *snapshot) {
    if (!snapshot || !state_mutex) return;
    xSemaphoreTake(state_mutex, portMAX_DELAY);
    *snapshot = state;
    xSemaphoreGive(state_mutex);
}

void bridge_state_set_metrics(const bridge_metrics_t *metrics) {
    if (!metrics || !state_mutex) return;
    xSemaphoreTake(state_mutex, portMAX_DELAY);
    state.metrics = *metrics;
    xSemaphoreGive(state_mutex);
}

void bridge_state_set_bike_status(bridge_bike_status_t status, const char *message) {
    if (!state_mutex) return;
    xSemaphoreTake(state_mutex, portMAX_DELAY);
    state.bike_status = status;
    if (message) {
        strncpy(state.status_message, message, sizeof(state.status_message) - 1);
        state.status_message[sizeof(state.status_message) - 1] = '\0';
    }
    xSemaphoreGive(state_mutex);
}

void bridge_state_set_watch_connected(bool connected) {
    xSemaphoreTake(state_mutex, portMAX_DELAY);
    state.watch_connected = connected;
    xSemaphoreGive(state_mutex);
}

void bridge_state_set_network(bool station_connected, bool access_point_active) {
    xSemaphoreTake(state_mutex, portMAX_DELAY);
    state.wifi_connected = station_connected;
    state.access_point_active = access_point_active;
    xSemaphoreGive(state_mutex);
}

void bridge_state_set_device_bound(bool bound) {
    xSemaphoreTake(state_mutex, portMAX_DELAY);
    state.device_bound = bound;
    xSemaphoreGive(state_mutex);
}

void bridge_state_set_max_resistance(uint8_t maximum) {
    xSemaphoreTake(state_mutex, portMAX_DELAY);
    state.max_resistance = maximum ? maximum : 18;
    xSemaphoreGive(state_mutex);
}

void bridge_state_set_control_pending(bool pending) {
    xSemaphoreTake(state_mutex, portMAX_DELAY);
    state.control_pending = pending;
    xSemaphoreGive(state_mutex);
}
