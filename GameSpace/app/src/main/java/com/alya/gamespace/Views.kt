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
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** Cincin beban + nilai (CPU / GPU). */
class DialView(ctx: Context) : View(ctx) {
    var pct = 0f
        set(v) { field = v.coerceIn(0f, 100f); invalidate() }
    var known = false
    var label = ""
    var value = "--"
    var unit = ""
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val prog = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        track.style = Paint.Style.STROKE; track.strokeCap = Paint.Cap.ROUND; track.color = 0x2AFFFFFF
        prog.style = Paint.Style.STROKE; prog.strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(c: Canvas) {
        val sw = dp(5).toFloat()
        track.strokeWidth = sw; prog.strokeWidth = sw
        val d = height - sw - dp(2)
        val l = sw / 2 + dp(1)
        rect.set(l, l, l + d, l + d)
        c.drawArc(rect, 0f, 360f, false, track)
        prog.color = Skin.a
        if (known && pct > 0f) c.drawArc(rect, -90f, 360f * pct / 100f, false, prog)
        txt.textAlign = Paint.Align.CENTER
        txt.color = Pal.TEXT; txt.typeface = Typeface.DEFAULT_BOLD; txt.textSize = spf(12f)
        val cy = rect.centerY() - (txt.ascent() + txt.descent()) / 2
        c.drawText(if (known) pct.toInt().toString() else "--", rect.centerX(), cy, txt)
        val x = rect.right + dp(8)
        txt.textAlign = Paint.Align.LEFT
        txt.typeface = Typeface.DEFAULT; txt.color = Pal.MUTED; txt.textSize = spf(9f)
        c.drawText(label, x, rect.centerY() - spf(8f), txt)
        txt.typeface = Typeface.DEFAULT_BOLD; txt.color = Pal.TEXT; txt.textSize = spf(15f)
        c.drawText(value, x, rect.centerY() + spf(7f), txt)
        txt.typeface = Typeface.DEFAULT; txt.color = Pal.MUTED; txt.textSize = spf(9f)
        c.drawText(unit, x, rect.centerY() + spf(19f), txt)
    }
}

/** Tombol fitur: emoji + label. Aktif = gradasi warna mode. */
class Tile(ctx: Context, icon: Int, label: String) : LinearLayout(ctx) {
    private val e = IconView(ctx, icon, Pal.TEXT)
    private val t = ctx.tv(label, 9.5f, Pal.TEXT, true)
    var active = false
        set(v) { field = v; refresh() }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(3), dp(6), dp(3), dp(6))
        t.gravity = Gravity.CENTER; t.maxLines = 1; t.ellipsize = TextUtils.TruncateAt.END
        addView(e, LinearLayout.LayoutParams(dp(22), dp(22)))
        addView(t, LinearLayout.LayoutParams(-1, -2))
        refresh()
    }

    fun refresh() {
        background = if (active) gbox(Skin.a, Skin.b, dp(14).toFloat(), GradientDrawable.Orientation.TL_BR)
        else rbox(0x1AFFFFFF, dp(14).toFloat(), 0x24FFFFFF, 1)
        t.setTextColor(if (active) Color.WHITE else Pal.TEXT)
        e.tint = if (active) Color.WHITE else Pal.TEXT
    }
}

class CrosshairView(ctx: Context) : View(ctx) {
    var style = 1
    var color = 0xFFFF3B3B.toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = width / 2f - dp(2)
        p.strokeWidth = dp(2).toFloat(); p.color = color; p.style = Paint.Style.FILL
        when (style) {
            1 -> c.drawCircle(cx, cy, r * 0.12f + dp(1), p)
            2 -> { c.drawLine(cx - r, cy, cx + r, cy, p); c.drawLine(cx, cy - r, cx, cy + r, p) }
            3 -> { c.drawCircle(cx, cy, r * 0.08f + dp(1), p); p.style = Paint.Style.STROKE; c.drawCircle(cx, cy, r * 0.7f, p) }
            4 -> {
                val g = r * 0.3f
                c.drawLine(cx - r, cy, cx - g, cy, p); c.drawLine(cx + g, cy, cx + r, cy, p)
                c.drawLine(cx, cy - r, cx, cy - g, p); c.drawLine(cx, cy + g, cx, cy + r, p)
            }
        }
    }
}

