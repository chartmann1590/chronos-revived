package com.chronosrevived

import android.content.Context
import java.io.File
import java.util.Calendar

/** Step history pulled off the disc, kept as "epochMillis,a,b" lines. */
object StepLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "steps.csv")

    fun save(ctx: Context, records: List<Proto.History>) {
        if (records.isEmpty()) return
        val now = System.currentTimeMillis()
        file(ctx).appendText(records.joinToString("") { "${now - it.secondsAgo * 1000},${it.a},${it.b}\n" })
        Chronos.say("saved ${records.size} history records")
    }

    /** Steps per day (yyyy-mm-dd -> steps), newest first. */
    fun daily(ctx: Context): List<Pair<String, Int>> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val cal = Calendar.getInstance()
        return f.readLines().mapNotNull { line ->
            val p = line.split(',')
            if (p.size != 3) return@mapNotNull null
            cal.timeInMillis = p[0].toLong()
            "%04d-%02d-%02d".format(cal[Calendar.YEAR], cal[Calendar.MONTH] + 1, cal[Calendar.DAY_OF_MONTH]) to
                p[1].toInt() + p[2].toInt()
        }.groupBy({ it.first }, { it.second }).map { it.key to it.value.sum() }.sortedByDescending { it.first }
    }
}

/** Battery readings over time ("epochMillis,percent,statusBits"), to see whether charging actually raises the level. */
object BatteryLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "battery.csv")

    fun add(ctx: Context, pct: Int, status: Int) {
        val f = file(ctx)
        val last = if (f.exists()) f.readLines().lastOrNull()?.split(',') else null
        val now = System.currentTimeMillis()
        // Keep a point when anything changes, otherwise one every 5 minutes.
        if (last != null && last[1].toInt() == pct && last[2].toInt() == status && now - last[0].toLong() < 300_000) return
        f.appendText("$now,$pct,$status\n")
    }

    /** Readings from the last [hours] hours as (millis, percent, status). */
    fun recent(ctx: Context, hours: Int = 24): List<Triple<Long, Int, Int>> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val from = System.currentTimeMillis() - hours * 3_600_000L
        return f.readLines().mapNotNull { l ->
            val p = l.split(','); if (p.size != 3) return@mapNotNull null
            Triple(p[0].toLong(), p[1].toInt(), p[2].toInt()).takeIf { it.first >= from }
        }
    }
}
