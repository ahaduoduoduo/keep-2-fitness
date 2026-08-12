package dev.c1bridge.app;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Builds the verified BLE BCP/CoAP subset used by the C1 Mini. */
public final class KirinFrameCodec {
    private static final byte[] CLIENT_ROUTE = {
            0x32, 0x16, (byte) 0xEF, 0x23
    };
    private static final int COAP_NON_TOKEN_5 = 0x55;
    private static final int COAP_GET = 0x01;
    private static final int COAP_PUT = 0x03;
    private static final int KIRIN_GET = 0x01;
    private static final int KIRIN_PUT = 0x02;

    private KirinFrameCodec() {
    }

    public static byte[] buildGet(int bcpSequence, int messageId, int requestId, String path) {
        return build(
                bcpSequence,
                COAP_GET,
                KIRIN_GET,
                messageId,
                requestId,
                path,
                null
        );
    }

    public static byte[] buildGetWithPayload(
            int bcpSequence,
            int messageId,
            int requestId,
            String path,
            byte[] payload
    ) {
        return build(
                bcpSequence,
                COAP_GET,
                KIRIN_GET,
                messageId,
                requestId,
                path,
                payload == null ? new byte[0] : payload
        );
    }

    public static byte[] buildPut(
            int bcpSequence,
            int messageId,
            int requestId,
            String path,
            byte[] payload
    ) {
        return build(
                bcpSequence,
                COAP_PUT,
                KIRIN_PUT,
                messageId,
                requestId,
                path,
                payload == null ? new byte[0] : payload
        );
    }

    public static boolean hasValidEnvelope(byte[] frame) {
        if (frame == null || frame.length < 8
                || frame[0] != (byte) 0xA5 || frame[1] != (byte) 0xA5) {
            return false;
        }
        int bodyLength = unsigned(frame[4]) | (unsigned(frame[5]) << 8);
        if (frame.length != 6 + bodyLength + 2) {
            return false;
        }
        int expected = unsigned(frame[frame.length - 2])
                | (unsigned(frame[frame.length - 1]) << 8);
        return crc16Xmodem(frame, 0, frame.length - 2) == expected;
    }

    private static byte[] build(
            int bcpSequence,
            int coapCode,
            int kirinMethod,
            int messageId,
            int requestId,
            String path,
            byte[] payload
    ) {
        byte[] pathBytes = path.getBytes(StandardCharsets.US_ASCII);
        if (pathBytes.length > 12) {
            throw new IllegalArgumentException("CoAP Uri-Path is too long for the compact encoder");
        }

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(CLIENT_ROUTE, 0, CLIENT_ROUTE.length);
        body.write(COAP_NON_TOKEN_5);
        body.write(coapCode);
        body.write((messageId >>> 8) & 0xFF);
        body.write(messageId & 0xFF);
        body.write(requestId & 0xFF);
        body.write((requestId >>> 8) & 0xFF);
        body.write((requestId >>> 16) & 0xFF);
        body.write((requestId >>> 24) & 0xFF);
        body.write(kirinMethod);
        body.write(0xB0 | pathBytes.length);
        body.write(pathBytes, 0, pathBytes.length);
        if (payload != null) {
            body.write(0xFF);
            body.write(payload, 0, payload.length);
        }

        byte[] bodyBytes = body.toByteArray();
        byte[] frame = new byte[6 + bodyBytes.length + 2];
        int sequence = bcpSequence & 0x1FFF;
        frame[0] = (byte) 0xA5;
        frame[1] = (byte) 0xA5;
        frame[2] = (byte) (0xA0 | ((sequence >>> 8) & 0x1F));
        frame[3] = (byte) sequence;
        frame[4] = (byte) bodyBytes.length;
        frame[5] = (byte) (bodyBytes.length >>> 8);
        System.arraycopy(bodyBytes, 0, frame, 6, bodyBytes.length);
        int crc = crc16Xmodem(frame, 0, frame.length - 2);
        frame[frame.length - 2] = (byte) crc;
        frame[frame.length - 1] = (byte) (crc >>> 8);
        return frame;
    }

    private static int crc16Xmodem(byte[] bytes, int offset, int length) {
        int crc = 0;
        for (int i = offset; i < offset + length; i++) {
            crc ^= unsigned(bytes[i]) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0
                        ? ((crc << 1) ^ 0x1021) & 0xFFFF
                        : (crc << 1) & 0xFFFF;
            }
        }
        return crc;
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }
}
