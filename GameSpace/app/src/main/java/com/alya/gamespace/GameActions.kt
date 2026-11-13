package com.alya.gamespace

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.widget.Toast

/** Semua aksi sistem. Panggil dari thread background. */
class GameActions(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile var screenRecOn = false

    fun toast(msg: String) { main.post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() } }

    private fun needShizuku(): Boolean {
        if (Sh.granted()) return false
        toast("Fitur ini butuh Shizuku aktif & diizinkan")
        return true
    }

    /** Refresh rate tertinggi layar pada resolusi saat ini (jangan asal bilang 120). */
    fun maxHz(): Int {
        var m = 60f
        try {
            val d = (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(0)
            val cur = d.mode
            for (mode in d.supportedModes)
                if (mode.physicalWidth == cur.physicalWidth && mode.physicalHeight == cur.physicalHeight && mode.refreshRate > m) m = mode.refreshRate
        } catch (_: Throwable) {}
        return Math.round(m)
    }

    fun modeCap(mode: Int) = Logic.modeCap(mode, maxHz())

    private fun fixed(on: Boolean) { Sh.exec("cmd power set-fixed-performance-mode-enabled $on") }
    private fun thermal(override: Boolean) {
        Sh.exec(if (override) "cmd thermalservice override-status 0" else "cmd thermalservice reset")
    }
    private fun gmOf(mode: Int) = when (mode) { 0 -> 3; 1 -> 1; else -> 2 }
    private fun unlockHw() { Hw.unlockCpu(ctx); Hw.unlockGpu(ctx) }

    private fun gameMode(game: String?, gm: Int, cap: Int) {
        if (game == null) return
        if (cap in 1 until maxHz()) Sh.exec("cmd game set --mode $gm --fps $cap --user 0 $game")
        else Sh.exec("cmd game reset --user 0 $game; cmd game set --mode $gm --user 0 $game")
    }

    /** Hemat/Seimbang: hanya membatasi puncak. Performa/Turbo: layar dikunci di batas (gacor). */
    fun applyRefresh(mode: Int, cap: Int) {
        val hz = maxHz()
        val v = cap.toFloat().toString()
        if (mode >= 2) {
            Cfg.put(ctx, "system", "min_refresh_rate", v); Cfg.put(ctx, "system", "peak_refresh_rate", v)
        } else if (cap >= hz) {
            Cfg.restore(ctx, "system", "peak_refresh_rate"); Cfg.restore(ctx, "system", "min_refresh_rate")
        } else {
            Cfg.put(ctx, "system", "peak_refresh_rate", v); Cfg.restore(ctx, "system", "min_refresh_rate")
        }
    }

    /** Dipakai governor suhu/idle untuk menurunkan atau menaikkan FPS tanpa mengubah mode. */
    fun setFpsCap(mode: Int, cap: Int, game: String?) {
        if (!Sh.granted()) return
        applyRefresh(mode, cap)
        gameMode(game, gmOf(mode), cap)
        ActionLog.add("Batas FPS -> $cap (layar maks " + maxHz() + " Hz)")
    }

    /** Ekstra untuk Performa/Turbo: hentikan pembatasan sistem pada game, jaringan latensi rendah, dll. */
    private fun perfExtras(on: Boolean, game: String?, turbo: Boolean) {
        if (on) {
            Sh.exec("cmd power set-adaptive-power-saver-enabled false")
            if (game != null) Sh.exec("am set-standby-bucket $game active; cmd deviceidle whitelist +$game")
            Sh.exec("cmd wifi force-low-latency-mode enabled" + if (turbo) "; cmd wifi force-hi-perf-mode enabled" else "")
            if (turbo) Cfg.put(ctx, "global", "adaptive_battery_management_enabled", "0")
        } else {
            if (game != null) Sh.exec("cmd deviceidle whitelist -$game")
            Sh.exec("cmd wifi force-low-latency-mode disabled; cmd wifi force-hi-perf-mode disabled")
        }
    }

    /** 0 Hemat · 1 Seimbang · 2 Performa · 3 Turbo (kunci CPU/GPU). capIn = batas FPS dari governor (-1 = batas mode). */
    fun applyMode(mode: Int, game: String?, capIn: Int = -1): String {
        if (needShizuku()) return "Shizuku belum aktif"
        val hz = maxHz()
        val cap = if (capIn > 0) capIn else Logic.modeCap(mode, hz)
        ActionLog.add("Mode -> " + Modes.all[mode].name + " (FPS maks " + cap + ", layar " + hz + " Hz)")
        var extra = ""
        when (mode) {
            0 -> {
                Cfg.put(ctx, "global", "low_power", "1")
                fixed(false); thermal(false); perfExtras(false, game, false); applyRefresh(0, cap); unlockHw(); gameMode(game, 3, cap)
            }
            1 -> {
                Cfg.restore(ctx, "global", "low_power")
                fixed(false); thermal(false); perfExtras(false, game, false); applyRefresh(1, cap); unlockHw(); gameMode(game, 1, cap)
            }
            2 -> {
                Cfg.put(ctx, "global", "low_power", "0")
                fixed(true); thermal(false); perfExtras(true, game, false); applyRefresh(2, cap); unlockHw(); gameMode(game, 2, cap)
            }
            else -> {
                Cfg.put(ctx, "global", "low_power", "0")
                fixed(true); thermal(true); perfExtras(true, game, true); applyRefresh(3, cap); gameMode(game, 2, cap)
                val c = Hw.lockCpu(ctx)
                val g = Hw.lockGpu(ctx, Hw.maxGpuKhz(ctx))
                extra = "  (CPU " + (if (c) "terkunci" else "gagal") + ", GPU " + (if (g) "terkunci" else "gagal") + ")"
            }
        }
        val msg = Modes.all[mode].name + " • " + cap + " FPS" + extra
        toast("Mode $msg")
        return msg
    }

    /** Ulangi pengaturan Performa/Turbo secara berkala: sistem kadang mereset saat layar mati / event termal. */
    fun reassert(mode: Int, game: String?) {
        if (mode < 2 || !Sh.granted()) return
        fixed(true)
        if (mode == 3) { thermal(true); Hw.reassert(ctx) }
    }

    // ───────── redup layar saat idle ─────────
    private fun getSys(key: String, def: Int) = try { Settings.System.getInt(ctx.contentResolver, key, def) } catch (_: Throwable) { def }
    private fun putSys(key: String, v: Int) {
        if (Sh.granted()) Sh.exec("settings put system $key $v")
        else if (Settings.System.canWrite(ctx)) Settings.System.putInt(ctx.contentResolver, key, v)
    }

    /** Redupkan sampai terendah. Mengembalikan [mode, level] lama untuk dipulihkan. */
    fun dim(): IntArray {
        val saved = intArrayOf(getSys("screen_brightness_mode", 1), getSys("screen_brightness", 128))
        putSys("screen_brightness_mode", 0); putSys("screen_brightness", 1)
        return saved
    }

    fun undim(saved: IntArray) {
        putSys("screen_brightness", saved[1]); putSys("screen_brightness_mode", saved[0])
    }

    // ───────── toggle sistem ─────────
    fun setDnd(on: Boolean) {
        if (Sh.granted()) {
            if (on) {
                if (Prefs.str(ctx, "orig_zen").isEmpty()) Prefs.setStr(ctx, "orig_zen", Cfg.get("global", "zen_mode"))
                Sh.exec("cmd notification set_dnd priority")
                if (Cfg.get("global", "zen_mode") != "1") Sh.exec("settings put global zen_mode 1")
            } else {
                val m = when (Prefs.str(ctx, "orig_zen", "0")) { "1" -> "priority"; "2" -> "none"; "3" -> "alarms"; else -> "off" }
                Sh.exec("cmd notification set_dnd $m")
                Prefs.setStr(ctx, "orig_zen", "")
            }
            return
        }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        } else {
            toast("Beri akses 'Jangan Ganggu' atau aktifkan Shizuku")
            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun sysToggle(ns: String, key: String, on: Boolean, onVal: String, offFallback: Int) {
        if (Sh.granted()) {
            if (on) Cfg.put(ctx, ns, key, onVal) else Cfg.restore(ctx, ns, key)
        } else if (ns == "system" && Settings.System.canWrite(ctx)) {
            Settings.System.putInt(ctx.contentResolver, key, if (on) onVal.toInt() else offFallback)
        } else toast("Butuh Shizuku atau izin ubah setelan sistem")
    }

    fun setBrightLock(on: Boolean) = sysToggle("system", "screen_brightness_mode", on, "0", 1)
    fun setRotLock(on: Boolean) = sysToggle("system", "accelerometer_rotation", on, "0", 1)

    fun setBrightness(v: Int) {
        val lv = v.coerceIn(1, 255)
        if (Sh.granted()) {
            Cfg.put(ctx, "system", "screen_brightness_mode", "0")
            Cfg.put(ctx, "system", "screen_brightness", lv.toString())
        } else if (Settings.System.canWrite(ctx)) {
            Settings.System.putInt(ctx.contentResolver, "screen_brightness_mode", 0)
            Settings.System.putInt(ctx.contentResolver, "screen_brightness", lv)
        } else toast("Butuh Shizuku atau izin ubah setelan sistem")
    }

    fun setAnim(on: Boolean) {
        if (needShizuku()) return
        for (k in listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")) {
            if (on) Cfg.put(ctx, "global", k, "0") else Cfg.restore(ctx, "global", k)
        }
    }
    fun setAdaptive(on: Boolean) {
        if (needShizuku()) return
        if (on) Cfg.put(ctx, "global", "adaptive_battery_management_enabled", "0")
        else Cfg.restore(ctx, "global", "adaptive_battery_management_enabled")
    }
    fun setTouch(on: Boolean) {
        if (needShizuku()) return
        if (on) Cfg.put(ctx, "secure", "long_press_timeout", "250") else Cfg.restore(ctx, "secure", "long_press_timeout")
    }

    // ───────── tampilan ─────────
    /** 0 Normal · 1 Vivid · 2 HDR · 3 Soft  (saturasi/kontras SurfaceFlinger). */
    fun setVisual(style: Int, quiet: Boolean = false): Boolean {
        if (!quiet && needShizuku()) return false
        if (!Sh.granted()) return false
        val sat = when (style) { 1 -> 1.35f; 2 -> 1.2f; 3 -> 0.82f; else -> 1f }
        val r1 = Sh.exec("service call SurfaceFlinger 1022 f $sat")
        val r2 = if (style == 2) {
            val c = 1.1f; val t = (1f - c) / 2f
            Sh.exec("service call SurfaceFlinger 1015 i32 1 f $c f 0 f 0 f 0 f 0 f $c f 0 f 0 f 0 f 0 f $c f 0 f $t f $t f $t f 1")
        } else Sh.exec("service call SurfaceFlinger 1015 i32 0")
        val ok = r1.contains("Parcel(00000000")
        ActionLog.add("Tampilan $style: sat=" + r1.trim().take(30) + " matrix=" + r2.trim().take(30))
        if (!quiet) toast(if (ok) "Tampilan: " + listOf("Normal", "Vivid", "HDR", "Soft")[style] else "Tampilan tidak didukung perangkat ini")
        return ok
    }

    // ───────── screenshot / rekam layar ─────────
    fun screenshot(): Boolean {
        if (needShizuku()) return false
        val f = "/sdcard/Pictures/GameSpace/GS_" + System.currentTimeMillis() + ".png"
        val out = Sh.exec("mkdir -p /sdcard/Pictures/GameSpace; screencap -p $f; ls -l $f; am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$f >/dev/null 2>&1")
        val ok = out.contains("GS_") && !out.contains("No such file")
        toast(if (ok) "Screenshot tersimpan di Pictures/GameSpace" else "Screenshot gagal")
        ActionLog.add("Screenshot: " + if (ok) "OK" else out.trim().take(60))
        return ok
    }

    fun startScreenRec() {
        if (needShizuku()) return
        val base = "/sdcard/Movies/GameSpace/GS_" + System.currentTimeMillis()
        Sh.exec("mkdir -p /sdcard/Movies/GameSpace; touch /data/local/tmp/gs_rec; (i=0; while [ -f /data/local/tmp/gs_rec ]; do screenrecord --time-limit 170 " +
            base + "_\$i.mp4 >/dev/null 2>&1; i=\$((i+1)); done) >/dev/null 2>&1 &")
        screenRecOn = true
        ActionLog.add("Rekam layar mulai")
        toast("Merekam layar... (Movies/GameSpace)")
    }

    fun stopScreenRec() {
        if (!screenRecOn) return
        Sh.exec("rm -f /data/local/tmp/gs_rec; pkill -2 screenrecord || killall -2 screenrecord", 8000)
        screenRecOn = false
        ActionLog.add("Rekam layar berhenti")
        toast("Rekaman layar tersimpan di Movies/GameSpace")
    }

    // ───────── memori & cache ─────────
    fun boost(game: String?): Long {
        if (needShizuku()) return 0L
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        fun avail(): Long { val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi); return mi.availMem }
        val before = avail()
        val script = try { ctx.assets.open("boost.sh").bufferedReader().use { it.readText() } } catch (_: Throwable) { "" }
        Sh.exec(script.replace("@GAME@", game ?: ""), 40_000)
        Thread.sleep(600)
        val mb = maxOf(0L, (avail() - before) / (1024 * 1024))
        ActionLog.add("Boost RAM: +" + mb + " MB")
        return mb
    }

    fun cleanCache(): Long {
        if (needShizuku()) return -1L
        fun free() = StatFs(Environment.getDataDirectory().path).availableBytes
        val before = free()
        Sh.exec("pm trim-caches 128G", 30_000)
        val mb = maxOf(0L, (free() - before) / (1024 * 1024))
        ActionLog.add("Bersihkan cache: " + mb + " MB")
        return mb
    }

    // ───────── batasi app latar (appops) ─────────
    private fun bgOps(): List<String> =
        if (Build.VERSION.SDK_INT >= 28) listOf("RUN_ANY_IN_BACKGROUND", "RUN_IN_BACKGROUND") else listOf("RUN_IN_BACKGROUND")

    fun pendingBackground(): Int = Prefs.str(ctx, "bg_changed").split(",").count { it.isNotBlank() }

    /**
     * Larang app pihak ketiga lain berjalan di latar selama sesi game (appops, bukan sekadar pembersih RAM).
     * Dilindungi: game, Game Space, Shizuku, keyboard, launcher, dan daftar pengecualian pengguna.
     * Nilai asli dicatat DULU supaya selalu bisa dipulihkan (juga setelah crash). Hasil: jumlah app, -1 = butuh Shizuku.
     */
    fun restrictBackground(game: String?): Int {
        if (needShizuku()) return -1
        val ops = bgOps()
        val prot = HashSet<String>()
        if (game != null) prot.add(game)
        prot.add(ctx.packageName); prot.add("moe.shizuku.privileged.api")
        for (p in Prefs.str(ctx, "bg_excl").split(",")) if (p.isNotBlank()) prot.add(p.trim())
        val ime = (Sh.exec("settings get secure default_input_method").trim().lines().lastOrNull() ?: "").substringBefore('/').trim()
        if (ime.isNotEmpty() && ime != "null") prot.add(ime)
        val home = Sh.exec("pm resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME")
        for (m in Regex("packageName=([\\w.]+)").findAll(home)) prot.add(m.groupValues[1])
        val installed = Sh.exec("pm list packages -3").lines().map { it.trim().removePrefix("package:") }
        val already = AppOpsLogic.parsePackages(Sh.exec("cmd appops query-op --user 0 " + ops[0] + " ignore"))
        val targets = AppOpsLogic.targets(installed, prot, already)
        if (targets.isEmpty()) return 0
        val all = LinkedHashSet<String>()
        for (p in Prefs.str(ctx, "bg_changed").split(",")) if (p.isNotBlank()) all.add(p)
        all.addAll(targets)
        Prefs.setStr(ctx, "bg_changed", all.joinToString(","))
        val sb = StringBuilder()
        for (p in targets) for (op in ops) sb.append("cmd appops set --user 0 ").append(p).append(' ').append(op).append(" ignore; ")
        Sh.exec(sb.toString(), 60_000)
        ActionLog.add("App latar dibatasi: " + targets.size + " (dilindungi " + prot.size + ")")
        return targets.size
    }

    /** Balikkan semua app yang kita batasi ke mode default. */
    fun restoreBackground(): Int {
        val list = Prefs.str(ctx, "bg_changed").split(",").filter { it.isNotBlank() }
        if (list.isEmpty() || !Sh.granted()) return 0
        val ops = bgOps()
        val sb = StringBuilder()
        for (p in list) for (op in ops) sb.append("cmd appops set --user 0 ").append(p).append(' ').append(op).append(" default; ")
        Sh.exec(sb.toString(), 60_000)
        Prefs.setStr(ctx, "bg_changed", "")
        ActionLog.add("App latar dipulihkan: " + list.size)
        return list.size
    }

    /** Kembalikan semua yang pernah diubah (keluar game / layanan berhenti / tombol pulihkan). */
    fun restoreAll(game: String?) {
        if (!Sh.granted()) return
        ActionLog.add("Pulihkan setelan")
        unlockHw()
        fixed(false); thermal(false)
        setVisual(0, true)
        perfExtras(false, game, false)
        if (game != null) Sh.exec("cmd game reset --user 0 $game; cmd game set --mode 1 --user 0 $game")
        Gfx.restoreRenderer(ctx)
        val pk = HashSet<String>(Prefs.games(ctx))
        if (game != null) pk.add(game)
        for (p in pk) if (Prefs.int(ctx, "gfx_s_$p", 100) < 100) Gfx.resetScale(p)
        restoreBackground()
        Cfg.restoreAll(ctx)
        if (Prefs.str(ctx, "orig_zen").isNotEmpty()) setDnd(false)
        stopScreenRec()
    }
}
