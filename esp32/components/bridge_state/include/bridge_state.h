#pragma once

#include <stdbool.h>
#include <stdint.h>

typedef enum {
    BRIDGE_BIKE_STOPPED = 0,
    BRIDGE_BIKE_SCANNING,
    BRIDGE_BIKE_CONNECTING,
    BRIDGE_BIKE_READABLE,
    BRIDGE_BIKE_AUTHORIZED,
    BRIDGE_BIKE_ERROR,
} bridge_bike_status_t;

typedef struct {
    uint32_t start_time_seconds;
    uint32_t distance_meters;
    uint32_t duration_seconds;
    uint32_t calories;
    uint8_t resistance;
    uint16_t cadence_rpm;
    int16_t power_watts;
    uint8_t training_status;
    uint64_t updated_at_ms;
} bridge_metrics_t;

typedef struct {
    bridge_bike_status_t bike_status;
    bool watch_connected;
    bool wifi_connected;
    bool access_point_active;
    bool device_bound;
    bool control_pending;
    uint8_t max_resistance;
    bridge_metrics_t metrics;
    char status_message[96];
} bridge_snapshot_t;

void bridge_state_init(void);
void bridge_state_get(bridge_snapshot_t *snapshot);
void bridge_state_set_metrics(const bridge_metrics_t *metrics);
void bridge_state_set_bike_status(bridge_bike_status_t status, const char *message);
void bridge_state_set_watch_connected(bool connected);
void bridge_state_set_network(bool station_connected, bool access_point_active);
void bridge_state_set_device_bound(bool bound);
void bridge_state_set_max_resistance(uint8_t maximum);
void bridge_state_set_control_pending(bool pending);
