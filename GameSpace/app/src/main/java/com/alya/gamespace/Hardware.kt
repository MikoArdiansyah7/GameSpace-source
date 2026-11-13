package com.alya.gamespace

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

object ActionLog {
    private val lines = ArrayList<String>()
    @Synchronized fun add(s: String) {
        lines.add(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + s)
        if (lines.size > 80) lines.removeAt(0)
    }
    @Synchronized fun dump(n: Int = 40): String = lines.takeLast(n).joinToString("\n")
}

/** Setelan sistem yang kita ubah disimpan nilai aslinya, supaya bisa dipulihkan (juga setelah crash). */
object Cfg {
    private const val K = "orig_cfg"
    private fun load(ctx: Context): JSONObject = try { JSONObject(Prefs.str(ctx, K, "{}")) } catch (_: Throwable) { JSONObject() }

    fun get(ns: String, key: String): String {
        val out = Sh.exec("settings get $ns $key").trim()
        return out.lines().lastOrNull { it.isNotBlank() }?.trim() ?: "null"
    }

    fun put(ctx: Context, ns: String, key: String, value: String) {
        val j = load(ctx)
        val id = "$ns/$key"
        if (!j.has(id)) { j.put(id, get(ns, key)); Prefs.setStr(ctx, K, j.toString()) }
        Sh.exec("settings put $ns $key $value")
    }

    fun restore(ctx: Context, ns: String, key: String) {
        val j = load(ctx)
        val id = "$ns/$key"
        if (!j.has(id)) return
        applyOrig(ns, key, j.getString(id))
        j.remove(id)
        Prefs.setStr(ctx, K, j.toString())
    }

    fun restoreAll(ctx: Context) {
        val j = load(ctx)
        val ids = ArrayList<String>()
        val it = j.keys()
        while (it.hasNext()) ids.add(it.next())
        for (id in ids) {
            val p = id.split("/")
            if (p.size == 2) applyOrig(p[0], p[1], j.getString(id))
        }
        Prefs.setStr(ctx, K, "{}")
    }

    fun pending(ctx: Context): Int = load(ctx).length()

    private fun applyOrig(ns: String, key: String, v: String) {
        if (v == "null" || v.isEmpty()) Sh.exec("settings delete $ns $key") else Sh.exec("settings put $ns $key $v")
    }
}

class CpuPolicy(
    val path: String, val maxKhz: Long, val minKhz: Long, val smin: Long, val smax: Long,
    val gov: String, val canMin: Boolean, val canGov: Boolean, val freqs: List<Long>
)

class HwInfo {
    var uid = -1
    var model = ""; var brand = ""; var plat = ""; var soc = ""; var rel = ""
    var sdk = 0
    val cpus = ArrayList<CpuPolicy>()
    var gKind = "none"; var gDev = ""; var gFreqNode = ""; var gLoadNode = ""
    val gFreqs = ArrayList<Long>()          // kHz, naik
    val oppIdx = ArrayList<Int>()           // MediaTek: index OPP
    val oppKhz = ArrayList<Long>()
    var gCanLock = false
    var gedWritable = false
    var hasSu = false
    var cpuTempNode = ""
    var raw = ""
}

object Hw {
    @Volatile var info: HwInfo? = null
    @Volatile var cpuLocked = false
    @Volatile var gpuLockKhz = 0L

    private val oppRe = Regex("\\[(\\d+)\\]\\s*freq\\s*[:=]\\s*(\\d+)")

    fun toKhz(v: Long): Long = when {
        v >= 100_000_000L -> v / 1000
        v >= 10_000L -> v
        else -> v * 1000
    }

    fun ensure(ctx: Context): HwInfo = info ?: discover(ctx)

    fun discover(ctx: Context): HwInfo {
        val script = try { ctx.assets.open("discover.sh").bufferedReader().use { it.readText() } } catch (_: Throwable) { "" }
        val out = Sh.exec(script, 20_000)
        val h = parse(out)
        h.raw = out
        info = h
        ActionLog.add("Hardware: " + h.plat + " cpu=" + h.cpus.size + " gpu=" + h.gKind + " uid=" + h.uid)
        return h
    }

