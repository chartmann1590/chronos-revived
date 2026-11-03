package com.chronosrevived

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Keeps the BLE link to the disc alive in the background and shows its state in a permanent notification.
 * Android only lets a foreground service hold a Bluetooth connection reliably when the app isn't on screen.
 */
class LinkService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val update: () -> Unit = { post() }
    private var lastText = ""

    // Re-render once a minute so "last seen 5 min ago" stays true.
    private val tick = object : Runnable {
        override fun run() { post(force = true); main.postDelayed(this, 60_000) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Chronos.init(this)
        createChannels(this)
        val n = build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(ID, n)
        Chronos.listeners += update
        main.postDelayed(tick, 60_000)
        Chronos.connect(autoConnect = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SYNC -> Chronos.sync()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Chronos.listeners -= update
        main.removeCallbacks(tick)
        super.onDestroy()
    }

    private var wasConnected: Boolean? = null

    private fun post(force: Boolean = false) {
        val n = build()
        val text = n.extras.getString(Notification.EXTRA_TITLE) + n.extras.getString(Notification.EXTRA_TEXT)
        if (force || text != lastText) {
            lastText = text
            getSystemService(NotificationManager::class.java).notify(ID, n)
        }
        // A separate, dismissable heads-up the moment a working link drops.
        val now = Chronos.connected
        if (wasConnected == true && !now && Chronos.address != null && !Chronos.dfuActive) alertLost()
        if (now) getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
        wasConnected = now
    }

    private fun alertLost() {
        // Turning Bluetooth off drops the link too; that's the user's doing, not the disc's, so stay quiet.
        val bt = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        if (bt == null || bt.state != android.bluetooth.BluetoothAdapter.STATE_ON) return
        val asleep = Chronos.link == Chronos.Link.ASLEEP
        val n = Notification.Builder(this, ALERTS)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(if (asleep) "Your Chronos went to sleep" else "Lost contact with your Chronos")
            .setContentText(if (asleep) "It reconnects when you move it." else "It's out of range or powered down. It reconnects when it's back.")
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ALERT_ID, n)
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun build(): Notification {
        val (title, text) = describe()
        val sync = PendingIntent.getService(
            this, 1, Intent(this, LinkService::class.java).setAction(ACTION_SYNC), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, STATUS)
            .setSmallIcon(if (Chronos.connected) android.R.drawable.stat_sys_data_bluetooth else android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null, if (Chronos.connected) "Sync now" else "Reconnect", sync).build())
            .build()
    }

    private fun describe(): Pair<String, String> {
        val c = Chronos
        if (c.address == null) return "Chronos: no disc paired" to "Open the app to find your disc."
        if (c.dfuActive) return "Chronos: installing firmware" to (if (c.dfuProgress >= 0) "${c.dfuProgress}%" else "Starting…")
        if (c.connected) {
            val bits = mutableListOf<String>()
            if (c.battery >= 0) bits += "Battery ${c.battery}%" + (if (c.millivolts > 0) " (%.2f V)".format(c.millivolts / 1000f) else "") + when {
                c.status and Proto.CHARGING != 0 -> ", charging"
                c.status and Proto.ON_CHARGER != 0 -> ", on charger"
                else -> ""
            }
            c.steps?.let { bits += "%,d steps today".format(it.total) }
            return "Chronos connected" to bits.joinToString(" · ").ifEmpty { "Reading the disc…" }
        }
        val seen = ago(c.lastSeen)
        return when (c.link) {
            Chronos.Link.BLUETOOTH_OFF -> "Chronos: Bluetooth is off" to "Turn Bluetooth on to reconnect."
            Chronos.Link.ASLEEP -> "Chronos asleep" to "Last seen $seen. Move it to wake it up."
            Chronos.Link.OUT_OF_RANGE -> "Chronos out of range" to "Last seen $seen. Waiting for it to come back."
            Chronos.Link.CONNECTING -> "Chronos: connecting…" to "Shake the disc if this takes a while."
            Chronos.Link.CONNECTED, Chronos.Link.NEVER ->
                "Chronos: waiting for disc" to (if (c.lastSeen > 0) "Last seen $seen." else "Shake the disc to wake it up.")
        }
    }

    companion object {
        const val ID = 7
        const val ALERT_ID = 8
        const val STATUS = "disc_status"
        const val ALERTS = "alerts"
        const val ACTION_SYNC = "com.chronosrevived.SYNC"

        fun ago(t: Long): String {
            if (t == 0L) return "never"
            val m = ((System.currentTimeMillis() - t) / 60_000).toInt()
            return when {
                m < 1 -> "just now"
                m < 60 -> "$m min ago"
                m < 48 * 60 -> "${m / 60} h ago"
                else -> "${m / 1440} days ago"
            }
        }

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.deleteNotificationChannel("status") // v1.0.0 used a low-importance channel that some launchers hide
            // Default importance so it shows in full in the shade, but silent: no sound or vibration on updates.
            nm.createNotificationChannel(NotificationChannel(STATUS, "Disc status", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Always-on status of your Chronos disc"; setShowBadge(false); setSound(null, null); enableVibration(false)
            })
            nm.createNotificationChannel(NotificationChannel(ALERTS, "Disconnect alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When the disc goes to sleep or out of range"
            })
        }

        /** Safe to call from anywhere; Android refuses background starts in some states, which we ignore. */
        fun start(ctx: Context) {
            try { ctx.startForegroundService(Intent(ctx, LinkService::class.java)) }
            catch (e: Exception) { Chronos.say("couldn't start background service: ${e.message}") }
        }
    }
}

/** Brings the background link back after a reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Chronos.run { init(ctx); address } != null) LinkService.start(ctx)
    }
}
