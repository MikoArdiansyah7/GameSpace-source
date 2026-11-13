package com.alya.gamespace

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.WindowManager

/** Efek kaca: blur sistem Android 12+ bila tersedia, kalau tidak pakai tint lebih pekat. */
object Glass {
    fun blurOk(wm: WindowManager): Boolean =
        Build.VERSION.SDK_INT >= 31 && try { wm.isCrossWindowBlurEnabled } catch (_: Throwable) { false }

    @Suppress("DEPRECATION")
    fun apply(lp: WindowManager.LayoutParams, radiusPx: Int) {
        if (Build.VERSION.SDK_INT >= 31) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            lp.setBlurBehindRadius(radiusPx)
        }
    }
}

/**
 * HUD kapsul: cincin FPS (warna sesuai rentang), jaringan, CPU/GPU/RAM/baterai, jam, suhu.
 * Item bisa dihidupkan/dimatikan satu per satu. Celah di tengah untuk knob pembuka Space.
 * Urutan item: 0 fps · 1 net · 2 cpu · 3 gpu · 4 ram · 5 bat · 6 jam · 7 suhu
 */
class HudView(ctx: Context) : View(ctx) {
    var fps = 0f
    var cpu = -1
    var gpu = -1
    var ram = -1
    var bat = -1
    var charging = false
    var temp = 0f
    var time = ""
    var net = 0              // 0 offline · 1 WiFi · 2 data seluler
    var ping = -1            // ms, -1 = belum diketahui
    var maxHz = 60
    var blur = false
    val show = BooleanArray(8)
    var wPx = 0
    var hPx = 0
    var gapCx = 0f

    private val gapW: Float get() = dp(38).toFloat()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private fun cellW(i: Int): Float = when (i) { 0 -> dp(42); 1 -> dp(46); 6 -> dp(46); 7 -> dp(38); else -> dp(36) }.toFloat()

    /** Hitung lebar & posisi celah; kalau c != null sekaligus menggambar. */
    private fun walk(c: Canvas?): Float {
        val idx = ArrayList<Int>()
        for (i in 0 until 8) if (show[i]) idx.add(i)
        val split = (idx.size + 1) / 2
        var x = dp(8).toFloat()
        var gapStart = -1f
        for (k in idx.indices) {
            if (k == split) { gapStart = x; x += gapW }
            val w = cellW(idx[k])
            if (c != null) drawCell(c, idx[k], x, w)
            x += w
        }
        if (gapStart < 0f) { gapStart = x; x += gapW }
        gapCx = gapStart + gapW / 2f
        return x + dp(8)
    }

