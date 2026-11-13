package com.alya.gamespace

import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.TimeZone

/** Waktu bermain game di perpustakaan, dibaca dari statistik penggunaan sistem (butuh izin Akses penggunaan). */
object PlayStats {
    /** [days] hari terakhir (elemen terakhir = hari ini). null bila izin belum diberikan. Panggil dari thread latar. */
    fun week(ctx: Context, games: Set<String>, days: Int = 7): List<PlayLogic.Day>? {
        if (!Perm.usage(ctx)) return null
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val starts = PlayLogic.dayStarts(now, days, TimeZone.getDefault())
        val out = ArrayList<PlayLogic.Day>()
        for (i in 0 until days) {
            val from = starts[i]
            val to = if (i == days - 1) now else starts[i + 1]
            val raw = HashMap<String, Long>()
            try {
                for ((pkg, s) in usm.queryAndAggregateUsageStats(from, to)) if (pkg in games) raw[pkg] = s.totalTimeInForeground
            } catch (_: Throwable) {}
            out.add(PlayLogic.buildDay(from, raw, games))
        }
        return out
    }
}
