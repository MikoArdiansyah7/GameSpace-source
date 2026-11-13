package com.alya.gamespace

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import java.net.InetSocketAddress
import java.net.Socket

/** Nilai -1 = tidak diketahui. */
class Snap(
    val cpuGhz: Float, val cpuLoad: Int, val gpuMhz: Int, val gpuLoad: Int,
    val temp: Float, val cpuTemp: Float, val battery: Int, val ram: Int,
    val ping: Int, val fps: Float, val watt: Float, val charging: Boolean = false
)

object Perm {
    fun usage(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }
}

object Stats {
    private var lastPing = -1
    private var prevTotal = 0L
    private var prevIdle = 0L
    private var script: String? = null

    fun reset() { prevTotal = 0L; prevIdle = 0L; script = null }

    private fun script(ctx: Context): String {
        script?.let { return it }
        val h = Hw.ensure(ctx)
        val t = try { ctx.assets.open("stats.sh").bufferedReader().use { it.readText() } } catch (_: Throwable) { "" }
        val s = t.replace("@GFREQ@", h.gFreqNode.ifEmpty { "/dev/null" })
            .replace("@GLOAD@", h.gLoadNode.ifEmpty { "/dev/null" })
            .replace("@CTEMP@", h.cpuTempNode.ifEmpty { "/dev/null" })
        script = s
        return s
    }

    private fun nums(l: String): List<Long> = l.split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }

    class Raw { var cpuMax = 0L; var load = -1; var gKhz = 0L; var gLoad = -1; var cTemp = -1f }

    /** Murni (tanpa Android) agar bisa diuji. */
    fun parse(out: String, h: HwInfo): Raw {
        val r = Raw()
        var sec = ""
        for (raw in out.lines()) {
            val l = raw.trim()
            if (l.startsWith("@")) { sec = l; continue }
            if (l.isEmpty()) continue
            when (sec) {
                "@C" -> l.toLongOrNull()?.let { if (it > r.cpuMax) r.cpuMax = it }
                "@S" -> if (l.startsWith("cpu ")) {
                    val p = nums(l)
                    if (p.size >= 5) {
                        var total = 0L
                        for (i in 0 until minOf(8, p.size)) total += p[i]
                        val idle = p[3] + p[4]
                        if (prevTotal > 0L && total > prevTotal)
                            r.load = (100L * ((total - prevTotal) - (idle - prevIdle)) / (total - prevTotal)).toInt().coerceIn(0, 100)
                        prevTotal = total; prevIdle = idle
                    }
                }
                "@GF" -> { var best = 0L; for (v in nums(l)) if (v > best) best = v; if (best > 0) r.gKhz = Hw.toKhz(best) }
                "@GL" -> {
                    val p = nums(l)
                    if (p.isNotEmpty()) {
                        r.gLoad = if (h.gLoadNode.endsWith("gpubusy") && p.size >= 2 && p[1] > 0) (100L * p[0] / p[1]).toInt().coerceIn(0, 100)
                        else if (p[0] in 0..100) p[0].toInt() else -1
                    }
                }
                "@T" -> l.toLongOrNull()?.let { r.cTemp = if (it > 200) it / 1000f else it.toFloat() }
            }
        }
        return r
    }

    fun read(ctx: Context, game: String?, meter: FpsMeter, doPing: Boolean): Snap {
        val h = Hw.ensure(ctx)
        val raw = parse(Sh.exec(script(ctx), 6000), h)
        val cpuMax = raw.cpuMax; val load = raw.load; val gKhz = raw.gKhz; val gLoad = raw.gLoad; val cTemp = raw.cTemp
        val bat: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = (bat?.getIntExtra("temperature", 0) ?: 0) / 10f
        val level = bat?.getIntExtra("level", 0) ?: 0
        val volt = bat?.getIntExtra("voltage", 0) ?: 0
        val bst = bat?.getIntExtra("status", -1) ?: -1

        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val ram = if (mi.totalMem > 0) (100 * (mi.totalMem - mi.availMem) / mi.totalMem).toInt() else 0

        var watt = -1f
        try {
            val cur = (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (cur != Long.MIN_VALUE && cur != 0L && volt > 0) {
                val a = Math.abs(cur).toDouble()
                val amps = if (a > 20_000) a / 1e6 else a / 1e3
                watt = (amps * volt / 1000.0).toFloat()
            }
        } catch (_: Throwable) {}

        if (doPing) lastPing = try {
            val t = System.nanoTime()
            Socket().use { it.connect(InetSocketAddress("1.1.1.1", 443), 1000) }
            ((System.nanoTime() - t) / 1_000_000).toInt()
        } catch (_: Throwable) { -1 }

        val fps = if (game != null) meter.sample(game) else 0f
        return Snap(if (cpuMax > 0) cpuMax / 1_000_000f else -1f, load, (gKhz / 1000).toInt().let { if (it > 0) it else -1 },
            gLoad, temp, cTemp, level, ram, lastPing, fps, watt, bst == 2 || bst == 5)
    }
}

