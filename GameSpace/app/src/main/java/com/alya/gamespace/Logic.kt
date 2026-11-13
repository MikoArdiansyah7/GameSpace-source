package com.alya.gamespace

/** Logika murni (tanpa Android) supaya mudah diuji. */
object Logic {
    /** 1-29 merah, 30-59 kuning, 60-73 hijau, 74+ biru. */
    fun fpsColor(f: Float): Int {
        val v = f.toInt()
        return when {
            v <= 0 -> 0xFF9A96B8.toInt()
            v < 30 -> 0xFFFF4D5E.toInt()
            v < 60 -> 0xFFFFC53D.toInt()
            v < 74 -> 0xFF35E08A.toInt()
            else -> 0xFF4DA8FF.toInt()
        }
    }

    const val HYST = 3f

    /** Suhu pemicu pembatasan FPS: Seimbang 38°C, Performa 40°C. Hemat & Turbo tidak memakai. */
    fun trigger(mode: Int): Float = when (mode) { 1 -> 38f; 2 -> 40f; else -> 1000f }

    /** Histeresis: begitu terbatas, baru lepas saat suhu turun HYST derajat di bawah pemicu. */
    fun thermalNext(limited: Boolean, temp: Float, trigger: Float): Boolean =
        if (!limited) temp >= trigger else temp > trigger - HYST

    /** Batas FPS per mode, mengikuti refresh rate tertinggi layar yang sebenarnya. */
    fun modeCap(mode: Int, maxHz: Int): Int = when (mode) {
        0 -> minOf(60, maxHz)
        1 -> minOf(120, maxHz)
        else -> maxHz
    }

    fun effectiveCap(modeCap: Int, limited: Boolean, idle: Boolean): Int =
        if (limited || idle) minOf(modeCap, 60) else modeCap

    /** Indeks renderer: 0 Bawaan, 1 OpenGL, 2 SkiaGL, 3 Vulkan. Hasil: (renderer efektif, catatan). */
    fun resolveRenderer(sdk: Int, vulkanOk: Boolean, wanted: Int): Pair<Int, String?> = when (wanted) {
        1 -> if (sdk >= 29) Pair(2, "OpenGL klasik tidak ada di Android 10+, otomatis memakai SkiaGL (OpenGL ES)") else Pair(1, null)
        2 -> Pair(2, null)
        3 -> if (vulkanOk) Pair(3, null)
        else if (sdk >= 29) Pair(2, "Vulkan tidak didukung perangkat ini, otomatis memakai SkiaGL")
        else Pair(1, "Vulkan tidak didukung perangkat ini, otomatis memakai OpenGL")
        else -> Pair(0, null)
    }

    fun scaleName(percent: Int): String? = when (percent) {
        90 -> "DOWNSCALE_90"; 80 -> "DOWNSCALE_80"; 70 -> "DOWNSCALE_70"; 60 -> "DOWNSCALE_60"; 50 -> "DOWNSCALE_50"
        45 -> "DOWNSCALE_45"; 40 -> "DOWNSCALE_40"; 35 -> "DOWNSCALE_35"; 30 -> "DOWNSCALE_30"
        else -> null
    }

    /** Mode game sendiri (0..3) bila diatur, kalau tidak ikut mode global. */
    fun pickMode(perGame: Int, global: Int): Int = if (perGame in 0..3) perGame else global.coerceIn(0, 3)

    fun fmtDuration(sec: Int): String =
        if (sec >= 3600) (sec / 3600).toString() + " j " + (sec % 3600) / 60 + " mnt"
        else if (sec >= 60) (sec / 60).toString() + " mnt" else sec.toString() + " dtk"

    /** Ringkasan sesi: durasi, rata-rata FPS, suhu maks, selisih baterai. Bagian yang tidak diketahui dilewati. */
    fun sessionText(durSec: Int, avgFps: Float, maxTemp: Float, bat0: Int, bat1: Int): String {
        val sb = StringBuilder(fmtDuration(durSec))
        if (avgFps > 0f) sb.append(" • rata-rata ").append(Math.round(avgFps)).append(" FPS")
        if (maxTemp > 0f) sb.append(" • suhu maks ").append(Math.round(maxTemp * 10) / 10.0).append("°C")
        if (bat0 >= 0 && bat1 >= 0) {
            val d = bat1 - bat0
            sb.append(" • baterai ").append(bat0).append("→").append(bat1).append("% (").append(if (d >= 0) "+" else "").append(d).append(")")
        }
        return sb.toString()
    }

    /** Warna ping: <=50 ms hijau (bagus), 51-100 kuning (agak lag), >100 merah (jelek). Tak diketahui = abu-abu. */
    fun pingColor(ms: Int): Int = when {
        ms < 0 -> 0xFF9A96B8.toInt()
        ms <= 50 -> 0xFF35E08A.toInt()
        ms <= 100 -> 0xFFFFC53D.toInt()
        else -> 0xFFFF4D5E.toInt()
    }

    fun pingText(ms: Int): String = if (ms < 0) "--" else Math.min(ms, 999).toString() + "ms"

    // ───────── HUD Display gaya PC ─────────
    fun pcPct(v: Int): String = (if (v < 0) "--%" else v.coerceIn(0, 100).toString() + "%").padStart(4)
    fun pcWatt(w: Float): String = (if (w < 0f) "--W" else (Math.round(w * 10) / 10.0).toString() + "W").padStart(5)
    fun pcTemp(t: Float): String = ((Math.round(t * 10) / 10.0).toString() + "°C").padStart(6)
    fun pcFps(f: Float): String = (if (f > 0f) f.toInt().toString() else "--").padStart(3)

