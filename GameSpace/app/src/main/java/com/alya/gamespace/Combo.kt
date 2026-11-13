package com.alya.gamespace

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import rikka.shizuku.Shizuku

// ───────────── data posisi (per game, per orientasi) ─────────────
class ComboState(
    var n1: Int, var n2: Int,
    val p1: Array<IntArray>, val p2: Array<IntArray>,   // titik: PUSAT layar (px)
    val b1: IntArray, val b2: IntArray                  // tombol combo: pojok kiri-atas (px)
)

object ComboStore {
    const val MAX1 = 10
    const val MAX2 = 4
    private fun key(pkg: String, land: Boolean, f: String) = "cb_" + pkg + "_" + (if (land) "L" else "P") + "_" + f

    private fun get(ctx: Context, k: String, dx: Int, dy: Int): IntArray {
        val p = Prefs.str(ctx, k).split(",")
        if (p.size == 2) { val x = p[0].toIntOrNull(); val y = p[1].toIntOrNull(); if (x != null && y != null) return intArrayOf(x, y) }
        return intArrayOf(dx, dy)
    }

    fun load(ctx: Context, pkg: String, sw: Int, sh: Int): ComboState {
        val land = sw > sh
        val n1 = Prefs.int(ctx, key(pkg, land, "n1"), 2).coerceIn(2, MAX1)
        val n2 = Prefs.int(ctx, key(pkg, land, "n2"), 2).coerceIn(2, MAX2)
        val p1 = Array(MAX1) { i -> get(ctx, key(pkg, land, "p1_$i"), (sw * (0.30f + 0.06f * i)).toInt(), (sh * 0.40f).toInt()) }
        val p2 = Array(MAX2) { i -> get(ctx, key(pkg, land, "p2_$i"), (sw * (0.45f + 0.08f * i)).toInt(), (sh * 0.65f).toInt()) }
        val b1 = get(ctx, key(pkg, land, "b1"), (sw * 0.05f).toInt(), (sh * 0.45f).toInt())
        val b2 = get(ctx, key(pkg, land, "b2"), (sw * 0.05f).toInt(), (sh * 0.65f).toInt())
        return ComboState(n1, n2, p1, p2, b1, b2)
    }

    fun save(ctx: Context, pkg: String, sw: Int, sh: Int, s: ComboState) {
        val land = sw > sh
        Prefs.setInt(ctx, key(pkg, land, "n1"), s.n1); Prefs.setInt(ctx, key(pkg, land, "n2"), s.n2)
        for (i in 0 until MAX1) Prefs.setStr(ctx, key(pkg, land, "p1_$i"), s.p1[i][0].toString() + "," + s.p1[i][1])
        for (i in 0 until MAX2) Prefs.setStr(ctx, key(pkg, land, "p2_$i"), s.p2[i][0].toString() + "," + s.p2[i][1])
        Prefs.setStr(ctx, key(pkg, land, "b1"), s.b1[0].toString() + "," + s.b1[1])
        Prefs.setStr(ctx, key(pkg, land, "b2"), s.b2[0].toString() + "," + s.b2[1])
    }
}

// ───────────── injeksi sentuhan ─────────────
object ComboClient {
    @Volatile var svc: IComboService? = null
    @Volatile var state = "belum terhubung"
    private var args: Shizuku.UserServiceArgs? = null
    private var bound = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            svc = IComboService.Stub.asInterface(b)
            state = try { "terhubung, " + svc?.info() } catch (_: Throwable) { "terhubung (info gagal)" }
            ActionLog.add("Combo injector: $state")
        }
        override fun onServiceDisconnected(n: ComponentName) { svc = null; bound = false; state = "terputus" }
    }

    fun bind(ctx: Context) {
        if (bound || !ShizukuHelper.granted()) return
        try {
            @Suppress("DEPRECATION") val ver = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
            val a = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, ComboUserService::class.java.name))
                .processNameSuffix("combo").daemon(false).version(ver)
            args = a
            Shizuku.bindUserService(a, conn)
            bound = true
            state = "menghubungkan..."
        } catch (t: Throwable) {
            state = "gagal bind: " + t.javaClass.simpleName + " " + t.message
            ActionLog.add(state)
        }
    }

    fun unbind() {
        try { args?.let { Shizuku.unbindUserService(it, conn, true) } } catch (_: Throwable) {}
        svc = null; bound = false
    }

    fun ready(): Boolean = svc != null || ShizukuHelper.granted()

    /** Utama: UserService (cepat & bersamaan). Cadangan: perintah `input tap` lewat shell (lebih lambat). */
    fun tap(xs: IntArray, ys: IntArray, downMs: Int): Boolean {
        val s = svc
        if (s != null) { try { if (s.tap(xs, ys, downMs)) return true } catch (_: Throwable) {} }
        if (!ShizukuHelper.granted()) return false
        val sb = StringBuilder()
        for (i in xs.indices) sb.append("input tap ").append(xs[i]).append(' ').append(ys[i]).append(" & ")
        sb.append("wait")
        Sh.exec(sb.toString(), 3000)
        return true
    }
}