/** FPS: metode 1 `--latency` (timestamp frame), cadangan metode 2 `--timestats` (hitung totalFrames). */
class FpsMeter {
    private var layer: String? = null
    private var pkg = ""
    private var misses = 0
    private var useStats = false
    private var lastFrames = HashMap<String, Long>()
    private var lastT = 0L

    fun sample(game: String): Float {
        if (!ShizukuHelper.granted()) return -1f
        if (game != pkg) { pkg = game; layer = null; misses = 0; useStats = false; lastFrames = HashMap(); lastT = 0L }
        if (!useStats) {
            val f = latency(game)
            if (f > 0f) { misses = 0; AlyaAiService.fpsSource = "SurfaceFlinger --latency"; return f }
            if (++misses >= 3) {
                useStats = true
                Sh.exec("dumpsys SurfaceFlinger --timestats -enable")
                ActionLog.add("FPS: pindah ke metode timestats")
            }
            return 0f
        }
        return timestats(game)
    }

    private fun latency(game: String): Float {
        if (layer == null) layer = findLayer(game)
        val l = layer ?: return 0f
        val out = Sh.exec("dumpsys SurfaceFlinger --latency '$l'")
        val rows = out.lines().drop(1).mapNotNull { r ->
            val p = r.trim().split(Regex("\\s+"))
            if (p.size >= 3) p[1].toLongOrNull() else null
        }.filter { it > 0 && it != Long.MAX_VALUE }
        if (rows.size < 2) { layer = null; return 0f }
        val last = rows.max()
        val recent = rows.filter { last - it <= 1_000_000_000L }
        if (recent.size < 2) return 0f
        val span = (recent.max() - recent.min()) / 1e9
        return if (span <= 0) 0f else ((recent.size - 1) / span).toFloat()
    }

    private fun findLayer(game: String): String? {
        val list = Sh.exec("dumpsys SurfaceFlinger --list").lines().map { it.trim() }.filter { it.contains(game) }
        return list.firstOrNull { it.startsWith("SurfaceView") && !it.contains("Background") }
            ?: list.firstOrNull { !it.startsWith("Background") }
    }

    private fun timestats(game: String): Float {
        val out = Sh.exec("dumpsys SurfaceFlinger --timestats -dump -maxlayers 40")
        val now = System.nanoTime()
        var name = ""
        val cur = HashMap<String, Long>()
        for (raw in out.lines()) {
            val l = raw.trim()
            if (l.startsWith("layerName")) name = l.substringAfter("=").trim()
            else if (l.startsWith("totalFrames") && name.isNotEmpty()) cur[name] = l.substringAfter("=").trim().toLongOrNull() ?: 0L
        }
        var best = 0f
        if (lastT != 0L) {
            val dt = (now - lastT) / 1e9
            for ((k, v) in cur) if (k.contains(game)) {
                val d = v - (lastFrames[k] ?: v)
                val f = (d / dt).toFloat()
                if (f > best) best = f
            }
        }
        lastFrames = cur; lastT = now
        if (best > 0f) AlyaAiService.fpsSource = "SurfaceFlinger --timestats"
        return best
    }
}

/** Deteksi app di depan. */
object Foreground {
    private var since = 0L
    private val resumed = Regex("u\\d+ ([\\w.]+)/")

    fun poll(ctx: Context): String? {
        val now = System.currentTimeMillis()
        if (Perm.usage(ctx)) {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val from = if (since == 0L) now - 3_600_000 else since - 500
            since = now
            val ev = usm.queryEvents(from, now)
            val e = UsageEvents.Event()
            var last: String? = null
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == 1) last = e.packageName
            }
            return last
        }
        if (ShizukuHelper.granted()) {
            val o = Sh.exec("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -n1")
            return resumed.find(o)?.groupValues?.get(1)
        }
        return null
    }

    fun reset() { since = 0L }
}
