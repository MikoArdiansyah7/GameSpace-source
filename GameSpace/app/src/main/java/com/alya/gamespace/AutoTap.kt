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
import android.os.SystemClock
import android.view.View

/** Posisi titik tap dan tombol kontrol, per game dan per orientasi (seperti Combo). */
object AutoTapStore {
    private fun key(pkg: String, land: Boolean, f: String) = "at_" + pkg + "_" + (if (land) "L" else "P") + "_" + f

    private fun get(ctx: Context, k: String, dx: Int, dy: Int, sw: Int, sh: Int): IntArray {
        val p = Prefs.str(ctx, k).split(",")
        if (p.size == 2) {
            val x = p[0].toIntOrNull(); val y = p[1].toIntOrNull()
            if (x != null && y != null) return intArrayOf(x.coerceIn(0, sw), y.coerceIn(0, sh))
        }
        return intArrayOf(dx, dy)
    }

    /** Pusat titik tap (px layar). Bawaan: tengah, agak ke bawah (tempat umum untuk melanjutkan dialog). */
    fun target(ctx: Context, pkg: String, sw: Int, sh: Int): IntArray = get(ctx, key(pkg, sw > sh, "t"), sw / 2, (sh * 0.62f).toInt(), sw, sh)
    fun saveTarget(ctx: Context, pkg: String, sw: Int, sh: Int, x: Int, y: Int) = Prefs.setStr(ctx, key(pkg, sw > sh, "t"), "$x,$y")

    /** Pojok kiri-atas tombol Mulai/Berhenti. */
    fun pill(ctx: Context, pkg: String, sw: Int, sh: Int): IntArray = get(ctx, key(pkg, sw > sh, "p"), (sw * 0.04f).toInt(), (sh * 0.28f).toInt(), sw, sh)
    fun savePill(ctx: Context, pkg: String, sw: Int, sh: Int, x: Int, y: Int) = Prefs.setStr(ctx, key(pkg, sw > sh, "p"), "$x,$y")
}

/** Mengetuk satu titik berulang-ulang di thread sendiri sampai [stop] atau salah satu syarat berhenti terpenuhi. */
class AutoTapRunner(private val tapFn: (IntArray, IntArray, Int) -> Boolean) {
    @Volatile private var gen = 0
    @Volatile var running = false
        private set

    /**
     * [shouldStop] dicek sebelum tiap ketukan (alasan berhenti atau null). [onTap] dipanggil di thread latar tiap ketukan.
     * [onStop] hanya dipanggil bila berhenti sendiri; berhenti lewat [stop] tidak memanggilnya.
     */
    fun start(x: Int, y: Int, baseMs: Int, jitterPct: Int, downMs: Int,
              shouldStop: (Long, Int) -> String?, onTap: (Int) -> Unit, onStop: (String) -> Unit) {
        val my = ++gen
        running = true
        Thread {
            val rnd = java.util.Random()
            val t0 = SystemClock.elapsedRealtime()
            var taps = 0
            var reason = "dihentikan"
            try {
                while (my == gen) {
                    val why = shouldStop(SystemClock.elapsedRealtime() - t0, taps)
                    if (why != null) { reason = why; break }
                    if (!tapFn(intArrayOf(x), intArrayOf(y), downMs)) { reason = "injeksi sentuhan gagal"; break }
                    taps++
                    onTap(taps)
                    var left = AutoTapLogic.nextDelay(baseMs, jitterPct, rnd.nextDouble()).toLong()
                    while (left > 0 && my == gen) {
                        val s = Math.min(left, 5L)
                        try { Thread.sleep(s) } catch (_: InterruptedException) {}
                        left -= s
                    }
                }
            } catch (t: Throwable) {
                reason = "error: " + t.javaClass.simpleName
            } finally {
                if (my == gen) { running = false; onStop(reason) }
            }
        }.also { it.name = "gs-autotap"; it.isDaemon = true }.start()
    }

    fun stop() { gen++; running = false }
}

/** Penanda titik tap (lingkaran bidik). Hijau dan berdenyut tiap ketukan saat berjalan. */
class AutoTapTarget(ctx: Context) : View(ctx) {
    var running = false
    private var pulseAt = 0L
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun pulse() { pulseAt = SystemClock.uptimeMillis(); postInvalidateOnAnimation() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = Math.min(width, height) / 2f - dp(6)
        val col = if (running) 0xFF35E08A.toInt() else Skin.a
        p.style = Paint.Style.FILL
        p.color = Color.argb(48, Color.red(col), Color.green(col), Color.blue(col))
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(2).toFloat(); p.strokeCap = Paint.Cap.ROUND; p.color = col
        c.drawCircle(cx, cy, r, p)
        val g = r * 0.42f
        val o = r + dp(3)
        c.drawLine(cx - o, cy, cx - g, cy, p); c.drawLine(cx + g, cy, cx + o, cy, p)
        c.drawLine(cx, cy - o, cx, cy - g, p); c.drawLine(cx, cy + g, cx, cy + o, p)
        p.style = Paint.Style.FILL
        c.drawCircle(cx, cy, dp(3).toFloat(), p)
        val age = SystemClock.uptimeMillis() - pulseAt
        if (running && age in 0..280) {
            val f = age / 280f
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(2).toFloat()
            p.color = Color.argb((210 * (1f - f)).toInt(), 255, 255, 255)
            c.drawCircle(cx, cy, r * (0.4f + 0.75f * f), p)
            postInvalidateOnAnimation()
        }
    }
}

/** Tombol kecil Mulai/Berhenti tap otomatis. Saat berjalan menampilkan jumlah ketukan. */
class AutoTapPill(ctx: Context) : View(ctx) {
    var running = false
    var count = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = h / 2f
        rect.set(0f, 0f, w, h)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, 0f, h, 0xF21B1838.toInt(), 0xF50B0A17.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, r, r, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1).toFloat()
        p.color = if (running) 0xFF35E08A.toInt() else 0x70FFFFFF
        rect.inset(0.5f, 0.5f)
        c.drawRoundRect(rect, r, r, p)
        // tombol bulat di kiri: segitiga (mulai) atau kotak (berhenti)
        val bx = r; val by = h / 2f; val br = r - dp(4)
        p.style = Paint.Style.FILL
        p.color = if (running) 0xFFFF4D5E.toInt() else 0xFF35E08A.toInt()
        c.drawCircle(bx, by, br, p)
        p.color = Color.WHITE
        val s = br * 0.5f
        if (running) {
            rect.set(bx - s, by - s, bx + s, by + s)
            c.drawRoundRect(rect, dp(2).toFloat(), dp(2).toFloat(), p)
        } else {
            path.reset()
            path.moveTo(bx - s * 0.6f, by - s); path.lineTo(bx + s, by); path.lineTo(bx - s * 0.6f, by + s); path.close()
            c.drawPath(path, p)
        }
        // teks di kanan
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = spf(11f)
        p.textAlign = Paint.Align.LEFT
        p.color = Color.WHITE
        val label = if (running) count.toString() else "TAP"
        c.drawText(label, h + dp(2), by - (p.ascent() + p.descent()) / 2f, p)
    }
}