    /** Skala yang dipakai agar HUD selalu muat di sisa lebar layar (lebar HUD berbanding lurus dengan skala). */
    fun fitScale(natural: Float, avail: Float, scale: Float): Float =
        if (avail <= 0f || natural <= avail) scale else maxOf(0.4f, scale * avail / natural)

    /** Batas atas sumbu grafik FPS: kelipatan 30, minimal 60. */
    fun graphMax(maxVal: Float): Float = maxOf(60f, Math.ceil((maxVal * 1.1f / 30f).toDouble()).toFloat() * 30f)

    /** Label API grafis: VULKAN bila libvulkan termuat; selain itu SKIA/OPENGL dari pipeline HWUI; "--" bila tak terbaca. */
    fun rendererLabel(vulkanMapped: Boolean, mapsReadable: Boolean, gfx: String): String {
        val g = gfx.lowercase()
        return when {
            vulkanMapped || g.contains("vulkan") -> "VULKAN"
            g.contains("skia") -> "SKIA"
            g.contains("opengl") -> "OPENGL"
            mapsReadable -> "OPENGL"
            else -> "--"
        }
    }

    /** Tawarkan pembaruan hanya bila lebih baru dan belum dilewati pengguna. */
    fun updateAvailable(code: Int, current: Int, skipped: Int): Boolean = code > current && code != skipped
}

/** Pengaturan peringatan pengisian. */
class ChargeCfg(val unplugOn: Boolean, val unplugAt: Int, val hotOn: Boolean, val hotTemp: Float, val lowOn: Boolean)

/** Ingatan antar-pemeriksaan agar peringatan tidak spam. */
class ChargeMemo {
    var wasCharging = false
    var unplugAt = -1L      // -1 = belum pernah memperingatkan
    var fullWarned = false
    var hotAt = -1L
    var dangerAt = -1L
    var lowWarned = false
    var lastSample = -1L
    val hist = ArrayList<DoubleArray>()   // [waktu ms, suhu]
}

object ChargeLogic {
    const val NONE = 0
    const val UNPLUG = 1    // sudah mencapai persentase target
    const val FULL = 2      // 100%
    const val HOT = 3       // mengisi + main + suhu >= batas
    const val DANGER = 4    // batas + 3°C
    const val RISE = 5      // suhu naik cepat
    const val LOW = 6       // baterai rendah, tidak mengisi
    const val UNPLUG_COOL = 10 * 60_000L
    const val HOT_COOL = 3 * 60_000L
    const val DANGER_COOL = 90_000L

    /** Kenaikan suhu dibanding sampel ~3 menit lalu (toleransi sekitar 40 dtk); 0 bila data belum cukup. */
    fun rise(m: ChargeMemo, now: Long, temp: Float): Double {
        var best: DoubleArray? = null
        for (h in m.hist) {
            val age = now - h[0].toLong()
            if (age in 140_000L..220_000L) {
                if (best == null || Math.abs(age - 180_000L) < Math.abs(now - best[0].toLong() - 180_000L)) best = h
            }
        }
        return if (best == null) 0.0 else temp - best[1]
    }

    fun evaluate(m: ChargeMemo, level: Int, charging: Boolean, temp: Float, inGame: Boolean, cfg: ChargeCfg, now: Long): Int {
        if (m.lastSample < 0L || now - m.lastSample >= 10_000L) {
            m.lastSample = now
            m.hist.add(doubleArrayOf(now.toDouble(), temp.toDouble()))
            while (m.hist.isNotEmpty() && now - m.hist[0][0].toLong() > 6 * 60_000L) m.hist.removeAt(0)
        }
        if (charging != m.wasCharging) {
            m.wasCharging = charging
            m.unplugAt = -1L; m.fullWarned = false; m.hotAt = -1L; m.dangerAt = -1L
            if (charging) m.lowWarned = false
        }
        if (!charging) {
            if (level > 20) m.lowWarned = false
            if (cfg.lowOn && level <= 15 && !m.lowWarned) { m.lowWarned = true; return LOW }
            return NONE
        }
        if (cfg.unplugOn && level >= 100 && !m.fullWarned) { m.fullWarned = true; m.unplugAt = now; return FULL }
        if (inGame && cfg.hotOn) {
            if (temp >= cfg.hotTemp + 3f) {
                if (m.dangerAt < 0L || now - m.dangerAt >= DANGER_COOL) { m.dangerAt = now; m.hotAt = now; return DANGER }
            } else if (temp >= cfg.hotTemp) {
                if (m.hotAt < 0L || now - m.hotAt >= HOT_COOL) { m.hotAt = now; return HOT }
            } else if (temp >= 37f && (m.hotAt < 0L || now - m.hotAt >= HOT_COOL) && rise(m, now, temp) >= 2.0) {
                m.hotAt = now; return RISE
            }
        }
        if (cfg.unplugOn && level >= cfg.unplugAt && (m.unplugAt < 0L || now - m.unplugAt >= UNPLUG_COOL)) {
            m.unplugAt = now; return UNPLUG
        }
        return NONE
    }
}

object AppOpsLogic {
    private val pkgRe = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    /** Ambil nama paket dari keluaran `cmd appops query-op`. */
    fun parsePackages(out: String): Set<String> {
        val s = HashSet<String>()
        for (line in out.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("No ")) continue
            for (m in pkgRe.findAll(t)) s.add(m.value)
        }
        return s
    }

    /** App yang akan dibatasi = terpasang - dilindungi - yang sudah dibatasi pengguna sendiri. */
    fun targets(installed: List<String>, protect: Set<String>, already: Set<String>): List<String> {
        val out = ArrayList<String>()
        for (p in installed) {
            val n = p.trim()
            if (n.isEmpty() || !n.contains('.') || n in protect || n in already || n in out) continue
            out.add(n)
        }
        return out
    }
}
