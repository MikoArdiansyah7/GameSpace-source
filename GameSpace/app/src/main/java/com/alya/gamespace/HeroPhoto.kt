package com.alya.gamespace

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/** Foto hero dashboard: pilih dari galeri, otomatis di-crop ke rasio kartu (tidak ditarik/melar). */
object HeroPhoto {
    private const val ASPECT = 250f / 182f
    private fun f(c: Context, n: String) = File(c.filesDir, n)

    fun has(c: Context) = f(c, "hero.jpg").exists()
    fun bitmap(c: Context): Bitmap? = if (has(c)) try { BitmapFactory.decodeFile(f(c, "hero.jpg").path) } catch (_: Throwable) { null } else null
    fun bgBitmap(c: Context): Bitmap? = if (f(c, "hero_bg.jpg").exists()) try { BitmapFactory.decodeFile(f(c, "hero_bg.jpg").path) } catch (_: Throwable) { null } else null

    fun import(c: Context, uri: Uri): Boolean = try {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / s > 1800) s *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = s
        val bmp = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
        if (bmp == null) false else {
            FileOutputStream(f(c, "hero_src.jpg")).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            rebuild(c)
            true
        }
    } catch (_: Throwable) { false }

    /** Potong dari foto asli sesuai fokus vertikal (0 = atas, 100 = bawah). */
    fun rebuild(c: Context) {
        val src = try { BitmapFactory.decodeFile(f(c, "hero_src.jpg").path) } catch (_: Throwable) { null } ?: return
        val focus = Prefs.int(c, "hero_focus", 35) / 100f
        var cw = src.width
        var ch = (cw / ASPECT).toInt()
        if (ch > src.height) { ch = src.height; cw = (ch * ASPECT).toInt() }
        val x = ((src.width - cw) / 2).coerceAtLeast(0)
        val y = ((src.height - ch) * focus).toInt().coerceIn(0, maxOf(0, src.height - ch))
        val crop = Bitmap.createBitmap(src, x, y, cw, ch)
        val out = Bitmap.createScaledBitmap(crop, 800, (800 / ASPECT).toInt(), true)
        FileOutputStream(f(c, "hero.jpg")).use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        val small = Bitmap.createScaledBitmap(crop, 32, (32 / ASPECT).toInt().coerceAtLeast(8), true)
        val big = Bitmap.createScaledBitmap(small, 320, (320 / ASPECT).toInt(), true)
        FileOutputStream(f(c, "hero_bg.jpg")).use { big.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    }

    fun reset(c: Context) {
        for (n in listOf("hero.jpg", "hero_src.jpg", "hero_bg.jpg")) f(c, n).delete()
    }

    fun hasSource(c: Context) = f(c, "hero_src.jpg").exists()
}
