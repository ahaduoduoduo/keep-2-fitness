package dev.c1bridge.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public final class C1SerialCodecTest {
    @Test
    public void validatesAndNormalizesFullSerial() {
        assertTrue(C1SerialCodec.isValid("ccabcd1234567890"));
        assertEquals("CCABCD1234567890", C1SerialCodec.normalize(" ccabcd1234567890 "));
        assertFalse(C1SerialCodec.isValid("CC_1234567890"));
        assertFalse(C1SerialCodec.isValid("ABABCD1234567890"));
    }

    @Test
    public void recognizesObservedC1MiniAdvertisedName() {
        assertTrue(C1SerialCodec.isC1MiniName("Keep_CC_12345678"));
        assertFalse(C1SerialCodec.isC1MiniName("C1 Bridge"));
        assertFalse(C1SerialCodec.isC1MiniName(null));
        assertTrue(C1SerialCodec.matchesAdvertisedName(
                "CCABCD0012345678",
                "Keep_CC_12345678"
        ));
        assertFalse(C1SerialCodec.matchesAdvertisedName(
                "CCABCD0087654321",
                "Keep_CC_12345678"
        ));
    }

    @Test
    public void extractsFullSerialAfterConfigByte() {
        byte[] serial = "CCABCD1234567890".getBytes(StandardCharsets.US_ASCII);
        byte[] manufacturerData = new byte[21];
        manufacturerData[0] = 0;
        System.arraycopy(serial, 0, manufacturerData, 1, serial.length);

        assertEquals(
                "CCABCD1234567890",
                C1SerialCodec.fromManufacturerData(manufacturerData)
        );
        assertNull(C1SerialCodec.fromManufacturerData(new byte[8]));
    }

    @Test
    public void extractsFullSerialFromKeepBikeQrPayload() {
        assertEquals(
                "CCABCD1234567890",
                C1SerialCodec.fromQrPayload(
                        "keep://puncheur/new?type=CC&sn=CCABCD1234567890&config=0&source=0"
                )
        );
        assertNull(C1SerialCodec.fromQrPayload(
                "https://example.com/?sn=CCABCD1234567890"
        ));
        assertNull(C1SerialCodec.fromQrPayload(
                "keep://puncheur/new?type=TR&sn=CCABCD1234567890"
        ));
    }
}
