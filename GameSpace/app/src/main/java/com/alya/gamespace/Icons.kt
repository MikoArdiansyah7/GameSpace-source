package com.alya.gamespace

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.TextView

/** Ikon gaming gambar sendiri (pengganti emoji). Data ikon ada di Ic (dibuat oleh icons/gen.py). */
object Icons {
    class Op(val code: Char, val v: FloatArray)
    private val cache = arrayOfNulls<Array<Op>>(Ic.SRC.size)

    fun ops(i: Int): Array<Op> {
        val c = cache[i]
        if (c != null) return c
        val list = ArrayList<Op>()
        for (s in Ic.SRC[i].split("|")) {
            if (s.isEmpty()) continue
            val nums = s.substring(1).split(",").filter { it.isNotEmpty() }.map { it.toFloat() }.toFloatArray()
            list.add(Op(s[0], nums))
        }
        val a = list.toTypedArray()
        cache[i] = a
        return a
    }
}

class IconDrawable(private val idx: Int, var iconColor: Int) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private var a = 255

    private fun poly(v: FloatArray, close: Boolean) {
        path.reset()
        path.moveTo(v[0], v[1])
        var i = 2
        while (i + 1 < v.size) { path.lineTo(v[i], v[i + 1]); i += 2 }
        if (close) path.close()
    }

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty || idx < 0) return
        val s = Math.min(b.width(), b.height()) / 24f
        val save = c.save()
        c.translate(b.left + (b.width() - 24f * s) / 2f, b.top + (b.height() - 24f * s) / 2f)
        c.scale(s, s)
        p.color = iconColor
        p.alpha = Color.alpha(iconColor) * a / 255
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = 2f
        for (op in Icons.ops(idx)) {
            val v = op.v
            when (op.code) {
                'L' -> { p.style = Paint.Style.STROKE; c.drawLine(v[0], v[1], v[2], v[3], p) }
                'C' -> { p.style = Paint.Style.STROKE; c.drawCircle(v[0], v[1], v[2], p) }
                'D' -> { p.style = Paint.Style.FILL; c.drawCircle(v[0], v[1], v[2], p) }
                'P' -> { poly(v, true); p.style = Paint.Style.FILL; c.drawPath(path, p) }
                'S' -> { poly(v, false); p.style = Paint.Style.STROKE; c.drawPath(path, p) }
                'Z' -> { poly(v, true); p.style = Paint.Style.STROKE; c.drawPath(path, p) }
                'R' -> { p.style = Paint.Style.STROKE; c.drawRoundRect(v[0], v[1], v[0] + v[2], v[1] + v[3], v[4], v[4], p) }
                'F' -> { p.style = Paint.Style.FILL; c.drawRoundRect(v[0], v[1], v[0] + v[2], v[1] + v[3], v[4], v[4], p) }
                'A' -> {
                    p.style = Paint.Style.STROKE
                    rect.set(v[0] - v[2], v[1] - v[2], v[0] + v[2], v[1] + v[2])
                    c.drawArc(rect, v[3], v[4], false, p)
                }
            }
        }
        c.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) { a = alpha; invalidateSelf() }
    override fun setColorFilter(f: ColorFilter?) { p.colorFilter = f; invalidateSelf() }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

class IconView(ctx: Context, idx: Int, color: Int) : View(ctx) {
    private var d = IconDrawable(idx, color)
    fun setIcon(i: Int) { d = IconDrawable(i, d.iconColor); invalidate() }
    var tint: Int
        get() = d.iconColor
        set(v) { d.iconColor = v; invalidate() }
    override fun onDraw(c: Canvas) {
        d.setBounds(0, 0, width, height)
        d.draw(c)
    }
}

fun Context.icon(idx: Int, sizeDp: Int, color: Int): IconDrawable {
    val d = IconDrawable(idx, color)
    val px = dp(sizeDp)
    d.setBounds(0, 0, px, px)
    return d
}

/** Ikon di kiri teks (teks rata kiri). */
fun TextView.iconStart(idx: Int, sizeDp: Int, color: Int, padDp: Int = 6) {
    setCompoundDrawables(context.icon(idx, sizeDp, color), null, null, null)
    compoundDrawablePadding = context.dp(padDp)
}
