package com.alya.gamespace

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

interface PanelCallbacks {
    fun onClose()
    fun onMode(m: Int)
    fun onToggle(id: String, on: Boolean)
    fun onAction(id: String)
    fun onPick(id: String, v: Int)
    fun onSeek(id: String, v: Int)
    fun isOn(id: String): Boolean
}

object Cross {
    val colors = intArrayOf(0xFFFF3B3B.toInt(), 0xFF34D399.toInt(), 0xFF22D3EE.toInt(), 0xFFFBBF24.toInt(), 0xFFFFFFFF.toInt())
}

/** Panel overlay: kartu melayang "liquid glass" (warna mode hanya sebagai aksen), 5 tab, ikon gaming (tanpa emoji). */
class PanelUi(private val ctx: Context, private val cb: PanelCallbacks, val widthPx: Int, private val bodyH: Int,
              private val glass: Boolean) {
    private class Pill(val box: LinearLayout, val icon: IconView, val text: TextView)
    private class Chip(val box: LinearLayout, val icon: IconView, val text: TextView)

    val root = FrameLayout(ctx)
    private val card = LinearLayout(ctx)
    private val body = FrameLayout(ctx)
    private val tiles = LinkedHashMap<String, Tile>()
    private val toggleIds = ArrayList<String>()
    private val tabViews = ArrayList<TextView>()
    private val pages = ArrayList<View>()
    private val modePills = ArrayList<Pill>()
    private val visualPills = ArrayList<TextView>()
    private val styleViews = ArrayList<TextView>()
    private val colorDots = ArrayList<View>()
    private val seeks = ArrayList<SeekBar>()
    private var tab = 0
    private var curMode = 2
    private var visualSel = 0
    private var styleSel = 0
    private var colorSel = 0
    private var gpuFreqs: List<Long> = emptyList()

    private lateinit var dot: View
    private lateinit var sub: TextView
    private lateinit var badge: Pill
    private lateinit var dialCpu: DialView
    private lateinit var dialGpu: DialView
    private lateinit var fpsBig: TextView
    private lateinit var chipRam: Chip
    private lateinit var chipBat: Chip
    private lateinit var chipTemp: Chip
    private lateinit var chipPing: Chip
    private lateinit var status: TextView
    private lateinit var gpuLbl: TextView
    private lateinit var gpuSeek: SeekBar
    private lateinit var briSeek: SeekBar
    private lateinit var volSeek: SeekBar
    private lateinit var comboStatus: TextView
    private lateinit var c1Lbl: TextView
    private lateinit var c2Lbl: TextView
    private lateinit var c1Seek: SeekBar
    private lateinit var c2Seek: SeekBar
    private lateinit var pcSizeLbl: TextView
    private lateinit var pcAlphaLbl: TextView
    private lateinit var pcSizeSeek: SeekBar
    private lateinit var pcAlphaSeek: SeekBar

    private fun dp(v: Int) = ctx.dp(v)
    private fun lpW(top: Int = 0, m: Int = 3, h: Int = -2) =
        LinearLayout.LayoutParams(0, h, 1f).apply { setMargins(dp(m), dp(top), dp(m), 0) }
    private fun lpTop(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    init {
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(12), dp(10), dp(12), dp(12))
        root.addView(card, FrameLayout.LayoutParams(-1, -2))
        buildHeader()
        buildTabs()
        card.addView(body, LinearLayout.LayoutParams(-1, bodyH).apply { topMargin = dp(8) })
        pages.add(buildMain()); pages.add(buildTools()); pages.add(buildCombo()); pages.add(buildHud()); pages.add(buildAdvanced())
        for (p in pages) body.addView(p, FrameLayout.LayoutParams(-1, -1))
        showTab(0)
        sync()
        restyle()
    }

    // ───────── konstruksi ─────────
    private fun iconText(icon: Int, label: String, sizeSp: Float, iconDp: Int, color: Int): Pill {
        val box = LinearLayout(ctx)
        box.gravity = Gravity.CENTER
        val ic = IconView(ctx, icon, color)
        val t = ctx.tv(label, sizeSp, color, true)
        t.maxLines = 1
        box.addView(ic, LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)).apply { rightMargin = dp(4) })
        box.addView(t)
        return Pill(box, ic, t)
    }

    private fun buildHeader() {
        val row = LinearLayout(ctx)
        row.gravity = Gravity.CENTER_VERTICAL
        dot = View(ctx)
        row.addView(dot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(8) })
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.addView(ctx.tv("Game Space", 14f, Pal.TEXT, true))
        sub = ctx.tv("Tidak ada game", 10f, Pal.MUTED)
        sub.maxLines = 1; sub.ellipsize = TextUtils.TruncateAt.END
        col.addView(sub)
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        badge = iconText(Ic.ROCKET, "", 10f, 12, Color.WHITE)
        badge.box.setPadding(dp(10), dp(3), dp(10), dp(3))
        row.addView(badge.box)
        val close = IconView(ctx, Ic.CLOSE, Pal.TEXT)
        close.setPadding(dp(4), dp(4), dp(4), dp(4))
        close.setOnClickListener { cb.onClose() }
        row.addView(close, LinearLayout.LayoutParams(dp(30), dp(26)).apply { leftMargin = dp(8) })
        card.addView(row)
    }

    private fun buildTabs() {
        val row = LinearLayout(ctx)
        val names = listOf("Utama", "Alat", "Combo", "HUD", "Lanjut")
        for (i in names.indices) {
            val t = ctx.tv(names[i], 11f, Pal.TEXT, true)
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(6), 0, dp(6))
            t.setOnClickListener { showTab(i) }
            tabViews.add(t)
            row.addView(t, lpW(0, 2))
        }
        card.addView(row, lpTop(8))
    }

    private fun chip(icon: Int): Chip {
        val box = LinearLayout(ctx)
        box.gravity = Gravity.CENTER
        box.setPadding(0, dp(4), 0, dp(4))
        box.background = rbox(0x1AFFFFFF, dp(10).toFloat(), 0x22FFFFFF, 1)
        val ic = IconView(ctx, icon, Pal.MUTED)
        val t = ctx.tv("--", 10f, Pal.TEXT, true)
        t.maxLines = 1
        box.addView(ic, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(3) })
        box.addView(t)
        return Chip(box, ic, t)
    }

    private fun tile(id: String, icon: Int, label: String, toggle: Boolean): Tile {
        val t = Tile(ctx, icon, label)
        t.setOnClickListener {
            if (toggle) { t.active = !t.active; cb.onToggle(id, t.active) } else cb.onAction(id)
        }
        tiles[id] = t
        if (toggle) toggleIds.add(id)
        return t
    }

    private fun scroller(): Pair<ScrollView, LinearLayout> {
        val sv = ScrollView(ctx)
        sv.isVerticalScrollBarEnabled = false
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        sv.addView(col, FrameLayout.LayoutParams(-1, -2))
        return Pair(sv, col)
    }

    private fun seek(max: Int, onStop: (Int) -> Unit, onChange: ((Int) -> Unit)? = null): SeekBar {
        val s = SeekBar(ctx)
        s.max = max
        s.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser && onChange != null) onChange(p) }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { onStop(sb.progress) }
        })
        seeks.add(s)
        return s
    }

    private fun sec(text: String, top: Int = 10): TextView {
        val t = ctx.tv(text.uppercase(), 9.5f, Pal.MUTED, true)
        t.layoutParams = lpTop(top)
        return t
    }

    private fun pill(text: String, onClick: () -> Unit): TextView {
        val t = ctx.tv(text, 10.5f, Pal.TEXT, true)
        t.gravity = Gravity.CENTER; t.maxLines = 1
        t.setPadding(0, dp(7), 0, dp(7))
        t.setOnClickListener { onClick() }
        return t
    }

    private fun tileRow(vararg ts: Tile): LinearLayout {
        val r = LinearLayout(ctx)
        for (t in ts) r.addView(t, lpW(0, 3, dp(50)))
        return r
    }

    private fun buildMain(): View {
        val (sv, col) = scroller()
        val r1 = LinearLayout(ctx)
        r1.gravity = Gravity.CENTER_VERTICAL
        dialCpu = DialView(ctx).also { it.label = "CPU"; it.unit = "GHz" }
        dialGpu = DialView(ctx).also { it.label = "GPU"; it.unit = "MHz" }
        r1.addView(dialCpu, LinearLayout.LayoutParams(0, dp(50), 1f))
        r1.addView(dialGpu, LinearLayout.LayoutParams(0, dp(50), 1f))
        val fb = LinearLayout(ctx)
        fb.orientation = LinearLayout.VERTICAL; fb.gravity = Gravity.CENTER
        fpsBig = ctx.tv("--", 26f, Skin.a, true)
        fb.addView(fpsBig); fb.addView(ctx.tv("FPS", 9f, Pal.MUTED))
        r1.addView(fb, LinearLayout.LayoutParams(0, dp(50), 0.7f))
        col.addView(r1)

        val r2 = LinearLayout(ctx)
        chipRam = chip(Ic.RAM); chipBat = chip(Ic.BATTERY); chipTemp = chip(Ic.THERMO); chipPing = chip(Ic.WIFI)
        for (c in listOf(chipRam, chipBat, chipTemp, chipPing)) r2.addView(c.box, lpW(0, 2))
        col.addView(r2, lpTop(6))

        val r3 = LinearLayout(ctx)
        for (i in Modes.all.indices) {
            val m = Modes.all[i]
            val p = iconText(m.icon, m.name, 10.5f, 14, Color.WHITE)
            p.box.setPadding(0, dp(7), 0, dp(7))
            p.box.setOnClickListener { curMode = i; stylePills(); cb.onMode(i) }
            modePills.add(p)
            r3.addView(p.box, lpW(0, 2))
        }
        col.addView(r3, lpTop(6))

        col.addView(tileRow(tile("hud", Ic.SPEED, "Overlay", true), tile("hudpc", Ic.MONITOR, "HUD PC", true),
            tile("cross", Ic.CROSSHAIR, "Crosshair", true), tile("rec", Ic.RECORD, "Rekam", true),
            tile("esport", Ic.TROPHY, "E-Sport", true)), lpTop(6))

        // kecerahan & volume langsung di panel
        val r5 = LinearLayout(ctx)
        r5.gravity = Gravity.CENTER_VERTICAL
        r5.addView(IconView(ctx, Ic.SUN, Pal.TEXT), LinearLayout.LayoutParams(dp(18), dp(18)))
        briSeek = seek(254, { cb.onSeek("bright", it + 1) }, { cb.onSeek("bright", it + 1) })
        r5.addView(briSeek, LinearLayout.LayoutParams(0, -2, 1f))
        r5.addView(IconView(ctx, Ic.SPEAKER, Pal.TEXT), LinearLayout.LayoutParams(dp(18), dp(18)))
        volSeek = seek(15, { cb.onSeek("vol", it) }, { cb.onSeek("vol", it) })
        r5.addView(volSeek, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(r5, lpTop(4))

        status = ctx.tv("", 9.5f, Pal.MUTED)
        status.gravity = Gravity.CENTER
        status.setPadding(0, dp(2), 0, 0)
        col.addView(status)
        return sv
    }

    private fun buildTools(): View {
        val (sv, col) = scroller()
        val defs = listOf(
            Triple("shot", Ic.SHOT, "Screenshot"), Triple("srec", Ic.VIDEO, "Rekam Layar"),
            Triple("boost", Ic.ROCKET, "Boost RAM"), Triple("clean", Ic.TRASH, "Bersih Cache"),
            Triple("dnd", Ic.BELL_OFF, "Jgn Ganggu"), Triple("bright", Ic.SUN, "Kunci Cerah"),
            Triple("rot", Ic.LOCK, "Kunci Rotasi"), Triple("guard", Ic.SHIELD, "Abaikan Sentuh"),
            Triple("anim", Ic.FILM, "Animasi Off"), Triple("adaptive", Ic.BATTERY, "Adaptive Off"),
            Triple("limit", Ic.BLOCK, "App Latar"), Triple("touch", Ic.POINTER, "Respon Sentuh")
        )
        val actionIds = setOf("shot", "boost", "clean")
        var row: LinearLayout? = null
        for (i in defs.indices) {
            if (i % 4 == 0) { row = LinearLayout(ctx); col.addView(row, lpTop(if (i == 0) 0 else 6)) }
            val d = defs[i]
            row!!.addView(tile(d.first, d.second, d.third, d.first !in actionIds), lpW(0, 3, dp(56)))
        }
        return sv
    }

    private fun buildCombo(): View {
        val (sv, col) = scroller()
        comboStatus = ctx.tv("", 10f, Pal.MUTED)
        col.addView(comboStatus)
        col.addView(tileRow(tile("combo", Ic.GAMES, "Combo ON", true), tile("c1", Ic.FASTFWD, "C1 Bertahap", true),
            tile("c2", Ic.MULTI, "C2 Bersama", true), tile("cedit", Ic.EDIT, "Edit posisi", false)), lpTop(6))
        col.addView(sec("Kecepatan bertahap (jeda antar tap)"))
        c1Lbl = ctx.tv("60 ms", 11f, Skin.a, true)
        col.addView(c1Lbl, lpTop(2))
        c1Seek = seek(280, { cb.onSeek("c1ms", it + 20) }, { c1Lbl.text = (it + 20).toString() + " ms" })
        col.addView(c1Seek, lpTop(0))
        col.addView(sec("Kecepatan bersama (jeda antar tap)"))
        c2Lbl = ctx.tv("80 ms", 11f, Skin.a, true)
        col.addView(c2Lbl, lpTop(2))
        c2Seek = seek(280, { cb.onSeek("c2ms", it + 20) }, { c2Lbl.text = (it + 20).toString() + " ms" })
        col.addView(c2Seek, lpTop(0))
        val hint = ctx.tv("Tahan tombol C1/C2 untuk menjalankan, lepas untuk berhenti.\n" +
            "C1: titik A1→B1→C1... berulang (maks 10).  C2: titik A2+B2+C2+D2 sekaligus (maks 4).\n" +
            "Posisi disimpan per game. Setelah Simpan, titik disembunyikan dan tombol tidak bisa bergeser.\n" +
            "Combo adalah auto-tap: pakai di game offline / yang membolehkan.", 9.5f, Pal.MUTED)
        col.addView(hint, lpTop(8))
        return sv
    }

    /** Tab HUD: isi Overlay FPS (kapsul) dan HUD Display ala PC. Keduanya terpisah, bisa dinyalakan bersamaan. */
    private fun buildHud(): View {
        val (sv, col) = scroller()
        col.addView(sec("Overlay FPS (kapsul)", 0))
        col.addView(tileRow(tile("hi_fps", Ic.SPEED, "FPS", true), tile("hi_net", Ic.SIGNAL, "Ping", true),
            tile("hi_cpu", Ic.CPU, "CPU", true), tile("hi_gpu", Ic.GPU, "GPU", true)), lpTop(4))
        col.addView(tileRow(tile("hi_ram", Ic.RAM, "RAM", true), tile("hi_bat", Ic.BATTERY, "Baterai", true),
            tile("hi_time", Ic.CLOCK, "Jam", true), tile("hi_temp", Ic.THERMO, "Suhu", true)), lpTop(6))
        col.addView(tileRow(tile("hdrag", Ic.POINTER, "Geser HUD", true), tile("hudreset", Ic.REFRESH, "Reset posisi", false)), lpTop(6))

        col.addView(sec("HUD Display (gaya PC)"))
        col.addView(tileRow(tile("pc_gfx", Ic.GAMES, "Render", true), tile("pc_gpu", Ic.GPU, "GPU", true),
            tile("pc_cpu", Ic.CPU, "CPU", true), tile("pc_ram", Ic.RAM, "RAM", true)), lpTop(4))
        col.addView(tileRow(tile("pc_pwr", Ic.ZAP, "Daya", true), tile("pc_tmp", Ic.THERMO, "Suhu", true),
            tile("pc_fps", Ic.SPEED, "FPS", true), tile("pc_graph", Ic.CHART, "Grafik", true)), lpTop(6))
        col.addView(tileRow(tile("pc_fpscolor", Ic.PALETTE, "FPS warna", true), tile("pcreset", Ic.REFRESH, "Reset posisi", false)), lpTop(6))
        pcSizeLbl = ctx.tv("Ukuran 130%", 11f, Skin.a, true)
        col.addView(pcSizeLbl, lpTop(8))
        pcSizeSeek = seek(140, { cb.onSeek("pcsize", it + 60) }, { pcSizeLbl.text = "Ukuran " + (it + 60) + "%"; cb.onSeek("pcsize", it + 60) })
        col.addView(pcSizeSeek, lpTop(0))
        pcAlphaLbl = ctx.tv("Transparansi latar 55%", 11f, Skin.a, true)
        col.addView(pcAlphaLbl, lpTop(4))
        pcAlphaSeek = seek(100, { cb.onSeek("pcalpha", it) }, { pcAlphaLbl.text = "Transparansi latar $it%"; cb.onSeek("pcalpha", it) })
        col.addView(pcAlphaSeek, lpTop(0))
        col.addView(ctx.tv("HUD Display di pojok kiri bawah, huruf besar. Nyalakan lewat tombol HUD PC di tab Utama. Bila ukuran terlalu besar, otomatis dikecilkan agar tetap muat di layar.", 9.5f, Pal.MUTED), lpTop(6))
        return sv
    }

    private fun buildAdvanced(): View {
        val (sv, col) = scroller()
        col.addView(sec("GPU Lock", 0))
        gpuLbl = ctx.tv("--", 11f, Skin.a, true)
        col.addView(gpuLbl, lpTop(2))
        gpuSeek = seek(1, { cb.onSeek("gpu", it) }, { updateGpuLabel(it) })
        col.addView(gpuSeek, lpTop(0))
        col.addView(tileRow(tile("gpulock", Ic.GPU, "Kunci GPU", true), tile("cpulock", Ic.CPU, "Kunci CPU", true)), lpTop(4))

        col.addView(sec("Tampilan"))
        val vr = LinearLayout(ctx)
        val vn = listOf("Normal", "Vivid", "HDR", "Soft")
        for (i in vn.indices) {
            val p = pill(vn[i]) { visualSel = i; stylePills(); cb.onPick("visual", i) }
            visualPills.add(p); vr.addView(p, lpW(0, 2))
        }
        col.addView(vr, lpTop(4))

        col.addView(sec("Crosshair"))
        val sr = LinearLayout(ctx)
        val sn = listOf("Mati", "Titik", "Silang", "Cincin", "Plus")
        for (i in sn.indices) {
            val p = pill(sn[i]) { styleSel = i; stylePills(); cb.onPick("cstyle", i) }
            styleViews.add(p); sr.addView(p, lpW(0, 2))
        }
        col.addView(sr, lpTop(4))
        val cr = LinearLayout(ctx)
        cr.gravity = Gravity.CENTER
        for (i in Cross.colors.indices) {
            val d = View(ctx)
            d.setOnClickListener { colorSel = i; stylePills(); cb.onPick("ccolor", i) }
            colorDots.add(d)
            cr.addView(d, LinearLayout.LayoutParams(dp(26), dp(26)).apply { setMargins(dp(6), 0, dp(6), 0) })
        }
        col.addView(cr, lpTop(8))
        col.addView(seek(72, { cb.onSeek("csize", it + 24) }, { cb.onSeek("csize", it + 24) }), lpTop(4))
        return sv
    }

    // ───────── tampilan & warna ─────────
    private fun showTab(i: Int) {
        tab = i
        for (j in pages.indices) pages[j].visibility = if (j == i) View.VISIBLE else View.GONE
        styleTabs()
    }

    private fun styleTabs() {
        for (i in tabViews.indices) {
            val t = tabViews[i]
            if (i == tab) { t.background = gbox(Skin.a, Skin.b, dp(12).toFloat()); t.setTextColor(Color.WHITE) }
            else { t.background = null; t.setTextColor(Pal.MUTED) }
        }
    }

    private fun stylePills() {
        for (i in modePills.indices) {
            val m = Modes.all[i]
            val p = modePills[i]
            val on = i == curMode
            p.box.background = if (on) gbox(m.a, m.b, dp(12).toFloat(), GradientDrawable.Orientation.TL_BR)
            else rbox(alpha(m.a, 36), dp(12).toFloat(), alpha(m.a, 150), 1)
            val c = if (on) Color.WHITE else m.a
            p.text.setTextColor(c); p.icon.tint = c
        }
        fun mark(p: TextView, on: Boolean) {
            if (on) { p.background = gbox(Skin.a, Skin.b, dp(10).toFloat()); p.setTextColor(Color.WHITE) }
            else { p.background = rbox(0x1AFFFFFF, dp(10).toFloat(), 0x22FFFFFF, 1); p.setTextColor(Pal.TEXT) }
        }
        for (i in visualPills.indices) mark(visualPills[i], i == visualSel)
        for (i in styleViews.indices) mark(styleViews[i], i == styleSel)
        for (i in colorDots.indices) {
            val c = Cross.colors[i]
            colorDots[i].background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(c)
                setStroke(dp(if (i == colorSel) 3 else 1), if (i == colorSel) Color.WHITE else 0x55FFFFFF)
            }
        }
    }

    /** Dipanggil saat mode berubah. Kartu kaca netral; warna mode hanya untuk aksen. */
    fun restyle() {
        val a = Skin.a
        card.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            if (glass) intArrayOf(0x38FFFFFF, 0x8C0A0914.toInt())
            else intArrayOf(0xF51F1C3C.toInt(), 0xF50B0A17.toInt())).apply {
            cornerRadius = dp(20).toFloat(); setStroke(dp(1), if (glass) 0x70FFFFFF else 0x44FFFFFF)
        }
        dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(a) }
        badge.text.text = Modes.all[curMode].name
        badge.icon.tint = Color.WHITE
        badge.box.background = gbox(Skin.a, Skin.b, dp(10).toFloat())
        fpsBig.setTextColor(a); gpuLbl.setTextColor(a); c1Lbl.setTextColor(a); c2Lbl.setTextColor(a)
        pcSizeLbl.setTextColor(a); pcAlphaLbl.setTextColor(a)
        for (t in tiles.values) t.refresh()
        for (s in seeks) {
            s.progressTintList = ColorStateList.valueOf(a)
            s.thumbTintList = ColorStateList.valueOf(a)
            s.progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
        }
        styleTabs(); stylePills()
        dialCpu.invalidate(); dialGpu.invalidate()
    }

    fun setMode(m: Int) { curMode = m; restyle() }

    fun sync() {
        for (id in toggleIds) tiles[id]?.active = cb.isOn(id)
    }

    fun setPicks(visual: Int, style: Int, color: Int) {
        visualSel = visual; styleSel = style; colorSel = color; stylePills()
    }

    fun setLevels(bri: Int, vol: Int, volMax: Int) {
        briSeek.progress = (bri - 1).coerceIn(0, 254)
        volSeek.max = maxOf(1, volMax)
        volSeek.progress = vol.coerceIn(0, maxOf(1, volMax))
    }

    fun setPc(size: Int, alphaPct: Int) {
        pcSizeSeek.progress = (size - 60).coerceIn(0, 140); pcSizeLbl.text = "Ukuran $size%"
        pcAlphaSeek.progress = alphaPct.coerceIn(0, 100); pcAlphaLbl.text = "Transparansi latar $alphaPct%"
    }

    fun setCombo(c1: Int, c2: Int, statusText: String) {
        c1Seek.progress = (c1 - 20).coerceIn(0, 280); c1Lbl.text = "$c1 ms"
        c2Seek.progress = (c2 - 20).coerceIn(0, 280); c2Lbl.text = "$c2 ms"
        comboStatus.text = statusText
    }

    fun setGpu(freqs: List<Long>, sel: Int, locked: Long, capable: Boolean) {
        gpuFreqs = freqs
        gpuSeek.max = maxOf(1, freqs.size - 1)
        gpuSeek.isEnabled = freqs.size > 1
        gpuSeek.progress = sel.coerceIn(0, maxOf(0, freqs.size - 1))
        updateGpuLabel(gpuSeek.progress)
        if (locked > 0) gpuLbl.text = "Terkunci " + (locked / 1000) + " MHz"
        else if (!capable && freqs.isNotEmpty()) gpuLbl.text = gpuLbl.text.toString() + "  (butuh akses root)"
    }

    private fun updateGpuLabel(i: Int) {
        gpuLbl.text = if (gpuFreqs.isEmpty()) "Frekuensi GPU tidak terbaca"
        else (gpuFreqs[i.coerceIn(0, gpuFreqs.size - 1)] / 1000).toString() + " MHz"
    }

    fun update(s: Snap, gameLabel: String, statusText: String) {
        sub.text = if (gameLabel.isEmpty()) "Tidak ada game" else gameLabel
        dialCpu.known = s.cpuLoad >= 0; dialCpu.pct = if (s.cpuLoad >= 0) s.cpuLoad.toFloat() else 0f
        dialCpu.value = if (s.cpuGhz > 0) "%.2f".format(s.cpuGhz) else "--"
        dialGpu.known = s.gpuLoad >= 0; dialGpu.pct = if (s.gpuLoad >= 0) s.gpuLoad.toFloat() else 0f
        dialGpu.value = if (s.gpuMhz > 0) s.gpuMhz.toString() else "--"
        fpsBig.text = if (s.fps > 0) s.fps.toInt().toString() else "--"
        fpsBig.setTextColor(if (s.fps > 0) Logic.fpsColor(s.fps) else Skin.a)
        chipRam.text.text = "RAM " + s.ram + "%"
        chipBat.text.text = s.battery.toString() + "%"
        chipBat.icon.tint = if (s.charging) Pal.OK else Pal.MUTED
        chipTemp.text.text = (if (s.cpuTemp > 0) "%.0f°·".format(s.cpuTemp) else "") + "%.1f°".format(s.temp)
        chipPing.text.text = if (s.ping > 0) s.ping.toString() + "ms" else "--"
        status.text = statusText
    }
}
