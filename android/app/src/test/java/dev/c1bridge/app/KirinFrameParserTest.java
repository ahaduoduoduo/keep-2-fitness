package dev.c1bridge.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class KirinFrameParserTest {
    @Test
    public void parsesDeviceResistanceFinishedEvent() {
        byte[] frame = hex(
                "a5 a5 a0 13 1c 00 ef 23 32 16 55 45 cf 4a 65 00 00 00 04 " +
                        "b5 31 30 36 2f 36 ff 08 09 18 01 28 01 40 01 46 a3"
        );

        KirinFrameParser.Result result = new KirinFrameParser().parse(frame);

        assertNotNull(result);
        assertEquals(Integer.valueOf(9), result.resistance);
        assertTrue(result.resistanceChangedByDevice);
        assertTrue(result.resistanceChangeFinished);
    }

    @Test
    public void parsesCycleConfigAndResistanceLimit() {
        byte[] frame = hex(
                "a5 a5 a0 0e 1b 00 ef 23 32 16 55 45 fb 86 33 04 00 00 01 " +
                        "b5 31 30 36 2f 35 ff 08 12 18 90 1c 30 01 cb 32"
        );

        KirinFrameParser.Result result = new KirinFrameParser().parse(frame);

        assertNotNull(result);
        assertNotNull(result.cycleConfig);
        assertEquals(18, result.cycleConfig.maxResistance);
        assertEquals(3600, result.cycleConfig.pauseTimeoutSeconds);
        assertTrue(result.cycleConfig.buzzerOn);
    }

    @Test
    public void parsesSuccessfulControlAuthorizationResponse() {
        byte[] frame = hex(
                "a5 a5 a0 03 13 00 ef 23 32 16 55 45 fb 7d 2e 04 00 00 02 " +
                        "b5 31 30 36 2f 33 1c d6"
        );

        KirinFrameParser.Result result = new KirinFrameParser().parse(frame);

        assertNotNull(result);
        assertEquals(Integer.valueOf(0x45), result.authorizationCoapCode);
    }

    @Test
    public void parsesUnauthorizedResistanceResponse() {
        byte[] frame = hex(
                "a5 a5 a0 26 13 00 ef 23 32 16 55 81 9f 56 26 00 00 00 02 " +
                        "b5 31 30 36 2f 36 d0 aa"
        );

        KirinFrameParser.Result result = new KirinFrameParser().parse(frame);

        assertNotNull(result);
        assertEquals(Integer.valueOf(0x81), result.controlCoapCode);
    }

    @Test
    public void parsesTrainingStatusPayload() {
        byte[] frame = KirinFrameCodec.buildPut(
                0,
                1,
                2,
                "106/4",
                KirinPayloadCodec.buildTrainingStatus(4)
        );

        KirinFrameParser.Result result = new KirinFrameParser().parse(frame);

        assertNotNull(result);
        assertEquals(Integer.valueOf(4), result.trainingStatus);
        assertEquals(Integer.valueOf(3), result.trainingCoapCode);
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
}