/** Selama tombol ditahan: ulang terus. Bertahap = A,B,C,A,B,C... · Bersama = semua titik sekaligus. */
class ComboRunner(private val tapFn: (IntArray, IntArray, Int) -> Boolean) {
    @Volatile private var gen = 0

    fun start(pts: List<IntArray>, together: Boolean, gapMs: Int, downMs: Int) {
        if (pts.isEmpty()) return
        val my = ++gen
        Thread {
            var i = 0
            while (my == gen) {
                if (together) {
                    val xs = IntArray(pts.size) { pts[it][0] }
                    val ys = IntArray(pts.size) { pts[it][1] }
                    tapFn(xs, ys, downMs)
                } else {
                    val p = pts[i % pts.size]
                    i++
                    tapFn(intArrayOf(p[0]), intArrayOf(p[1]), downMs)
                }
                var left = gapMs.toLong()
                while (left > 0 && my == gen) {
                    val s = minOf(left, 4L)
                    try { Thread.sleep(s) } catch (_: InterruptedException) {}
                    left -= s
                }
            }
        }.start()
    }

    fun stop() { gen++ }
}

// ───────────── tampilan tombol ─────────────
/** Combo 1 (bertahap) = kotak membulat. Combo 2 (bersama) = lingkaran. Warna mengikuti mode. */
fun View.drawCombo(c: Canvas, kind: Int, cx: Float, cy: Float, size: Float, pressed: Boolean, p: Paint, rect: RectF) {
    val half = size / 2f - dp(3)
    rect.set(cx - half, cy - half, cx + half, cy + half)
    val rr = dp(16).toFloat()
    p.shader = null
    p.style = Paint.Style.FILL
    p.color = alpha(Skin.a, if (pressed) 235 else 95)
    if (kind == 1) c.drawRoundRect(rect, rr, rr, p) else c.drawCircle(cx, cy, half, p)
    p.style = Paint.Style.STROKE; p.strokeWidth = dp(2).toFloat() + 1f
    p.color = if (pressed) Color.WHITE else Skin.a
    if (kind == 1) c.drawRoundRect(rect, rr, rr, p) else c.drawCircle(cx, cy, half, p)
    p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER
    p.typeface = Typeface.DEFAULT_BOLD; p.color = Color.WHITE; p.textSize = spf(15f)
    c.drawText(if (kind == 1) "C1" else "C2", cx, cy - dp(1).toFloat(), p)
    p.typeface = Typeface.DEFAULT; p.color = 0xE0FFFFFF.toInt(); p.textSize = spf(7f)
    c.drawText(if (kind == 1) "bertahap" else "bersama", cx, cy + dp(11).toFloat(), p)
}

class ComboButton(ctx: Context, val kind: Int) : View(ctx) {
    var down = false
        set(v) { field = v; alpha = if (v) 1f else 0.85f; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    init { alpha = 0.85f }
    override fun onDraw(c: Canvas) {
        drawCombo(c, kind, width / 2f, height / 2f, width.toFloat(), down, p, rect)
    }
}

// ───────────── editor posisi ─────────────
class EditCanvas(ctx: Context, private val s: ComboState) : View(ctx) {
    private class Hit(val arr: IntArray, val button: Boolean)
    private val loc = IntArray(2)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var hit: Hit? = null
    private val btn get() = dp(60)
    private val rad get() = dp(21)

