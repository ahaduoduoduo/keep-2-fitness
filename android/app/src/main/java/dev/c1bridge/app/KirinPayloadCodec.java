package dev.c1bridge.app;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Encodes the small protobuf payload subset used by the C1 Mini resources. */
public final class KirinPayloadCodec {
    private KirinPayloadCodec() {
    }

    public static byte[] buildResistance(int resistance) {
        return buildSingleVarint(resistance);
    }

    public static byte[] buildTrainingStatus(int status) {
        return buildSingleVarint(status);
    }

    private static byte[] buildSingleVarint(int value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x08);
        writeVarint(output, value);
        return output.toByteArray();
    }

    public static byte[] buildUserInfo(
            String userId,
            String deviceId,
            float userWeightKg,
            long timestampSeconds
    ) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeString(output, 1, userId);
        writeString(output, 2, deviceId);

        output.write(0x1D);
        int weightBits = Float.floatToIntBits(userWeightKg);
        output.write(weightBits & 0xFF);
        output.write((weightBits >>> 8) & 0xFF);
        output.write((weightBits >>> 16) & 0xFF);
        output.write((weightBits >>> 24) & 0xFF);

        output.write(0x20);
        writeVarint(output, timestampSeconds);
        return output.toByteArray();
    }

    private static void writeString(ByteArrayOutputStream output, int field, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.write((field << 3) | 2);
        writeVarint(output, bytes.length);
        output.write(bytes, 0, bytes.length);
    }

    private static void writeVarint(ByteArrayOutputStream output, long value) {
        long remaining = value;
        while ((remaining & ~0x7FL) != 0) {
            output.write(((int) remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        output.write((int) remaining);
    }
}