    fun computeSize() { wPx = walk(null).toInt(); hPx = dp(42) }
    fun anyShown(): Boolean { for (b in show) if (b) return true; return false }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = dp(14).toFloat()
        rect.set(0f, 0f, w, h)
        p.style = Paint.Style.FILL
        p.shader = if (blur) LinearGradient(0f, 0f, 0f, h, 0x48FFFFFF, 0x6A10101C, Shader.TileMode.CLAMP)
        else LinearGradient(0f, 0f, 0f, h, 0xEE1B1838.toInt(), 0xF50B0A17.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, r, r, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1).toFloat(); p.color = 0x70FFFFFF
        rect.inset(0.5f, 0.5f)
        c.drawRoundRect(rect, r, r, p)
        walk(c)
    }

    private fun txt(c: Canvas, cx: Float, cy: Float, s: String, sp: Float, color: Int, bold: Boolean) {
        p.style = Paint.Style.FILL; p.shader = null
        p.textAlign = Paint.Align.CENTER
        p.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        p.textSize = spf(sp); p.color = color
        c.drawText(s, cx, cy - (p.ascent() + p.descent()) / 2f, p)
    }

    private fun caption(c: Canvas, cx: Float, s: String) = txt(c, cx, hPx - dp(6).toFloat(), s, 6.5f, 0xB0FFFFFF.toInt(), false)

    private fun ring(c: Canvas, cx: Float, cy: Float, pct: Float, color: Int, value: String, cap: String) {
        val r = dp(11).toFloat()
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(3).toFloat(); p.strokeCap = Paint.Cap.ROUND; p.shader = null
        p.color = 0x33FFFFFF
        c.drawCircle(cx, cy, r, p)
        p.color = color
        rect.set(cx - r, cy - r, cx + r, cy + r)
        val sweep = 360f * pct.coerceIn(0f, 100f) / 100f
        if (sweep > 0f) c.drawArc(rect, -90f, sweep, false, p)
        txt(c, cx, cy, value, if (value.length > 2) 7.5f else 9f, Color.WHITE, true)
        caption(c, cx, cap)
    }

    private fun drawCell(c: Canvas, i: Int, x: Float, w: Float) {
        val cx = x + w / 2f
        val cy = hPx * 0.42f
        val a = Skin.a
        when (i) {
            0 -> {
                // FPS: hanya angka, warnanya sesuai rentang (1-29 merah, 30-59 kuning, 60-73 hijau, 74+ biru)
                p.setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), 0xCC000000.toInt())
                txt(c, cx, cy, if (fps > 0f) fps.toInt().toString() else "--", 17f, Logic.fpsColor(fps), true)
                p.clearShadowLayer()
                caption(c, cx, "FPS")
            }
            1 -> {
                // PING: angka ms berwarna (<=50 hijau, 51-100 kuning, >100 merah); teks di bawah tetap WIFI / DATA
                p.setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), 0xCC000000.toInt())
                txt(c, cx, cy, Logic.pingText(ping), 10.5f, Logic.pingColor(ping), true)
                p.clearShadowLayer()
                caption(c, cx, NetLogic.caption(net, ping))
            }
            2 -> ring(c, cx, cy, cpu.toFloat(), a, if (cpu >= 0) cpu.toString() else "--", "CPU")
            3 -> ring(c, cx, cy, gpu.toFloat(), a, if (gpu >= 0) gpu.toString() else "--", "GPU")
            4 -> ring(c, cx, cy, ram.toFloat(), a, if (ram >= 0) ram.toString() else "--", "RAM")
            5 -> ring(c, cx, cy, bat.toFloat(), if (bat >= 50) Pal.OK else if (bat >= 20) Pal.WARN else Pal.BAD,
                if (bat >= 0) bat.toString() else "--", if (charging) "CHG" else "BAT")
            6 -> { txt(c, cx, cy, time, 11f, Color.WHITE, true); caption(c, cx, "JAM") }
            7 -> {
                val col = if (temp >= 40f) Pal.BAD else if (temp >= 38f) Pal.WARN else Color.WHITE
                txt(c, cx, cy, temp.toInt().toString() + "°", 11f, col, true); caption(c, cx, "SUHU")
            }
        }
    }
}

/** Tombol kecil di tengah HUD: warna mengikuti mode. Ketuk atau geser untuk membuka Space. */
class KnobView(ctx: Context) : View(ctx) {
    var alone = true
    var blur = false
    /** Mode RGB: warna berputar selama layar direkam, supaya terlihat sekilas bahwa rekaman sedang berjalan. */
    var rgb = false
        private set
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val hsv = floatArrayOf(0f, 0.85f, 1f)

    fun setRgb(on: Boolean) { if (rgb == on) return; rgb = on; invalidate() }

    private fun hue(deg: Float): Int { hsv[0] = ((deg % 360f) + 360f) % 360f; return Color.HSVToColor(hsv) }

