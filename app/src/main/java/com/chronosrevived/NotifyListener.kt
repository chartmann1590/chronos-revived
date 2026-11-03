package com.chronosrevived

import android.app.Notification
import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Forwards phone notifications to the disc as a light + buzz.
 * Being a bound system service it also keeps our process (and the BLE link) alive in the background.
 */
class NotifyListener : NotificationListenerService() {
    private val lastBuzz = HashMap<String, Long>()

    override fun onListenerConnected() {
        Chronos.init(this)
        Chronos.connect(autoConnect = true)
        if (Chronos.address != null) LinkService.start(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        val call = n.category == Notification.CATEGORY_CALL // incoming calls are "ongoing" but must buzz
        if ((sbn.isOngoing && !call) || sbn.packageName == packageName) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ranking = Ranking()
        if (currentRanking?.getRanking(sbn.key, ranking) == true &&
            ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return // silent notifications
        val now = System.currentTimeMillis()
        if (now - (lastBuzz[sbn.key] ?: 0) < 5000) return // apps re-post the same notification a lot
        lastBuzz[sbn.key] = now
        val style = Settings(this).appStyles[sbn.packageName] ?: return // only apps the user picked
        Chronos.say("notification from ${sbn.packageName}")
        Chronos.notify(style.first, style.second)
    }
}

class Settings(ctx: android.content.Context) {
    private val p = ctx.getSharedPreferences("chronos", 0)
    var mediaTaps: Boolean
        get() = p.getBoolean("mediaTaps", true)
        set(v) { p.edit().putBoolean("mediaTaps", v).apply() }
    var findPhone: Boolean
        get() = p.getBoolean("findPhone", true)
        set(v) { p.edit().putBoolean("findPhone", v).apply() }
    /** Apps that buzz the disc, each with its own light colour and pulse count. */
    var appStyles: Map<String, Pair<Proto.Color, Int>>
        get() = p.getStringSet("apps", emptySet())!!.mapNotNull {
            val (pkg, c, n) = it.split('|').takeIf { parts -> parts.size == 3 } ?: return@mapNotNull null
            pkg to (Proto.Color.valueOf(c) to n.toInt())
        }.toMap()
        set(v) { p.edit().putStringSet("apps", v.map { (k, s) -> "$k|${s.first.name}|${s.second}" }.toSet()).apply() }

    /** First run: texts and calls buzz out of the box. */
    fun seedDefaults(ctx: android.content.Context) {
        if (p.getBoolean("seeded", false)) return
        val defaults = buildMap {
            android.provider.Telephony.Sms.getDefaultSmsPackage(ctx)?.let { put(it, Proto.Color.GREEN to 2) }
            (ctx.getSystemService(android.content.Context.TELECOM_SERVICE) as? android.telecom.TelecomManager)
                ?.defaultDialerPackage?.let { put(it, Proto.Color.WHITE to 3) }
        }
        appStyles = defaults
        p.edit().putBoolean("seeded", true).apply()
    }
}
