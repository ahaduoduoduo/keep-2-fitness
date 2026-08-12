#include "c1_protocol.h"

#include <string.h>

static const uint8_t CLIENT_ROUTE[] = {0x32, 0x16, 0xef, 0x23};

static bool put_byte(uint8_t *output, size_t capacity, size_t *offset, uint8_t value) {
    if (*offset >= capacity) return false;
    output[(*offset)++] = value;
    return true;
}

static bool put_bytes(uint8_t *output, size_t capacity, size_t *offset,
                      const uint8_t *value, size_t length) {
    if (!value || *offset + length > capacity) return false;
    memcpy(output + *offset, value, length);
    *offset += length;
    return true;
}

static bool put_varint(uint8_t *output, size_t capacity, size_t *offset, uint64_t value) {
    do {
        uint8_t byte = value & 0x7f;
        value >>= 7;
        if (value) byte |= 0x80;
        if (!put_byte(output, capacity, offset, byte)) return false;
    } while (value);
    return true;
}

uint16_t c1_crc16_xmodem(const uint8_t *bytes, size_t length) {
    uint16_t crc = 0;
    for (size_t index = 0; index < length; index++) {
        crc ^= (uint16_t)bytes[index] << 8;
        for (int bit = 0; bit < 8; bit++) {
            crc = (crc & 0x8000) ? (uint16_t)((crc << 1) ^ 0x1021) : (uint16_t)(crc << 1);
        }
    }
    return crc;
}

static size_t build_frame(uint8_t *output, size_t capacity, uint16_t bcp_sequence,
                          uint8_t coap_code, uint8_t kirin_method, uint16_t message_id,
                          uint32_t request_id, const char *path,
                          const uint8_t *payload, size_t payload_length, bool include_payload) {
    if (!output || !path) return 0;
    size_t path_length = strlen(path);
    if (path_length > 12) return 0;
    size_t body_length = sizeof(CLIENT_ROUTE) + 9 + 1 + path_length +
                         (include_payload ? 1 + payload_length : 0);
    size_t total_length = 6 + body_length + 2;
    if (capacity < total_length || body_length > UINT16_MAX) return 0;

    uint16_t sequence = bcp_sequence & 0x1fff;
    output[0] = 0xa5;
    output[1] = 0xa5;
    output[2] = 0xa0 | ((sequence >> 8) & 0x1f);
    output[3] = sequence & 0xff;
    output[4] = body_length & 0xff;
    output[5] = (body_length >> 8) & 0xff;
    size_t offset = 6;
    if (!put_bytes(output, capacity, &offset, CLIENT_ROUTE, sizeof(CLIENT_ROUTE)) ||
        !put_byte(output, capacity, &offset, 0x55) ||
        !put_byte(output, capacity, &offset, coap_code) ||
        !put_byte(output, capacity, &offset, message_id >> 8) ||
        !put_byte(output, capacity, &offset, message_id & 0xff) ||
        !put_byte(output, capacity, &offset, request_id & 0xff) ||
        !put_byte(output, capacity, &offset, (request_id >> 8) & 0xff) ||
        !put_byte(output, capacity, &offset, (request_id >> 16) & 0xff) ||
        !put_byte(output, capacity, &offset, (request_id >> 24) & 0xff) ||
        !put_byte(output, capacity, &offset, kirin_method) ||
        !put_byte(output, capacity, &offset, 0xb0 | path_length) ||
        !put_bytes(output, capacity, &offset, (const uint8_t *)path, path_length)) return 0;
    if (include_payload) {
        if (!put_byte(output, capacity, &offset, 0xff) ||
            (payload_length && !put_bytes(output, capacity, &offset, payload, payload_length))) return 0;
    }
    uint16_t crc = c1_crc16_xmodem(output, offset);
    output[offset++] = crc & 0xff;
    output[offset++] = crc >> 8;
    return offset;
}

size_t c1_kirin_build_get(uint8_t *output, size_t capacity, uint16_t bcp_sequence,
                          uint16_t message_id, uint32_t request_id, const char *path) {
    return build_frame(output, capacity, bcp_sequence, 0x01, 0x01, message_id,
                       request_id, path, NULL, 0, false);
}

