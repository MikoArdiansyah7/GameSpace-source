package com.alya.gamespace

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Logika murni statistik waktu bermain dan pengingat istirahat. Tanpa API Android supaya bisa dites di JVM. */
object PlayLogic {
    const val DAY_MS = 86_400_000L

    /** Keluar game kurang dari ini dianggap masih satu sesi main (cek chat, ganti app sebentar). */
    const val MERGE_GAP_MS = 5 * 60_000L

    private val DAY_NAMES = arrayOf("Min", "Sen", "Sel", "Rab", "Kam", "Jum", "Sab")

    /** Awal tiap hari (00:00 lokal) untuk [days] hari terakhir; elemen terakhir = hari ini. Benar juga saat jam berubah (DST). */
    fun dayStarts(now: Long, days: Int, tz: TimeZone): LongArray {
        val c = Calendar.getInstance(tz, Locale.US)
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val out = LongArray(days)
        for (i in days - 1 downTo 0) {
            out[i] = c.timeInMillis
            c.add(Calendar.DAY_OF_YEAR, -1)
        }
        return out
    }

    fun dayName(startMs: Long, tz: TimeZone): String {
        val c = Calendar.getInstance(tz, Locale.US)
        c.timeInMillis = startMs + 3_600_000L          // +1 jam: aman bila pukul 00:00 tidak ada pada hari DST tertentu
        return DAY_NAMES[c.get(Calendar.DAY_OF_WEEK) - 1]
    }

    /** "2j 14m", "1j", "45m", "<1m", "0m". */
    fun fmt(ms: Long): String {
        if (ms <= 0L) return "0m"
        val min = ms / 60_000L
        if (min < 1L) return "<1m"
        val h = min / 60L
        val m = min % 60L
        return if (h == 0L) m.toString() + "m" else if (m == 0L) h.toString() + "j" else h.toString() + "j " + m + "m"
    }

    class Day(val start: Long, val perPkg: Map<String, Long>) {
        val total: Long = perPkg.values.sum()
    }

    /** Hanya paket game, buang nilai <= 0, dan batasi 24 jam per game per hari (jaga-jaga data aneh dari sistem). */
    fun buildDay(start: Long, raw: Map<String, Long>, games: Set<String>): Day {
        val m = HashMap<String, Long>()
        for ((k, v) in raw) if (k in games && v > 0L) m[k] = Math.min(v, DAY_MS)
        return Day(start, m)
    }

    fun weekTotal(days: List<Day>): Long {
        var t = 0L
        for (d in days) t += d.total
        return t
    }

    /** Game teratas dari beberapa hari: (paket, ms), terbanyak dulu; waktu sama diurutkan menurut nama paket. */
    fun top(days: List<Day>, n: Int): List<Pair<String, Long>> {
        val m = HashMap<String, Long>()
        for (d in days) for ((k, v) in d.perPkg) m[k] = (m[k] ?: 0L) + v
        return m.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(n).map { Pair(it.key, it.value) }
    }

    /** Tinggi batang 0..1 relatif terhadap hari terbanyak. Hari yang ada waktunya minimal 0.06 supaya tetap terlihat. */
    fun barFractions(days: List<Day>): FloatArray {
        var mx = 0L
        for (d in days) if (d.total > mx) mx = d.total
        return FloatArray(days.size) { i ->
            val t = days[i].total
            if (mx <= 0L || t <= 0L) 0f else Math.max(0.06f, t.toFloat() / mx.toFloat())
        }
    }

    // ───────── pengingat istirahat ─────────
    /** Berapa kali pengingat seharusnya sudah muncul pada waktu main nonstop [elapsedMs] dengan selang [intervalMs]. */
    fun breaksDue(elapsedMs: Long, intervalMs: Long): Int =
        if (intervalMs <= 0L || elapsedMs <= 0L) 0 else (elapsedMs / intervalMs).toInt()

    /** Waktu main nonstop yang dilanjutkan saat kembali ke game: jeda pendek (< 5 menit) melanjutkan hitungan, jeda panjang mulai dari nol. */
    fun resume(prevMs: Long, gapMs: Long): Long = if (prevMs > 0L && gapMs in 0L until MERGE_GAP_MS) prevMs else 0L

    fun breakText(elapsedMs: Long): String = "Sudah main " + fmt(elapsedMs) + " nonstop. Yuk istirahat sebentar & rilekskan mata"
}

/** Logika murni daftar rekaman layar (nama file, ukuran, durasi). */
object RecLibLogic {
    private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des")
    private val NAME = Regex("GameSpace_(\\d{4})(\\d{2})(\\d{2})_(\\d{2})(\\d{2})(\\d{2})(?:_part(\\d+))?\\.mp4", RegexOption.IGNORE_CASE)

    /** Hanya berkas yang dibuat perekam Game Space. */
    fun isOurs(name: String): Boolean = name.startsWith("GameSpace_") && name.endsWith(".mp4", ignoreCase = true)

    fun sizeText(bytes: Long, loc: Locale = Locale.getDefault()): String {
        if (bytes < 1024L) return bytes.toString() + " B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(loc, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(loc, "%.1f MB", mb)
        return String.format(loc, "%.2f GB", mb / 1024.0)
    }

    fun durText(ms: Long): String {
        if (ms <= 0L) return "--:--"
        val s = ms / 1000L
        val h = s / 3600L
        val m = (s % 3600L) / 60L
        val ss = s % 60L
        return if (h > 0L) String.format(Locale.US, "%d:%02d:%02d", h, m, ss) else String.format(Locale.US, "%02d:%02d", m, ss)
    }

    /** GameSpace_20261110_233500_part2.mp4 -> "10 Nov 2026  23:35  •  bagian 2"; nama lain dikembalikan apa adanya. */
    fun title(name: String): String {
        val g = NAME.matchEntire(name)?.groupValues ?: return name
        val mon = g[2].toInt().coerceIn(1, 12)
        val base = g[3].toInt().toString() + " " + MONTHS[mon - 1] + " " + g[1] + "  " + g[4] + ":" + g[5]
        return if (g[7].isNotEmpty()) base + "  •  bagian " + g[7] else base
    }
}
