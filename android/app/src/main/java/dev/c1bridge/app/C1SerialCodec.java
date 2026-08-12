package dev.c1bridge.app;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Validates QR serials and extracts the full serial from Kirin manufacturer data. */
final class C1SerialCodec {
    static final int SERIAL_LENGTH = 16;
    private static final String ADVERTISED_NAME_PREFIX = "Keep_CC_";

    private C1SerialCodec() {
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    static boolean isValid(String value) {
        String normalized = normalize(value);
        if (normalized.length() != SERIAL_LENGTH || !normalized.startsWith("CC")) {
            return false;
        }
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            if (!(current >= 'A' && current <= 'Z')
                    && !(current >= '0' && current <= '9')) {
                return false;
            }
        }
        return true;
    }

    static String fromQrPayload(String payload) {
        if (payload == null || !payload.startsWith("keep://puncheur/new?")) {
            return null;
        }
        String query = payload.substring(payload.indexOf('?') + 1);
        String serial = null;
        String type = null;
        for (String part : query.split("&")) {
            int separator = part.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = part.substring(0, separator);
            String value = part.substring(separator + 1);
            if ("sn".equals(key)) {
                serial = normalize(value);
            } else if ("type".equals(key)) {
                type = normalize(value);
            }
        }
        return (type == null || "CC".equals(type)) && isValid(serial) ? serial : null;
    }

    static boolean isC1MiniName(String value) {
        return value != null && value.startsWith(ADVERTISED_NAME_PREFIX);
    }

    static boolean matchesAdvertisedName(String serial, String advertisedName) {
        String normalized = normalize(serial);
        if (!isValid(normalized) || !isC1MiniName(advertisedName)) {
            return false;
        }
        String suffix = advertisedName.substring(ADVERTISED_NAME_PREFIX.length());
        return suffix.length() >= 8 && normalized.endsWith(suffix);
    }

    static String fromManufacturerData(byte[] data) {
        // Android removes the two-byte manufacturer ID. Kirin then stores:
        // one config byte, sixteen ASCII SN bytes, followed by optional network data.
        if (data == null || data.length < SERIAL_LENGTH + 1) {
            return null;
        }
        String candidate = new String(
                data,
                1,
                SERIAL_LENGTH,
                StandardCharsets.US_ASCII
        );
        return isValid(candidate) ? normalize(candidate) : null;
    }
}
