package dev.c1bridge.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class KirinFrameCodecTest {
    @Test
    public void buildsCapturedTrainDataRequestExactly() {
        byte[] expected = hex(
                "a5 a5 a0 13 13 00 32 16 ef 23 55 01 fb 8d " +
                        "3a 04 00 00 01 b5 31 30 36 2f 37 da 62"
        );

        byte[] actual = KirinFrameCodec.buildGet(0x13, 0xFB8D, 0x043A, "106/7");

        assertArrayEquals(expected, actual);
        assertTrue(KirinFrameCodec.hasValidEnvelope(actual));
    }

    @Test
    public void rejectsCorruptedCrc() {
        byte[] frame = KirinFrameCodec.buildGet(0, 1, 2, "106/7");
        frame[10] ^= 1;

        assertFalse(KirinFrameCodec.hasValidEnvelope(frame));
    }

    @Test
    public void buildsResistancePutWithProtobufPayload() {
        byte[] frame = KirinFrameCodec.buildPut(
                0,
                0x1234,
                0x56,
                "106/6",
                hex("08 09")
        );

        assertTrue(KirinFrameCodec.hasValidEnvelope(frame));
        assertTrue(contains(frame, hex("b5 31 30 36 2f 36 ff 08 09")));
    }

    @Test
    public void buildsTrainingStatusPutWithProtobufPayload() {
        byte[] frame = KirinFrameCodec.buildPut(
                0,
                0x1234,
                0x56,
                "106/4",
                KirinPayloadCodec.buildTrainingStatus(3)
        );

        assertTrue(KirinFrameCodec.hasValidEnvelope(frame));
        assertTrue(contains(frame, hex("b5 31 30 36 2f 34 ff 08 03")));
    }

    @Test
    public void buildsCapturedHandshakeExactly() {
        byte[] expected = hex(
                "a5 a5 a0 00 23 00 32 16 ef 23 55 01 fb 7a 00 00 00 00 01 " +
                        "b3 31 2f 31 ff 33 37 62 62 62 61 64 65 34 63 33 64 " +
                        "36 33 36 30 00 02 50"
        );
        byte[] clientId = hex(
                "33 37 62 62 62 61 64 65 34 63 33 64 36 33 36 30 00"
        );

        byte[] actual = KirinFrameCodec.buildGetWithPayload(
                0,
                0xFB7A,
                0,
                "1/1",
                clientId
        );

        assertArrayEquals(expected, actual);
    }

    @Test
    public void buildsPrivacySafeUserInfoPayload() {
        byte[] expected = hex(
                "0a 18 30 31 32 33 34 35 36 37 38 39 61 62 63 64 65 66 " +
                        "30 31 32 33 34 35 36 37 " +
                        "12 10 38 39 61 62 63 64 65 66 30 31 32 33 34 35 36 37 " +
                        "1d 00 00 70 42 20 80 e2 cf aa 06"
        );

        byte[] actual = KirinPayloadCodec.buildUserInfo(
                "0123456789abcdef01234567",
                "89abcdef01234567",
                60.0f,
                1_700_000_000L
        );

        assertArrayEquals(expected, actual);
    }

    private static byte[] hex(String value) {
        String compact = value.replace(" ", "");
        byte[] bytes = new byte[compact.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) Integer.parseInt(
                    compact.substring(index * 2, index * 2 + 2),
                    16
            );
        }
        return bytes;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        for (int index = 0; index <= haystack.length - needle.length; index++) {
            int offset = 0;
            while (offset < needle.length && haystack[index + offset] == needle[offset]) {
                offset++;
            }
            if (offset == needle.length) {
                return true;
            }
        }
        return false;
    }
}
