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

/**
 * Battery readings over time ("epochMillis,percent,statusBits,millivolts"), to see whether charging actually
 * raises the level. Lines from before v1.2 have no millivolts column.
 */
object BatteryLog {
    data class Reading(val t: Long, val pct: Int, val status: Int, val mv: Int)

    private fun file(ctx: Context) = File(ctx.filesDir, "battery.csv")

    fun add(ctx: Context, pct: Int, status: Int, mv: Int) {
        if (pct < 0) return
        val f = file(ctx)
        val last = if (f.exists()) f.readLines().lastOrNull()?.let(::parse) else null
        val now = System.currentTimeMillis()
        // Keep a point when anything changes, otherwise one every 5 minutes.
        if (last != null && last.pct == pct && last.status == status && last.mv == mv && now - last.t < 300_000) return
        f.appendText("$now,$pct,$status,$mv\n")
    }

    fun parse(l: String): Reading? {
        val p = l.split(','); if (p.size !in 3..4) return null
        return try { Reading(p[0].toLong(), p[1].toInt(), p[2].toInt(), p.getOrNull(3)?.toInt() ?: -1) }
        catch (e: NumberFormatException) { null }
    }

    /** Readings from the last [hours] hours. */
    fun recent(ctx: Context, hours: Int = 24): List<Reading> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val from = System.currentTimeMillis() - hours * 3_600_000L
        return f.readLines().mapNotNull(::parse).filter { it.t >= from }
    }
}
