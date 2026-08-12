package dev.c1bridge.app;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Parses the verified subset of C1 Mini Kirin frames without protobuf runtime dependencies. */
public final class KirinFrameParser {
    private static final byte[] TRAIN_DATA_PATH = "106/7".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TRAIN_STATUS_PATH = "106/4".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TRAIN_ATTRIBUTE_PATH = "106/6".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CYCLE_CONFIG_PATH = "106/5".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] USER_INFO_PATH = "106/3".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HANDSHAKE_PATH = "1/1".getBytes(StandardCharsets.US_ASCII);

    public static final class CycleConfig {
        public final int maxResistance;
        public final boolean resistanceChangeable;
        public final int pauseTimeoutSeconds;
        public final int batteryPercent;
        public final boolean charging;
        public final boolean buzzerOn;

        private CycleConfig(Map<Integer, Long> fields) {
            maxResistance = value(fields, 1);
            resistanceChangeable = value(fields, 2) != 0;
            pauseTimeoutSeconds = value(fields, 3);
            batteryPercent = value(fields, 4);
            charging = value(fields, 5) != 0;
            buzzerOn = value(fields, 6) != 0;
        }
    }

    public static final class Metrics {
        public final long startTimeSeconds;
        public final int distanceMeters;
        public final int durationSeconds;
        public final int calories;
        public final int resistance;
        public final int cadenceRpm;
        public final int powerWatts;
        public final int status;

        private Metrics(Map<Integer, Long> fields) {
            startTimeSeconds = value(fields, 1);
            distanceMeters = value(fields, 2);
            durationSeconds = value(fields, 3);
            calories = value(fields, 4);
            resistance = value(fields, 5);
            cadenceRpm = value(fields, 6);
            powerWatts = value(fields, 7);
            status = value(fields, 8);
        }

        static Metrics preview(
                int distanceMeters,
                int durationSeconds,
                int calories,
                int resistance,
                int cadenceRpm,
                int powerWatts,
                int status
        ) {
            Map<Integer, Long> fields = new HashMap<>();
            fields.put(2, (long) distanceMeters);
            fields.put(3, (long) durationSeconds);
            fields.put(4, (long) calories);
            fields.put(5, (long) resistance);
            fields.put(6, (long) cadenceRpm);
            fields.put(7, (long) powerWatts);
            fields.put(8, (long) status);
            return new Metrics(fields);
        }
    }

    public static final class Result {
        public final Metrics metrics;
        public final CycleConfig cycleConfig;
        public final Integer resistance;
        public final boolean resistanceChangedByDevice;
        public final boolean resistanceChangeFinished;
        public final boolean handshakeComplete;
        public final Integer authorizationCoapCode;
        public final Integer controlCoapCode;
        public final Integer trainingStatus;
        public final Integer trainingCoapCode;

        private Result(
                Metrics metrics,
                CycleConfig cycleConfig,
                Integer resistance,
                boolean resistanceChangedByDevice,
                boolean resistanceChangeFinished,
                boolean handshakeComplete,
                Integer authorizationCoapCode,
                Integer controlCoapCode,
                Integer trainingStatus,
                Integer trainingCoapCode
        ) {
            this.metrics = metrics;
            this.cycleConfig = cycleConfig;
            this.resistance = resistance;
            this.resistanceChangedByDevice = resistanceChangedByDevice;
            this.resistanceChangeFinished = resistanceChangeFinished;
            this.handshakeComplete = handshakeComplete;
            this.authorizationCoapCode = authorizationCoapCode;
            this.controlCoapCode = controlCoapCode;
            this.trainingStatus = trainingStatus;
            this.trainingCoapCode = trainingCoapCode;
        }
    }

    public Result parse(byte[] frame) {
        if (!KirinFrameCodec.hasValidEnvelope(frame)) {
            return null;
        }
        int pathIndex = indexOf(frame, TRAIN_STATUS_PATH, 6);
        if (pathIndex >= 0) {
            byte[] payload = payloadAfterPath(frame, pathIndex + TRAIN_STATUS_PATH.length);
            Map<Integer, Long> fields = payload == null
                    ? new HashMap<>()
                    : parseVarintFields(payload);
            Long status = fields.get(1);
            return new Result(
                    null,
                    null,
                    null,
                    false,
                    false,
                    false,
                    null,
                    null,
                    status == null ? null : status.intValue(),
                    coapCode(frame)
            );
        }
        pathIndex = indexOf(frame, TRAIN_DATA_PATH, 6);
        if (pathIndex >= 0) {
            byte[] payload = payloadAfterPath(frame, pathIndex + TRAIN_DATA_PATH.length);
            return payload == null
                    ? null
                    : new Result(
                            new Metrics(parseVarintFields(payload)),
                            null,
                            null,
                            false,
                            false,
                            false,
                            null,
                            null,
                            null,
                            null
                    );
        }
        pathIndex = indexOf(frame, TRAIN_ATTRIBUTE_PATH, 6);
        if (pathIndex >= 0) {
            byte[] payload = payloadAfterPath(frame, pathIndex + TRAIN_ATTRIBUTE_PATH.length);
            if (payload == null) {
                return null;
            }
            Map<Integer, Long> fields = parseVarintFields(payload);
            Long resistance = fields.get(1);
            return new Result(
                    null,
                    null,
                    resistance == null ? null : resistance.intValue(),
                    value(fields, 3) != 0,
                    value(fields, 8) != 0,
                    false,
                    null,
                    coapCode(frame),
                    null,
                    null
            );
        }
        pathIndex = indexOf(frame, CYCLE_CONFIG_PATH, 6);
        if (pathIndex >= 0) {
            byte[] payload = payloadAfterPath(frame, pathIndex + CYCLE_CONFIG_PATH.length);
            return payload == null
                    ? null
                    : new Result(
                            null,
                            new CycleConfig(parseVarintFields(payload)),
                            null,
                            false,
                            false,
                            false,
                            null,
                            null,
                            null,
                            null
                    );
        }
        pathIndex = indexOf(frame, USER_INFO_PATH, 6);
        if (pathIndex >= 0) {
            return new Result(
                    null,
                    null,
                    null,
                    false,
                    false,
                    false,
                    coapCode(frame),
                    null,
                    null,
                    null
            );
        }
        pathIndex = indexOf(frame, HANDSHAKE_PATH, 6);
        if (pathIndex >= 0) {
            return new Result(
                    null, null, null, false, false, true, null, null, null, null
            );
        }
        return null;
    }

    private static byte[] payloadAfterPath(byte[] frame, int start) {
        int marker = -1;
        for (int i = start; i < frame.length - 2; i++) {
            if (frame[i] == (byte) 0xFF) {
                marker = i;
                break;
            }
        }
        if (marker < 0) {
            return new byte[0];
        }
        int length = frame.length - marker - 3;
        byte[] payload = new byte[length];
        System.arraycopy(frame, marker + 1, payload, 0, length);
        return payload;
    }

    private static Map<Integer, Long> parseVarintFields(byte[] payload) {
        Map<Integer, Long> fields = new HashMap<>();
        int[] offset = {0};
        while (offset[0] < payload.length) {
            long key = readVarint(payload, offset);
            if (key < 0) {
                break;
            }
            int fieldNumber = (int) (key >>> 3);
            int wireType = (int) (key & 7);
            if (wireType == 0) {
                long value = readVarint(payload, offset);
                if (value < 0) {
                    break;
                }
                fields.put(fieldNumber, value);
            } else if (!skipField(payload, offset, wireType)) {
                break;
            }
        }
        return fields;
    }

    private static long readVarint(byte[] bytes, int[] offset) {
        long result = 0;
        for (int shift = 0; shift < 64 && offset[0] < bytes.length; shift += 7) {
            int current = unsigned(bytes[offset[0]++]);
            result |= (long) (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return result;
            }
        }
        return -1;
    }

    private static boolean skipField(byte[] bytes, int[] offset, int wireType) {
        int length;
        switch (wireType) {
            case 1:
                length = 8;
                break;
            case 2:
                long value = readVarint(bytes, offset);
                if (value < 0 || value > Integer.MAX_VALUE) {
                    return false;
                }
                length = (int) value;
                break;
            case 5:
                length = 4;
                break;
            default:
                return false;
        }
        if (offset[0] + length > bytes.length) {
            return false;
        }
        offset[0] += length;
        return true;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int start) {
        for (int i = start; i <= haystack.length - needle.length; i++) {
            int j = 0;
            while (j < needle.length && haystack[i + j] == needle[j]) {
                j++;
            }
            if (j == needle.length) {
                return i;
            }
        }
        return -1;
    }

    private static int value(Map<Integer, Long> fields, int key) {
        Long value = fields.get(key);
        return value == null ? 0 : value.intValue();
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }

    private static int coapCode(byte[] frame) {
        return unsigned(frame[11]);
    }
}
