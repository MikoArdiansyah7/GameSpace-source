package com.alya.gamespace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlyaAiService : Service(), PanelCallbacks {

    companion object {
        @Volatile var running = false
        @Volatile var fpsSource = "-"
        @Volatile var inGame = false
        const val ACTION_STOP = "com.alya.gamespace.STOP"
        const val ACTION_TEST = "com.alya.gamespace.TEST_ALERT"
        private const val CH = "alya_ai_service"
        private const val ALERT_CH = "alya_alert"
    }

    private lateinit var wm: WindowManager
    private lateinit var actions: GameActions
    private val mainH = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("alya-bg").also { it.start() }
    private val bgH = Handler(thread.looper)
    private val meter = FpsMeter()
    private val runners = arrayOf(ComboRunner { xs, ys, ms -> ComboClient.tap(xs, ys, ms) },
        ComboRunner { xs, ys, ms -> ComboClient.tap(xs, ys, ms) })
    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var blur = false

    // jendela overlay
    private var panel: PanelUi? = null
    private var knobView: KnobView? = null
    private var knobLp: WindowManager.LayoutParams? = null
    private var hudView: HudView? = null
    private var hudLp: WindowManager.LayoutParams? = null
    private var pcHud: PcHudView? = null
    private var pcLp: WindowManager.LayoutParams? = null
    private var rendererLabel = "--"
    private var detCount = 0
    private var crossView: CrosshairView? = null
    private var watcher: View? = null
    private val guardViews = ArrayList<View>()
    private val comboViews = ArrayList<ComboButton>()
    private var editor: ComboEditor? = null
    private var panelOpen = false
    private var knobX = 0
    private var knobY = 0

    // game & mode
    private var game: String? = null
    private var gameLabel = ""
    private var curMode = 2
    private var counter = 0
    private val st = HashMap<String, Boolean>()
    private var gpuIdx = -1
    private var visualSel = 0

    // governor suhu & idle
    @Volatile private var lastTouch = 0L
    @Volatile private var watcherSeen = false
    private var thermalLimited = false
    private var idle = false
    private var dimSaved: IntArray? = null
    private var lastCap = -1
    private var govCount = 0
    private var notifExtra = ""

    // peringatan pengisian, banner, persetujuan
    private val chargeMemo = ChargeMemo()
    private var bannerView: LinearLayout? = null
    private var bannerText: TextView? = null
    private var bannerIcon: IconView? = null
    private val bannerHide = Runnable { hideBanner() }
    private var consent: View? = null
    private val recListener: () -> Unit = { mainH.post { panel?.sync(); applyRecKnob() } }

    // ringkasan sesi
    private var sessStart = 0L
    private var playBase = 0L        // ms main nonstop sebelum sesi sekarang (jeda < 5 menit dianggap satu sesi)
    private var lastLeaveAt = 0L
    // Tap Otomatis
    private var atTarget: AutoTapTarget? = null
    private var atTargetLp: WindowManager.LayoutParams? = null
    private var atPill: AutoTapPill? = null
    private var atPillLp: WindowManager.LayoutParams? = null
    private val atRunner = AutoTapRunner { xs, ys, d -> ComboClient.tap(xs, ys, d) }
    private var atPkg = ""
    private var lastFg = ""
    @Volatile private var atInApp = true
    private var atLastUi = 0L
    private var dragSx = 0f
    private var dragSy = 0f
    private var dragOx = 0
    private var dragOy = 0
    private var dragMoved = false
    private var breakShown = 0       // berapa kali pengingat istirahat sudah muncul pada rentang main nonstop ini
    private var sessBat0 = -1
    private var sessBatNow = -1
    private var sessMaxTemp = 0f
    private var sessFpsSum = 0.0
    private var sessFpsN = 0

    // rekam performa
    private val rFps = ArrayList<Float>()
    private val rTemp = ArrayList<Float>()
    private val rWatt = ArrayList<Float>()
    private var rStart = 0L
    private var rGame = ""
    private var rLabel = ""

    private val overlayType get() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    private val fUi get() = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    private val fPass get() = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (i?.action == ACTION_TEST) {
            // contoh peringatan: banner kuning + notifikasi + getar, supaya pengguna tahu tampilannya
            bgH.post { chargeAlert(ChargeLogic.HOT, 55, 41.5f, ChargeCfg(true, 90, true, 40f, true)) }
            return START_STICKY
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        actions = GameActions(this)
        blur = Glass.blurOk(wm)
        Skin.mode = Prefs.autoMode(this); curMode = Skin.mode
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "Alya AI Service", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(ALERT_CH, "Peringatan Game Space", NotificationManager.IMPORTANCE_HIGH))
        val n = buildNotif()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        knobX = Prefs.int(this, "knob_x", dp(110)); knobY = Prefs.int(this, "knob_y", dp(6))
        st["combo"] = Prefs.bool(this, "combo_on", false) && Prefs.bool(this, "combo_ack", false)
        st["c1"] = Prefs.bool(this, "c1_on", true)
        st["c2"] = Prefs.bool(this, "c2_on", true)
        if (Prefs.bool(this, "always", false)) showKnob()
        Foreground.reset(); Stats.reset()
        ComboClient.bind(this)
        RecorderService.listeners.add(recListener)
        NetWatch.start(this)
        applyRecKnob()
        bgH.post { Hw.discover(this); mainH.post { refreshGpuUi() } }
        bgH.post(tick); bgH.post(watch); bgH.postDelayed(govTask, 4000); bgH.postDelayed(chargeTask, 3000)
        // sisa pembatasan app latar dari sesi yang crash: pulihkan bila tidak sedang main
        bgH.postDelayed({ if (game == null && actions.pendingBackground() > 0) actions.restoreBackground() }, 6000)
    }

    // ───────── notifikasi ─────────
    private fun buildNotif(): Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, AlyaAiService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val base = if (game != null) Modes.all[curMode].name + " • " + gameLabel else "Menunggu game dibuka"
        val text = if (notifExtra.isNotEmpty() && game != null) base + "\n" + notifExtra else base
        return Notification.Builder(this, CH)
            .setContentTitle("Alya AI Service")
            .setContentText(base)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_fg)
            .setColor(Skin.a)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_fg), "Hentikan", stop).build())
            .build()
    }

    private fun updateNotif() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1, buildNotif())
    }

    /** Pemberitahuan naik/turun FPS dan kejadian penting lain. */
    private fun announce(msg: String, level: Int = 0, icon: Int = -1) {
        ActionLog.add(msg)
        notifExtra = msg
        mainH.post { showBanner(msg, level, icon); updateNotif() }
    }

    // ───────── mode per game & ringkasan sesi ─────────
    /** Mode khusus game (diatur di tab Game) atau, kalau Auto, mode global dashboard. */
    private fun modeFor(pkg: String?): Int =
        Logic.pickMode(if (pkg == null) -1 else Prefs.int(this, "gm_$pkg", -1), Prefs.autoMode(this))

    private fun batteryPct(): Int {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val raw = b.getIntExtra("level", -1)
        val scale = b.getIntExtra("scale", 100)
        return if (raw < 0 || scale <= 0) -1 else raw * 100 / scale
    }

    /** Setelah keluar game: banner ringkasan (durasi, rata-rata FPS, suhu maks, selisih baterai) + simpan untuk Beranda. */
    private fun finishSession(show: Boolean) {
        if (sessStart == 0L) return
        val dur = ((System.currentTimeMillis() - sessStart) / 1000).toInt()
        sessStart = 0L
        if (dur < 60) return
        val avg = if (sessFpsN > 0) (sessFpsSum / sessFpsN).toFloat() else 0f
        val now = batteryPct().let { if (it >= 0) it else sessBatNow }
        val label = gameLabel.replace('|', '/')
        Prefs.setStr(this, "last_session", label + "|" + System.currentTimeMillis() + "|" + dur + "|" + avg + "|" + sessMaxTemp + "|" + sessBat0 + "|" + now)
        if (show) announce(label + ": " + Logic.sessionText(dur, avg, sessMaxTemp, sessBat0, now), 0, Ic.FLAG)
    }

    // ───────── indikator rekam (knob RGB) ─────────
    private fun recRgbOn(): Boolean = RecorderService.recording && Prefs.bool(this, "knob_rgb", true)

    /** Selama merekam layar: knob dipaksa tampil dan berwarna RGB; setelah selesai kembali seperti biasa. */
    private fun applyRecKnob() {
        if (recRgbOn()) {
            if (knobView == null) showKnob()
            knobView?.setRgb(true)
        } else {
            knobView?.setRgb(false)
            if (knobView != null && game == null && !Prefs.bool(this, "always", false)) hideKnob()
        }
    }

    // ───────── Tap Otomatis ─────────
    private fun screenOn(): Boolean = (getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive

    /** Koreksi selisih posisi jendela overlay terhadap koordinat layar (inset/cutout), seperti tombol Combo. */
    private fun alignWindow(v: View, lp: WindowManager.LayoutParams, wantX: Int, wantY: Int) {
        v.post {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val dx = wantX - loc[0]; val dy = wantY - loc[1]
            if (dx != 0 || dy != 0) { lp.x += dx; lp.y += dy; try { wm.updateViewLayout(v, lp) } catch (_: Throwable) {} }
        }
    }

    private fun dragTouch(v: View, lp: WindowManager.LayoutParams, e: MotionEvent, onClick: (() -> Unit)?, onDrop: () -> Unit) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragSx = e.rawX; dragSy = e.rawY; dragOx = lp.x; dragOy = lp.y; dragMoved = false; ownTouch() }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - dragSx).toInt(); val dy = (e.rawY - dragSy).toInt()
                if (!dragMoved && Math.abs(dx) + Math.abs(dy) > dp(6)) dragMoved = true
                if (dragMoved) { lp.x = dragOx + dx; lp.y = dragOy + dy; try { wm.updateViewLayout(v, lp) } catch (_: Throwable) {} }
            }
            MotionEvent.ACTION_UP -> { if (dragMoved) onDrop() else onClick?.invoke() }
            MotionEvent.ACTION_CANCEL -> { if (dragMoved) onDrop() }
        }
    }

    private fun showAutoTap() {
        hideAutoTap()
        if (st["atap"] != true) return
        ComboClient.bind(this)
        val sw = screenW(); val sh = screenH()
        val key = comboKey()
        val t = AutoTapStore.target(this, key, sw, sh)
        val pl = AutoTapStore.pill(this, key, sw, sh)
        val ts = dp(56)
        val tg = AutoTapTarget(this)
        val tlp = baseLp(ts, ts, fUi)
        tlp.x = t[0] - ts / 2; tlp.y = t[1] - ts / 2
        tg.setOnTouchListener { _, e -> dragTouch(tg, tlp, e, null) { saveAutoTarget() }; true }
        try { wm.addView(tg, tlp) } catch (_: Throwable) { return }
        atTarget = tg; atTargetLp = tlp
        alignWindow(tg, tlp, t[0] - ts / 2, t[1] - ts / 2)
        val pg = AutoTapPill(this)
        val plp = baseLp(dp(92), dp(36), fUi)
        plp.x = pl[0]; plp.y = pl[1]
        pg.setOnTouchListener { _, e -> dragTouch(pg, plp, e, { atToggleRun() }) { saveAutoPill() }; true }
        try { wm.addView(pg, plp) } catch (_: Throwable) { return }
        atPill = pg; atPillLp = plp
        alignWindow(pg, plp, pl[0], pl[1])
    }

    private fun hideAutoTap() {
        atRunner.stop()
        atTarget?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        atPill?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        atTarget = null; atTargetLp = null; atPill = null; atPillLp = null
    }

    private fun saveAutoTarget() {
        val v = atTarget ?: return
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        AutoTapStore.saveTarget(this, comboKey(), screenW(), screenH(), loc[0] + v.width / 2, loc[1] + v.height / 2)
    }

    private fun saveAutoPill() {
        val v = atPill ?: return
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        AutoTapStore.savePill(this, comboKey(), screenW(), screenH(), loc[0], loc[1])
    }

    private fun atToggleRun() {
        if (atRunner.running) { atRunner.stop(); atFinish("dihentikan"); return }
        if (!ComboClient.ready()) { toast("Tap Otomatis butuh Shizuku aktif dan diizinkan"); return }
        val tg = atTarget ?: return
        val pv = atPill ?: return
        val tl = IntArray(2); tg.getLocationOnScreen(tl)
        val x = tl[0] + tg.width / 2; val y = tl[1] + tg.height / 2
        val pl = IntArray(2); pv.getLocationOnScreen(pl)
        if (AutoTapLogic.overlaps(x, y, pl[0], pl[1], pv.width, pv.height, dp(8))) { toast("Geser lingkaran bidik menjauh dari tombol TAP"); return }
        atPkg = game ?: lastFg
        atInApp = true
        val ms = Prefs.int(this, "at_ms", 500); val jit = Prefs.int(this, "at_jit", 0)
        val down = Prefs.int(this, "at_down", 30); val lim = Prefs.int(this, "at_limit", 0)
        tg.running = true; tg.invalidate()
        pv.running = true; pv.count = 0; pv.invalidate()
        // saat berjalan jendela bidik tembus sentuhan; opasitas jendela <= 0,8 supaya Android 12+ tidak memblokir ketukan yang melewatinya
        atTargetLp?.let { lp -> lp.flags = fPass; lp.alpha = 0.6f; try { wm.updateViewLayout(tg, lp) } catch (_: Throwable) {} }
        ActionLog.add("Tap Otomatis mulai di " + x + "," + y + " tiap " + ms + " ms (acak " + jit + "%, sentuh " + down + " ms, batas " + lim + " mnt)")
        atRunner.start(x, y, ms, jit, down,
            { el, n -> AutoTapLogic.stopReason(el, lim, n, 0, screenOn(), atInApp) },
            { n ->
                val now = android.os.SystemClock.uptimeMillis()
                if (now - atLastUi > 80L) {
                    atLastUi = now
                    mainH.post { atTarget?.pulse(); atPill?.let { it.count = n; it.invalidate() } }
                }
            },
            { why -> mainH.post { atFinish(why) } })
    }

    private fun atFinish(why: String) {
        atTarget?.let { it.running = false; it.invalidate() }
        atPill?.let { it.running = false; it.invalidate() }
        val tg = atTarget; val lp = atTargetLp
        if (tg != null && lp != null) { lp.flags = fUi; lp.alpha = 1f; try { wm.updateViewLayout(tg, lp) } catch (_: Throwable) {} }
        ActionLog.add("Tap Otomatis berhenti: " + why)
        if (why != "dihentikan") toast("Tap Otomatis berhenti: " + why)
    }

    /** Pengingat istirahat: tiap kelipatan waktu main nonstop (diatur di Info), tampil sebagai banner di atas game. */
    private fun checkBreak() {
        val min = Prefs.int(this, "brk_min", 0)
        if (min <= 0 || sessStart == 0L || game == null) return
        val el = playBase + (System.currentTimeMillis() - sessStart)
        val due = PlayLogic.breaksDue(el, min * 60_000L)
        if (due > breakShown) {
            breakShown = due
            announce(PlayLogic.breakText(el), 1, Ic.CLOCK)
        }
    }

    // ───────── loop statistik, deteksi game, governor ─────────
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val recording = st["rec"] == true
            if (panelOpen || st["hud"] == true || st["hudpc"] == true || recording) {
                val s = Stats.read(this@AlyaAiService, game, meter, counter % 3 == 0)
                if (s.fps > 0f) { sessFpsSum += s.fps; sessFpsN++ }
                if (recording) synchronized(rFps) {
                    rFps.add(if (s.fps > 0f) s.fps else 0f); rTemp.add(s.temp); rWatt.add(if (s.watt > 0f) s.watt else 0f)
                }
                mainH.post { onSnap(s) }
            }
            checkBreak()
            counter++
            bgH.postDelayed(this, 1000)
        }
    }

    private val watch = object : Runnable {
        override fun run() {
            if (!running) return
            Foreground.poll(this@AlyaAiService)?.let { fg -> mainH.post { onForeground(fg) } }
            bgH.postDelayed(this, 2000)
        }
    }

    private val govTask = object : Runnable {
        override fun run() {
            if (!running) return
            try { govStep() } catch (t: Throwable) { ActionLog.add("governor: " + t.javaClass.simpleName) }
            bgH.postDelayed(this, 4000)
        }
    }

    private fun batteryTemp(): Float {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (b?.getIntExtra("temperature", 0) ?: 0) / 10f
    }

    private fun isCharging(): Boolean {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val s = b?.getIntExtra("status", -1) ?: -1
        return s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun f1(v: Float) = "%.1f".format(v)

    private fun effCap(): Int = Logic.effectiveCap(actions.modeCap(curMode), thermalLimited, idle)

    private fun applyCapIfChanged() {
        val cap = effCap()
        if (cap != lastCap) { lastCap = cap; actions.setFpsCap(curMode, cap, game) }
    }

    /** Berjalan tiap 4 dtk (thread bg) selama ada game. */
    private fun govStep() {
        val g = game ?: return
        val mode = curMode
        val hz = actions.maxHz()
        // 1) Batas suhu: Seimbang 38°C, Performa 40°C. Turun 3°C dulu baru FPS naik lagi (anti plin-plan).
        if (Prefs.bool(this, "fps_thermal", true) && hz > 60 && (mode == 1 || mode == 2)) {
            val t = batteryTemp()
            val trig = Logic.trigger(mode)
            val next = Logic.thermalNext(thermalLimited, t, trig)
            if (next != thermalLimited) {
                thermalLimited = next
                val cap = effCap()
                if (next) announce("Suhu " + f1(t) + "°C (batas " + trig.toInt() + "°C): FPS turun ke " + cap + " (layar maks " + hz + " Hz)", 1, Ic.THERMO)
                else announce("Suhu turun ke " + f1(t) + "°C: FPS naik lagi ke " + cap, 3, Ic.CHECK)
            }
        } else if (thermalLimited) thermalLimited = false
        // 2) Idle: tanpa sentuhan 3-5 menit -> layar redup + FPS 60
        if (Prefs.bool(this, "auto_dim", true) && watcherSeen && !idle) {
            val limit = Prefs.int(this, "idle_min", 4) * 60_000L
            if (System.currentTimeMillis() - lastTouch > limit) {
                idle = true
                dimSaved = actions.dim()
                announce("Tidak ada sentuhan " + Prefs.int(this, "idle_min", 4) + " menit: layar diredupkan, FPS " + effCap(), 0, Ic.CLOCK)
            }
        }
        // deteksi API grafis untuk label HUD PC: sering sampai ketemu, lalu tiap ~60 dtk
        if (pcHud != null && pcHud?.show?.get(0) == true) {
            detCount++
            if ((rendererLabel == "--" && detCount <= 12) || detCount % 15 == 0) {
                val label = Gfx.detect(g)
                if (label != rendererLabel) { rendererLabel = label; mainH.post { pcHud?.let { it.renderer = label; it.computeSize(); layoutPcHud(); it.invalidate() } } }
            }
        }
        applyCapIfChanged()
        // 3) Performa/Turbo: tegakkan ulang pengaturan tiap ~20 dtk
        if (mode >= 2 && ++govCount % 5 == 0) actions.reassert(mode, g)
    }

    private fun wake() {
        if (!idle) return
        idle = false
        dimSaved?.let { actions.undim(it) }
        dimSaved = null
        applyCapIfChanged()
        announce("Aktif lagi: kecerahan dipulihkan, FPS " + effCap(), 0, Ic.POINTER)
    }

    private fun ownTouch() {
        lastTouch = System.currentTimeMillis()
        if (idle) bgH.post { wake() }
    }

    private fun outsideTouch() {
        watcherSeen = true
        ownTouch()
    }

    private inner class Watcher(c: Context) : View(c) {
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) outsideTouch()
            return false
        }
    }

    private fun showWatcher() {
        if (watcher != null) return
        val v = Watcher(this)
        val lp = baseLp(1, 1, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        try { wm.addView(v, lp); watcher = v } catch (_: Throwable) {}
    }

    private fun hideWatcher() {
        watcher?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        watcher = null
    }

    // ───────── data HUD ─────────
    private fun netType(): Int = try {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null) 0 else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) 2 else 1
    } catch (_: Throwable) { 0 }

    private fun onSnap(s: Snap) {
        panel?.update(s, gameLabel, Hw.statusText())
        pcHud?.let { h ->
            h.gpu = s.gpuLoad; h.cpu = s.cpuLoad; h.ram = s.ram; h.watt = s.watt; h.charging = s.charging; h.temp = s.temp; h.fps = s.fps
            h.renderer = rendererLabel
            h.push(if (s.fps > 0f) s.fps else 0f)
            h.invalidate()
        }
        hudView?.let { h ->
            h.fps = s.fps; h.cpu = s.cpuLoad; h.gpu = s.gpuLoad; h.ram = s.ram; h.bat = s.battery; h.ping = s.ping
            h.charging = isCharging(); h.temp = s.temp; h.time = clockFmt.format(Date()); h.net = NetWatch.label().let { if (it != NetLogic.NONE) it else netType() }
            h.invalidate()
        }
    }

    private fun ignored(p: String) = p == packageName || p == "com.android.systemui" ||
        p.contains("inputmethod") || p.contains("keyboard")

    private fun onForeground(fg: String) {
        lastFg = fg
        if (atRunner.running) atInApp = (fg == atPkg || ignored(fg))
        if (fg in Prefs.games(this)) { if (game != fg) enterGame(fg) }
        else if (game != null && !ignored(fg)) leaveGame()
    }

    private fun enterGame(pkg: String) {
        game = pkg
        inGame = true
        gameLabel = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { pkg }
        curMode = modeFor(pkg); Skin.mode = curMode
        val prevStart = sessStart
        sessStart = System.currentTimeMillis(); sessBat0 = batteryPct(); sessBatNow = sessBat0
        if (prevStart != 0L) { playBase += sessStart - prevStart; lastLeaveAt = sessStart }   // pindah langsung antar game tetap satu rentang main
        playBase = PlayLogic.resume(playBase, if (lastLeaveAt > 0L) sessStart - lastLeaveAt else Long.MAX_VALUE)
        if (playBase == 0L) breakShown = 0
        sessMaxTemp = 0f; sessFpsSum = 0.0; sessFpsN = 0
        thermalLimited = false; idle = false; dimSaved = null; lastCap = -1; govCount = 0; notifExtra = ""
        lastTouch = System.currentTimeMillis(); watcherSeen = false
        showKnob(); restyleAll(); updateNotif()
        actions.toast("Game Space aktif: $gameLabel")
        if (Prefs.bool(this, "auto_dim", true)) showWatcher()
        rendererLabel = "--"; detCount = 0
        if (Prefs.bool(this, "auto_hud", true)) { st["hud"] = true; showHud() }
        if (Prefs.bool(this, "auto_pc", false)) { st["hudpc"] = true; showPcHud() }
        if (Prefs.bool(this, "auto_rec", false)) { st["rec"] = true; startRecord() }
        showCombo()
        ComboClient.bind(this)
        bgH.post {
            val cap = effCap()
            actions.applyMode(curMode, pkg, cap)
            lastCap = cap
            if (curMode >= 2 && Prefs.bool(this, "auto_boost", true)) actions.boost(pkg)
            mainH.post { refreshGpuUi() }
            if (Prefs.bool(this, "bg_auto", true)) {
                mainH.post { st["limit"] = true; panel?.sync() }
                val n = actions.restrictBackground(pkg)
                if (n > 0) announce("$n app dibatasi di latar selama kamu main (dipulihkan otomatis)", 0, Ic.BLOCK)
                else if (n == 0) announce("Tidak ada app lain yang perlu dibatasi", 0, Ic.BLOCK)
            }
        }
        panel?.sync()
    }

    private fun leaveGame() {
        val g = game
        game = null
        inGame = false
        if (sessStart != 0L) { val t = System.currentTimeMillis(); playBase += t - sessStart; lastLeaveAt = t }
        finishSession(true)
        closePanel(false)
        hideHud(); hidePcHud(); hideWatcher(); hideCombo(); hideAutoTap()
        editor?.close(); editor = null
        if (st["rec"] == true) bgH.post { stopRecord() }
        for (id in listOf("hud", "hudpc", "rec", "cross", "srec", "dnd", "bright", "rot", "guard", "anim", "adaptive", "limit", "touch", "esport", "cpulock", "gpulock", "atap")) st[id] = false
        applyCross(); setGuard(false)
        visualSel = 0
        val wasIdle = idle
        val saved = dimSaved
        idle = false; dimSaved = null; thermalLimited = false; lastCap = -1
        if (!Prefs.bool(this, "always", false) && !recRgbOn()) hideKnob()
        panel?.sync(); updateNotif()
        bgH.post {
            if (wasIdle && saved != null) actions.undim(saved)
            actions.restoreBackground()
            if (Prefs.bool(this, "auto_restore", true)) actions.restoreAll(g)
            mainH.post { refreshGpuUi() }
        }
    }

    // ───────── knob + HUD (pojok atas) ─────────
    private fun baseLp(w: Int, h: Int, flags: Int): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(w, h, overlayType, flags, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        return lp
    }

    private fun hudDrag() = Prefs.bool(this, "hud_drag", false)

    private fun hudDefault(name: String) = name == "fps" || name == "net" || name == "ram" || name == "bat" || name == "time"

    private fun hudFlags(): Int {
        var f = fUi
        if (!hudDrag()) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return f
    }

    private fun attachTouch(v: View, isKnob: Boolean) {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        var moved = false; var opened = false
        val slop = dp(8)
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = knobX; oy = knobY; moved = false; opened = false; ownTouch() }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                    if (Math.abs(dx) > slop || Math.abs(dy) > slop) moved = true
                    if (hudDrag() && moved) { knobX = ox + dx; knobY = oy + dy; layoutHud() }
                    else if (isKnob && moved && !opened && (Math.abs(dx) > dp(14) || Math.abs(dy) > dp(14))) { opened = true; openPanel() }
                }
                MotionEvent.ACTION_UP -> {
                    if (hudDrag() && moved) { Prefs.setInt(this, "knob_x", knobX); Prefs.setInt(this, "knob_y", knobY) }
                    else if (!moved && !opened) {
                        val h = hudView
                        if (isKnob || (h != null && Math.abs(e.x - h.gapCx) < dp(22))) openPanel()
                    }
                }
            }
            true
        }
    }

    private fun showKnob() {
        if (knobView != null) return
        val v = KnobView(this)
        v.blur = blur
        v.alone = hudView == null
        v.setRgb(recRgbOn())
        val lp = baseLp(dp(40), dp(26), fUi)
        lp.x = knobX; lp.y = knobY
        attachTouch(v, true)
        try { wm.addView(v, lp); knobView = v; knobLp = lp } catch (_: Throwable) { return }
        layoutHud()
    }

    private fun hideKnob() {
        knobView?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        knobView = null; knobLp = null
    }

    private fun bringKnobFront() {
        val v = knobView ?: return
        val lp = knobLp ?: return
        try { wm.removeView(v); wm.addView(v, lp) } catch (_: Throwable) {}
    }

    private fun applyHudPrefs(v: HudView) {
        val names = arrayOf("fps", "net", "cpu", "gpu", "ram", "bat", "time", "temp")
        for (i in names.indices) v.show[i] = Prefs.bool(this, "hud_" + names[i], hudDefault(names[i]))
        v.blur = blur
        v.maxHz = actions.maxHz()
        v.computeSize()
    }

    private fun showHud() {
        if (hudView != null) { refreshHudConfig(); return }
        showKnob()
        val v = HudView(this)
        applyHudPrefs(v)
        if (!v.anyShown()) return
        val lp = baseLp(v.wPx, v.hPx, hudFlags())
        if (blur) Glass.apply(lp, dp(14))
        try { wm.addView(v, lp); hudView = v; hudLp = lp } catch (_: Throwable) { return }
        attachTouch(v, false)
        knobView?.alone = false; knobView?.invalidate()
        layoutHud(); bringKnobFront()
    }

    private fun hideHud() {
        hudView?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        hudView = null; hudLp = null
        knobView?.alone = true; knobView?.invalidate()
    }

    /** Dipanggil saat isi HUD / mode geser diubah. */
    private fun refreshHudConfig() {
        val v = hudView ?: return
        applyHudPrefs(v)
        if (!v.anyShown()) { hideHud(); return }
        hudLp?.let { it.flags = hudFlags() or (if (blur) WindowManager.LayoutParams.FLAG_BLUR_BEHIND else 0) }
        layoutHud(); v.invalidate()
    }

    /** Selaraskan knob dan HUD: celah HUD selalu tepat di bawah knob. */
    private fun layoutHud() {
        val k = knobView ?: return
        val kl = knobLp ?: return
        val sw = screenW(); val sh = screenH()
        val h = hudView
        var minX = 0; var maxX = maxOf(0, sw - kl.width)
        if (h != null) {
            minX = (h.gapCx - kl.width / 2f).toInt()
            maxX = maxOf(minX, (sw - (h.wPx - h.gapCx) - kl.width / 2f).toInt())
        }
        knobX = knobX.coerceIn(minX, maxX)
        knobY = knobY.coerceIn(0, maxOf(0, sh - kl.height))
        kl.x = knobX; kl.y = knobY
        try { wm.updateViewLayout(k, kl) } catch (_: Throwable) {}
        val hl = hudLp
        if (h != null && hl != null) {
            hl.width = h.wPx; hl.height = h.hPx
            hl.x = (knobX + kl.width / 2f - h.gapCx).toInt().coerceAtLeast(0)
            hl.y = (knobY + kl.height / 2 - h.hPx / 2).coerceAtLeast(0)
            try { wm.updateViewLayout(h, hl) } catch (_: Throwable) {}
        }
    }

    // ───────── HUD Display gaya PC (pojok kiri bawah) ─────────
    private fun applyPcPrefs(v: PcHudView) {
        val keys = arrayOf("gfx", "gpu", "cpu", "ram", "pwr", "tmp", "fps", "graph")
        for (i in keys.indices) v.show[i] = Prefs.bool(this, "pc_" + keys[i], true)
        v.scale = Prefs.int(this, "pc_size", 130).coerceIn(60, 200) / 100f
        v.bgAlpha = (Prefs.int(this, "pc_alpha", 55).coerceIn(0, 100) * 255 / 100)
        v.fpsColored = Prefs.bool(this, "pc_fpscolor", false)
        v.renderer = rendererLabel
    }

    private fun pcFlags(): Int {
        var f = fUi
        if (!hudDrag()) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return f
    }

    /** Posisi bawaan: kiri bawah, tidak menempel ke tepi kiri. Ukuran otomatis mengecil bila tidak muat. */
    private fun layoutPcHud() {
        val v = pcHud ?: return
        val lp = pcLp ?: return
        val sw = screenW(); val sh = screenH()
        var x = Prefs.int(this, "pc_x", -1)
        var y = Prefs.int(this, "pc_y", -1)
        if (x < 0) x = dp(28)
        v.maxW = sw - x - dp(8)
        v.computeSize()
        if (y < 0) y = sh - v.hPx - dp(12)
        x = x.coerceIn(0, maxOf(0, sw - v.wPx)); y = y.coerceIn(0, maxOf(0, sh - v.hPx))
        lp.width = v.wPx; lp.height = v.hPx; lp.x = x; lp.y = y
        try { wm.updateViewLayout(v, lp) } catch (_: Throwable) {}
    }

    private fun showPcHud() {
        if (pcHud != null) { refreshPcHud(); return }
        val v = PcHudView(this)
        applyPcPrefs(v)
        if (!v.anyShown()) { toast("Pilih minimal satu item HUD PC di tab HUD"); st["hudpc"] = false; panel?.sync(); return }
        v.maxW = screenW() - dp(36)
        v.computeSize()
        val lp = baseLp(v.wPx, v.hPx, pcFlags())
        try { wm.addView(v, lp); pcHud = v; pcLp = lp } catch (_: Throwable) { st["hudpc"] = false; return }
        attachPcDrag(v)
        layoutPcHud()
    }

    private fun hidePcHud() {
        pcHud?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        pcHud = null; pcLp = null
    }

    private fun refreshPcHud() {
        val v = pcHud ?: return
        applyPcPrefs(v)
        if (!v.anyShown()) { hidePcHud(); st["hudpc"] = false; panel?.sync(); return }
        pcLp?.let { it.flags = pcFlags() }
        layoutPcHud(); v.invalidate()
    }

    private fun attachPcDrag(v: View) {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        v.setOnTouchListener { _, e ->
            val lp = pcLp
            if (lp != null && hudDrag()) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; ownTouch() }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = (ox + (e.rawX - sx).toInt()).coerceIn(0, maxOf(0, screenW() - lp.width))
                        lp.y = (oy + (e.rawY - sy).toInt()).coerceIn(0, maxOf(0, screenH() - lp.height))
                        try { wm.updateViewLayout(v, lp) } catch (_: Throwable) {}
                    }
                    MotionEvent.ACTION_UP -> { Prefs.setInt(this, "pc_x", lp.x); Prefs.setInt(this, "pc_y", lp.y); layoutPcHud() }
                }
            }
            true
        }
    }

    // ───────── panel (liquid glass, dari kanan, maksimal sampai tengah layar) ─────────
    private fun screenW(): Int =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.width()
        else DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }.widthPixels

    private fun screenH(): Int =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.height()
        else DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }.heightPixels

    private fun panelWidth(): Int { val sw = screenW(); return minOf(sw / 2, dp(380)).coerceAtLeast(minOf(dp(330), sw)) }
    private fun bodyHeight(): Int = minOf(dp(236), (screenH() * 0.9f).toInt() - dp(100)).coerceAtLeast(dp(140))

    private fun readBrightness(): Int = try { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) } catch (_: Throwable) { 128 }
    private fun audio() = getSystemService(AUDIO_SERVICE) as AudioManager

    private fun comboStatusText(): String =
        "Injector: " + ComboClient.state + if (!ComboClient.ready()) "  (butuh Shizuku)" else ""

    private fun openPanel() {
        if (panelOpen) return
        val w = panelWidth()
        val existing = panel
        val ui: PanelUi = if (existing != null && existing.widthPx == w) existing
        else PanelUi(this, this, w, bodyHeight(), blur).also { panel = it }
        ui.sync(); ui.setMode(curMode); ui.setPicks(visualSel, pickStyle(), Prefs.int(this, "ccolor", 0))
        val a = audio()
        ui.setLevels(readBrightness(), a.getStreamVolume(AudioManager.STREAM_MUSIC), a.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        ui.setPc(Prefs.int(this, "pc_size", 130), Prefs.int(this, "pc_alpha", 55))
        ui.setCombo(Prefs.int(this, "c1_ms", 60), Prefs.int(this, "c2_ms", 80), comboStatusText())
        ui.setAuto(Prefs.int(this, "at_ms", 500))
        refreshGpuUi()
        val lp = WindowManager.LayoutParams(w, WindowManager.LayoutParams.WRAP_CONTENT, overlayType, fUi, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        lp.x = dp(8)
        if (blur) Glass.apply(lp, dp(24))
        ui.root.animate().cancel()
        ui.root.translationX = (w + dp(8)).toFloat()
        try { wm.addView(ui.root, lp) } catch (_: Throwable) { return }
        panelOpen = true
        ui.root.animate().translationX(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun closePanel(animate: Boolean = true) {
        val ui = panel ?: return
        if (!panelOpen) return
        panelOpen = false
        ui.root.animate().cancel()
        val finish = Runnable { try { wm.removeView(ui.root) } catch (_: Throwable) {} }
        if (animate) ui.root.animate().translationX((ui.root.width + dp(8)).toFloat()).setDuration(180).withEndAction(finish).start()
        else finish.run()
    }

    private fun reAddPanel() {
        if (!panelOpen) return
        closePanel(false)
        openPanel()
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        val wasOpen = panelOpen
        closePanel(false); panel = null
        layoutHud(); layoutPcHud()
        if (st["combo"] == true && game != null) showCombo()
        if (st["atap"] == true) { val was = atRunner.running; showAutoTap(); if (was) toast("Tap Otomatis berhenti karena layar diputar") }
        if (wasOpen) openPanel()
    }

    private fun pickStyle(): Int = if (st["cross"] == true) Prefs.int(this, "cstyle", 1) else 0

    private fun refreshGpuUi() {
        val ui = panel ?: return
        val h = Hw.info ?: return
        if (gpuIdx < 0 || gpuIdx >= h.gFreqs.size) gpuIdx = h.gFreqs.size - 1
        ui.setGpu(h.gFreqs, gpuIdx, Hw.gpuLockKhz, h.gCanLock || h.uid == 0)
    }

    private fun restyleAll() {
        panel?.setMode(curMode)
        knobView?.invalidate(); hudView?.invalidate()
        comboViews.forEach { it.invalidate() }
    }

    // ───────── crosshair & penjaga tepi ─────────
    private fun applyCross() {
        if (st["cross"] != true) {
            crossView?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
            crossView = null
            return
        }
        val size = dp(Prefs.int(this, "csize", 48))
        val view: CrosshairView = crossView ?: CrosshairView(this).also { cv ->
            val lp = WindowManager.LayoutParams(size, size, overlayType, fPass, PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.CENTER
            try { wm.addView(cv, lp); crossView = cv } catch (_: Throwable) {}
        }
        val lp = view.layoutParams as? WindowManager.LayoutParams
        if (lp != null && lp.width != size) { lp.width = size; lp.height = size; try { wm.updateViewLayout(view, lp) } catch (_: Throwable) {} }
        view.style = Prefs.int(this, "cstyle", 1).coerceIn(1, 4)
        view.color = Cross.colors[Prefs.int(this, "ccolor", 0).coerceIn(0, Cross.colors.size - 1)]
        view.invalidate()
    }

    private fun setGuard(on: Boolean) {
        for (v in guardViews) try { wm.removeView(v) } catch (_: Throwable) {}
        guardViews.clear()
        if (!on) return
        val t = dp(14)
        fun add(w: Int, h: Int, g: Int) {
            val v = View(this)
            v.setBackgroundColor(0x01000000)
            val lp = WindowManager.LayoutParams(w, h, overlayType, fUi, PixelFormat.TRANSLUCENT)
            lp.gravity = g
            try { wm.addView(v, lp); guardViews.add(v) } catch (_: Throwable) {}
        }
        val m = WindowManager.LayoutParams.MATCH_PARENT
        add(t, m, Gravity.START or Gravity.TOP); add(t, m, Gravity.END or Gravity.TOP)
        add(m, t, Gravity.TOP or Gravity.START); add(m, t, Gravity.BOTTOM or Gravity.START)
        bringKnobFront(); hudView?.let { hideHud(); showHud() }
        reAddPanel()
    }

    // ───────── Combo ─────────
    private fun comboKey(): String = game ?: "global"

    private fun showCombo() {
        hideCombo()
        if (st["combo"] != true || !Prefs.bool(this, "combo_ack", false)) return
        ComboClient.bind(this)
        val sw = screenW(); val sh = screenH()
        val s = ComboStore.load(this, comboKey(), sw, sh)
        for (kind in 1..2) {
            if (st[if (kind == 1) "c1" else "c2"] != true) continue
            val b = ComboButton(this, kind)
            val pos = if (kind == 1) s.b1 else s.b2
            val size = dp(60)
            val lp = baseLp(size, size, fUi)
            lp.x = pos[0]; lp.y = pos[1]
            b.setOnTouchListener { _, e -> comboTouch(kind, b, e); true }
            try {
                wm.addView(b, lp); comboViews.add(b)
                b.post {
                    val loc = IntArray(2)
                    b.getLocationOnScreen(loc)
                    val dx = pos[0] - loc[0]; val dy = pos[1] - loc[1]
                    if (dx != 0 || dy != 0) { lp.x += dx; lp.y += dy; try { wm.updateViewLayout(b, lp) } catch (_: Throwable) {} }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun hideCombo() {
        for (r in runners) r.stop()
        for (b in comboViews) try { wm.removeView(b) } catch (_: Throwable) {}
        comboViews.clear()
    }

    private fun comboTouch(kind: Int, b: ComboButton, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ownTouch()
                b.down = true; b.invalidate()
                b.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                startMacro(kind)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                b.down = false; b.invalidate()
                runners[kind - 1].stop()
            }
        }
    }

    private fun startMacro(kind: Int) {
        if (!ComboClient.ready()) { actions.toast("Combo butuh Shizuku aktif & diizinkan"); return }
        val s = ComboStore.load(this, comboKey(), screenW(), screenH())
        val pts = ArrayList<IntArray>()
        if (kind == 1) for (i in 0 until s.n1) pts.add(s.p1[i]) else for (i in 0 until s.n2) pts.add(s.p2[i])
        val gap = Prefs.int(this, if (kind == 1) "c1_ms" else "c2_ms", if (kind == 1) 60 else 80)
        runners[kind - 1].start(pts, kind == 2, gap, 22)
    }

    private fun startEdit() {
        if (editor != null) return
        if (!ComboClient.ready()) actions.toast("Combo butuh Shizuku agar bisa menekan tombol game")
        closePanel(false)
        hideCombo()
        val sw = screenW(); val sh = screenH()
        val s = ComboStore.load(this, comboKey(), sw, sh)
        val ed = ComboEditor(this, wm, s, Prefs.int(this, "c1_ms", 60), Prefs.int(this, "c2_ms", 80),
            { ns -> ComboStore.save(this, comboKey(), sw, sh, ns); editor = null; showCombo(); toast("Posisi combo disimpan") },
            { editor = null; showCombo() })
        editor = ed
        ed.show()
    }

    // ───────── rekam performa ─────────
    private fun startRecord() {
        synchronized(rFps) { rFps.clear(); rTemp.clear(); rWatt.clear() }
        rStart = System.currentTimeMillis(); rGame = game ?: ""; rLabel = gameLabel.ifEmpty { "Sesi uji" }
        toast("Rekam performa dimulai")
    }

    private fun stopRecord() {
        val d = synchronized(rFps) {
            val r = Triple(rFps.toFloatArray(), rTemp.toFloatArray(), rWatt.toFloatArray())
            rFps.clear(); rTemp.clear(); rWatt.clear(); r
        }
        if (d.first.size < 10) { actions.toast("Rekaman terlalu singkat, tidak disimpan"); return }
        val rec = Rec(rStart.toString(), rGame, rLabel, rStart, d.first.size, Hw.info?.plat ?: "", Build.MODEL,
            Build.VERSION.SDK_INT, curMode, d.first, d.second, d.third)
        Records.save(this, rec)
        actions.toast("Rekaman tersimpan (" + d.first.size + " dtk). Lihat di tab Laporan")
    }

    // ───────── banner pemberitahuan di atas game (tembus sentuhan) ─────────
    /** level: 0 info (warna mode) · 1 peringatan (kuning) · 2 bahaya (merah) · 3 oke (hijau). */
    private fun showBanner(text: String, level: Int, icon: Int) {
        val col = when (level) { 1 -> Pal.WARN; 2 -> Pal.BAD; 3 -> Pal.OK; else -> Skin.a }
        val ic = if (icon >= 0) icon else when (level) { 1 -> Ic.WARN; 2 -> Ic.ALERT; 3 -> Ic.CHECK; else -> Ic.STAR }
        var v = bannerView
        if (v == null) {
            val box = LinearLayout(this)
            box.gravity = Gravity.CENTER_VERTICAL
            box.setPadding(dp(12), dp(8), dp(14), dp(8))
            val iv = IconView(this, ic, col)
            val tx = TextView(this)
            tx.setTextColor(Pal.TEXT); tx.textSize = 12f; tx.typeface = Typeface.DEFAULT_BOLD
            tx.maxWidth = (screenW() * 0.62f).toInt()
            box.addView(iv, LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) })
            box.addView(tx)
            val lp = baseLp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, fPass)
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.y = dp(58)
            try { wm.addView(box, lp) } catch (_: Throwable) { actions.toast(text); return }
            bannerView = box; bannerText = tx; bannerIcon = iv
            v = box
        }
        bannerText?.text = text
        bannerIcon?.setIcon(ic); bannerIcon?.tint = col
        v.background = rbox(0xF20B0A17.toInt(), dp(14).toFloat(), col, dp(2))
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(180).start()
        mainH.removeCallbacks(bannerHide)
        mainH.postDelayed(bannerHide, if (level == 2) 9000L else 5500L)
    }

    private fun hideBanner() {
        bannerView?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        bannerView = null; bannerText = null; bannerIcon = null
    }

    // ───────── persetujuan fitur Combo (sekali) ─────────
    private fun hideConsent() {
        consent?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        consent = null
    }

    private fun showComboConsent(onOk: () -> Unit, onCancel: () -> Unit, auto: Boolean = false) {
        if (consent != null) return
        closePanel(false)
        val root = FrameLayout(this)
        root.setBackgroundColor(0xAA000000.toInt())
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(20), dp(16), dp(20), dp(16))
        card.background = rbox(0xF50B0A17.toInt(), dp(20).toFloat(), alpha(Pal.WARN, 190), dp(2))
        val ct = tv(if (auto) "Peringatan fitur Tap Otomatis" else "Peringatan fitur Combo", 16f, Pal.WARN, true)
        ct.iconStart(Ic.WARN, 20, Pal.WARN, 8)
        card.addView(ct)
        card.addView(tv(if (auto) AUTO_CONSENT else "Combo adalah auto-tap (macro): menekan titik layar berulang saat tombol C1/C2 ditahan.\n\n" +
            "•  Di game online (battle royale, MOBA, dll.) macro bisa melanggar aturan dan membuat akun di-ban oleh anti-cheat. Pakai di game offline atau game yang membolehkan.\n" +
            "•  Game Space tidak menyembunyikan Combo dari deteksi.\n" +
            "•  Semua risiko menjadi tanggung jawab pengguna.", 11.5f, Pal.TEXT),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val row = LinearLayout(this)
        row.gravity = Gravity.END
        fun b(label: String, primary: Boolean, click: () -> Unit) {
            val t = tv(label, 12f, Pal.TEXT, true)
            t.gravity = Gravity.CENTER
            t.setPadding(dp(16), dp(9), dp(16), dp(9))
            t.background = if (primary) gbox(Pal.WARN, 0xFFD97706.toInt(), dp(12).toFloat()) else rbox(0x22FFFFFF, dp(12).toFloat(), 0x33FFFFFF, 1)
            if (primary) t.setTextColor(0xFF1A1200.toInt())
            t.setOnClickListener { click() }
            row.addView(t, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        }
        b("Batal", false) { hideConsent(); onCancel() }
        b("Saya mengerti", true) { hideConsent(); onOk() }
        card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        root.addView(card, FrameLayout.LayoutParams(minOf(dp(440), (screenW() * 0.8f).toInt()), -2, Gravity.CENTER))
        val lp = baseLp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        try { wm.addView(root, lp); consent = root } catch (_: Throwable) { toast("Izinkan Combo lewat menu Info > Combo") }
    }

    // ───────── pemantau baterai / pengisian ─────────
    private val chargeTask = object : Runnable {
        override fun run() {
            if (!running) return
            try { chargeStep() } catch (t: Throwable) { ActionLog.add("pengisian: " + t.javaClass.simpleName) }
            bgH.postDelayed(this, 10_000)
        }
    }

    private fun chargeStep() {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val raw = b.getIntExtra("level", -1)
        val scale = b.getIntExtra("scale", 100)
        if (raw < 0 || scale <= 0) return
        val pct = raw * 100 / scale
        val status = b.getIntExtra("status", -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val temp = b.getIntExtra("temperature", 0) / 10f
        if (game != null) { if (temp > sessMaxTemp) sessMaxTemp = temp; sessBatNow = pct }
        val cfg = ChargeCfg(Prefs.bool(this, "chg_unplug_on", true), Prefs.int(this, "chg_unplug_at", 90),
            Prefs.bool(this, "chg_hot_on", true), Prefs.int(this, "chg_hot_temp", 40).toFloat(), Prefs.bool(this, "chg_low_on", true))
        val code = ChargeLogic.evaluate(chargeMemo, pct, charging, temp, game != null, cfg, System.currentTimeMillis())
        if (code != ChargeLogic.NONE) chargeAlert(code, pct, temp, cfg)
    }

    private fun chargeAlert(code: Int, level: Int, temp: Float, cfg: ChargeCfg) {
        val t = f1(temp)
        val r = when (code) {
            ChargeLogic.UNPLUG -> Triple("Baterai $level%: saatnya cabut charger (target " + cfg.unplugAt + "%)", 1, Ic.PLUG)
            ChargeLogic.FULL -> Triple("Baterai penuh 100%: cabut charger supaya baterai awet", 1, Ic.BATTERY)
            ChargeLogic.HOT -> Triple("Mengisi sambil main, suhu $t°C. Cabut charger atau jeda main supaya baterai tidak cepat rusak", 1, Ic.FLAME)
            ChargeLogic.DANGER -> Triple("Suhu $t°C saat mengisi, terlalu panas! Cabut charger atau hentikan game sekarang", 2, Ic.ALERT)
            ChargeLogic.RISE -> Triple("Suhu naik cepat saat mengisi ($t°C). Pertimbangkan cabut charger", 1, Ic.THERMO)
            else -> Triple("Baterai $level%: colok charger atau pakai Mode Hemat", 1, Ic.BATTERY)
        }
        announce(r.first, r.second, r.third)
        try {
            val n = Notification.Builder(this, ALERT_CH).setContentTitle("Game Space").setContentText(r.first)
                .setStyle(Notification.BigTextStyle().bigText(r.first)).setSmallIcon(R.drawable.ic_fg)
                .setColor(Skin.a).setAutoCancel(true).build()
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(2, n)
        } catch (_: Throwable) {}
        if (Prefs.bool(this, "chg_vibrate", true)) vibrate(r.second == 2)
    }

    private fun vibrate(strong: Boolean) {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val pat = if (strong) longArrayOf(0, 160, 90, 160, 90, 260) else longArrayOf(0, 140, 90, 140)
            v.vibrate(VibrationEffect.createWaveform(pat, -1))
        } catch (_: Throwable) {}
    }

    // ───────── callback dari panel ─────────
    override fun isOn(id: String): Boolean = when {
        id.startsWith("hi_") -> Prefs.bool(this, "hud_" + id.substring(3), hudDefault(id.substring(3)))
        id == "hdrag" -> hudDrag()
        id == "srec" -> RecorderService.recording
        id.startsWith("pc_") -> Prefs.bool(this, id, id != "pc_fpscolor")
        else -> st[id] == true
    }

    override fun onClose() = closePanel()

    override fun onMode(m: Int) {
        curMode = m; Skin.mode = m
        thermalLimited = false
        restyleAll(); updateNotif()
        bgH.post {
            val cap = effCap()
            actions.applyMode(m, game, cap)
            lastCap = cap
            if (m >= 2 && Prefs.bool(this, "auto_boost", true)) actions.boost(game)
            mainH.post { refreshGpuUi(); panel?.sync() }
        }
    }

    override fun onToggle(id: String, on: Boolean) {
        if (id.startsWith("hi_")) { Prefs.setBool(this, "hud_" + id.substring(3), on); refreshHudConfig(); return }
        if (id == "hdrag") { Prefs.setBool(this, "hud_drag", on); refreshHudConfig(); refreshPcHud(); return }
        if (id.startsWith("pc_")) { Prefs.setBool(this, id, on); refreshPcHud(); return }
        st[id] = on
        when (id) {
            "hud" -> if (on) showHud() else hideHud()
            "hudpc" -> if (on) showPcHud() else hidePcHud()
            "rec" -> if (on) startRecord() else bgH.post { stopRecord() }
            "cross" -> {
                if (on && Prefs.int(this, "cstyle", 1) == 0) Prefs.setInt(this, "cstyle", 1)
                applyCross(); panel?.setPicks(visualSel, pickStyle(), Prefs.int(this, "ccolor", 0))
            }
            "esport" -> setEsport(on)
            "dnd" -> bgH.post { actions.setDnd(on) }
            "bright" -> bgH.post { actions.setBrightLock(on) }
            "rot" -> bgH.post { actions.setRotLock(on) }
            "guard" -> setGuard(on)
            "anim" -> bgH.post { actions.setAnim(on) }
            "adaptive" -> bgH.post { actions.setAdaptive(on) }
            "touch" -> bgH.post { actions.setTouch(on) }
            "limit" -> bgH.post {
                if (on) {
                    val n = actions.restrictBackground(game)
                    if (n >= 0) announce("$n app dibatasi di latar", 0, Ic.BLOCK)
                    else mainH.post { st["limit"] = false; panel?.sync() }
                } else {
                    val n = actions.restoreBackground()
                    announce("$n app latar dipulihkan", 3, Ic.CHECK)
                }
            }
            "srec" -> { closePanel(); if (on) RecorderService.requestStart(this) else RecorderService.requestStop(this) }
            "combo" -> {
                if (on && !Prefs.bool(this, "combo_ack", false)) {
                    st["combo"] = false; panel?.sync()
                    showComboConsent({
                        Prefs.setBool(this, "combo_ack", true); Prefs.setBool(this, "combo_on", true)
                        st["combo"] = true; showCombo(); panel?.sync()
                    }, { })
                } else { Prefs.setBool(this, "combo_on", on); if (on) showCombo() else hideCombo() }
            }
            "atap" -> {
                if (on && !Prefs.bool(this, "atap_ack", false)) {
                    st["atap"] = false; panel?.sync()
                    showComboConsent({
                        Prefs.setBool(this, "atap_ack", true)
                        st["atap"] = true; showAutoTap(); closePanel(); panel?.sync()
                    }, { }, true)
                } else if (on) { st["atap"] = true; showAutoTap(); closePanel() }
                else { st["atap"] = false; hideAutoTap() }
            }
            "c1" -> { Prefs.setBool(this, "c1_on", on); showCombo() }
            "c2" -> { Prefs.setBool(this, "c2_on", on); showCombo() }
            "cpulock" -> bgH.post {
                val ok = if (on) Hw.lockCpu(this) else { Hw.unlockCpu(this); true }
                mainH.post {
                    if (on && !ok) { st["cpulock"] = false; toast("CPU tidak bisa dikunci: sistem menolak (butuh Shizuku mode root)") }
                    panel?.sync()
                }
            }
            "gpulock" -> if (on) lockGpuSel() else bgH.post { Hw.unlockGpu(this); mainH.post { refreshGpuUi() } }
        }
    }

    private fun lockGpuSel() {
        val h = Hw.info ?: return
        if (gpuIdx < 0 || gpuIdx >= h.gFreqs.size) { st["gpulock"] = false; panel?.sync(); toast("Daftar frekuensi GPU tidak terbaca"); return }
        val khz = h.gFreqs[gpuIdx]
        bgH.post {
            val ok = Hw.lockGpu(this, khz)
            mainH.post {
                if (!ok) { st["gpulock"] = false; toast("GPU tidak bisa dikunci: sistem menolak (butuh Shizuku mode root)") }
                panel?.sync(); refreshGpuUi()
            }
        }
    }

    /** E-Sport: paket mode kompetitif sekali tekan. */
    private fun setEsport(on: Boolean) {
        for (id in listOf("dnd", "bright", "rot", "guard", "anim", "touch")) if ((st[id] == true) != on) onToggle(id, on)
        onMode(if (on) 3 else modeFor(game))
        panel?.sync()
    }

    override fun onAction(id: String) {
        when (id) {
            "shot" -> { closePanel(); mainH.postDelayed({ bgH.post { actions.screenshot() } }, 450) }
            "boost" -> bgH.post { val mb = actions.boost(game); actions.toast("RAM dibersihkan ± $mb MB") }
            "clean" -> bgH.post { val mb = actions.cleanCache(); if (mb >= 0) actions.toast("Cache dibersihkan ± $mb MB") }
            "cedit" -> startEdit()
            "atrun" -> { if (st["atap"] != true) toast("Nyalakan Tap Otomatis dulu") else { closePanel(); mainH.postDelayed({ atToggleRun() }, 300) } }
            "pcreset" -> { Prefs.setInt(this, "pc_x", -1); Prefs.setInt(this, "pc_y", -1); layoutPcHud() }
            "hudreset" -> {
                knobX = dp(110); knobY = dp(6)
                Prefs.setInt(this, "knob_x", knobX); Prefs.setInt(this, "knob_y", knobY)
                layoutHud()
            }
        }
    }

    override fun onPick(id: String, v: Int) {
        when (id) {
            "visual" -> { visualSel = v; bgH.post { actions.setVisual(v) } }
            "cstyle" -> {
                if (v == 0) st["cross"] = false else { st["cross"] = true; Prefs.setInt(this, "cstyle", v) }
                applyCross(); panel?.sync()
            }
            "ccolor" -> { Prefs.setInt(this, "ccolor", v); applyCross() }
        }
    }

    override fun onSeek(id: String, v: Int) {
        when (id) {
            "gpu" -> { gpuIdx = v; if (st["gpulock"] == true) lockGpuSel() }
            "bright" -> bgH.post { actions.setBrightness(v) }
            "vol" -> try { audio().setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) } catch (_: Throwable) {}
            "csize" -> { Prefs.setInt(this, "csize", v); applyCross() }
            "pcsize" -> { Prefs.setInt(this, "pc_size", v); refreshPcHud() }
            "pcalpha" -> { Prefs.setInt(this, "pc_alpha", v); refreshPcHud() }
            "c1ms" -> Prefs.setInt(this, "c1_ms", v)
            "c2ms" -> Prefs.setInt(this, "c2_ms", v)
            "atms" -> Prefs.setInt(this, "at_ms", v)
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        running = false
        RecorderService.listeners.remove(recListener)
        NetWatch.stop()
        if (game != null) finishSession(false)
        bgH.removeCallbacksAndMessages(null)
        closePanel(false); hideHud(); hidePcHud(); hideKnob(); hideWatcher(); hideCombo(); hideAutoTap(); setGuard(false); hideBanner(); hideConsent()
        editor?.close(); editor = null
        st["cross"] = false; applyCross()
        val g = game
        val wasRec = st["rec"] == true
        val saved = if (idle) dimSaved else null
        Thread {
            if (saved != null) actions.undim(saved)
            actions.restoreBackground()
            if (wasRec) stopRecord()
            if (Prefs.bool(this, "auto_restore", true)) actions.restoreAll(g)
            ComboClient.unbind()
        }.start()
        thread.quitSafely()
        super.onDestroy()
    }
}

private const val AUTO_CONSENT = "Tap Otomatis menekan satu titik layar berulang-ulang sampai kamu menghentikannya.\n\n" +
    "•  Di game online (battle royale, MOBA, dll.) auto-tap bisa melanggar aturan dan membuat akun di-ban oleh anti-cheat. Pakai untuk mode cerita, farming offline, atau game yang membolehkan.\n" +
    "•  Game Space tidak menyembunyikan Tap Otomatis dari deteksi.\n" +
    "•  Berhenti sendiri saat keluar game atau layar mati, dan bisa dihentikan kapan saja lewat tombol TAP.\n" +
    "•  Semua risiko menjadi tanggung jawab pengguna."