    private fun rainbow(w: Float, t: Float) = LinearGradient(0f, 0f, w, 0f,
        intArrayOf(hue(t), hue(t + 90f), hue(t + 180f), hue(t + 270f)), null, Shader.TileMode.CLAMP)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val t = if (rgb) (SystemClock.uptimeMillis() % 2400L) / 2400f * 360f else 0f
        if (alone) {
            rect.set(0f, 0f, w, h)
            val r = h / 2f
            p.style = Paint.Style.FILL
            p.shader = if (blur) LinearGradient(0f, 0f, 0f, h, 0x48FFFFFF, 0x6A10101C, Shader.TileMode.CLAMP)
            else LinearGradient(0f, 0f, 0f, h, 0xEE1B1838.toInt(), 0xF50B0A17.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, r, r, p)
            p.shader = null
            p.style = Paint.Style.STROKE
            if (rgb) { p.strokeWidth = dp(2).toFloat(); p.shader = rainbow(w, t); rect.inset(1f, 1f) }
            else { p.strokeWidth = dp(1).toFloat(); p.color = 0x70FFFFFF; rect.inset(0.5f, 0.5f) }
            c.drawRoundRect(rect, r, r, p)
            p.shader = null
        } else if (rgb) {
            // knob berada di celah kapsul HUD: cincin RGB tipis di sekeliling area knob
            rect.set(dp(1).toFloat(), dp(1).toFloat(), w - dp(1), h - dp(1))
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(2).toFloat(); p.shader = rainbow(w, t)
            c.drawRoundRect(rect, h / 2f, h / 2f, p)
            p.shader = null
        }
        val bw = dp(24).toFloat(); val bh = dp(6).toFloat()
        rect.set((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f)
        p.style = Paint.Style.FILL
        p.shader = if (rgb) LinearGradient(rect.left, 0f, rect.right, 0f, hue(t), hue(t + 140f), Shader.TileMode.CLAMP)
        else LinearGradient(rect.left, 0f, rect.right, 0f, Skin.a, Skin.b, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, bh / 2f, bh / 2f, p)
        p.shader = null
        if (rgb) postInvalidateDelayed(33)   // ~30 fps, hanya selama merekam layar
    }
}

/**
 * HUD Display gaya PC (seperti MangoHud): label berwarna & huruf besar, nilai putih, grafik FPS hijau.
 * Urutan: 0 render · 1 GPU · 2 CPU · 3 RAM · 4 PWR · 5 TMP · 6 FPS · 7 grafik. Font monospace supaya kolom tidak bergeser.
 */
class PcHudView(ctx: Context) : View(ctx) {
    var renderer = "--"
    var gpu = -1
    var cpu = -1
    var ram = -1
    var watt = -1f
    var charging = false
    var temp = 0f
    var fps = 0f
    val show = BooleanArray(8) { true }
    var scale = 1f
    var bgAlpha = 140
    var fpsColored = false
    var maxW = 0
    val hist = FloatArray(60)
    var histN = 0
    var wPx = 0
    var hPx = 0
    var effScale = 1f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    private val cPink = 0xFFFF5FA2.toInt()
    private val cGreen = 0xFF4ADE80.toInt()
    private val cOrange = 0xFFFFB020.toInt()
    private val cBlue = 0xFF38BDF8.toInt()
    private val cPurple = 0xFFC084FC.toInt()
    private val cRed = 0xFFFF5C5C.toInt()
    private val cYellow = 0xFFFDE047.toInt()
    private val cGraph = 0xFF22E03A.toInt()

    fun push(f: Float) {
        if (histN < hist.size) hist[histN++] = f
        else { System.arraycopy(hist, 1, hist, 0, hist.size - 1); hist[hist.size - 1] = f }
    }

    private fun tsize(sc: Float) = spf(11f) * sc

    private class Seg(val label: String, val value: String, val color: Int)

    private fun segs(): List<Seg> {
        val l = ArrayList<Seg>()
        if (show[0]) l.add(Seg(renderer.uppercase(), "", cPink))
        if (show[1]) l.add(Seg("GPU", Logic.pcPct(gpu), cGreen))
        if (show[2]) l.add(Seg("CPU", Logic.pcPct(cpu), cOrange))
        if (show[3]) l.add(Seg("RAM", Logic.pcPct(ram), cBlue))
        if (show[4]) l.add(Seg("PWR", Logic.pcWatt(watt, charging), cPurple))
        if (show[5]) l.add(Seg("TMP", Logic.pcTemp(temp), cRed))
        if (show[6]) l.add(Seg("FPS", Logic.pcFps(fps), cYellow))
        return l
    }