size_t c1_kirin_build_get_payload(uint8_t *output, size_t capacity, uint16_t bcp_sequence,
                                  uint16_t message_id, uint32_t request_id, const char *path,
                                  const uint8_t *payload, size_t payload_length) {
    return build_frame(output, capacity, bcp_sequence, 0x01, 0x01, message_id,
                       request_id, path, payload, payload_length, true);
}

size_t c1_kirin_build_put(uint8_t *output, size_t capacity, uint16_t bcp_sequence,
                          uint16_t message_id, uint32_t request_id, const char *path,
                          const uint8_t *payload, size_t payload_length) {
    return build_frame(output, capacity, bcp_sequence, 0x03, 0x02, message_id,
                       request_id, path, payload, payload_length, true);
}

static size_t single_varint_payload(uint8_t *output, size_t capacity, uint32_t value) {
    size_t offset = 0;
    if (!put_byte(output, capacity, &offset, 0x08) ||
        !put_varint(output, capacity, &offset, value)) return 0;
    return offset;
}

size_t c1_payload_resistance(uint8_t *output, size_t capacity, uint32_t resistance) {
    return single_varint_payload(output, capacity, resistance);
}

size_t c1_payload_training_status(uint8_t *output, size_t capacity, uint32_t status) {
    return single_varint_payload(output, capacity, status);
}

static bool put_string_field(uint8_t *output, size_t capacity, size_t *offset,
                             uint8_t field, const char *value) {
    size_t length = strlen(value);
    return put_byte(output, capacity, offset, (field << 3) | 2) &&
           put_varint(output, capacity, offset, length) &&
           put_bytes(output, capacity, offset, (const uint8_t *)value, length);
}

size_t c1_payload_user_info(uint8_t *output, size_t capacity, const char *user_id,
                            const char *device_id, float weight_kg, uint64_t timestamp_seconds) {
    if (!output || !user_id || !device_id) return 0;
    size_t offset = 0;
    if (!put_string_field(output, capacity, &offset, 1, user_id) ||
        !put_string_field(output, capacity, &offset, 2, device_id) ||
        !put_byte(output, capacity, &offset, 0x1d)) return 0;
    uint32_t weight_bits;
    memcpy(&weight_bits, &weight_kg, sizeof(weight_bits));
    for (int shift = 0; shift < 32; shift += 8) {
        if (!put_byte(output, capacity, &offset, weight_bits >> shift)) return 0;
    }
    if (!put_byte(output, capacity, &offset, 0x20) ||
        !put_varint(output, capacity, &offset, timestamp_seconds)) return 0;
    return offset;
}

bool c1_kirin_valid_envelope(const uint8_t *frame, size_t length) {
    if (!frame || length < 8 || frame[0] != 0xa5 || frame[1] != 0xa5) return false;
    size_t body_length = frame[4] | ((size_t)frame[5] << 8);
    if (length != 6 + body_length + 2) return false;
    uint16_t expected = frame[length - 2] | ((uint16_t)frame[length - 1] << 8);
    return c1_crc16_xmodem(frame, length - 2) == expected;
}

static const uint8_t *find_bytes(const uint8_t *bytes, size_t length,
                                 const char *needle, size_t start) {
    size_t needle_length = strlen(needle);
    if (needle_length > length) return NULL;
    for (size_t index = start; index + needle_length <= length; index++) {
        if (memcmp(bytes + index, needle, needle_length) == 0) return bytes + index;
    }
    return NULL;
}

static bool read_varint(const uint8_t *bytes, size_t length, size_t *offset, uint64_t *value) {
    uint64_t result = 0;
    for (unsigned shift = 0; shift < 64 && *offset < length; shift += 7) {
        uint8_t current = bytes[(*offset)++];
        result |= (uint64_t)(current & 0x7f) << shift;
        if (!(current & 0x80)) {
            *value = result;
            return true;
        }
    }
    return false;
}

