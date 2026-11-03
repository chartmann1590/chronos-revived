package com.chronosrevived

import java.util.UUID

/** Chronos disc BLE protocol, recovered from com.wearchronos.chronos v1.0. See PROTOCOL.md. */
object Proto {
    private fun uuid(s: String) = UUID.fromString(s)
    val SERVICE = uuid("beb81000-b1b1-5b9d-b4a0-c030b58bc170")
    val STATUS = uuid("beb81001-b1b1-5b9d-b4a0-c030b58bc170")   // read + notify, u16 LE bitfield
    val COMMAND = uuid("beb81002-b1b1-5b9d-b4a0-c030b58bc170")  // write
    val RESPONSE = uuid("beb81003-b1b1-5b9d-b4a0-c030b58bc170") // notify
    val DFU_FACTORY = uuid("beb81530-b1b1-5b9d-b4a0-c030b58bc170") // present only in bootloader mode
    val BATTERY_SVC = uuid("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL = uuid("00002a19-0000-1000-8000-00805f9b34fb")
    val INFO_SVC = uuid("0000180a-0000-1000-8000-00805f9b34fb")
    val FIRMWARE_REV = uuid("00002a26-0000-1000-8000-00805f9b34fb")
    val CCCD = uuid("00002902-0000-1000-8000-00805f9b34fb")

    // Status bits
    const val BUSY = 1; const val CHARGING = 2; const val ON_CHARGER = 4; const val FACE_REPEATED_TAP = 8
    const val LED_OFF = 16; const val LED_LOW = 32; const val FACE_DOUBLE_TAP = 512
    const val CROWN_DOUBLE_TAP = 8192; const val CROWN_TRIPLE_TAP = 16384

    // Gesture features the disc can be told to report (opcode 0xB0..0xB3)
    enum class Gesture(val op: Int) { SILENCE_CALL(0xB0), CAMERA(0xB1), MEDIA(0xB2), FIND_PHONE(0xB3) }

    /** LED colour = bit3 R, bit2 G, bit1 B, plus 0x10. Index order matches the original app. */
    enum class Color(val code: Int) {
        RED(0x18), YELLOW(0x1C), GREEN(0x14), CYAN(0x16), BLUE(0x12), MAGENTA(0x1A), WHITE(0x1E), DARK(0x10)
    }

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    fun brightness(level: Int) = b(0x97, 1, level and 0xFF)
    fun currentSteps() = b(0x9C, 0)
    fun historicalSteps() = b(0x9D, 0)
    fun gesture(g: Gesture, on: Boolean) = b(g.op, 1, if (on) 1 else 0)
    val PLAY = b(0x95, 4, 0x61, 1, 0x40, 1)
    /** Firmware's hidden "extended status" mode (opcode A0): status grows to 8 bytes and is pushed every 0.5 s. */
    fun extendedStatus(on: Boolean) = b(0xA0, 1, if (on) 1 else 0)

    /** LED program for a notification. pattern 1..3 = number of flashes/pulses. */
    fun light(color: Color, pattern: Int): ByteArray {
        require(pattern in 1..3)
        val p4 = 0x80 + pattern
        val p8 = intArrayOf(0x18, 0x06, 0x03)[pattern - 1]
        val c = color.code
        return b(0x91, 0x11, 0x61, 1, p4, c, 0xFF, 0x2F, p8, c, 4, 0x2F, 2, c, 2, 0x2F, 2, 0x20, p8)
    }

    /** Vibration program for a notification. */
    fun vibrate(pattern: Int): ByteArray = when (pattern) {
        1 -> b(0x90, 10, 0x40, 1, 0x2F, 0x2F, 0, 0, 0, 0, 0, 0)
        2 -> b(0x90, 10, 0x40, 1, 0x0E, 0x8F, 0x0E, 0, 0, 0, 0, 0)
        3 -> b(0x90, 10, 0x40, 1, 0x31, 0x82, 0x31, 0x82, 0x31, 0, 0, 0)
        else -> throw IllegalArgumentException("pattern 1..3")
    }

    sealed interface Event
    data class Steps(val a: Int, val b: Int) : Event { val total get() = a + b }
    /** One stored record; [remaining] counts down to 1 on the last record, 0 = none. */
    data class History(val a: Int, val b: Int, val remaining: Int, val secondsAgo: Long) : Event
    data class Gestures(val bits: Int) : Event
    data class Unknown(val raw: ByteArray) : Event

    private fun u8(v: ByteArray, i: Int) = v[i].toInt() and 0xFF
    private fun be16(v: ByteArray, i: Int) = (u8(v, i) shl 8) or u8(v, i + 1)
    private fun be32(v: ByteArray, i: Int) = (be16(v, i).toLong() shl 16) or be16(v, i + 2).toLong()

    /**
     * Extended status tail (firmware 0x1a9e4 / 0x1cece): the disc's own battery measurement.
     * Bytes 2-3 = averaged cell millivolts (raw * 0x4571 >> 12, 4-sample mean), 6-7 = latest raw ADC count.
     * Bytes 4-5 come from the accelerometer driver and aren't used here.
     */
    data class Power(val mv: Int, val adc: Int)
    fun power(v: ByteArray): Power? = if (v.size >= 8) Power(be16(v, 2), be16(v, 6)) else null

    fun status(v: ByteArray) = u8(v, 0) or (if (v.size > 1) u8(v, 1) shl 8 else 0)

    fun parseResponse(v: ByteArray): Event = try {
        when (u8(v, 0)) {
            0x9C -> Steps(be16(v, 2), be16(v, 4))
            0x9D -> History(be16(v, 7), be16(v, 9), u8(v, 1), be32(v, 3) / 20)
            0xB0 -> Gestures(u8(v, 1) or (u8(v, 2) shl 8))
            else -> Unknown(v)
        }
    } catch (e: IndexOutOfBoundsException) { Unknown(v) }

    fun hex(v: ByteArray) = v.joinToString(" ") { "%02X".format(it) }
}