    fun parse(out: String): HwInfo {
        val h = HwInfo()
        val zones = ArrayList<Array<String>>()
        for (line in out.lines()) {
            val i = line.indexOf('=')
            if (i <= 0) continue
            val k = line.substring(0, i).trim()
            val v = line.substring(i + 1).trim()
            when (k) {
                "uid" -> h.uid = v.toIntOrNull() ?: -1
                "model" -> h.model = v
                "brand" -> h.brand = v
                "plat" -> h.plat = v
                "soc" -> h.soc = v
                "rel" -> h.rel = v
                "sdk" -> h.sdk = v.toIntOrNull() ?: 0
                "su" -> h.hasSu = true
                "cpu" -> {
                    val p = v.split("|")
                    if (p.size >= 9) {
                        val fr = p[8].split(",").mapNotNull { it.trim().toLongOrNull() }
                        h.cpus.add(CpuPolicy(p[0], p[1].toLongOrNull() ?: 0L, p[2].toLongOrNull() ?: 0L,
                            p[3].toLongOrNull() ?: 0L, p[4].toLongOrNull() ?: 0L, p[5], p[6] == "1", p[7] == "1", fr))
                    }
                }
                "gkind" -> h.gKind = v
                "gdev" -> h.gDev = v
                "gfreq" -> h.gFreqNode = v
                "gload" -> h.gLoadNode = v
                "gfreqs" -> for (n in v.split(",")) { val x = n.trim().toLongOrNull(); if (x != null && x > 0) h.gFreqs.add(toKhz(x)) }
                "gopp" -> for (m in oppRe.findAll(v)) {
                    h.oppIdx.add(m.groupValues[1].toInt()); h.oppKhz.add(m.groupValues[2].toLong())
                }
                "gw" -> h.gCanLock = v == "1"
                "ged" -> h.gedWritable = v == "1"
                "tz" -> { val p = v.split("|"); if (p.size >= 2) zones.add(arrayOf(p[0], p[1])) }
            }
        }
        if (h.gFreqs.isEmpty() && h.oppKhz.isNotEmpty()) h.gFreqs.addAll(h.oppKhz)
        Collections.sort(h.gFreqs)
        val uniq = ArrayList<Long>(); for (f in h.gFreqs) if (uniq.isEmpty() || uniq[uniq.size - 1] != f) uniq.add(f)
        h.gFreqs.clear(); h.gFreqs.addAll(uniq)
        var best = ""
        for (key in listOf("cpu", "soc", "tsens", "ap")) {
            for (z in zones) if (best.isEmpty() && z[1].lowercase().contains(key)) best = z[0]
            if (best.isNotEmpty()) break
        }
        if (best.isNotEmpty()) h.cpuTempNode = "$best/temp"
        return h
    }

    fun maxGpuKhz(ctx: Context): Long { val f = ensure(ctx).gFreqs; return if (f.isEmpty()) 0L else f[f.size - 1] }

    // ───────── CPU ─────────
    fun lockCpu(ctx: Context): Boolean {
        val h = ensure(ctx)
        if (h.cpus.isEmpty()) { ActionLog.add("CPU lock: policy cpufreq tidak ditemukan"); return false }
        if (Prefs.str(ctx, "bk_cpu").isEmpty())
            Prefs.setStr(ctx, "bk_cpu", h.cpus.joinToString(";") { it.path + "," + it.smin + "," + it.smax + "," + it.gov })
        val cmd = h.cpus.joinToString("; ") {
            "echo " + it.maxKhz + " > " + it.path + "/scaling_max_freq; echo " + it.maxKhz + " > " + it.path + "/scaling_min_freq"
        }
        Sh.exec(cmd)
        var n = verifyCpu(h)
        if (n == 0 && h.hasSu) { Sh.su(cmd); n = verifyCpu(h) }
        cpuLocked = n > 0
        ActionLog.add("CPU lock: " + n + "/" + h.cpus.size + " cluster terkunci" + if (n == 0) " (sysfs hanya-baca untuk shell)" else "")
        return n > 0
    }

    private fun verifyCpu(h: HwInfo): Int {
        val out = Sh.exec(h.cpus.joinToString("; ") { "cat " + it.path + "/scaling_min_freq" })
        val vals = out.lines().mapNotNull { it.trim().toLongOrNull() }
        var n = 0
        for (i in h.cpus.indices) if (i < vals.size && vals[i] >= h.cpus[i].maxKhz) n++
        return n
    }

    fun unlockCpu(ctx: Context) {
        val bk = Prefs.str(ctx, "bk_cpu")
        if (bk.isEmpty()) { cpuLocked = false; return }
        val cmd = bk.split(";").joinToString("; ") {
            val p = it.split(",")
            if (p.size >= 3) "echo " + p[2] + " > " + p[0] + "/scaling_max_freq; echo " + p[1] + " > " + p[0] + "/scaling_min_freq" else "true"
        }
        Sh.exec(cmd)
        if (info?.hasSu == true && cpuLocked) Sh.su(cmd)
        Prefs.setStr(ctx, "bk_cpu", "")
        cpuLocked = false
        ActionLog.add("CPU lock dilepas")
    }

