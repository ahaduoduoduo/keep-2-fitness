#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define C1_KIRIN_MAX_FRAME 512

typedef struct {
    uint8_t buffer[C1_KIRIN_MAX_FRAME];
    size_t length;
} c1_kirin_stream_t;

typedef struct {
    uint32_t start_time_seconds;
    uint32_t distance_meters;
    uint32_t duration_seconds;
    uint32_t calories;
    uint32_t resistance;
    uint32_t cadence_rpm;
    int32_t power_watts;
    uint32_t status;
} c1_train_metrics_t;

typedef struct {
    uint32_t max_resistance;
    bool resistance_changeable;
    uint32_t pause_timeout_seconds;
    uint32_t battery_percent;
    bool charging;
    bool buzzer_on;
} c1_cycle_config_t;

typedef enum {
    C1_PARSE_NONE = 0,
    C1_PARSE_HANDSHAKE,
    C1_PARSE_AUTHORIZATION,
    C1_PARSE_METRICS,
    C1_PARSE_CYCLE_CONFIG,
    C1_PARSE_RESISTANCE,
    C1_PARSE_TRAINING_STATUS,
} c1_parse_kind_t;

typedef struct {
    c1_parse_kind_t kind;
    uint8_t coap_code;
    c1_train_metrics_t metrics;
    c1_cycle_config_t cycle_config;
    uint32_t resistance;
    bool changed_by_device;
    bool change_finished;
    uint32_t training_status;
} c1_parse_result_t;

size_t c1_kirin_build_get(uint8_t *output, size_t capacity, uint16_t bcp_sequence,
                          uint16_t message_id, uint32_t request_id, const char *path);
size_t c1_kirin_build_get_payload(uint8_t *output, size_t capacity, uint16_t bcp_sequence,
                                  uint16_t message_id, uint32_t request_id, const char *path,
                                  const uint8_t *payload, size_t payload_length);
size_t c1_kirin_build_put(uint8_t *output, size_t capacity, uint16_t bcp_sequence,
                          uint16_t message_id, uint32_t request_id, const char *path,
                          const uint8_t *payload, size_t payload_length);
size_t c1_payload_resistance(uint8_t *output, size_t capacity, uint32_t resistance);
size_t c1_payload_training_status(uint8_t *output, size_t capacity, uint32_t status);
size_t c1_payload_user_info(uint8_t *output, size_t capacity, const char *user_id,
                            const char *device_id, float weight_kg, uint64_t timestamp_seconds);
bool c1_kirin_valid_envelope(const uint8_t *frame, size_t length);
bool c1_kirin_parse(const uint8_t *frame, size_t length, c1_parse_result_t *result);
bool c1_kirin_stream_feed(c1_kirin_stream_t *stream, const uint8_t *data, size_t data_length,
                          uint8_t *frame, size_t frame_capacity, size_t *frame_length);
uint16_t c1_crc16_xmodem(const uint8_t *bytes, size_t length);
