package com.alya.gamespace

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.TextView

class ModeDef(val name: String, val icon: Int, val a: Int, val b: Int, val desc: String)

object Modes {
    val all = listOf(
        ModeDef("Hemat", Ic.LEAF, 0xFF34D399.toInt(), 0xFF059669.toInt(), "Baterai irit, suhu adem"),
        ModeDef("Seimbang", Ic.BALANCE, 0xFFA78BFA.toInt(), 0xFF7C3AED.toInt(), "Setelan standar harian"),
        ModeDef("Performa", Ic.ROCKET, 0xFF60A5FA.toInt(), 0xFF2563EB.toInt(), "Lebih ngebut dan halus"),
        ModeDef("Turbo", Ic.FLAME, 0xFFF87171.toInt(), 0xFFDC2626.toInt(), "Maksimal + kunci CPU/GPU")
    )
}

/** Warna aktif mengikuti mode: Hemat hijau, Seimbang ungu, Performa biru, Turbo merah. */
object Skin {
    @Volatile var mode = 2
    val a: Int get() = Modes.all[mode].a
    val b: Int get() = Modes.all[mode].b
}

object Pal {
    val BG0 = 0xFF090814.toInt()
    val BG1 = 0xFF100E24.toInt()
    val CARD = 0xFF17142F.toInt()
    val STROKE = 0xFF2D2858.toInt()
    val TEXT = 0xFFF5F3FF.toInt()
    val MUTED = 0xFFA29CCB.toInt()
    val OK = 0xFF34D399.toInt()
    val BAD = 0xFFF87171.toInt()
    val WARN = 0xFFFBBF24.toInt()
}

fun mix(c1: Int, c2: Int, t: Float): Int {
    fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
    return Color.argb(
        ch(Color.alpha(c1), Color.alpha(c2)), ch(Color.red(c1), Color.red(c2)),
        ch(Color.green(c1), Color.green(c2)), ch(Color.blue(c1), Color.blue(c2))
    )
}

fun alpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Int): Float = v * resources.displayMetrics.density
fun View.dp(v: Int): Int = context.dp(v)
fun View.spf(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

fun Context.tv(text: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

fun rbox(color: Int, radius: Float, strokeColor: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (strokeW > 0) setStroke(strokeW, strokeColor)
}

fun gbox(a: Int, b: Int, radius: Float, o: GradientDrawable.Orientation = GradientDrawable.Orientation.LEFT_RIGHT) =
    GradientDrawable(o, intArrayOf(a, b)).apply { cornerRadius = radius }

fun View.clipRound(radius: Float) {
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, radius) }
    }
    clipToOutline = true
}