/** Grafik FPS (warna mode) + suhu (kuning) untuk laporan rekaman. */
class ChartView(ctx: Context) : View(ctx) {
    var fps = FloatArray(0)
    var temp = FloatArray(0)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lf = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tx = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val fillPath = Path()

    override fun onDraw(c: Canvas) {
        val padL = dp(32).toFloat(); val padR = dp(32).toFloat()
        val padT = dp(8).toFloat(); val padB = dp(20).toFloat()
        val w = width - padL - padR; val h = height - padT - padB
        grid.color = 0x22FFFFFF; grid.strokeWidth = 1f; grid.style = Paint.Style.STROKE
        tx.textSize = spf(9f); tx.color = Pal.MUTED
        val n = fps.size
        if (n < 2 || w <= 0f || h <= 0f) {
            tx.textAlign = Paint.Align.CENTER
            c.drawText("Data belum cukup", width / 2f, height / 2f, tx); return
        }
        var maxF = 60f
        for (v in fps) if (v > maxF) maxF = v
        maxF = Math.ceil((maxF / 30f).toDouble()).toFloat() * 30f
        var tmin = Float.MAX_VALUE; var tmax = 0f
        for (t in temp) if (t > 0f) { if (t < tmin) tmin = t; if (t > tmax) tmax = t }
        if (tmin == Float.MAX_VALUE) { tmin = 30f; tmax = 40f }
        tmin = Math.floor((tmin - 1f).toDouble()).toFloat(); tmax = Math.ceil((tmax + 1f).toDouble()).toFloat()
        if (tmax - tmin < 4f) tmax = tmin + 4f
        for (i in 0..4) {
            val y = padT + h * i / 4f
            c.drawLine(padL, y, padL + w, y, grid)
            tx.textAlign = Paint.Align.RIGHT
            c.drawText((maxF * (4 - i) / 4f).toInt().toString(), padL - dp(4), y + dp(3), tx)
            tx.textAlign = Paint.Align.LEFT
            c.drawText((tmin + (tmax - tmin) * (4 - i) / 4f).toInt().toString(), padL + w + dp(4), y + dp(3), tx)
        }
        path.reset(); fillPath.reset()
        for (i in 0 until n) {
            val x = padL + w * i / (n - 1)
            val y = padT + h * (1f - (fps[i] / maxF).coerceIn(0f, 1f))
            if (i == 0) { path.moveTo(x, y); fillPath.moveTo(x, padT + h); fillPath.lineTo(x, y) }
            else { path.lineTo(x, y); fillPath.lineTo(x, y) }
        }
        fillPath.lineTo(padL + w, padT + h); fillPath.close()
        fill.shader = LinearGradient(0f, padT, 0f, padT + h, alpha(Skin.a, 120), alpha(Skin.a, 0), Shader.TileMode.CLAMP)
        fill.style = Paint.Style.FILL
        c.drawPath(fillPath, fill)
        lf.style = Paint.Style.STROKE; lf.strokeWidth = dp(2).toFloat(); lf.color = Skin.a; lf.strokeJoin = Paint.Join.ROUND
        c.drawPath(path, lf)
        if (temp.size == n) {
            path.reset()
            var started = false
            for (i in 0 until n) {
                if (temp[i] <= 0f) continue
                val x = padL + w * i / (n - 1)
                val y = padT + h * (1f - ((temp[i] - tmin) / (tmax - tmin)).coerceIn(0f, 1f))
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
            }
            lt.style = Paint.Style.STROKE; lt.strokeWidth = dp(2).toFloat(); lt.color = Pal.WARN
            c.drawPath(path, lt)
        }
        tx.textAlign = Paint.Align.LEFT
        c.drawText("0s", padL, height - dp(5).toFloat(), tx)
        tx.textAlign = Paint.Align.RIGHT
        c.drawText(fmtTime(n), padL + w, height - dp(5).toFloat(), tx)
    }

    private fun fmtTime(sec: Int) = if (sec >= 60) (sec / 60).toString() + "m" + (sec % 60).toString() + "s" else sec.toString() + "s"
}