static void parse_varints(const uint8_t *payload, size_t length, uint64_t fields[16]) {
    size_t offset = 0;
    while (offset < length) {
        uint64_t key;
        if (!read_varint(payload, length, &offset, &key)) break;
        unsigned field = key >> 3;
        unsigned wire = key & 7;
        if (wire == 0) {
            uint64_t value;
            if (!read_varint(payload, length, &offset, &value)) break;
            if (field < 16) fields[field] = value;
        } else if (wire == 1) {
            if (offset + 8 > length) break;
            offset += 8;
        } else if (wire == 2) {
            uint64_t field_length;
            if (!read_varint(payload, length, &offset, &field_length) || offset + field_length > length) break;
            offset += field_length;
        } else if (wire == 5) {
            if (offset + 4 > length) break;
            offset += 4;
        } else break;
    }
}

bool c1_kirin_parse(const uint8_t *frame, size_t length, c1_parse_result_t *result) {
    if (!result || !c1_kirin_valid_envelope(frame, length)) return false;
    memset(result, 0, sizeof(*result));
    const char *paths[] = {"106/4", "106/7", "106/6", "106/5", "106/3", "1/1"};
    const uint8_t *path = NULL;
    size_t path_index = 0;
    for (; path_index < sizeof(paths) / sizeof(paths[0]); path_index++) {
        path = find_bytes(frame, length - 2, paths[path_index], 6);
        if (path) break;
    }
    if (!path) return false;
    result->coap_code = length > 11 ? frame[11] : 0;
    if (path_index == 5) {
        result->kind = C1_PARSE_HANDSHAKE;
        return true;
    }
    if (path_index == 4) {
        result->kind = C1_PARSE_AUTHORIZATION;
        return true;
    }
    const uint8_t *after_path = path + strlen(paths[path_index]);
    const uint8_t *payload = memchr(after_path, 0xff, (frame + length - 2) - after_path);
    uint64_t fields[16] = {0};
    if (payload) parse_varints(payload + 1, (frame + length - 2) - (payload + 1), fields);
    if (path_index == 0) {
        result->kind = C1_PARSE_TRAINING_STATUS;
        result->training_status = fields[1];
    } else if (path_index == 1) {
        result->kind = C1_PARSE_METRICS;
        result->metrics.start_time_seconds = fields[1];
        result->metrics.distance_meters = fields[2];
        result->metrics.duration_seconds = fields[3];
        result->metrics.calories = fields[4];
        result->metrics.resistance = fields[5];
        result->metrics.cadence_rpm = fields[6];
        result->metrics.power_watts = fields[7];
        result->metrics.status = fields[8];
    } else if (path_index == 2) {
        result->kind = C1_PARSE_RESISTANCE;
        result->resistance = fields[1];
        result->changed_by_device = fields[3] != 0;
        result->change_finished = fields[8] != 0;
    } else if (path_index == 3) {
        result->kind = C1_PARSE_CYCLE_CONFIG;
        result->cycle_config.max_resistance = fields[1];
        result->cycle_config.resistance_changeable = fields[2] != 0;
        result->cycle_config.pause_timeout_seconds = fields[3];
        result->cycle_config.battery_percent = fields[4];
        result->cycle_config.charging = fields[5] != 0;
        result->cycle_config.buzzer_on = fields[6] != 0;
    }
    return true;
}

bool c1_kirin_stream_feed(c1_kirin_stream_t *stream, const uint8_t *data, size_t data_length,
                          uint8_t *frame, size_t frame_capacity, size_t *frame_length) {
    if (!stream || !data || !frame || !frame_length) return false;
    for (size_t index = 0; index < data_length; index++) {
        if (stream->length == 0 && data[index] != 0xa5) continue;
        if (stream->length == 1 && data[index] != 0xa5) {
            stream->length = data[index] == 0xa5 ? 1 : 0;
            continue;
        }
        if (stream->length >= sizeof(stream->buffer)) stream->length = 0;
        stream->buffer[stream->length++] = data[index];
        if (stream->length >= 6) {
            size_t expected = 6 + stream->buffer[4] + ((size_t)stream->buffer[5] << 8) + 2;
            if (expected > sizeof(stream->buffer)) {
                stream->length = 0;
                continue;
            }
            if (stream->length == expected) {
                bool valid = c1_kirin_valid_envelope(stream->buffer, expected);
                if (valid && expected <= frame_capacity) {
                    memcpy(frame, stream->buffer, expected);
                    *frame_length = expected;
                }
                stream->length = 0;
                if (valid && expected <= frame_capacity) return true;
            }
        }
    }
    return false;
}