    override fun onDraw(c: Canvas) {
        getLocationOnScreen(loc)
        val ox = loc[0].toFloat(); val oy = loc[1].toFloat()
        // titik "bersama" (A2..D2): kotak membulat putih-berbingkai
        for (i in 0 until s.n2) {
            val cx = s.p2[i][0] - ox; val cy = s.p2[i][1] - oy
            rect.set(cx - rad, cy - rad, cx + rad, cy + rad)
            p.style = Paint.Style.FILL; p.shader = null; p.color = alpha(Skin.b, 200)
            c.drawRoundRect(rect, dp(9).toFloat(), dp(9).toFloat(), p)
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(2).toFloat(); p.color = Color.WHITE
            c.drawRoundRect(rect, dp(9).toFloat(), dp(9).toFloat(), p)
            label(c, cx, cy, ('A' + i).toString() + "2")
        }
        // titik "bertahap" (A1..J1): lingkaran
        for (i in 0 until s.n1) {
            val cx = s.p1[i][0] - ox; val cy = s.p1[i][1] - oy
            p.style = Paint.Style.FILL; p.shader = null; p.color = alpha(Skin.a, 215)
            c.drawCircle(cx, cy, rad.toFloat(), p)
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(2).toFloat(); p.color = Color.WHITE
            c.drawCircle(cx, cy, rad.toFloat(), p)
            label(c, cx, cy, ('A' + i).toString() + "1")
        }
        // tombol combo
        val h = btn / 2f
        drawCombo(c, 1, s.b1[0] - ox + h, s.b1[1] - oy + h, btn.toFloat(), false, p, rect)
        drawCombo(c, 2, s.b2[0] - ox + h, s.b2[1] - oy + h, btn.toFloat(), false, p, rect)
    }

    private fun label(c: Canvas, cx: Float, cy: Float, t: String) {
        p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.DEFAULT_BOLD
        p.color = Color.WHITE; p.textSize = spf(11f)
        c.drawText(t, cx, cy - (p.ascent() + p.descent()) / 2f, p)
    }

    private fun near(ax: Int, ay: Int, rx: Float, ry: Float, r: Float): Boolean {
        val dx = ax - rx; val dy = ay - ry
        return dx * dx + dy * dy <= r * r
    }

    private fun find(rx: Float, ry: Float): Hit? {
        val h = btn / 2
        if (near(s.b2[0] + h, s.b2[1] + h, rx, ry, h.toFloat())) return Hit(s.b2, true)
        if (near(s.b1[0] + h, s.b1[1] + h, rx, ry, h.toFloat())) return Hit(s.b1, true)
        val r = rad + dp(8).toFloat()
        for (i in s.n2 - 1 downTo 0) if (near(s.p2[i][0], s.p2[i][1], rx, ry, r)) return Hit(s.p2[i], false)
        for (i in s.n1 - 1 downTo 0) if (near(s.p1[i][0], s.p1[i][1], rx, ry, r)) return Hit(s.p1[i], false)
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val dm = resources.displayMetrics
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> hit = find(e.rawX, e.rawY)
            MotionEvent.ACTION_MOVE -> hit?.let { t ->
                val off = if (t.button) btn / 2 else 0
                t.arr[0] = (e.rawX.toInt() - off).coerceIn(0, dm.widthPixels)
                t.arr[1] = (e.rawY.toInt() - off).coerceIn(0, dm.heightPixels)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> hit = null
        }
        return true
    }
}

class ComboEditor(
    private val ctx: Context, private val wm: WindowManager, private val s: ComboState,
    private val gapSeq: Int, private val gapTogether: Int,
    private val onSave: (ComboState) -> Unit, private val onCancel: () -> Unit
) {
    private val root = FrameLayout(ctx)
    private val canvas = EditCanvas(ctx, s)
    private lateinit var info: TextView
    private lateinit var lp: WindowManager.LayoutParams

    init {
        root.setBackgroundColor(0x66000000)
        root.addView(canvas, FrameLayout.LayoutParams(-1, -1))
        root.addView(toolbar(), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = ctx.dp(6) })
        updateInfo()
    }