    private fun natural(sc: Float): Float {
        p.typeface = mono; p.textSize = tsize(sc)
        val cw = p.measureText("M")
        var w = cw * 1.2f
        var first = true
        for (sg in segs()) {
            if (!first) w += cw * 2.2f
            first = false
            w += cw * (sg.label.length + (if (sg.value.isEmpty()) 0 else 1 + sg.value.length))
        }
        if (show[7]) { if (!first) w += cw * 2.2f; w += cw * 18f }
        return w + cw * 1.2f
    }

    fun anyShown(): Boolean { for (b in show) if (b) return true; return false }

    /** Hitung ukuran akhir; mengecil otomatis kalau tidak muat di sisa lebar layar. */
    fun computeSize() {
        effScale = Logic.fitScale(natural(scale), maxW.toFloat(), scale)
        wPx = Math.ceil(natural(effScale).toDouble()).toInt()
        hPx = (tsize(effScale) * 2.0f).toInt()
    }

    override fun onDraw(c: Canvas) {
        val sc = effScale
        val ts = tsize(sc)
        p.typeface = mono; p.textSize = ts
        val cw = p.measureText("M")
        p.style = Paint.Style.FILL; p.shader = null
        p.color = Color.argb(bgAlpha, 0, 0, 0)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val r = dp(6) * sc
        c.drawRoundRect(rect, r, r, p)
        p.setShadowLayer(ts * 0.08f, 0f, 0f, 0xCC000000.toInt())
        val base = height / 2f - (p.ascent() + p.descent()) / 2f
        var x = cw * 1.2f
        var first = true
        for (sg in segs()) {
            if (!first) { x = drawSep(c, x, cw, ts); p.setShadowLayer(ts * 0.08f, 0f, 0f, 0xCC000000.toInt()) }
            first = false
            p.style = Paint.Style.FILL
            p.color = sg.color
            c.drawText(sg.label, x, base, p)
            x += cw * sg.label.length
            if (sg.value.isNotEmpty()) {
                x += cw
                p.color = if (sg.label == "FPS" && fpsColored) Logic.fpsColor(fps) else if (sg.label == "PWR" && charging && watt >= 0f) 0xFF4ADE80.toInt() else Color.WHITE
                c.drawText(sg.value, x, base, p)
                x += cw * sg.value.length
            }
        }
        p.clearShadowLayer()
        if (show[7]) {
            if (!first) x = drawSep(c, x, cw, ts)
            drawGraph(c, x, cw * 18f, ts)
        }
    }

    private fun drawSep(c: Canvas, x: Float, cw: Float, ts: Float): Float {
        p.clearShadowLayer()
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = ts * 0.1f; p.color = 0xE6FFFFFF.toInt()
        val sx = x + cw * 1.1f
        c.drawLine(sx, height * 0.22f, sx, height * 0.78f, p)
        return x + cw * 2.2f
    }

    private fun drawGraph(c: Canvas, x: Float, w: Float, ts: Float) {
        val gh = height * 0.56f
        val gy = (height - gh) / 2f
        var mx = 0f
        for (i in 0 until histN) if (hist[i] > mx) mx = hist[i]
        val top = Logic.graphMax(mx)
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = Math.max(1.5f, ts * 0.12f)
        p.color = 0x33FFFFFF
        c.drawLine(x, gy + gh, x + w, gy + gh, p)
        if (histN < 2) return
        path.reset()
        for (i in 0 until histN) {
            val px = x + w * i / (hist.size - 1).toFloat()
            val py = gy + gh * (1f - (hist[i] / top).coerceIn(0f, 1f))
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        p.color = cGraph
        c.drawPath(path, p)
    }
}
