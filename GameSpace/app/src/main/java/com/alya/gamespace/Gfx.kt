package com.alya.gamespace

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Renderer HWUI + skala resolusi per game. Berlaku saat game dimulai ulang dari Game Space. */
object Gfx {
    val names = listOf("Bawaan", "OpenGL", "SkiaGL", "Vulkan")
    val scales = intArrayOf(100, 90, 80, 70, 60, 50, 45, 40, 35, 30)
    private val all = listOf("DOWNSCALE_90", "DOWNSCALE_80", "DOWNSCALE_70", "DOWNSCALE_60", "DOWNSCALE_50",
        "DOWNSCALE_45", "DOWNSCALE_40", "DOWNSCALE_35", "DOWNSCALE_30")

    fun vulkanOk(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 29 && try {
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x00400003)
    } catch (_: Throwable) { false }

    fun scaleSupported() = Build.VERSION.SDK_INT >= 31

    private fun prop(eff: Int) = when (eff) { 1 -> "opengl"; 2 -> "skiagl"; 3 -> "skiavk"; else -> "" }

    fun applyRenderer(ctx: Context, eff: Int) {
        if (Prefs.str(ctx, "orig_hwui", "#") == "#") Prefs.setStr(ctx, "orig_hwui", Sh.exec("getprop debug.hwui.renderer").trim())
        Sh.exec("setprop debug.hwui.renderer '" + prop(eff) + "'")
        ActionLog.add("Renderer HWUI -> " + (if (eff == 0) "bawaan" else prop(eff)))
    }

    fun restoreRenderer(ctx: Context) {
        val o = Prefs.str(ctx, "orig_hwui", "#")
        if (o == "#") return
        Sh.exec("setprop debug.hwui.renderer '$o'")
        Prefs.setStr(ctx, "orig_hwui", "#")
    }

    fun resetScale(pkg: String) {
        if (!scaleSupported()) return
        Sh.exec(all.joinToString("; ") { "am compat reset $it $pkg" })
    }

    fun applyScale(pkg: String, percent: Int): Boolean {
        if (!scaleSupported()) return false
        resetScale(pkg)
        val n = Logic.scaleName(percent) ?: return true
        val out = Sh.exec("am compat enable $n $pkg")
        ActionLog.add("Resolusi $percent% ($pkg): " + out.trim().take(60))
        return out.contains("Enabled", true)
    }

    /** Deteksi API grafis game yang sedang jalan: libvulkan termuat? lalu pipeline HWUI (Skia/OpenGL). */
    fun detect(pkg: String): String {
        var mapped = false
        var readable = false
        val pid = Sh.exec("pidof $pkg").trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
        if (pid != null) {
            val n = Sh.exec("grep -c libvulkan /proc/$pid/maps 2>/dev/null").lines().firstOrNull { it.isNotBlank() }?.trim()?.toIntOrNull()
            if (n != null) { readable = true; mapped = n > 0 }
        }
        return Logic.rendererLabel(mapped, readable, verify(pkg))
    }

    /** Baca pipeline render yang benar-benar dipakai game (hanya jalur HWUI). */
    fun verify(pkg: String): String = Sh.exec("dumpsys gfxinfo $pkg | grep -i -m1 pipeline").trim()
}