    private fun toolbar(): View {
        val bar = LinearLayout(ctx)
        bar.orientation = LinearLayout.VERTICAL
        bar.background = rbox(0xEE0B0A17.toInt(), ctx.dpf(16), alpha(Skin.a, 170), ctx.dp(1))
        bar.setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(10), ctx.dp(8))
        info = ctx.tv("", 10.5f, Pal.TEXT, true)
        info.gravity = Gravity.CENTER
        bar.addView(info, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val row = LinearLayout(ctx)
        row.gravity = Gravity.CENTER
        fun b(label: String, icon: Int, primary: Boolean, click: () -> Unit) {
            val box = LinearLayout(ctx)
            box.gravity = Gravity.CENTER
            box.setPadding(ctx.dp(11), ctx.dp(7), ctx.dp(11), ctx.dp(7))
            box.background = if (primary) gbox(Skin.a, Skin.b, ctx.dpf(11)) else rbox(0x22FFFFFF, ctx.dpf(11), 0x33FFFFFF, 1)
            if (icon >= 0) box.addView(IconView(ctx, icon, Color.WHITE),
                LinearLayout.LayoutParams(ctx.dp(14), ctx.dp(14)).apply { if (label.isNotEmpty()) rightMargin = ctx.dp(5) })
            if (label.isNotEmpty()) box.addView(ctx.tv(label, 11f, Color.WHITE, true))
            box.setOnClickListener { click() }
            row.addView(box, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ctx.dp(3), 0, ctx.dp(3), 0) })
        }
        b("A1 −", -1, false) { if (s.n1 > 2) { s.n1--; changed() } }
        b("A1 +", -1, false) { if (s.n1 < ComboStore.MAX1) { s.n1++; changed() } }
        b("A2 −", -1, false) { if (s.n2 > 2) { s.n2--; changed() } }
        b("A2 +", -1, false) { if (s.n2 < ComboStore.MAX2) { s.n2++; changed() } }
        b("Tes", Ic.PLAY, false) { test() }
        b("Simpan", Ic.SAVE, true) { close(); onSave(s) }
        b("", Ic.CLOSE, false) { close(); onCancel() }
        bar.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(6); gravity = Gravity.CENTER_HORIZONTAL })
        val hint = ctx.tv("Seret titik ke posisi tombol di game, lalu Simpan. Setelah disimpan titik disembunyikan.", 9f, Pal.MUTED)
        hint.gravity = Gravity.CENTER
        bar.addView(hint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(4); gravity = Gravity.CENTER_HORIZONTAL })
        return bar
    }

    private fun changed() { updateInfo(); canvas.invalidate() }

    private fun updateInfo() {
        info.text = "Bertahap: A1–" + ('A' + s.n1 - 1) + "1 (" + s.n1 + " titik)   •   Bersama: A2–" + ('A' + s.n2 - 1) + "2 (" + s.n2 + " titik)"
    }

    fun show() {
        val l = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
        l.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) l.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else if (Build.VERSION.SDK_INT >= 28) l.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        lp = l
        try { wm.addView(root, l) } catch (_: Throwable) { onCancel() }
    }

    fun close() { try { wm.removeView(root) } catch (_: Throwable) {} }

    /** Sembunyikan editor sebentar lalu jalankan satu putaran combo supaya posisi bisa diuji. */
    private fun test() {
        root.visibility = View.INVISIBLE
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { wm.updateViewLayout(root, lp) } catch (_: Throwable) {}
        Thread {
            try {
                Thread.sleep(300)
                for (i in 0 until s.n1) {
                    ComboClient.tap(intArrayOf(s.p1[i][0]), intArrayOf(s.p1[i][1]), 22)
                    Thread.sleep(gapSeq.toLong())
                }
                Thread.sleep(350)
                ComboClient.tap(IntArray(s.n2) { s.p2[it][0] }, IntArray(s.n2) { s.p2[it][1] }, 22)
                Thread.sleep(300)
            } catch (_: Throwable) {}
            root.post {
                root.visibility = View.VISIBLE
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                try { wm.updateViewLayout(root, lp) } catch (_: Throwable) {}
            }
        }.start()
    }
}
