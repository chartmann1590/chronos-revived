package com.chronosrevived

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import no.nordicsemi.android.dfu.DfuBaseService
import no.nordicsemi.android.dfu.DfuProgressListenerAdapter
import no.nordicsemi.android.dfu.DfuServiceInitiator
import no.nordicsemi.android.dfu.DfuServiceListenerHelper
import java.util.UUID

class DfuService : DfuBaseService() {
    override fun getNotificationTarget(): Class<out Activity> = MainActivity::class.java
}

/**
 * Flashes the original Chronos firmware over Nordic legacy DFU. The firmware is Chronos's own and is not
 * redistributed with this app: the user picks the original Chronos APK (or its firmware.zip) and we extract it.
 * The Chronos bootloader uses vendor UUIDs instead of Nordic's 0000153x-1212-efde-1523-785feabcd123.
 */
object Firmware {
    private fun u(s: String) = UUID.fromString("beb8$s-b1b1-5b9d-b4a0-c030b58bc170")

    private fun cached(ctx: Context) = java.io.File(ctx.filesDir, "firmware.zip")
    fun available(ctx: Context) = cached(ctx).exists()

    /**
     * Accepts either the original Chronos APK (res/raw/firmware.zip inside it) or a DFU zip itself.
     * Returns false if the file doesn't contain Chronos firmware.
     */
    fun importFrom(ctx: Context, uri: android.net.Uri): Boolean {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
        val fw = entry(bytes, "res/raw/firmware.zip") ?: bytes
        if (entry(fw, "manifest.json") == null || entry(fw, "chronos.bin") == null) return false
        cached(ctx).writeBytes(fw)
        Chronos.say("Firmware imported (${fw.size} bytes)")
        return true
    }

    private fun entry(zip: ByteArray, name: String): ByteArray? = try {
        java.util.zip.ZipInputStream(zip.inputStream()).use { z ->
            generateSequence { z.nextEntry }.firstOrNull { it.name == name }?.let { z.readBytes() }
        }
    } catch (e: java.io.IOException) { null }

    fun install(ctx: Context) {
        val addr = Chronos.address ?: return
        if (!available(ctx)) { Chronos.say("No firmware imported yet"); return }
        val app = ctx.applicationContext
        Chronos.dfuActive = true
        Chronos.dfuProgress = 0
        Chronos.release()
        DfuServiceListenerHelper.registerProgressListener(app, listener)
        // Settings copied from the original app's ChronosDevice.updateFirmware().
        Handler(Looper.getMainLooper()).postDelayed({
            DfuServiceInitiator(addr)
                .setDeviceName("Chronos")
                .setKeepBond(false)
                .setDisableNotification(true)
                .setForeground(false)
                .setPacketsReceiptNotificationsEnabled(true)
                .setPacketsReceiptNotificationsValue(12)
                .setCustomUuidsForLegacyDfu(u("1530"), u("1531"), u("1532"), u("1534"))
                .setZip(cached(app).absolutePath)
                .start(app, DfuService::class.java)
            Chronos.say("Firmware install started")
        }, 1000)
    }

    /** After a factory flash the app firmware advertises at bootloader address - 1 (low 16 bits). */
    fun appAddress(bootloader: String): String {
        val low = bootloader.takeLast(5).replace(":", "").toInt(16) - 1
        val hex = "%04X".format(low and 0xFFFF)
        return bootloader.dropLast(5) + hex.substring(0, 2) + ":" + hex.substring(2)
    }

    private val listener = object : DfuProgressListenerAdapter() {
        override fun onProgressChanged(a: String, percent: Int, s: Float, avg: Float, part: Int, parts: Int) {
            Chronos.dfuProgress = percent
            if (percent % 10 == 0) Chronos.say("Firmware $percent%")
        }
        override fun onDfuCompleted(a: String) = finish("Firmware installed", true)
        override fun onDfuAborted(a: String) = finish("Firmware install aborted", false)
        override fun onError(a: String, error: Int, type: Int, message: String?) =
            finish("Firmware install error $error: $message", false)
    }

    private fun finish(msg: String, ok: Boolean) {
        Chronos.say(msg)
        Chronos.dfuActive = false
        Chronos.dfuProgress = -1
        val addr = Chronos.address ?: return
        if (ok) {
            Chronos.address = appAddress(addr)
            Chronos.say("Disc now expected at ${Chronos.address}")
        }
        Handler(Looper.getMainLooper()).postDelayed({ Chronos.connect() }, 3000)
    }
}
