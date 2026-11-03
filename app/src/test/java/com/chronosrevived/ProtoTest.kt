package com.chronosrevived

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtoTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun commandsMatchOriginalApp() {
        // Bytes copied from ChronosDevice.ILLUMINATION_VALUES[GREEN] with pattern 2 patch applied.
        assertArrayEquals(b(-111, 17, 97, 1, -126, 20, -1, 47, 6, 20, 4, 47, 2, 20, 2, 47, 2, 32, 6),
            Proto.light(Proto.Color.GREEN, 2))
        assertArrayEquals(b(-112, 10, 64, 1, 49, -126, 49, -126, 49, 0, 0, 0), Proto.vibrate(3))
        assertArrayEquals(b(-105, 1, -1), Proto.brightness(255))
        assertArrayEquals(b(-78, 1, 1), Proto.gesture(Proto.Gesture.MEDIA, true))
    }

    @Test fun parsesResponses() {
        assertEquals(Proto.Steps(0x0102, 0x0304), Proto.parseResponse(b(0x9C, 4, 1, 2, 3, 4)))
        // remaining=3, ticks=0x000004B0 (1200/20 = 60s ago), a=10, b=5
        assertEquals(Proto.History(10, 5, 3, 60), Proto.parseResponse(b(0x9D, 3, 0, 0, 0, 4, 0xB0, 0, 10, 0, 5)))
        assertEquals(0x0206, Proto.status(b(6, 2)))
        assert(Proto.parseResponse(b(0x9C)) is Proto.Unknown)
    }

    @Test fun parsesExtendedStatus() {
        val v = byteArrayOf(0x04, 0x00, 0x0E, 0x42, 0x00, 0x00, 0x01, 0x2C) // on charger, 3650 mV, charger 300
        assertEquals(Proto.Power(3650, 300), Proto.power(v))
        assertEquals(4, Proto.status(v))
        assertEquals(null, Proto.power(byteArrayOf(0x04, 0x00)))
        assertArrayEquals(byteArrayOf(0xA0.toByte(), 1, 1), Proto.extendedStatus(true))
    }

    @Test fun appAddressIsBootloaderMinusOne() {
        assertEquals("F3:33:9F:56:DD:AA", Firmware.appAddress("F3:33:9F:56:DD:AB"))
        assertEquals("F3:33:9F:56:FF:FF", Firmware.appAddress("F3:33:9F:56:00:00"))
    }
}