    // ───────── GPU ─────────
    fun lockGpu(ctx: Context, khz: Long): Boolean {
        val h = ensure(ctx)
        if (khz <= 0L) { ActionLog.add("GPU lock: daftar frekuensi GPU tidak terbaca"); return false }
        var cmd: String
        when (h.gKind) {
            "kgsl", "devfreq" -> {
                val d = if (h.gKind == "kgsl") h.gDev + "/devfreq" else h.gDev
                if (Prefs.str(ctx, "bk_gpu").isEmpty()) {
                    val o = Sh.exec("cat $d/min_freq; cat $d/max_freq").lines().mapNotNull { it.trim().toLongOrNull() }
                    if (o.size >= 2) Prefs.setStr(ctx, "bk_gpu", "$d|" + o[0] + "|" + o[1])
                }
                val hz = khz * 1000
                cmd = "echo 0 > $d/min_freq; echo $hz > $d/max_freq; echo $hz > $d/min_freq"
                if (h.gKind == "kgsl") cmd += "; echo 1 > " + h.gDev + "/force_clk_on"
            }
            "mtk2" -> {
                val k = h.oppKhz.indexOf(khz)
                if (k < 0) { ActionLog.add("GPU lock: OPP " + khz + " tidak ada di tabel"); return false }
                cmd = "echo " + h.oppIdx[k] + " > /proc/gpufreqv2/fix_target_opp_index"
            }
            "mtk1" -> cmd = "echo $khz > /proc/gpufreq/gpufreq_opp_freq"
            else -> { ActionLog.add("GPU lock: jenis GPU tidak dikenali"); return false }
        }
        if (h.gedWritable) cmd += "; echo $khz > /sys/module/ged/parameters/gpu_cust_boost_freq; echo $khz > /sys/module/ged/parameters/gpu_cust_upbound_freq"
        val out = Sh.exec(cmd)
        var denied = out.contains("denied", true) || out.contains("Read-only", true) || out.contains("No such file", true) || out.contains("Operation not permitted", true)
        if (denied && h.hasSu) { Sh.su(cmd); denied = false }
        Thread.sleep(450)
        val cur = currentGpuKhz(h)
        val ok = if (cur > 0) cur >= khz * 97 / 100 && cur <= khz * 103 / 100 else !denied
        if (ok) gpuLockKhz = khz
        ActionLog.add("GPU lock " + (khz / 1000) + " MHz: " + (if (ok) "OK" else "gagal") + " (baca " + (cur / 1000) + " MHz)" + if (denied) " akses ditolak" else "")
        return ok
    }

    fun currentGpuKhz(h: HwInfo): Long {
        if (h.gFreqNode.isEmpty()) return 0L
        val out = Sh.exec("cat " + h.gFreqNode)
        var best = 0L
        for (t in out.split(Regex("\\s+"))) { val v = t.toLongOrNull(); if (v != null && v > best) best = v }
        return if (best > 0) toKhz(best) else 0L
    }

    fun unlockGpu(ctx: Context) {
        val h = info ?: return
        val bk = Prefs.str(ctx, "bk_gpu")
        when (h.gKind) {
            "kgsl", "devfreq" -> {
                val p = bk.split("|")
                if (p.size >= 3) Sh.exec("echo " + p[2] + " > " + p[0] + "/max_freq; echo " + p[1] + " > " + p[0] + "/min_freq")
                if (h.gKind == "kgsl") Sh.exec("echo 0 > " + h.gDev + "/force_clk_on")
            }
            "mtk2" -> Sh.exec("echo -1 > /proc/gpufreqv2/fix_target_opp_index")
            "mtk1" -> Sh.exec("echo 0 > /proc/gpufreq/gpufreq_opp_freq")
        }
        if (h.gedWritable) Sh.exec("echo 0 > /sys/module/ged/parameters/gpu_cust_boost_freq; echo 0 > /sys/module/ged/parameters/gpu_cust_upbound_freq")
        Prefs.setStr(ctx, "bk_gpu", "")
        if (gpuLockKhz > 0) ActionLog.add("GPU lock dilepas")
        gpuLockKhz = 0L
    }

    /** Dipanggil berkala: pastikan kunci CPU/GPU belum direset sistem. */
    fun reassert(ctx: Context) {
        val h = info ?: return
        if (cpuLocked && verifyCpu(h) == 0) { ActionLog.add("CPU lock hilang, mengunci ulang"); lockCpu(ctx) }
        if (gpuLockKhz > 0) {
            val cur = currentGpuKhz(h)
            if (cur > 0 && cur < gpuLockKhz * 97 / 100) { ActionLog.add("GPU lock hilang, mengunci ulang"); lockGpu(ctx, gpuLockKhz) }
        }
    }

    fun statusText(): String {
        val h = info ?: return "Hardware belum dipindai"
        val c = if (cpuLocked) "terkunci" else if (h.cpus.any { it.canMin }) "siap" else "tidak bisa"
        val g = if (gpuLockKhz > 0) (gpuLockKhz / 1000).toString() + " MHz" else if (h.gCanLock) "siap" else "tidak bisa"
        return "Kunci CPU: $c  •  GPU: $g"
    }
}
