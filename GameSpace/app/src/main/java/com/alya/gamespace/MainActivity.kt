package com.alya.gamespace

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private class AppItem(val label: String, val pkg: String, val icon: Drawable)

    private val REQ_MUSIC = 100
    private val REQ_HERO = 200
    private val REQ_DIR = 300
    private val recListener: () -> Unit = { runOnUiThread { if (tab == 2) render() } }
    private lateinit var root: FrameLayout
    private var tab = 0
    private var openRec: String? = null
    private var pendingSlot = 0
    private val ins = intArrayOf(0, 0, 0, 0)
    private val apps = ArrayList<AppItem>()
    private val selected = LinkedHashSet<String>()
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { render() } }
    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { render() } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        selected.addAll(Prefs.games(this))
        Skin.mode = Prefs.autoMode(this)
        root = FrameLayout(this)
        root.setBackgroundColor(Pal.BG0)
        root.setOnApplyWindowInsetsListener { _, i ->
            val n = IntArray(4)
            if (Build.VERSION.SDK_INT >= 30) {
                val x = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                n[0] = x.left; n[1] = x.top; n[2] = x.right; n[3] = x.bottom
            } else {
                @Suppress("DEPRECATION") run { n[0] = i.systemWindowInsetLeft; n[1] = i.systemWindowInsetTop; n[2] = i.systemWindowInsetRight; n[3] = i.systemWindowInsetBottom }
            }
            if (!n.contentEquals(ins)) { for (k in 0..3) ins[k] = n[k]; render() }
            i
        }
        setContentView(root)
        try { Shizuku.addRequestPermissionResultListener(permListener) } catch (_: Throwable) {}
        try { Shizuku.addBinderReceivedListenerSticky(binderListener) } catch (_: Throwable) {}
        render()
        loadApps()
        root.post { maybeWhatsNew(); autoCheckUpdate() }
    }

    override fun onStart() {
        super.onStart()
        RecorderService.listeners.add(recListener)
        // musik otomatis dari slot pilihan saat aplikasi dibuka
        if (Prefs.bool(this, "mus_auto", true) && !Music.userPaused) {
            if (Music.current >= 0) Music.resume()
            else {
                val i = if (Music.filled(this, Music.selected(this))) Music.selected(this) else Music.firstFilled(this)
                if (i >= 0) Music.play(this, i)
            }
        }
    }

    override fun onResume() { super.onResume(); render() }

    override fun onStop() {
        super.onStop()
        RecorderService.listeners.remove(recListener)
        root.removeCallbacks(recTick)
        if (Music.playing) Music.pause()
    }

    override fun onDestroy() {
        try { Shizuku.removeRequestPermissionResultListener(permListener) } catch (_: Throwable) {}
        try { Shizuku.removeBinderReceivedListener(binderListener) } catch (_: Throwable) {}
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data
        if (res != RESULT_OK || uri == null) return
        if (req == REQ_MUSIC) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
            Music.set(this, pendingSlot, uri, displayName(uri))
            Prefs.setInt(this, "mus_sel", pendingSlot)
            Music.userPaused = false
            if (!Music.play(this, pendingSlot)) Toast.makeText(this, "Lagu tidak bisa diputar, coba file lain", Toast.LENGTH_LONG).show()
            render()
        } else if (req == REQ_DIR) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Throwable) {}
            Prefs.setStr(this, "rec_dir", uri.toString())
            Toast.makeText(this, "Folder rekaman: " + RecStorage.label(this), Toast.LENGTH_SHORT).show()
            render()
        } else if (req == REQ_HERO) {
            Toast.makeText(this, "Memproses foto...", Toast.LENGTH_SHORT).show()
            Thread {
                val ok = HeroPhoto.import(this, uri)
                runOnUiThread {
                    Toast.makeText(this, if (ok) "Foto diganti & di-crop otomatis" else "Foto tidak bisa dibaca", Toast.LENGTH_SHORT).show()
                    render()
                }
            }.start()
        }
    }

    private fun displayName(uri: Uri): String {
        var n = ""
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) n = it.getString(0) ?: ""
            }
        } catch (_: Throwable) {}
        if (n.isEmpty()) n = uri.lastPathSegment ?: "Lagu"
        val dot = n.lastIndexOf('.')
        return if (dot > 0) n.substring(0, dot) else n
    }

    // ───────── kerangka ─────────
    private fun lpTop(t: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(t) }
    private fun lpW(l: Int = 3) = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(l), 0, dp(l), 0) }

    private fun render() {
        root.removeAllViews()
        root.addView(backdrop(), FrameLayout.LayoutParams(-1, -1))
        val body = LinearLayout(this)
        body.setPadding(ins[0], ins[1], ins[2], ins[3])
        body.addView(rail(), LinearLayout.LayoutParams(dp(80), -1))
        body.addView(content(), LinearLayout.LayoutParams(0, -1, 1f))
        root.removeCallbacks(recTick)
        if (tab == 2) root.postDelayed(recTick, 1000)
        root.addView(body, FrameLayout.LayoutParams(-1, -1))
    }

    private fun backdrop(): View {
        val f = FrameLayout(this)
        val img = ImageView(this)
        val custom = HeroPhoto.bgBitmap(this)
        if (custom != null) img.setImageBitmap(custom) else img.setImageResource(R.drawable.art_bg)
        img.scaleType = ImageView.ScaleType.CENTER_CROP; img.alpha = 0.55f
        val ov = View(this)
        ov.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(alpha(Pal.BG0, 238), alpha(mix(Skin.b, Pal.BG0, 0.82f), 228)))
        f.addView(img, FrameLayout.LayoutParams(-1, -1)); f.addView(ov, FrameLayout.LayoutParams(-1, -1))
        return f
    }

    private fun rail(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL; col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(8), dp(14), dp(8), dp(14))
        col.setBackgroundColor(0x77050410)
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.art_icon)
        col.addView(logo, LinearLayout.LayoutParams(dp(48), dp(48)).apply { bottomMargin = dp(14) })
        val items = listOf(Pair(Ic.HOME, "Beranda"), Pair(Ic.GAMES, "Game"), Pair(Ic.VIDEO, "Rekam"), Pair(Ic.CHART, "Laporan"), Pair(Ic.GEAR, "Info"))
        for (i in items.indices) {
            val v = LinearLayout(this)
            v.orientation = LinearLayout.VERTICAL; v.gravity = Gravity.CENTER
            v.setPadding(0, dp(6), 0, dp(6))
            if (tab == i) v.background = gbox(Skin.a, Skin.b, dpf(14), GradientDrawable.Orientation.TL_BR)
            v.addView(IconView(this, items[i].first, if (tab == i) Color.WHITE else Pal.MUTED), LinearLayout.LayoutParams(dp(24), dp(24)).apply { bottomMargin = dp(2) })
            v.addView(tv(items[i].second, 9.5f, if (tab == i) Color.WHITE else Pal.MUTED, true))
            v.setOnClickListener { tab = i; openRec = null; render() }
            col.addView(v, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
        return col
    }

    private fun content(): View {
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        val page = when (tab) { 0 -> pageHome(); 1 -> pageGames(); 2 -> pageRecord(); 3 -> pageRecords(); else -> pageInfo() }
        sv.addView(page, FrameLayout.LayoutParams(-1, -2))
        return sv
    }

    private fun pageCol(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(18), dp(14), dp(18), dp(20))
        return c
    }

    private fun pageTitle(t: String, s: String): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.addView(tv(t, 20f, Pal.TEXT, true))
        c.addView(tv(s, 11f, Pal.MUTED))
        return c
    }

    private fun sectionTitle(t: String) = tv(t, 13f, Pal.TEXT, true)

    private fun card(pad: Int = 14): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        c.background = rbox(alpha(Pal.CARD, 235), dpf(18), Pal.STROKE, 1)
        return c
    }

    private fun btn(text: String, primary: Boolean, icon: Int = -1, click: () -> Unit): View {
        val b = LinearLayout(this)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(14), dp(10), dp(14), dp(10))
        b.background = if (primary) gbox(Skin.a, Skin.b, dpf(14)) else rbox(0x1AFFFFFF, dpf(14), alpha(Skin.a, 150), 1)
        if (icon >= 0) b.addView(IconView(this, icon, Color.WHITE), LinearLayout.LayoutParams(dp(16), dp(16)).apply { if (text.isNotEmpty()) rightMargin = dp(8) })
        if (text.isNotEmpty()) { val t = tv(text, 12f, Color.WHITE, true); t.maxLines = 1; b.addView(t) }
        b.setOnClickListener { click() }
        return b
    }

    /** Kotak pemberitahuan kecil berwarna dengan ikon. */
    private fun notice(text: String, icon: Int, color: Int, click: () -> Unit): View {
        val c = tv(text, 11f, color, true)
        c.iconStart(icon, 14, color, 8)
        c.setPadding(dp(12), dp(9), dp(12), dp(9))
        c.background = rbox(alpha(color, 30), dpf(12), alpha(color, 120), 1)
        c.setOnClickListener { click() }
        return c
    }

    private fun styledSeek(max: Int, progress: Int, onChange: (Int) -> Unit): SeekBar {
        val s = SeekBar(this)
        s.max = max; s.progress = progress
        s.progressTintList = ColorStateList.valueOf(Skin.a)
        s.thumbTintList = ColorStateList.valueOf(Skin.a)
        s.progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
        s.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        return s
    }

    // ───────── Beranda ─────────
    private fun pageHome(): View {
        val running = AlyaAiService.running
        val wrap = LinearLayout(this)
        wrap.setPadding(dp(16), dp(14), dp(16), dp(18))
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        left.addView(hero(running), LinearLayout.LayoutParams(-1, dp(182)))
        left.addView(sectionTitle("Izin & akses"), lpTop(14))
        left.addView(permissions(), lpTop(6))
        if (File(filesDir, "crash.txt").exists()) {
            left.addView(notice("Aplikasi sempat crash. Ketuk untuk lihat laporan", Ic.WARN, Pal.WARN) { showCrash() }, lpTop(10))
        }
        val pend = GameActions(this).pendingBackground()
        if (pend > 0 && !AlyaAiService.inGame) {
            left.addView(notice("$pend app masih dibatasi di latar. Ketuk untuk memulihkan", Ic.WARN, Pal.WARN) { restoreNow() }, lpTop(10))
        }
        if (Prefs.int(this, "upd_code", 0) > versionCodeNow()) {
            left.addView(notice("Pembaruan v" + Prefs.str(this, "upd_name", "?") + " tersedia. Ketuk untuk memperbarui", Ic.UP, Pal.OK) { updateDialog(storedUpdate()) }, lpTop(10))
        }
        lastSessionCard()?.let { left.addView(it, lpTop(10)) }
        left.addView(musicCard(), lpTop(12))
        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        val hz = GameActions(this).maxHz()
        right.addView(sectionTitle("Mode otomatis"))
        right.addView(tv("Layar kamu: $hz Hz  •  warna tema mengikuti mode", 10.5f, Pal.MUTED), lpTop(2))
        right.addView(modeGrid(hz), lpTop(8))
        right.addView(gameSummary(), lpTop(10))
        wrap.addView(left, LinearLayout.LayoutParams(0, -2, 1.18f))
        wrap.addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) })
        return wrap
    }

    private fun hero(running: Boolean): View {
        val f = FrameLayout(this)
        val c1 = mix(Skin.a, Pal.BG0, 0.55f)
        val c2 = mix(Skin.b, Pal.BG0, 0.35f)
        f.background = gbox(c1, c2, dpf(22), GradientDrawable.Orientation.TL_BR)
        f.clipRound(dpf(22))
        val art = ImageView(this)
        val custom = HeroPhoto.bitmap(this)
        if (custom != null) art.setImageBitmap(custom) else art.setImageResource(R.drawable.art_hero)
        art.scaleType = ImageView.ScaleType.CENTER_CROP
        f.addView(art, FrameLayout.LayoutParams(dp(250), -1, Gravity.END))
        val edge = mix(c1, c2, 0.7f)
        val fade = View(this)
        fade.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(edge, alpha(edge, 0)))
        f.addView(fade, FrameLayout.LayoutParams(dp(120), -1, Gravity.END).apply { rightMargin = dp(130) })
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL; col.gravity = Gravity.CENTER_VERTICAL
        col.setPadding(dp(18), dp(14), dp(10), dp(14))
        val ver = tv("v" + appVersion(), 10f, Color.WHITE, true)
        ver.setPadding(dp(9), dp(2), dp(9), dp(2)); ver.background = rbox(0x33FFFFFF, dpf(8))
        col.addView(ver, LinearLayout.LayoutParams(-2, -2))
        col.addView(tv("Game Space", 28f, Color.WHITE, true), lpTop(6))
        col.addView(tv("Alya AI Service", 12f, 0xCCFFFFFF.toInt()))
        val stc = if (running) 0xFFB9F6CA.toInt() else 0xAAFFFFFF.toInt()
        val stv = tv(if (running) "Aktif, menunggu game" else "Nonaktif", 11f, stc, true)
        stv.iconStart(if (running) Ic.DOT else Ic.RING, 10, stc, 6)
        col.addView(stv, lpTop(8))
        val b = tv(if (running) "Hentikan" else "Mulai", 13f, Color.WHITE, true)
        b.iconStart(if (running) Ic.STOP else Ic.PLAY, 14, Color.WHITE, 8)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(24), dp(10), dp(24), dp(10))
        b.background = if (running) gbox(0xFFF87171.toInt(), 0xFFB91C1C.toInt(), dpf(16)) else gbox(Skin.a, Skin.b, dpf(16))
        b.setOnClickListener { toggleService() }
        col.addView(b, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        f.addView(col, FrameLayout.LayoutParams(-2, -1))
        return f
    }

    private fun permChip(text: String, ok: Boolean, click: () -> Unit): TextView {
        val t = tv(text, 11f, if (ok) Pal.OK else Pal.WARN, true)
        t.maxLines = 1; t.ellipsize = TextUtils.TruncateAt.END
        t.setPadding(dp(10), dp(9), dp(10), dp(9))
        val c = if (ok) Pal.OK else Pal.WARN
        t.iconStart(if (ok) Ic.CHECK else Ic.WARN, 13, c, 6)
        t.background = rbox(alpha(c, 26), dpf(12), alpha(c, 110), 1)
        t.setOnClickListener { if (!ok) click() }
        return t
    }

    private fun permissions(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val r1 = LinearLayout(this); val r2 = LinearLayout(this)
        r1.addView(permChip("Tampil di atas app", Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }, lpW(0))
        r1.addView(permChip("Akses penggunaan", Perm.usage(this) || ShizukuHelper.granted()) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }, lpW(6))
        val notifOk = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        r2.addView(permChip("Notifikasi", notifOk) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }, lpW(0))
        val shz = if (ShizukuHelper.granted()) "Shizuku " + (if (ShizukuHelper.serverUid() == 0) "(root)" else "(ADB)")
        else if (ShizukuHelper.running()) "Izinkan Shizuku" else "Aktifkan Shizuku"
        r2.addView(permChip(shz, ShizukuHelper.granted()) {
            if (!ShizukuHelper.running()) {
                val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (i != null) startActivity(i) else Toast.makeText(this, "Install dan jalankan Shizuku dulu", Toast.LENGTH_LONG).show()
            } else ShizukuHelper.request()
        }, lpW(6))
        col.addView(r1); col.addView(r2, lpTop(6))
        return col
    }

    /** Deskripsi mode memakai refresh rate layar yang sebenarnya (bukan asal 120). */
    private fun modeDesc(i: Int, hz: Int): String = when (i) {
        0 -> "Maks " + Logic.modeCap(0, hz) + " FPS • baterai irit"
        1 -> if (hz > 60) "Maks " + Logic.modeCap(1, hz) + " FPS • turun 60 jika ≥38°C" else "Layar 60 Hz • setelan standar"
        2 -> if (hz > 60) "Maks $hz FPS • turun 60 jika ≥40°C" else "Layar 60 Hz • performa maksimal"
        else -> "Maks $hz FPS • kunci CPU/GPU"
    }

    private fun modeGrid(hz: Int): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        for (r in 0..1) {
            val row = LinearLayout(this)
            for (c in 0..1) {
                val i = r * 2 + c
                val m = Modes.all[i]
                val sel = Prefs.autoMode(this) == i
                val cell = LinearLayout(this)
                cell.orientation = LinearLayout.VERTICAL
                cell.setPadding(dp(12), dp(10), dp(10), dp(10))
                cell.background = if (sel) gbox(m.a, m.b, dpf(18), GradientDrawable.Orientation.TL_BR)
                else rbox(alpha(Pal.CARD, 235), dpf(18), alpha(m.a, 120), 1)
                val top = LinearLayout(this)
                top.gravity = Gravity.CENTER_VERTICAL
                top.addView(IconView(this, m.icon, if (sel) Color.WHITE else m.a), LinearLayout.LayoutParams(dp(20), dp(20)))
                top.addView(tv("  " + m.name, 14f, if (sel) Color.WHITE else m.a, true))
                cell.addView(top)
                cell.addView(tv(modeDesc(i, hz), 9.5f, if (sel) 0xEEFFFFFF.toInt() else Pal.MUTED), lpTop(4))
                cell.setOnClickListener { Prefs.setInt(this, "auto", i); Skin.mode = i; render() }
                row.addView(cell, LinearLayout.LayoutParams(0, dp(88), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
            }
            col.addView(row, lpTop(if (r == 0) 0 else 6))
        }
        return col
    }

    private fun gameSummary(): View {
        val c = card(12)
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        val t = LinearLayout(this)
        t.orientation = LinearLayout.VERTICAL
        val gt = tv(selected.size.toString() + " game dipilih", 13f, Pal.TEXT, true)
        gt.iconStart(Ic.GAMES, 16, Skin.a, 8)
        t.addView(gt)
        t.addView(tv(if (selected.isEmpty()) "Pilih dulu agar panel muncul" else "Mainkan langsung dari tab Game", 10f, Pal.MUTED))
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(btn("Kelola", true) { tab = 1; render() })
        c.addView(row)
        return c
    }

    // ───────── Musik ─────────
    private fun ctl(icon: Int, click: () -> Unit): View {
        val f = FrameLayout(this)
        f.background = rbox(0x1AFFFFFF, dpf(12), alpha(Skin.a, 140), 1)
        f.addView(IconView(this, icon, Color.WHITE), FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        f.setOnClickListener { click() }
        return f
    }

    private fun musicCard(): View {
        val c = card(12)
        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        val tc = LinearLayout(this)
        tc.orientation = LinearLayout.VERTICAL
        val mt = tv("Musik", 13f, Pal.TEXT, true)
        mt.iconStart(Ic.NOTE, 16, Skin.a, 8)
        tc.addView(mt)
        val curName = if (Music.current >= 0) Music.name(this, Music.current)
        else if (Music.filled(this, Music.selected(this))) Music.name(this, Music.selected(this)) else "Belum ada lagu, ketuk slot kosong"
        val sub = tv(curName, 10f, Pal.MUTED)
        sub.maxLines = 1; sub.ellipsize = TextUtils.TruncateAt.END
        tc.addView(sub)
        head.addView(tc, LinearLayout.LayoutParams(0, -2, 1f))
        fun m() = LinearLayout.LayoutParams(dp(40), dp(34)).apply { leftMargin = dp(6) }
        head.addView(ctl(Ic.PREV) { Music.prev(this); render() }, m())
        head.addView(ctl(if (Music.playing) Ic.PAUSE else Ic.PLAY) { Music.toggle(this); render() }, m())
        head.addView(ctl(Ic.NEXT) { Music.next(this); render() }, m())
        c.addView(head)
        val slots = LinearLayout(this)
        for (i in 0 until Music.SLOTS) {
            val filled = Music.filled(this, i)
            val on = filled && (Music.current == i || (Music.current < 0 && Music.selected(this) == i))
            val sc = if (on) Color.WHITE else if (filled) Skin.a else Pal.MUTED
            val t = LinearLayout(this)
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(8), 0, dp(8))
            t.addView(IconView(this, if (filled) Ic.NOTE else Ic.PLUS, sc), LinearLayout.LayoutParams(dp(14), dp(14)).apply { if (filled) rightMargin = dp(3) })
            if (filled) t.addView(tv((i + 1).toString(), 12f, sc, true))
            t.background = if (on) gbox(Skin.a, Skin.b, dpf(12)) else rbox(0x14FFFFFF, dpf(12), if (filled) alpha(Skin.a, 130) else Pal.STROKE, 1)
            t.setOnClickListener { if (filled) { Music.play(this, i); render() } else pickMusic(i) }
            t.setOnLongClickListener { slotMenu(i); true }
            slots.addView(t, lpW(3))
        }
        c.addView(slots, lpTop(8))
        c.addView(tv("Ketuk slot kosong untuk memilih lagu. Tahan slot untuk ganti/hapus.", 9.5f, Pal.MUTED), lpTop(4))
        c.addView(switchRow("Putar otomatis saat buka aplikasi", "Lagu dari slot terpilih", "mus_auto", true))
        val vr = LinearLayout(this)
        vr.gravity = Gravity.CENTER_VERTICAL
        vr.addView(IconView(this, Ic.SPEAKER, Pal.TEXT), LinearLayout.LayoutParams(dp(18), dp(18)))
        vr.addView(styledSeek(100, Prefs.int(this, "mus_vol", 70)) { Prefs.setInt(this, "mus_vol", it); Music.setVolume(it) },
            LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(vr, lpTop(2))
        return c
    }

    @Suppress("DEPRECATION")
    private fun pickMusic(slot: Int) {
        pendingSlot = slot
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try { startActivityForResult(i, REQ_MUSIC) } catch (_: Throwable) { Toast.makeText(this, "Tidak ada pemilih file", Toast.LENGTH_SHORT).show() }
    }

    private fun slotMenu(slot: Int) {
        val items = if (Music.filled(this, slot)) arrayOf("Ganti lagu", "Hapus lagu") else arrayOf("Pilih lagu")
        AlertDialog.Builder(this).setTitle("Slot " + (slot + 1)).setItems(items) { _, which ->
            if (which == 0) pickMusic(slot) else { Music.clear(this, slot); render() }
        }.show()
    }

    private fun toggleService() {
        val i = Intent(this, AlyaAiService::class.java)
        if (AlyaAiService.running) stopService(i)
        else {
            if (!Settings.canDrawOverlays(this)) { Toast.makeText(this, "Aktifkan izin 'Tampil di atas app' dulu", Toast.LENGTH_LONG).show(); return }
            startForegroundService(i)
        }
        root.postDelayed({ render() }, 450)
    }

    // ───────── Game ─────────
    private fun pageGames(): View {
        val col = pageCol()
        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(pageTitle("Game Saya", "Main langsung dari sini dengan renderer & resolusi pilihanmu"), LinearLayout.LayoutParams(0, -2, 1f))
        val counter = tv(selected.size.toString() + " dipilih", 12f, Skin.a, true)
        head.addView(counter)
        col.addView(head)
        if (apps.isEmpty()) { col.addView(tv("Memuat daftar aplikasi...", 12f, Pal.MUTED), lpTop(14)); return col }
        val lib = ArrayList<AppItem>()
        for (a in apps) if (a.pkg in selected) lib.add(a)
        col.addView(sectionTitle("Perpustakaan"), lpTop(12))
        if (lib.isEmpty()) col.addView(tv("Belum ada game. Pilih dari daftar di bawah.", 11f, Pal.MUTED), lpTop(4))
        for (a in lib) col.addView(libRow(a), lpTop(6))
        col.addView(sectionTitle("Pilih game"), lpTop(16))
        val sorted = ArrayList<AppItem>(apps)
        sorted.sortWith(Comparator { a, b -> a.label.lowercase().compareTo(b.label.lowercase()) })
        val perRow = 5
        var row: LinearLayout? = null
        for (i in sorted.indices) {
            if (i % perRow == 0) { row = LinearLayout(this); col.addView(row, lpTop(8)) }
            row!!.addView(gameCell(sorted[i], counter), LinearLayout.LayoutParams(0, dp(106), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        val rem = sorted.size % perRow
        if (rem != 0) for (k in rem until perRow) row!!.addView(View(this), LinearLayout.LayoutParams(0, dp(106), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        return col
    }

    private fun libRow(a: AppItem): View {
        val c = card(10)
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        val img = ImageView(this)
        img.setImageDrawable(a.icon)
        row.addView(img, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(12) })
        val t = LinearLayout(this)
        t.orientation = LinearLayout.VERTICAL
        t.addView(tv(a.label, 14f, Pal.TEXT, true))
        val r = Prefs.int(this, "gfx_r_" + a.pkg, 0)
        val s = Prefs.int(this, "gfx_s_" + a.pkg, 100)
        t.addView(tv("Renderer: " + Gfx.names[r.coerceIn(0, 3)] + "  •  Resolusi: " + s + "%", 10.5f, Pal.MUTED))
        t.addView(gameModeRow(a.pkg), lpTop(5))
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(btn("", false, Ic.GEAR) { gameDialog(a) }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        row.addView(btn("Main", true, Ic.PLAY) { playGame(a.pkg) })
        c.addView(row)
        return c
    }

    private fun gameCell(app: AppItem, counter: TextView): View {
        val f = FrameLayout(this)
        val inner = LinearLayout(this)
        inner.orientation = LinearLayout.VERTICAL; inner.gravity = Gravity.CENTER
        inner.setPadding(dp(4), dp(10), dp(4), dp(8))
        val img = ImageView(this)
        img.setImageDrawable(app.icon)
        inner.addView(img, LinearLayout.LayoutParams(dp(46), dp(46)))
        val name = tv(app.label, 10.5f, Pal.TEXT, true)
        name.gravity = Gravity.CENTER; name.maxLines = 2; name.ellipsize = TextUtils.TruncateAt.END
        inner.addView(name, lpTop(6))
        f.addView(inner, FrameLayout.LayoutParams(-1, -1))
        val badge = FrameLayout(this)
        badge.addView(IconView(this, Ic.CHECK, Color.WHITE), FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER))
        f.addView(badge, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.END or Gravity.TOP).apply { setMargins(0, dp(6), dp(6), 0) })
        fun style() {
            val sel = app.pkg in selected
            f.background = if (sel) rbox(alpha(Skin.a, 40), dpf(16), Skin.a, dp(2)) else rbox(alpha(Pal.CARD, 235), dpf(16), Pal.STROKE, 1)
            badge.visibility = if (sel) View.VISIBLE else View.GONE
            badge.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Skin.a) }
            counter.text = selected.size.toString() + " dipilih"
        }
        style()
        f.setOnClickListener {
            if (app.pkg in selected) selected.remove(app.pkg) else selected.add(app.pkg)
            Prefs.setGames(this, selected)
            style()
        }
        f.setOnLongClickListener { render(); true }   // tahan = segarkan perpustakaan
        return f
    }

    /** Pengaturan per game: renderer HWUI + skala resolusi (berlaku saat game dimulai ulang dari sini). */
    private fun gameDialog(a: AppItem) {
        val pkg = a.pkg
        val sdk = Build.VERSION.SDK_INT
        val vk = Gfx.vulkanOk(this)
        var r = Prefs.int(this, "gfx_r_$pkg", 0).coerceIn(0, 3)
        var si = Gfx.scales.indexOf(Prefs.int(this, "gfx_s_$pkg", 100)).let { if (it < 0) 0 else it }
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(10), dp(20), dp(4))
        col.addView(tv("Renderer (jalur render HWUI)", 11f, Pal.MUTED, true))
        val row = LinearLayout(this)
        val pills = ArrayList<TextView>()
        val note = tv("", 10.5f, Pal.WARN)
        fun refresh() {
            for (i in pills.indices) {
                val on = i == r
                pills[i].background = if (on) gbox(Skin.a, Skin.b, dpf(10)) else rbox(0x1AFFFFFF, dpf(10), 0x33FFFFFF, 1)
                pills[i].setTextColor(if (on) Color.WHITE else Pal.TEXT)
            }
            val res = Logic.resolveRenderer(sdk, vk, r)
            note.text = if (r == 0) "Tidak diubah, memakai bawaan sistem."
            else res.second ?: ("Dipakai: " + Gfx.names[res.first] + if (r == 3) " (Vulkan didukung)" else "")
        }
        for (i in 0..3) {
            val p = tv(Gfx.names[i], 11f, Pal.TEXT, true)
            p.gravity = Gravity.CENTER; p.setPadding(0, dp(8), 0, dp(8))
            p.setOnClickListener { r = i; refresh() }
            pills.add(p); row.addView(p, lpW(2))
        }
        col.addView(row, lpTop(6))
        col.addView(note, lpTop(6))
        col.addView(tv("Resolusi game (downscale)", 11f, Pal.MUTED, true), lpTop(14))
        val sl = tv(Gfx.scales[si].toString() + "%", 13f, Skin.a, true)
        col.addView(sl, lpTop(2))
        val seek = styledSeek(Gfx.scales.size - 1, si) { si = it; sl.text = Gfx.scales[it].toString() + "%" }
        seek.isEnabled = Gfx.scaleSupported()
        col.addView(seek, lpTop(0))
        col.addView(tv(if (Gfx.scaleSupported()) "100% = asli. Makin kecil makin ringan (minimum 30%)."
        else "Downscale butuh Android 12+. Perangkat ini Android " + Build.VERSION.RELEASE + ".", 10f, Pal.MUTED), lpTop(2))
        col.addView(tv("Perubahan berlaku saat game dimulai ulang dari tombol Main. Engine game (Unity/Unreal) punya renderer sendiri; " +
            "pilihan ini mengatur jalur HWUI dan skala jendela.", 9.5f, Pal.MUTED), lpTop(8))
        refresh()
        val sv = ScrollView(this)
        sv.addView(col)
        fun save() { Prefs.setInt(this, "gfx_r_$pkg", r); Prefs.setInt(this, "gfx_s_$pkg", Gfx.scales[si]) }
        AlertDialog.Builder(this).setTitle(a.label).setView(sv)
            .setPositiveButton("Simpan & Main") { _, _ -> save(); playGame(pkg) }
            .setNeutralButton("Simpan") { _, _ -> save(); render() }
            .setNegativeButton("Batal", null).show()
    }

    /** Hentikan game, terapkan renderer + resolusi, lalu buka lagi (service Game Space ikut menyala). */
    private fun playGame(pkg: String) {
        if (!Settings.canDrawOverlays(this)) { Toast.makeText(this, "Aktifkan izin 'Tampil di atas app' dulu", Toast.LENGTH_LONG).show(); return }
        selected.add(pkg); Prefs.setGames(this, selected)
        val r = Prefs.int(this, "gfx_r_$pkg", 0)
        val sc = Prefs.int(this, "gfx_s_$pkg", 100)
        Thread {
            val notes = ArrayList<String>()
            var eff = 0
            if (ShizukuHelper.granted()) {
                Sh.exec("am force-stop $pkg")
                val res = Logic.resolveRenderer(Build.VERSION.SDK_INT, Gfx.vulkanOk(this), r)
                eff = res.first
                res.second?.let { notes.add(it) }
                Gfx.applyRenderer(this, eff)
                if (sc < 100) { if (!Gfx.applyScale(pkg, sc)) notes.add("Resolusi $sc% tidak diterapkan (butuh Android 12+)") } else Gfx.resetScale(pkg)
            } else if (r != 0 || sc < 100) notes.add("Renderer & resolusi butuh Shizuku aktif")
            runOnUiThread {
                if (!AlyaAiService.running) startForegroundService(Intent(this, AlyaAiService::class.java))
                val li = packageManager.getLaunchIntentForPackage(pkg)
                if (li != null) startActivity(li) else Toast.makeText(this, "Game tidak bisa dibuka", Toast.LENGTH_SHORT).show()
                if (notes.isNotEmpty()) Toast.makeText(this, notes.joinToString("\n"), Toast.LENGTH_LONG).show()
            }
            if (ShizukuHelper.granted() && eff != 0) {
                Thread.sleep(8000)
                val v = Gfx.verify(pkg)
                ActionLog.add("Verifikasi renderer ($pkg): " + v.ifEmpty { "tidak terbaca" })
                runOnUiThread { Toast.makeText(this, "Renderer aktif: " + v.ifEmpty { "tidak terbaca (engine game memakai renderer sendiri)" }, Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun loadApps() {
        Thread {
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = ArrayList<AppItem>()
            val seen = HashSet<String>()
            for (ri in packageManager.queryIntentActivities(q, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || !seen.add(pkg)) continue
                list.add(AppItem(ri.loadLabel(packageManager).toString(), pkg, ri.loadIcon(packageManager)))
            }
            runOnUiThread { apps.clear(); apps.addAll(list); if (tab == 1) render() }
        }.start()
    }

    // ───────── Rekaman ─────────
    private fun fmtDur(sec: Int) = if (sec >= 60) (sec / 60).toString() + " mnt " + (sec % 60) + " dtk" else sec.toString() + " dtk"
    private fun fmtDate(t: Long) = SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault()).format(Date(t))

    private fun pageRecords(): View {
        val id = openRec
        val list = Records.list(this)
        if (id != null) {
            val r = list.firstOrNull { it.id == id }
            if (r != null) return recordDetail(r)
            openRec = null
        }
        val col = pageCol()
        col.addView(pageTitle("Laporan Performa", "Laporan FPS dan suhu dari sesi game"))
        if (list.isEmpty()) {
            val c = card(18)
            c.gravity = Gravity.CENTER
            c.addView(IconView(this, Ic.CHART, Pal.MUTED), LinearLayout.LayoutParams(dp(44), dp(44)))
            c.addView(tv("Belum ada rekaman", 14f, Pal.TEXT, true), lpTop(6))
            c.addView(tv("Buka game, buka panel, lalu tekan 'Rekam Perf' di tab Utama.\nHentikan rekaman untuk menyimpan laporan.", 11f, Pal.MUTED), lpTop(4))
            col.addView(c, lpTop(14))
        } else for (r in list) col.addView(recRow(r), lpTop(8))
        return col
    }

    private fun recRow(r: Rec): View {
        val c = card(12)
        val s = Records.stats(r)
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        val img = ImageView(this)
        try { img.setImageDrawable(packageManager.getApplicationIcon(r.game)) } catch (_: Throwable) { img.setImageResource(R.drawable.art_icon) }
        row.addView(img, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(12) })
        val t = LinearLayout(this)
        t.orientation = LinearLayout.VERTICAL
        t.addView(tv(r.label, 14f, Pal.TEXT, true))
        t.addView(tv(fmtDate(r.start) + "  •  " + fmtDur(r.dur) + "  •  " + Modes.all[r.mode.coerceIn(0, 3)].name, 10.5f, Pal.MUTED))
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        val v = LinearLayout(this)
        v.orientation = LinearLayout.VERTICAL; v.gravity = Gravity.END
        v.addView(tv("%.1f".format(s.avg), 20f, Logic.fpsColor(s.avg), true))
        v.addView(tv("FPS rata-rata", 9f, Pal.MUTED))
        row.addView(v)
        c.addView(row)
        c.setOnClickListener { openRec = r.id; render() }
        return c
    }

    private fun statCell(label: String, value: String, unit: String, color: Int): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL; c.gravity = Gravity.CENTER_HORIZONTAL
        c.setPadding(dp(4), dp(10), dp(4), dp(10))
        c.background = rbox(alpha(Pal.CARD, 235), dpf(14), Pal.STROKE, 1)
        c.addView(tv(label, 9f, Pal.MUTED, true))
        c.addView(tv(value, 21f, color, true))
        c.addView(tv(unit, 9f, Pal.MUTED))
        return c
    }

    private fun recordDetail(r: Rec): View {
        val s = Records.stats(r)
        val col = pageCol()
        val back = tv("Kembali", 12f, Skin.a, true)
        back.iconStart(Ic.BACK, 14, Skin.a, 8)
        back.setPadding(0, 0, dp(12), dp(8))
        back.setOnClickListener { openRec = null; render() }
        col.addView(back)
        col.addView(tv(r.label, 20f, Pal.TEXT, true))
        col.addView(tv(fmtDate(r.start) + "  •  " + fmtDur(r.dur) + "  •  " + r.model + "  •  " + r.plat + "  •  SDK " + r.sdk +
            "  •  Profil " + Modes.all[r.mode.coerceIn(0, 3)].name, 10.5f, Pal.MUTED))
        val g1 = LinearLayout(this); val g2 = LinearLayout(this)
        fun add(row: LinearLayout, label: String, value: String, unit: String, color: Int) =
            row.addView(statCell(label, value, unit, color), lpW(3))
        add(g1, "MAKS", "%.1f".format(s.max), "FPS", Pal.OK)
        add(g1, "MIN", "%.1f".format(s.min), "FPS", Pal.BAD)
        add(g1, "RATA-RATA", "%.1f".format(s.avg), "FPS", Pal.WARN)
        add(g1, "VARIANS", "%.1f".format(s.variance), "FPS", Pal.TEXT)
        add(g2, "≥45 FPS", "%.1f%%".format(s.smooth), "Kehalusan", Pal.TEXT)
        add(g2, "5% RENDAH", "%.1f".format(s.low5), "FPS", Pal.BAD)
        add(g2, "MAKS", "%.1f".format(s.maxTemp), "Suhu (°C)", Pal.WARN)
        add(g2, "RATA-RATA", if (s.avgW > 0) "%.2f".format(s.avgW) else "--", "Daya (W)", Pal.TEXT)
        col.addView(g1, lpTop(12)); col.addView(g2, lpTop(6))
        val cc = card(12)
        val legend = LinearLayout(this)
        legend.gravity = Gravity.CENTER_VERTICAL
        legend.addView(IconView(this, Ic.DOT, Skin.a), LinearLayout.LayoutParams(dp(12), dp(12))); legend.addView(tv(" FPS      ", 10.5f, Pal.MUTED))
        legend.addView(IconView(this, Ic.DOT, Pal.WARN), LinearLayout.LayoutParams(dp(12), dp(12))); legend.addView(tv(" Suhu (°C)", 10.5f, Pal.MUTED))
        cc.addView(legend)
        val ch = ChartView(this)
        ch.fps = r.fps; ch.temp = r.temp
        cc.addView(ch, LinearLayout.LayoutParams(-1, dp(190)).apply { topMargin = dp(6) })
        col.addView(cc, lpTop(10))
        col.addView(btn("Hapus rekaman ini", false, Ic.TRASH) {
            Records.delete(this, r.id); openRec = null; render()
        }, lpTop(10))
        return col
    }

    // ───────── Info & pengaturan ─────────
    private fun switchRow(title: String, desc: String, key: String, def: Boolean, onChange: (() -> Unit)? = null): View {
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, dp(7), 0, dp(7))
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.addView(tv(title, 13f, Pal.TEXT, true))
        c.addView(tv(desc, 10f, Pal.MUTED))
        r.addView(c, LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(this)
        sw.isChecked = Prefs.bool(this, key, def)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(Skin.a, 0xFF8C86B5.toInt()))
        sw.trackTintList = ColorStateList(states, intArrayOf(alpha(Skin.a, 110), 0x44FFFFFF))
        sw.setOnCheckedChangeListener { _, v -> Prefs.setBool(this, key, v); if (onChange != null) onChange() }
        r.addView(sw)
        return r
    }

    private fun pills(labels: List<String>, current: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this)
        for (i in labels.indices) {
            val on = i == current
            val t = tv(labels[i], 11f, if (on) Color.WHITE else Pal.TEXT, true)
            t.gravity = Gravity.CENTER; t.maxLines = 1
            t.setPadding(0, dp(8), 0, dp(8))
            t.background = if (on) gbox(Skin.a, Skin.b, dpf(12)) else rbox(0x1AFFFFFF, dpf(12), 0x33FFFFFF, 1)
            t.setOnClickListener { onPick(i) }
            row.addView(t, lpW(3))
        }
        return row
    }

    private fun pageInfo(): View {
        val col = pageCol()
        col.addView(pageTitle("Info & Pengaturan", "Atur perilaku Game Space"))

        val about = card(14)
        val ar = LinearLayout(this)
        ar.gravity = Gravity.CENTER_VERTICAL
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.art_icon)
        ar.addView(logo, LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(14) })
        val at = LinearLayout(this)
        at.orientation = LinearLayout.VERTICAL
        at.addView(tv("Game Space", 18f, Pal.TEXT, true))
        at.addView(tv("Alya AI Service  •  Versi " + appVersion(), 11f, Skin.a, true))
        at.addView(tv(Build.MANUFACTURER + " " + Build.MODEL + "  •  Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")", 10.5f, Pal.MUTED))
        at.addView(tv("Dibuat oleh Claude (Anthropic)  •  challenge harian", 10.5f, Pal.MUTED))
        at.addView(tv("Ide, arahan & uji coba: KhimDev", 10.5f, Skin.a, true))
        ar.addView(at, LinearLayout.LayoutParams(0, -2, 1f))
        about.addView(ar)
        col.addView(about, lpTop(12))
        col.addView(updateCard(), lpTop(10))

        val set = card(14)
        set.addView(sectionTitle("Pengaturan"))
        set.addView(switchRow("Overlay FPS otomatis", "Tampilkan HUD kapsul saat game dibuka", "auto_hud", true))
        set.addView(switchRow("HUD Display (PC) otomatis", "Tampilkan HUD gaya PC di kiri bawah saat game dibuka", "auto_pc", false))
        set.addView(switchRow("Batasi FPS saat panas", "Seimbang ≥38°C, Performa ≥40°C → 60 FPS; naik lagi setelah turun 3°C", "fps_thermal", true))
        set.addView(switchRow("Redup otomatis saat idle", "Tanpa sentuhan: layar redup & FPS 60. Sentuh lagi = normal", "auto_dim", true))
        val idleLabels = listOf("3 menit", "4 menit", "5 menit")
        set.addView(pills(idleLabels, (Prefs.int(this, "idle_min", 4) - 3).coerceIn(0, 2)) { Prefs.setInt(this, "idle_min", it + 3); render() }, lpTop(2))
        set.addView(switchRow("Boost RAM otomatis (Performa/Turbo)", "Bersihkan app latar saat game dibuka", "auto_boost", true))
        set.addView(switchRow("Rekam performa otomatis", "Mulai merekam FPS/suhu begitu game dibuka", "auto_rec", false))
        set.addView(switchRow("Pulihkan setelan saat keluar game", "Kembalikan semua yang diubah Game Space", "auto_restore", true))
        set.addView(switchRow("Selalu tampilkan knob (uji coba)", "Knob/HUD muncul walau tidak sedang di game", "always", false))
        set.addView(switchRow("Mulai otomatis saat HP menyala", "Layanan siap tanpa membuka aplikasi (Shizuku tetap perlu dinyalakan)", "boot_start", false))
        col.addView(set, lpTop(10))

        val photo = card(14)
        photo.addView(sectionTitle("Foto dashboard"))
        photo.addView(tv("Foto otomatis di-crop ke rasio kartu supaya tidak ketarik/melar.", 10.5f, Pal.MUTED), lpTop(2))
        val pr = LinearLayout(this)
        pr.addView(btn("Ganti foto", true, Ic.IMAGE) { pickHero() }, lpW(3))
        pr.addView(btn("Foto bawaan", false, Ic.REFRESH) { HeroPhoto.reset(this); render() }, lpW(3))
        photo.addView(pr, lpTop(8))
        if (HeroPhoto.hasSource(this)) {
            photo.addView(tv("Fokus crop", 10.5f, Pal.MUTED, true), lpTop(10))
            val vals = intArrayOf(0, 35, 50, 100)
            var cur = 1
            val saved = Prefs.int(this, "hero_focus", 35)
            for (i in vals.indices) if (vals[i] == saved) cur = i
            photo.addView(pills(listOf("Atas", "Otomatis", "Tengah", "Bawah"), cur) { i ->
                Prefs.setInt(this, "hero_focus", vals[i])
                Thread { HeroPhoto.rebuild(this); runOnUiThread { render() } }.start()
            }, lpTop(4))
        }
        col.addView(photo, lpTop(10))
        col.addView(chargeCard(), lpTop(10))
        col.addView(bgCard(), lpTop(10))
        col.addView(comboCard(), lpTop(10))

        val tools = card(14)
        tools.addView(sectionTitle("Alat"))
        val r1 = LinearLayout(this); val r2 = LinearLayout(this)
        r1.addView(btn("Diagnostik", true, Ic.SEARCH) { runDiag() }, lpW(3))
        r1.addView(btn("Pulihkan setelan", false, Ic.REFRESH) { restoreNow() }, lpW(3))
        r2.addView(btn("Riwayat versi", false, Ic.DOC) { showText("Riwayat versi", changelog()) }, lpW(3))
        r2.addView(btn("Reset tampilan", false, Ic.PALETTE) { Thread { GameActions(this).setVisual(0) }.start() }, lpW(3))
        tools.addView(r1, lpTop(10)); tools.addView(r2, lpTop(6))
        if (File(filesDir, "crash.txt").exists()) tools.addView(btn("Laporan error terakhir", false, Ic.WARN) { showCrash() }, lpTop(6))
        col.addView(tools, lpTop(10))
        return col
    }

    @Suppress("DEPRECATION")
    private fun pickHero() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        try { startActivityForResult(i, REQ_HERO) } catch (_: Throwable) { Toast.makeText(this, "Tidak ada pemilih file", Toast.LENGTH_SHORT).show() }
    }

    // ───────── peringatan pengisian ─────────
    private fun chargeCard(): View {
        val c = card(14)
        c.addView(sectionTitle("Peringatan pengisian"))
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lv = b?.getIntExtra("level", -1) ?: -1
        val stt = b?.getIntExtra("status", -1) ?: -1
        val tp = (b?.getIntExtra("temperature", 0) ?: 0) / 10f
        val chg = stt == 2 || stt == 5
        c.addView(tv("Sekarang: " + (if (lv >= 0) "$lv%" else "--") + (if (chg) " • mengisi" else " • tidak mengisi") +
            " • " + "%.1f".format(tp) + "°C", 11f, Skin.a, true), lpTop(2))
        c.addView(switchRow("Ingatkan cabut charger", "Banner di atas game + notifikasi + getar saat baterai mencapai target", "chg_unplug_on", true))
        val tg = intArrayOf(80, 85, 90, 95, 100)
        var cur = tg.indexOf(Prefs.int(this, "chg_unplug_at", 90))
        if (cur < 0) cur = 2
        c.addView(pills(tg.map { "$it%" }, cur) { Prefs.setInt(this, "chg_unplug_at", tg[it]); render() }, lpTop(2))
        c.addView(switchRow("Peringatan panas saat mengisi sambil main", "Juga memberi tahu bila suhu naik cepat (≥2°C dalam 3 menit)", "chg_hot_on", true))
        val tt = intArrayOf(38, 40, 42)
        var ct = tt.indexOf(Prefs.int(this, "chg_hot_temp", 40))
        if (ct < 0) ct = 1
        c.addView(pills(tt.map { "$it°C" }, ct) { Prefs.setInt(this, "chg_hot_temp", tt[it]); render() }, lpTop(2))
        c.addView(switchRow("Ingatkan baterai rendah (≤15%)", "Saat tidak mengisi", "chg_low_on", true))
        c.addView(switchRow("Getar saat peringatan", "Supaya terasa walau fokus main", "chg_vibrate", true))
        c.addView(btn("Coba peringatan", false, Ic.BELL) {
            if (AlyaAiService.running) startService(Intent(this, AlyaAiService::class.java).setAction(AlyaAiService.ACTION_TEST))
            else Toast.makeText(this, "Mulai Alya AI Service dulu (tombol Mulai di Beranda)", Toast.LENGTH_LONG).show()
        }, lpTop(8))
        return c
    }

    // ───────── batasi app latar ─────────
    private fun bgCard(): View {
        val c = card(14)
        c.addView(sectionTitle("Batasi app latar saat main"))
        c.addView(tv("Memakai appops (RUN_ANY_IN_BACKGROUND): app lain dilarang jalan di latar selama sesi game, lalu dikembalikan saat selesai. " +
            "Beda dengan pembersih RAM yang hanya menutup proses sesaat.", 10.5f, Pal.MUTED), lpTop(2))
        c.addView(switchRow("Batasi otomatis saat game dibuka", "Butuh Shizuku. Keyboard, launcher, Shizuku & pengecualianmu dilindungi", "bg_auto", true))
        val ex = Prefs.str(this, "bg_excl").split(",").count { it.isNotBlank() }
        val pend = GameActions(this).pendingBackground()
        val r = LinearLayout(this)
        r.addView(btn("Pengecualian ($ex app)", false) { bgExclusionDialog() }, lpW(3))
        r.addView(btn(if (pend > 0) "Pulihkan ($pend)" else "Tidak ada yang dibatasi", pend > 0, if (pend > 0) Ic.REFRESH else Ic.CHECK) { if (pend > 0) restoreNow() }, lpW(3))
        c.addView(r, lpTop(8))
        return c
    }

    private fun bgExclusionDialog() {
        if (apps.isEmpty()) { Toast.makeText(this, "Daftar app belum siap, coba lagi sebentar", Toast.LENGTH_SHORT).show(); return }
        val list = ArrayList<AppItem>(apps)
        list.sortWith(Comparator { a, b -> a.label.lowercase().compareTo(b.label.lowercase()) })
        val ex = HashSet<String>()
        for (p in Prefs.str(this, "bg_excl").split(",")) if (p.isNotBlank()) ex.add(p.trim())
        val labels = Array(list.size) { list[it].label }
        val checked = BooleanArray(list.size) { list[it].pkg in ex }
        AlertDialog.Builder(this).setTitle("App yang TIDAK dibatasi (mis. musik, chat)")
            .setMultiChoiceItems(labels, checked) { _, which, on -> if (on) ex.add(list[which].pkg) else ex.remove(list[which].pkg) }
            .setPositiveButton("Simpan") { _, _ -> Prefs.setStr(this, "bg_excl", ex.joinToString(",")); render() }
            .setNegativeButton("Batal", null).show()
    }

    // ───────── Combo: persetujuan ─────────
    private fun comboCard(): View {
        val c = card(14)
        c.addView(sectionTitle("Combo (auto-tap)"))
        c.addView(tv("Combo adalah macro yang menekan titik layar berulang saat tombol ditahan. Di game online hal ini bisa melanggar aturan dan " +
            "membuat akun di-ban; pakai di game offline atau yang membolehkan. Semua risiko ada pada pengguna, dan Game Space tidak " +
            "menyembunyikan Combo dari anti-cheat.", 10.5f, Pal.WARN), lpTop(2))
        c.addView(switchRow("Saya mengerti & izinkan Combo", "Harus aktif agar Combo bisa dinyalakan di panel Space", "combo_ack", false))
        return c
    }

    // ───────── mode per game ─────────
    private fun gameModeRow(pkg: String): View {
        val cur = Prefs.int(this, "gm_$pkg", -1)
        val row = LinearLayout(this)
        for (i in 0..4) {
            val v = i - 1
            val on = cur == v
            val t = LinearLayout(this)
            t.gravity = Gravity.CENTER
            t.setPadding(dp(10), dp(4), dp(10), dp(4))
            t.background = if (on) (if (v < 0) gbox(Skin.a, Skin.b, dpf(10)) else gbox(Modes.all[v].a, Modes.all[v].b, dpf(10)))
            else rbox(0x1AFFFFFF, dpf(10), 0x33FFFFFF, 1)
            if (v < 0) t.addView(tv("Auto", 11f, if (on) Color.WHITE else Pal.TEXT, true))
            else t.addView(IconView(this, Modes.all[v].icon, if (on) Color.WHITE else Modes.all[v].a), LinearLayout.LayoutParams(dp(16), dp(16)))
            t.setOnClickListener { Prefs.setInt(this, "gm_$pkg", v); render() }
            row.addView(t, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(5) })
        }
        return row
    }

    // ───────── sesi terakhir ─────────
    private fun lastSessionCard(): View? {
        val p = Prefs.str(this, "last_session").split("|")
        if (p.size < 7) return null
        val dur = p[2].toIntOrNull() ?: return null
        val c = card(12)
        val ht = tv("Sesi terakhir • " + p[0], 12f, Pal.TEXT, true)
        ht.iconStart(Ic.FLAG, 16, Skin.a, 8)
        c.addView(ht)
        c.addView(tv(Logic.sessionText(dur, p[3].toFloatOrNull() ?: 0f, p[4].toFloatOrNull() ?: 0f, p[5].toIntOrNull() ?: -1, p[6].toIntOrNull() ?: -1),
            10.5f, Pal.MUTED), lpTop(2))
        c.addView(tv(fmtDate(p[1].toLongOrNull() ?: 0L), 9.5f, Pal.MUTED), lpTop(2))
        return c
    }

    // ───────── Pusat Pembaruan ─────────
    @Suppress("DEPRECATION")
    private fun versionCodeNow(): Int = try { packageManager.getPackageInfo(packageName, 0).versionCode } catch (_: Throwable) { 0 }

    /** Popup "Apa yang baru" sekali setelah versi berubah. */
    private fun maybeWhatsNew() {
        val cur = versionCodeNow()
        if (cur <= 0 || Prefs.int(this, "seen_ver", 0) >= cur || isFinishing) return
        Prefs.setInt(this, "seen_ver", cur)
        showText("Apa yang baru di v" + appVersion(), changelog().substringBefore("\n\n"),
            Pair("Riwayat lengkap") { showText("Riwayat versi", changelog()) })
    }

    private fun autoCheckUpdate() {
        if (!Prefs.bool(this, "upd_auto", true) || Prefs.str(this, "upd_url").isBlank()) return
        if (System.currentTimeMillis() - Prefs.long(this, "upd_last", 0L) < 24 * 3600_000L) return
        checkUpdate(false)
    }

    private fun saveUpdate(i: UpdateInfo) {
        Prefs.setInt(this, "upd_code", i.code); Prefs.setStr(this, "upd_name", i.name); Prefs.setStr(this, "upd_notes", i.notes)
        Prefs.setStr(this, "upd_apk", i.apkUrl); Prefs.setStr(this, "upd_sha", i.sha256)
    }

    private fun storedUpdate() = UpdateInfo(Prefs.int(this, "upd_code", 0), Prefs.str(this, "upd_name", "?"), Prefs.str(this, "upd_notes"),
        Prefs.str(this, "upd_apk"), Prefs.str(this, "upd_sha"))

    private fun checkUpdate(manual: Boolean) {
        val url = Prefs.str(this, "upd_url").trim()
        if (url.isEmpty()) { if (manual) formatHelp(); return }
        if (manual) Toast.makeText(this, "Memeriksa pembaruan...", Toast.LENGTH_SHORT).show()
        Thread {
            val r = Updater.fetch(url)
            Prefs.setLong(this, "upd_last", System.currentTimeMillis())
            val info = r.info
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (info == null) {
                    if (manual) Toast.makeText(this, r.error ?: "Gagal memeriksa", Toast.LENGTH_LONG).show()
                } else if (info.code > versionCodeNow()) {
                    saveUpdate(info)
                    if (manual || Logic.updateAvailable(info.code, versionCodeNow(), Prefs.int(this, "upd_skip", 0))) updateDialog(info)
                    render()
                } else {
                    Prefs.setInt(this, "upd_code", 0)
                    if (manual) Toast.makeText(this, "Sudah versi terbaru (v" + appVersion() + ")", Toast.LENGTH_SHORT).show()
                    render()
                }
            }
        }.start()
    }

    private fun updateDialog(info: UpdateInfo) {
        if (isFinishing) return
        val t = tv(if (info.notes.isNotBlank()) info.notes else "(tidak ada catatan rilis)", 12f, Pal.TEXT)
        t.setPadding(dp(20), dp(10), dp(20), dp(10))
        val sv = ScrollView(this)
        sv.addView(t)
        AlertDialog.Builder(this).setTitle("Pembaruan tersedia: v" + info.name).setView(sv)
            .setPositiveButton("Unduh & pasang") { _, _ -> startUpdate(info) }
            .setNegativeButton("Nanti", null)
            .setNeutralButton("Lewati versi ini") { _, _ -> Prefs.setInt(this, "upd_skip", info.code); render() }
            .show()
    }

    /** Unduh APK, cek SHA-256, lalu serahkan ke PackageInstaller (konfirmasi muncul di layar sistem). */
    private fun startUpdate(info: UpdateInfo) {
        if (!Updater.canInstall(this)) {
            Toast.makeText(this, "Izinkan 'Pasang aplikasi tidak dikenal' untuk Game Space, lalu ulangi", Toast.LENGTH_LONG).show()
            try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (_: Throwable) {}
            return
        }
        val msg = tv("Mengunduh... 0%", 13f, Pal.TEXT)
        msg.setPadding(dp(24), dp(20), dp(24), dp(12))
        val dlg = AlertDialog.Builder(this).setTitle("Memperbarui Game Space").setView(msg).setCancelable(false).create()
        dlg.show()
        Thread {
            val dl = Updater.download(this, info) { p -> runOnUiThread { msg.text = "Mengunduh... $p%" } }
            runOnUiThread { try { dlg.dismiss() } catch (_: Throwable) {} }
            val f = dl.file
            if (f == null) { runOnUiThread { Toast.makeText(this, dl.error ?: "Unduhan gagal", Toast.LENGTH_LONG).show() }; return@Thread }
            val err = Updater.install(this, f)
            runOnUiThread {
                Toast.makeText(this, if (err == null) "Konfirmasi pemasangan di layar berikutnya" else "Pemasangan gagal: $err", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun setUrlDialog() {
        val et = EditText(this)
        et.setText(Prefs.str(this, "upd_url"))
        et.hint = "https://.../version.json"
        et.setTextColor(Pal.TEXT); et.setHintTextColor(Pal.MUTED); et.setSingleLine()
        val box = LinearLayout(this)
        box.setPadding(dp(20), dp(10), dp(20), dp(0))
        box.addView(et, LinearLayout.LayoutParams(-1, -2))
        AlertDialog.Builder(this).setTitle("URL file pembaruan").setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                val u = et.text.toString().trim()
                if (u.isNotEmpty() && !u.startsWith("https://")) Toast.makeText(this, "URL harus diawali https://", Toast.LENGTH_LONG).show()
                else { Prefs.setStr(this, "upd_url", u); Prefs.setLong(this, "upd_last", 0L); render() }
            }
            .setNegativeButton("Batal", null).show()
    }

    private fun formatHelp() {
        showText("Format file pembaruan",
            "Buat file version.json di tempat yang bisa dibuka lewat https (mis. GitHub: raw.githubusercontent.com/<user>/<repo>/main/version.json), isinya:\n\n" +
            "{\n  \"versionCode\": " + (versionCodeNow() + 1) + ",\n  \"versionName\": \"1.6\",\n" +
            "  \"apkUrl\": \"https://.../GameSpace-v1.6.apk\",\n  \"sha256\": \"(opsional)\",\n" +
            "  \"notes\": \"- fitur baru A\\n- perbaikan B\"\n}\n\n" +
            "• versionCode harus LEBIH BESAR dari versi terpasang (sekarang " + versionCodeNow() + ").\n" +
            "• APK harus ditandatangani kunci yang sama supaya bisa menimpa aplikasi ini.\n" +
            "• sha256 (opsional) mencegah file rusak/palsu; hitung dengan: sha256sum nama.apk\n" +
            "• Tanpa URL, tidak ada koneksi jaringan sama sekali.",
            Pair("Atur URL") { setUrlDialog() })
    }

    private fun updateCard(): View {
        val c = card(14)
        c.addView(sectionTitle("Pembaruan"))
        val url = Prefs.str(this, "upd_url")
        val last = Prefs.long(this, "upd_last", 0L)
        c.addView(tv("Terpasang: v" + appVersion() + (if (last > 0) "  •  dicek " + fmtDate(last) else ""), 11f, Skin.a, true), lpTop(2))
        c.addView(tv(if (url.isEmpty()) "Belum ada URL pembaruan. Isi URL file version.json agar pengguna bisa mendapat update dari dalam aplikasi."
        else "Sumber: $url", 10.5f, Pal.MUTED), lpTop(2))
        c.addView(switchRow("Cek otomatis saat aplikasi dibuka", "Maksimal 1× per hari (butuh URL)", "upd_auto", true))
        val r1 = LinearLayout(this); val r2 = LinearLayout(this)
        r1.addView(btn("Cek pembaruan", true, Ic.REFRESH) { checkUpdate(true) }, lpW(3))
        r1.addView(btn("Atur URL", false, Ic.LINK) { setUrlDialog() }, lpW(3))
        r2.addView(btn("Format file", false, Ic.BOOK) { formatHelp() }, lpW(3))
        r2.addView(btn("Apa yang baru", false, Ic.STAR) { showText("Apa yang baru di v" + appVersion(), changelog().substringBefore("\n\n"),
            Pair("Riwayat lengkap") { showText("Riwayat versi", changelog()) }) }, lpW(3))
        c.addView(r1, lpTop(8)); c.addView(r2, lpTop(6))
        return c
    }

    // ───────── Perekam layar ─────────
    private var recStatusTv: TextView? = null
    private val recTick = object : Runnable {
        override fun run() {
            recStatusTv?.text = recStatusText()
            if (tab == 2 && !isFinishing) root.postDelayed(this, 1000)
        }
    }

    private fun recStatusText(): String =
        if (RecorderService.recording) "Sedang merekam  " + RecorderService.clock((System.currentTimeMillis() - RecorderService.startedAt) / 1000) +
            "  •  " + RecorderService.files + " file  •  " + (RecorderService.bytes / 1_048_576L) + " MB"
        else if (RecorderService.lastError.isNotEmpty()) "Perekaman terakhir gagal: " + RecorderService.lastError
        else "Siap merekam"

    private fun sTitle(t: String) = tv(t, 12.5f, Pal.TEXT, true)
    private fun sNote(t: String) = tv(t, 10.5f, Pal.MUTED)

    private fun pillGrid(labels: List<String>, current: Int, perRow: Int, onPick: (Int) -> Unit): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        var i = 0
        while (i < labels.size) {
            val row = LinearLayout(this)
            for (j in i until i + perRow) {
                if (j < labels.size) {
                    val on = j == current
                    val t = tv(labels[j], 11f, if (on) Color.WHITE else Pal.TEXT, true)
                    t.gravity = Gravity.CENTER; t.maxLines = 1
                    t.setPadding(0, dp(8), 0, dp(8))
                    t.background = if (on) gbox(Skin.a, Skin.b, dpf(12)) else rbox(0x1AFFFFFF, dpf(12), 0x33FFFFFF, 1)
                    t.setOnClickListener { onPick(j) }
                    row.addView(t, lpW(3))
                } else row.addView(View(this), lpW(3))
            }
            col.addView(row, lpTop(if (i == 0) 0 else 6))
            i += perRow
        }
        return col
    }

    /** Kolom angka yang bisa diketik. Di luar batas [min, max] otomatis dikembalikan ke batas terdekat. */
    private fun numberField(value: Int, min: Int, max: Int, suffix: String, onValue: (Int) -> Unit): View {
        val et = EditText(this)
        et.setText(value.toString())
        et.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        et.setSingleLine()
        et.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        et.setTextColor(Pal.TEXT); et.setHintTextColor(Pal.MUTED)
        et.filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(5))
        et.background = rbox(0x1AFFFFFF, dpf(10), 0x33FFFFFF, 1)
        et.setPadding(dp(12), dp(8), dp(12), dp(8))
        var last = value
        fun commit() {
            val raw = et.text.toString().toIntOrNull()
            val v = (raw ?: last).coerceIn(min, max)
            if (raw != null && raw != v) Toast.makeText(this, "Harus antara $min dan $max, diubah ke $v", Toast.LENGTH_SHORT).show()
            if (et.text.toString() != v.toString()) et.setText(v.toString())
            if (v != last || raw != null) { last = v; onValue(v) }
        }
        et.setOnEditorActionListener { _, _, _ -> commit(); et.clearFocus(); true }
        et.setOnFocusChangeListener { _, has -> if (!has) commit() }
        // nilai yang sah langsung disimpan saat mengetik, jadi tidak hilang bila pindah halaman tanpa menekan Selesai
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val v = s?.toString()?.toIntOrNull() ?: return
                if (v in min..max && v != last) { last = v; onValue(v) }
            }
        })
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(et, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(tv(suffix, 12f, Pal.MUTED, true), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        return row
    }

    private fun pageRecord(): View {
        val col = pageCol()
        col.addView(pageTitle("Perekam Layar", "Atur video dan audio, lalu mulai merekam. Berlaku juga untuk tombol Rekam Layar di panel Space"))
        col.addView(recControlCard(), lpTop(12))
        col.addView(recVideoCard(), lpTop(10))
        col.addView(recAudioCard(), lpTop(10))
        col.addView(recFileCard(), lpTop(10))
        return col
    }

    private fun recControlCard(): View {
        val c = card(14)
        val rec = RecorderService.recording
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        val t = LinearLayout(this)
        t.orientation = LinearLayout.VERTICAL
        val st = tv(recStatusText(), 13f, if (rec) Pal.BAD else Skin.a, true)
        st.iconStart(if (rec) Ic.DOT else Ic.RECORD, 14, if (rec) Pal.BAD else Skin.a, 8)
        recStatusTv = st
        t.addView(st)
        t.addView(tv("Saat mulai, Android meminta izin rekam layar. Pilih 'Seluruh layar' agar game ikut terekam. Arah video (tegak/mendatar) mengikuti arah layar saat rekaman dimulai, jadi paling pas dimulai dari tombol Rekam Layar di panel saat sudah di dalam game. Apa pun yang tampil di layar (termasuk kapsul dan HUD) ikut terekam.", 10.5f, Pal.MUTED), lpTop(2))
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(btn(if (rec) "Berhenti" else "Mulai rekam", !rec, if (rec) Ic.STOP else Ic.RECORD) {
            if (RecorderService.recording) RecorderService.requestStop(this) else startActivity(Intent(this, ProjectionActivity::class.java))
            root.postDelayed({ render() }, 1800)
        })
        c.addView(row)
        return c
    }

    private fun recVideoCard(): View {
        val c = card(14)
        c.addView(sectionTitle("Video"))
        val real = Recorder.realSize(this)

        // 1. ukuran layar
        val res = Prefs.int(this, "rec_res", 0).coerceIn(0, 7)
        c.addView(sTitle("Ukuran layar (resolusi rekaman)"), lpTop(10))
        c.addView(pillGrid(RecLogic.RES_NAMES, res, 4) { Prefs.setInt(this, "rec_res", it); render() }, lpTop(6))
        if (res == RecLogic.CUSTOM) {
            val row = LinearLayout(this)
            row.gravity = Gravity.CENTER_VERTICAL
            row.addView(numberField(Prefs.int(this, "rec_w", 1280), RecLogic.MIN_DIM, RecLogic.MAX_DIM, "lebar") { Prefs.setInt(this, "rec_w", RecLogic.clampDim(it)) }, lpW(3))
            row.addView(tv("×", 15f, Pal.MUTED, true))
            row.addView(numberField(Prefs.int(this, "rec_h", 720), RecLogic.MIN_DIM, RecLogic.MAX_DIM, "tinggi") { Prefs.setInt(this, "rec_h", RecLogic.clampDim(it)) }, lpW(3))
            c.addView(row, lpTop(8))
        }
        val sz = RecLogic.size(res, Prefs.int(this, "rec_w", 1280), Prefs.int(this, "rec_h", 720), real[0], real[1])
        c.addView(sNote("Hasil: " + sz[0] + "×" + sz[1] + "  (layar HP " + real[0] + "×" + real[1] + "). Semakin besar semakin tajam, tapi file lebih besar dan HP lebih berat. " +
            "'Asli' = sama dengan layar. Preset 1080p/720p dst mengikuti sisi pendek layar dengan rasio tetap. Kustom: lebar × tinggi sendiri (genap, 160-4096)."), lpTop(6))

        // 2. bit rate video
        val br = Prefs.int(this, "rec_vbr", 6)
        c.addView(sTitle("Bit rate video (rata-rata, Mbps)"), lpTop(14))
        c.addView(pillGrid(RecLogic.BITRATES.map { it.toString() }, RecLogic.BITRATES.indexOf(br).let { if (it < 0) 3 else it }, 5) {
            Prefs.setInt(this, "rec_vbr", RecLogic.BITRATES[it]); render()
        }, lpTop(6))
        c.addView(sNote(RecLogic.bitrateNote(br) + "\nBit rate = jumlah data per detik video. Makin tinggi makin tajam dan makin besar file. " +
            "Rumus: Mbps × 60 ÷ 8 = MB per menit."), lpTop(6))

        // 3. codec
        val codec = Prefs.int(this, "rec_codec", 0).coerceIn(0, 2)
        c.addView(sTitle("Pengkodean video (codec)"), lpTop(14))
        c.addView(pillGrid(RecLogic.CODEC_NAMES, codec, 3) { Prefs.setInt(this, "rec_codec", it); render() }, lpTop(6))
        val sup = RecLogic.CODEC_NAMES.filterIndexed { i, _ -> Recorder.encoderSupported(RecLogic.CODEC_MIME[i]) }
        c.addView(sNote(RecLogic.codecNote(codec)), lpTop(6))
        c.addView(tv("Didukung HP ini: " + sup.joinToString(", ") + if (!sup.contains(RecLogic.CODEC_NAMES[codec])) ".  Pilihan sekarang tidak didukung, otomatis memakai H.264." else ".",
            10.5f, if (sup.contains(RecLogic.CODEC_NAMES[codec])) Pal.OK else Pal.WARN, true), lpTop(4))

        // 4. frame rate
        val fps = Prefs.int(this, "rec_fps", 30)
        val hz = GameActions(this).maxHz()
        c.addView(sTitle("Frame rate (rata-rata, fps)"), lpTop(14))
        c.addView(pillGrid(RecLogic.FPS_OPTS.map { it.toString() }, RecLogic.FPS_OPTS.indexOf(fps).let { if (it < 0) 1 else it }, 5) {
            Prefs.setInt(this, "rec_fps", RecLogic.FPS_OPTS[it]); render()
        }, lpTop(6))
        c.addView(sNote("Jumlah gambar per detik pada video. 24-30 fps hemat dan cukup untuk tontonan; 60 fps lebih halus untuk game cepat; 90/120 fps untuk game dengan layar tinggi. " +
            "Layar HP ini maksimal $hz Hz, nilai di atas itu tidak menambah halus. Makin tinggi fps, makin besar file dan beban HP."), lpTop(6))
        return c
    }

    private fun recAudioCard(): View {
        val c = card(14)
        c.addView(sectionTitle("Audio"))
        c.addView(switchRow("Rekam audio", "Suara game dan/atau mikrofon ikut direkam. Butuh izin mikrofon", "rec_audio", true) { render() })
        if (!Prefs.bool(this, "rec_audio", true)) {
            c.addView(sNote("Audio dimatikan. Video akan direkam tanpa suara."), lpTop(4))
            return c
        }
        // 9. sumber suara
        val src = Prefs.int(this, "rec_src", 0).coerceIn(0, 2)
        c.addView(sTitle("Sumber suara"), lpTop(8))
        c.addView(pillGrid(RecLogic.SRC_NAMES, src, 3) { Prefs.setInt(this, "rec_src", it); render() }, lpTop(6))
        c.addView(sNote(when (src) {
            0 -> "Internal: suara yang keluar dari HP (game, musik, video). Butuh Android 10+. Beberapa app melarang direkam, hasilnya bisa hening."
            1 -> "Internal + Mic: suara game dicampur mikrofon, cocok untuk komentar atau voice chat saat main. Butuh Android 10+. Pakai headset supaya suara game tidak masuk dua kali lewat mic."
            else -> "Mic: hanya suara dari mikrofon."
        }), lpTop(6))
        if (src != 2 && android.os.Build.VERSION.SDK_INT < 29)
            c.addView(tv("HP ini Android " + android.os.Build.VERSION.RELEASE + ": audio internal tidak tersedia, otomatis memakai mic.", 10.5f, Pal.WARN, true), lpTop(4))

        // 6. bit rate audio
        val abr = Prefs.int(this, "rec_abr", 128)
        c.addView(sTitle("Bit rate audio (kbps)"), lpTop(14))
        c.addView(pillGrid(RecLogic.ABR_OPTS.map { it.toString() }, RecLogic.ABR_OPTS.indexOf(abr).let { if (it < 0) 2 else it }, 6) {
            Prefs.setInt(this, "rec_abr", RecLogic.ABR_OPTS[it]); render()
        }, lpTop(6))
        c.addView(sNote(when {
            abr <= 64 -> "$abr kbps: hemat tempat, kualitas suara pas-pasan. Cukup untuk suara bicara."
            abr <= 96 -> "$abr kbps: hemat, cukup baik untuk suara game."
            abr <= 128 -> "$abr kbps: standar yang bagus untuk video. Disarankan."
            else -> "$abr kbps: jernih, file sedikit lebih besar. Terasa bedanya terutama pada musik."
        }), lpTop(6))

        // 7. tingkat sampel
        val sr = Prefs.int(this, "rec_sr", 48000)
        c.addView(sTitle("Tingkat sampel audio"), lpTop(14))
        c.addView(pillGrid(listOf("22,05 kHz", "44,1 kHz", "48 kHz"), RecLogic.SR_OPTS.indexOf(sr).let { if (it < 0) 2 else it }, 3) {
            Prefs.setInt(this, "rec_sr", RecLogic.SR_OPTS[it]); render()
        }, lpTop(6))
        c.addView(sNote("Seberapa sering suara dicuplik per detik. 48 kHz = standar video, 44,1 kHz = standar musik/CD, 22,05 kHz = hemat tetapi suara tinggi berkurang."), lpTop(6))

        // 8. saluran
        val ch = Prefs.int(this, "rec_ch", 2)
        c.addView(sTitle("Saluran audio"), lpTop(14))
        c.addView(pillGrid(listOf("Stereo", "Mono"), if (ch == 1) 1 else 0, 2) { Prefs.setInt(this, "rec_ch", if (it == 0) 2 else 1); render() }, lpTop(6))
        c.addView(sNote(if (ch == 1) "Mono: satu saluran, file lebih kecil. Cukup untuk suara bicara/komentar."
        else "Stereo: kiri dan kanan terpisah, suara game terasa lebih luas. File sedikit lebih besar."), lpTop(6))
        return c
    }

    private fun splitNote(mb: Int): String {
        val br = Prefs.int(this, "rec_vbr", 6)
        return "Perkiraan ≈ " + RecLogic.minutesPerFile(mb, br) + " menit per file pada video $br Mbps. Batas: minimum " + RecLogic.SPLIT_MIN +
            " MB, maksimum " + RecLogic.SPLIT_MAX + " MB (sekitar 4 GB)."
    }

    private fun recFileCard(): View {
        val c = card(14)
        c.addView(sectionTitle("File & penyimpanan"))
        // 10. file baru otomatis
        c.addView(switchRow("Buat file baru otomatis", "Rekaman terus berjalan tanpa berhenti. Saat file mencapai batas ukuran, file video baru dibuat (_part2, _part3, ...)", "rec_split", false) { render() })
        if (Prefs.bool(this, "rec_split", false)) {
            val mb = RecLogic.clampSplitMb(Prefs.int(this, "rec_split_mb", 1000))
            c.addView(sTitle("Batas ukuran per file (ketik angka, MB)"), lpTop(6))
            val est = sNote(splitNote(mb))
            c.addView(numberField(mb, RecLogic.SPLIT_MIN, RecLogic.SPLIT_MAX, "MB") { v -> Prefs.setInt(this, "rec_split_mb", v); est.text = splitNote(v) }, lpTop(6))
            c.addView(est, lpTop(6))
            c.addView(sNote("Berguna untuk montage: tiap file pendek mudah dipilah, dan file tanpa momen seru tinggal dihapus tanpa menyimpan rekaman panjang."), lpTop(4))
        }
        // 11. lokasi penyimpanan
        c.addView(sTitle("Lokasi penyimpanan"), lpTop(14))
        val loc = tv(RecStorage.label(this), 11.5f, Skin.a, true)
        loc.iconStart(Ic.IMAGE, 14, Skin.a, 8)
        c.addView(loc, lpTop(6))
        val row = LinearLayout(this)
        row.addView(btn("Pilih folder", true, Ic.IMAGE) { pickRecDir() }, lpW(3))
        row.addView(btn("Bawaan", false, Ic.REFRESH) { Prefs.setStr(this, "rec_dir", ""); render() }, lpW(3))
        c.addView(row, lpTop(8))
        c.addView(sNote("Bawaan: folder Movies/GameSpace (muncul di galeri). Folder pilihan dipakai untuk semua file rekaman, termasuk file otomatis."), lpTop(6))
        return c
    }

    @Suppress("DEPRECATION")
    private fun pickRecDir() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try { startActivityForResult(i, REQ_DIR) } catch (_: Throwable) { Toast.makeText(this, "Tidak ada pemilih folder", Toast.LENGTH_SHORT).show() }
    }

    private fun restoreNow() {
        Thread {
            GameActions(this).restoreAll(null)
            runOnUiThread { Toast.makeText(this, "Setelan sistem & app latar dipulihkan", Toast.LENGTH_SHORT).show(); render() }
        }.start()
    }

    private fun changelog(): String =
        "v1.7\n" +
        " • Perekam Layar bawaan (tab Rekam): ukuran layar (preset + kustom), bit rate video, codec H.264 / MPEG_4_SP / H.265 (HEVC), frame rate, audio (sumber internal / internal + mic / mic, bit rate, sample rate, stereo/mono), file baru otomatis saat mencapai batas ukuran (100-4000 MB) tanpa menghentikan rekaman, dan lokasi penyimpanan\n" +
        " • Overlay kapsul: bagian jaringan kini menampilkan PING (contoh 1ms, maks 999ms) berwarna: <=50 hijau, 51-100 kuning, >100 merah. Teks WIFI / DATA tetap di bawahnya\n" +
        " • Tab 'Rekaman' diganti nama menjadi 'Laporan' (laporan performa) agar tidak tertukar dengan perekam layar\n" +
        " • Kredit KhimDev ditambahkan di bagian Tentang\n\n" +
        "v1.6\n" +
        " • HUD Display gaya PC (seperti MangoHud): RENDER (VULKAN/SKIA/OPENGL), GPU, CPU, RAM, PWR (watt), TMP, FPS, dan grafik FPS. Huruf besar berwarna, di pojok kiri bawah. Tiap item bisa dipilih, ukuran 60-200%, transparansi latar bisa diatur, bisa digeser\n" +
        " • Overlay FPS kapsul tetap ada. Keduanya bisa dinyalakan sendiri-sendiri atau bersamaan (tab HUD di panel Space)\n" +
        " • Semua emoji diganti ikon gaming gambar sendiri: dashboard, panel, banner, dan tombol\n" +
        " • Label render pada HUD PC terdeteksi dari game yang berjalan\n\n" +
        "v1.5\n" +
        " • Pusat Pembaruan: popup 'Apa yang baru' otomatis setelah update, plus Cek pembaruan dari dalam aplikasi (isi URL file version.json di Info). Unduh & pasang langsung, dengan verifikasi SHA-256\n" +
        " • Mode per game: tiap game bisa punya mode sendiri (Hemat/Seimbang/Performa/Turbo) di tab Game. Auto = ikut mode global\n" +
        " • Ringkasan sesi: setelah keluar game muncul banner durasi, rata-rata FPS, suhu maks, dan selisih baterai. Ada kartu 'Sesi terakhir' di Beranda\n" +
        " • Opsi mulai otomatis saat HP menyala\n\n" +
        "v1.4\n" +
        " • Overlay FPS: cincin FPS dihapus, kini hanya angka yang berwarna (1-29 merah, 30-59 kuning, 60-73 hijau, 74+ biru)\n" +
        " • Perbaikan: build v1.3 sebelumnya salah label versi & tidak memuat izin getar. v1.4 memuat semua fitur v1.3\n" +
        " • Tombol 'Coba peringatan' untuk melihat tampilan banner, notifikasi, dan getar\n\n" +
        "v1.3\n" +
        " • Peringatan pengisian: ingatkan cabut charger di persentase pilihanmu (80-100%), peringatan panas saat mengisi sambil main, suhu naik cepat, dan baterai rendah. Tampil sebagai banner di atas game + notifikasi + getar\n" +
        " • Batasi app latar (appops): selama sesi game, app lain dilarang jalan di latar lalu dipulihkan otomatis. Ada daftar pengecualian dan tombol Pulihkan\n" +
        " • Combo kini meminta persetujuan sekali (risiko ban di game online) dan bisa dimatikan dari Info\n" +
        " • Semua pemberitahuan (FPS naik/turun, redup idle, dll) kini berupa banner berwarna di atas game\n\n" +
        "v1.2\n" +
        " • Main game langsung dari dashboard, dengan pilihan renderer (OpenGL / SkiaGL / Vulkan; otomatis pindah ke yang didukung) dan resolusi game sampai 30%\n" +
        " • Musik di dashboard: 5 slot lagu pilihan, putar otomatis saat aplikasi dibuka\n" +
        " • Foto dashboard bisa diganti, otomatis di-crop (tidak ketarik)\n" +
        " • Overlay FPS baru: kapsul kaca blur dengan cincin berwarna (1-29 merah, 30-59 kuning, 60-73 hijau, 74+ biru), ikon WiFi/data, RAM, baterai, jam. Tiap item bisa dimatikan. Tembus sentuhan, bisa digeser bila diaktifkan\n" +
        " • Knob kecil berwarna mode di tengah HUD untuk membuka Space. Panel Space kini liquid glass\n" +
        " • Kecerahan & volume langsung di panel Space\n" +
        " • Combo: tombol tahan C1 (bertahap, hingga 10 titik A1-J1) dan C2 (bersamaan, hingga 4 titik A2-D2), kecepatan bisa diatur, posisi tersimpan per game\n" +
        " • Seimbang: batas FPS mengikuti layar, turun 60 FPS saat ≥38°C. Performa: turun 60 saat ≥40°C. Naik lagi setelah suhu turun 3°C, ada pemberitahuan\n" +
        " • Redup otomatis saat idle 3-5 menit (kecerahan terendah + FPS 60), normal lagi saat disentuh\n" +
        " • Performa & Turbo lebih gacor: refresh rate dikunci, game dibebaskan dari pembatasan sistem, jaringan latensi rendah, pengaturan ditegakkan ulang tiap 20 dtk\n\n" +
        "v1.1\n" +
        " • Dashboard & overlay didesain ulang, warna per mode, Rekam Performa, GPU Lock, Tampilan, Rekam Layar, E-Sport, dll\n\n" +
        "v1.0\n • Rilis awal"

    private fun showText(title: String, text: String, neutral: Pair<String, () -> Unit>? = null) {
        val t = tv(text, 11f, Pal.TEXT)
        t.typeface = Typeface.MONOSPACE
        t.setPadding(dp(20), dp(10), dp(20), dp(10))
        t.setTextIsSelectable(true)
        val sv = ScrollView(this)
        sv.addView(t)
        val b = AlertDialog.Builder(this).setTitle(title).setView(sv).setPositiveButton("Tutup", null)
        if (neutral != null) b.setNeutralButton(neutral.first) { _, _ -> neutral.second() }
        b.show()
    }

    private fun showCrash() {
        val f = File(filesDir, "crash.txt")
        val txt = try { f.readText() } catch (_: Throwable) { "(tidak terbaca)" }
        showText("Laporan error", txt, Pair("Hapus") { f.delete(); render() })
    }

    private fun runDiag() {
        Toast.makeText(this, "Memindai perangkat...", Toast.LENGTH_SHORT).show()
        ComboClient.bind(this)
        Thread {
            val h = Hw.discover(this)
            try { Thread.sleep(1200) } catch (_: Throwable) {}
            val sb = StringBuilder()
            sb.append("Game Space v").append(appVersion()).append("\n")
            sb.append("Perangkat : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n")
            sb.append("Android   : ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
            sb.append("Platform  : ").append(h.plat).append(" / ").append(h.soc).append("\n")
            sb.append("Layar     : maks ").append(GameActions(this).maxHz()).append(" Hz\n")
            sb.append("Blur kaca : ").append(if (Glass.blurOk(getSystemService(WINDOW_SERVICE) as WindowManager)) "aktif" else "tidak tersedia (pakai tint pekat)").append("\n")
            sb.append("Vulkan    : ").append(if (Gfx.vulkanOk(this)) "didukung" else "tidak didukung").append(" • downscale ")
                .append(if (Gfx.scaleSupported()) "didukung" else "butuh Android 12+").append("\n")
            sb.append("Shizuku   : ").append(if (ShizukuHelper.granted()) "diizinkan, uid server " + ShizukuHelper.serverUid()
                else if (ShizukuHelper.running()) "berjalan, belum diizinkan" else "tidak berjalan").append("\n")
            sb.append("Shell uid : ").append(h.uid).append(if (h.hasSu) "  (su tersedia)" else "").append("\n")
            sb.append("Combo     : ").append(ComboClient.state).append("\n")
            sb.append("Izin      : overlay=").append(Settings.canDrawOverlays(this)).append("  usage=").append(Perm.usage(this)).append("\n\n")
            sb.append("CPU cluster (").append(h.cpus.size).append("):\n")
            for (c in h.cpus) sb.append("  ").append(c.path.substringAfterLast('/')).append(": max ").append(c.maxKhz / 1000)
                .append(" MHz, gov ").append(c.gov).append(", kunci ").append(if (c.canMin) "BISA" else "ditolak").append("\n")
            sb.append("\nGPU: ").append(h.gKind).append("  kunci ").append(if (h.gCanLock) "BISA" else "ditolak").append("\n")
            sb.append("  frekuensi : ").append(h.gFreqs.joinToString(", ") { (it / 1000).toString() }).append(" MHz\n")
            sb.append("  node freq : ").append(h.gFreqNode.ifEmpty { "-" }).append("\n")
            sb.append("  node load : ").append(h.gLoadNode.ifEmpty { "-" }).append("\n")
            sb.append("  suhu CPU  : ").append(h.cpuTempNode.ifEmpty { "-" }).append("\n\n")
            sb.append("Sumber FPS: ").append(AlyaAiService.fpsSource).append("\n")
            sb.append("Setelan menunggu dipulihkan: ").append(Cfg.pending(this)).append("\n\n")
            sb.append("Log aksi:\n").append(ActionLog.dump())
            runOnUiThread { showText("Diagnostik", sb.toString()) }
        }.start()
    }
}
