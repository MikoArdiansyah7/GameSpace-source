package com.alya.gamespace

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Semua pengaturan perekam layar, dibaca dari Prefs (diatur di dashboard) lalu disesuaikan dengan kemampuan HP. */
class RecSettings(
    val w: Int, val h: Int, val dpi: Int, val videoBps: Int, val codec: Int, val fps: Int,
    val audio: Boolean, val useInternal: Boolean, val useMic: Boolean,
    val audioBps: Int, val sampleRate: Int, val channels: Int, val splitBytes: Long, val notes: List<String>
) {
    val mime: String get() = RecLogic.CODEC_MIME[codec]

    companion object {
        fun load(ctx: Context, noAudio: Boolean): RecSettings {
            val notes = ArrayList<String>()
            val real = Recorder.realSize(ctx)
            val size = RecLogic.size(Prefs.int(ctx, "rec_res", 0), Prefs.int(ctx, "rec_w", 1280), Prefs.int(ctx, "rec_h", 720), real[0], real[1])
            val wanted = Prefs.int(ctx, "rec_codec", 0).coerceIn(0, 2)
            val supported = BooleanArray(3) { Recorder.encoderSupported(RecLogic.CODEC_MIME[it]) }
            val codec = RecLogic.pickCodec(wanted, supported)
            if (codec != wanted) notes.add(RecLogic.CODEC_NAMES[wanted] + " tidak didukung HP ini, otomatis memakai H.264")
            val fit = Recorder.fitVideo(RecLogic.CODEC_MIME[codec], size[0], size[1], Prefs.int(ctx, "rec_fps", 30),
                Prefs.int(ctx, "rec_vbr", 6).coerceIn(1, 100) * 1_000_000)
            if (fit[0] != size[0] || fit[1] != size[1]) notes.add("Ukuran disesuaikan encoder: " + fit[0] + "x" + fit[1])
            val permOk = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val wantAudio = Prefs.bool(ctx, "rec_audio", true)
            val plan = RecLogic.audioPlan(Prefs.int(ctx, "rec_src", 0), Build.VERSION.SDK_INT)
            if (wantAudio && plan[2] == 1) notes.add("Audio internal butuh Android 10+, memakai mic")
            if (wantAudio && !permOk) notes.add("Izin mikrofon belum diberikan, merekam tanpa audio")
            val audio = wantAudio && permOk && !noAudio
            return RecSettings(fit[0], fit[1], real[2], fit[2], codec, fit[3], audio, plan[0] == 1, plan[1] == 1,
                Prefs.int(ctx, "rec_abr", 128) * 1000, Prefs.int(ctx, "rec_sr", 48000), if (Prefs.int(ctx, "rec_ch", 2) == 1) 1 else 2,
                if (Prefs.bool(ctx, "rec_split", false)) RecLogic.splitBytes(Prefs.int(ctx, "rec_split_mb", 1000)) else 0L, notes)
        }
    }
}

object Recorder {
    /** [lebar, tinggi, dpi] layar saat ini. */
    fun realSize(ctx: Context): IntArray {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            intArrayOf(b.width(), b.height(), ctx.resources.displayMetrics.densityDpi)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            intArrayOf(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
    }

    fun findEncoder(mime: String): MediaCodecInfo? = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { ci -> ci.isEncoder && ci.supportedTypes.any { it.equals(mime, true) } }
    } catch (_: Throwable) { null }

    fun encoderSupported(mime: String) = findEncoder(mime) != null

    /** Sesuaikan ukuran/fps/bitrate dengan kemampuan encoder. Hasil: [w, h, bitrate, fps]. */
    fun fitVideo(mime: String, w0: Int, h0: Int, fps0: Int, bps0: Int): IntArray {
        var w = w0; var h = h0; var bps = bps0; var fps = fps0
        try {
            val caps = findEncoder(mime)?.getCapabilitiesForType(mime)?.videoCapabilities
            if (caps != null) {
                var tries = 0
                while (tries++ < 14) {
                    w -= w % caps.widthAlignment; h -= h % caps.heightAlignment
                    if (caps.isSizeSupported(w, h)) break
                    w = (w * 0.9f).toInt(); h = (h * 0.9f).toInt()
                }
                bps = bps.coerceIn(caps.bitrateRange.lower, caps.bitrateRange.upper)
                try { fps = Math.min(fps, caps.getSupportedFrameRatesFor(w, h).upper.toInt()) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        return intArrayOf(w and 1.inv(), h and 1.inv(), bps, Math.max(1, fps))
    }
}

// ───────── penyimpanan ─────────
class RecFile(val pfd: ParcelFileDescriptor, val name: String, val finish: () -> Unit)

object RecStorage {
    /** Folder pilihan pengguna (SAF) bila ada; kalau tidak Movies/GameSpace lewat MediaStore (Android 10+), cadangan: folder app. */
    fun create(ctx: Context, stamp: String, part: Int): RecFile? {
        val name = RecLogic.fileName(stamp, part)
        val dir = Prefs.str(ctx, "rec_dir")
        try {
            if (dir.isNotEmpty()) {
                val tree = Uri.parse(dir)
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val doc = DocumentsContract.createDocument(ctx.contentResolver, parent, "video/mp4", name) ?: return null
                val pfd = ctx.contentResolver.openFileDescriptor(doc, "rw") ?: return null
                return RecFile(pfd, name) { try { pfd.close() } catch (_: Throwable) {} }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues()
                v.put(MediaStore.Video.Media.DISPLAY_NAME, name)
                v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GameSpace")
                v.put(MediaStore.Video.Media.IS_PENDING, 1)
                val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: return null
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw") ?: return null
                return RecFile(pfd, name) {
                    try { pfd.close() } catch (_: Throwable) {}
                    val u = ContentValues(); u.put(MediaStore.Video.Media.IS_PENDING, 0)
                    try { ctx.contentResolver.update(uri, u, null, null) } catch (_: Throwable) {}
                }
            }
            val d = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
            d.mkdirs()
            val pfd = ParcelFileDescriptor.open(File(d, name), ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
            return RecFile(pfd, name) { try { pfd.close() } catch (_: Throwable) {} }
        } catch (_: Throwable) { return null }
    }

    fun label(ctx: Context): String {
        val dir = Prefs.str(ctx, "rec_dir")
        if (dir.isEmpty()) return if (Build.VERSION.SDK_INT >= 29) "Movies/GameSpace (muncul di galeri)" else "Android/data/com.alya.gamespace/files/Movies"
        return try {
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(dir))
            val parts = id.split(":", limit = 2)
            (if (parts[0] == "primary") "Penyimpanan internal" else parts[0]) + "/" + (parts.getOrNull(1) ?: "")
        } catch (_: Throwable) { dir }
    }
}

// ───────── mesin perekam ─────────
class RecEngine(private val ctx: Context, private val proj: MediaProjection, private val s: RecSettings, private val onError: (String) -> Unit) {
    @Volatile var running = false
    @Volatile var bytes = 0L
    @Volatile var files = 0
    @Volatile var audioActive = false
    private var vc: MediaCodec? = null
    private var ac: MediaCodec? = null
    private var vd: VirtualDisplay? = null
    private var recI: AudioRecord? = null
    private var recM: AudioRecord? = null
    private var vFormat: MediaFormat? = null
    private var aFormat: MediaFormat? = null
    private var muxer: MediaMuxer? = null
    private var cur: RecFile? = null
    private var writer: SplitWriter? = null
    private var vThread: Thread? = null
    private var aThread: Thread? = null
    private var began = false
    @Volatile private var stopDeadline = Long.MAX_VALUE
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun nowUs() = System.nanoTime() / 1000L

    private val sink = object : MuxSink {
        private var vTrack = -1
        private var aTrack = -1
        override fun open(part: Int) {
            val f = RecStorage.create(ctx, stamp, part) ?: throw IllegalStateException("Tidak bisa membuat file di lokasi penyimpanan")
            cur = f
            val m = MediaMuxer(f.pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            vTrack = m.addTrack(vFormat!!)
            aTrack = if (audioActive && aFormat != null) m.addTrack(aFormat!!) else -1
            m.start()
            muxer = m
            files++
        }
        override fun write(video: Boolean, data: ByteBuffer, offset: Int, size: Int, ptsUs: Long, flags: Int) {
            val m = muxer ?: return
            val track = if (video) vTrack else aTrack
            if (track < 0) return
            val bi = MediaCodec.BufferInfo()
            bi.set(offset, size, ptsUs, flags)
            data.position(offset); data.limit(offset + size)
            m.writeSampleData(track, data, bi)
            bytes += size
        }
        override fun close() {
            try { muxer?.stop() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
            muxer = null
            cur?.finish()
            cur = null
        }
    }

    private fun requestKey() {
        try { val b = Bundle(); b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0); vc?.setParameters(b) } catch (_: Throwable) {}
    }

    private fun configureVideo(): MediaCodec {
        fun build(extras: Boolean): MediaFormat {
            val f = MediaFormat.createVideoFormat(s.mime, s.w, s.h)
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            f.setInteger(MediaFormat.KEY_BIT_RATE, s.videoBps)
            f.setInteger(MediaFormat.KEY_FRAME_RATE, s.fps)
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (extras) {
                f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)   // bit rate RATA-RATA
                f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / s.fps)
                if (Build.VERSION.SDK_INT >= 29) f.setInteger(MediaFormat.KEY_MAX_FPS_TO_ENCODER, s.fps)
            }
            return f
        }
        try {
            val c = MediaCodec.createEncoderByType(s.mime)
            c.configure(build(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            return c
        } catch (_: Throwable) {
            val c = MediaCodec.createEncoderByType(s.mime)
            c.configure(build(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            return c
        }
    }

    private fun setupAudio(): String? {
        try {
            val mask = if (s.channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
            val af = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(s.sampleRate).setChannelMask(mask).build()
            val minBuf = AudioRecord.getMinBufferSize(s.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = Math.max(minBuf, 4096 * s.channels) * 2
            if (s.useInternal && Build.VERSION.SDK_INT >= 29) {
                val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
                recI = AudioRecord.Builder().setAudioFormat(af).setBufferSizeInBytes(bufSize).setAudioPlaybackCaptureConfig(cfg).build()
            }
            if (s.useMic) {
                recM = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(af).setBufferSizeInBytes(bufSize).build()
            }
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, s.sampleRate, s.channels)
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            f.setInteger(MediaFormat.KEY_BIT_RATE, s.audioBps)
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            val a = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            a.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            a.start()
            ac = a
            recI?.startRecording(); recM?.startRecording()
            audioActive = true
            return null
        } catch (t: Throwable) {
            try { recI?.release() } catch (_: Throwable) {}
            try { recM?.release() } catch (_: Throwable) {}
            recI = null; recM = null; ac = null; audioActive = false
            return "Audio tidak bisa direkam (" + t.javaClass.simpleName + "), video tetap direkam tanpa suara"
        }
    }

    /** Mulai merekam. Mengembalikan catatan audio bila audio terpaksa dimatikan. */
    fun start(): String? {
        val v = configureVideo()
        val surface = v.createInputSurface()
        v.start()
        vc = v
        var note: String? = null
        if (s.audio && (s.useInternal || s.useMic)) note = setupAudio()
        writer = SplitWriter(sink, s.splitBytes, s.videoBps.toLong()) { requestKey() }
        running = true
        vd = proj.createVirtualDisplay("GameSpaceRec", s.w, s.h, s.dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
        vThread = Thread { videoLoop() }.also { it.name = "gs-rec-video"; it.start() }
        if (audioActive) aThread = Thread { audioLoop() }.also { it.name = "gs-rec-audio"; it.start() }
        return note
    }

    @Synchronized private fun formatsReady() {
        if (began || vFormat == null || (audioActive && aFormat == null)) return
        began = true
        try { writer?.begin() } catch (t: Throwable) { running = false; onError(t.message ?: t.javaClass.simpleName) }
    }

    private fun videoLoop() {
        val c = vc ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val idx = c.dequeueOutputBuffer(info, 10_000)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { vFormat = c.outputFormat; formatsReady() }
                else if (idx >= 0) {
                    val buf = c.getOutputBuffer(idx)
                    if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0)
                        writer?.video(buf, info.offset, info.size, info.presentationTimeUs, info.flags)
                    c.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
                if (!running && System.currentTimeMillis() > stopDeadline) break
            }
        } catch (t: Throwable) { if (running) { running = false; onError(t.message ?: t.javaClass.simpleName) } }
    }

    private fun feed(enc: MediaCodec, data: ByteArray, size: Int, ptsUs: Long, flags: Int) {
        val idx = enc.dequeueInputBuffer(10_000)
        if (idx >= 0) {
            val b = enc.getInputBuffer(idx) ?: return
            b.clear(); b.put(data, 0, size)
            enc.queueInputBuffer(idx, 0, size, ptsUs, flags)
        }
    }

    /** Ambil semua keluaran encoder audio yang siap; true bila sudah EOS. */
    private fun drainAudio(enc: MediaCodec, info: MediaCodec.BufferInfo): Boolean {
        while (true) {
            val idx = enc.dequeueOutputBuffer(info, 0)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { aFormat = enc.outputFormat; formatsReady() }
            else if (idx >= 0) {
                val b = enc.getOutputBuffer(idx)
                if (b != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0)
                    writer?.audio(b, info.offset, info.size, info.presentationTimeUs, info.flags)
                enc.releaseOutputBuffer(idx, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true
            } else return false
        }
    }

    private fun audioLoop() {
        val enc = ac ?: return
        val ch = s.channels
        val samples = 1024 * ch
        val bi = ShortArray(samples); val bm = ShortArray(samples)
        val bytes = ByteArray(samples * 2)
        val info = MediaCodec.BufferInfo()
        val a = recI; val m = recM
        try {
            // sepotong hening di awal supaya format audio muncul cepat dan muxer bisa mulai walau belum ada suara
            feed(enc, ByteArray(samples * 2), samples * 2, nowUs(), 0)
            drainAudio(enc, info)
            while (running) {
                var nI = 0; var nM = 0
                if (a != null) nI = a.read(bi, 0, samples, AudioRecord.READ_BLOCKING)
                if (m != null) nM = m.read(bm, 0, samples, AudioRecord.READ_BLOCKING)
                if (nI < 0 || nM < 0) break
                val n = Math.max(nI, nM)
                if (n <= 0) continue
                for (i in 0 until n) {
                    var x = 0
                    if (a != null && i < nI) x += bi[i]
                    if (m != null && i < nM) x += bm[i]
                    val y = x.coerceIn(-32768, 32767)
                    bytes[2 * i] = (y and 0xFF).toByte()
                    bytes[2 * i + 1] = (y shr 8).toByte()
                }
                val durUs = (n / ch) * 1_000_000L / s.sampleRate
                feed(enc, bytes, n * 2, nowUs() - durUs, 0)
                drainAudio(enc, info)
            }
            feed(enc, ByteArray(0), 0, nowUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            val end = System.currentTimeMillis() + 800
            while (System.currentTimeMillis() < end) { if (drainAudio(enc, info)) break; Thread.sleep(10) }
        } catch (t: Throwable) { if (running) { running = false; onError(t.message ?: t.javaClass.simpleName) } }
    }

    fun stop() {
        val wasRunning = running
        running = false
        stopDeadline = System.currentTimeMillis() + 3000
        if (wasRunning) try { vc?.signalEndOfInputStream() } catch (_: Throwable) {}
        try { vThread?.join(3500) } catch (_: Throwable) {}
        try { aThread?.join(3500) } catch (_: Throwable) {}
        try { vd?.release() } catch (_: Throwable) {}
        try { recI?.stop() } catch (_: Throwable) {}
        try { recI?.release() } catch (_: Throwable) {}
        try { recM?.stop() } catch (_: Throwable) {}
        try { recM?.release() } catch (_: Throwable) {}
        try { vc?.stop() } catch (_: Throwable) {}
        try { vc?.release() } catch (_: Throwable) {}
        try { ac?.stop() } catch (_: Throwable) {}
        try { ac?.release() } catch (_: Throwable) {}
        try { writer?.finish() } catch (_: Throwable) {}
        try { proj.stop() } catch (_: Throwable) {}
    }
}

// ───────── layanan & izin ─────────
class RecorderService : Service() {
    companion object {
        @Volatile var recording = false
        @Volatile var startedAt = 0L
        @Volatile var files = 0
        @Volatile var bytes = 0L
        @Volatile var lastError = ""
        val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
        fun notifyState() { for (l in listeners) try { l() } catch (_: Throwable) {} }
        const val ACTION_STOP = "com.alya.gamespace.REC_STOP"
        private const val CH = "alya_rec"
        private const val NID = 7

        fun requestStart(ctx: Context) {
            ctx.startActivity(Intent(ctx, ProjectionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        fun requestStop(ctx: Context) {
            ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_STOP))
        }
        fun clock(sec: Long): String = String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)
    }

    private var engine: RecEngine? = null
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val e = engine ?: return
            if (!recording) return
            files = e.files; bytes = e.bytes
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NID, notif())
            h.postDelayed(this, 1000)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(CH, "Perekam Layar", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notif(): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val el = if (recording) (System.currentTimeMillis() - startedAt) / 1000 else 0L
        val text = if (recording) "Merekam " + clock(el) + "  •  " + files + " file  •  " + (bytes / 1_048_576L) + " MB" else "Menyiapkan perekam..."
        return Notification.Builder(this, CH).setContentTitle("Perekam Layar Game Space").setContentText(text)
            .setSmallIcon(R.drawable.ic_fg).setOngoing(true).setColor(Skin.a)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_fg), "Berhenti", stop).build()).build()
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == ACTION_STOP) { stopRec(); stopSelf(); return START_NOT_STICKY }
        val data = i?.getParcelableExtra<Intent>("data")
        if (data == null || recording) { if (!recording) stopSelf(); return START_NOT_STICKY }
        begin(i.getIntExtra("code", 0), data, i.getBooleanExtra("noAudio", false))
        return START_NOT_STICKY
    }

    private fun toast(m: String) = h.post { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }

    private fun begin(code: Int, data: Intent, noAudio: Boolean) {
        var started: RecEngine? = null
        var fg = false
        try {
            val st = RecSettings.load(this, noAudio)
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (st.audio && st.useMic && Build.VERSION.SDK_INT >= 30) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (Build.VERSION.SDK_INT >= 29) startForeground(NID, notif(), type) else startForeground(NID, notif())
            fg = true
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("Izin rekam layar tidak valid")
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { h.post { stopRec(); stopSelf() } }
            }, h)
            val e = RecEngine(this, p, st) { msg -> h.post { fail(msg) } }
            started = e
            val audioNote = e.start()
            engine = e
            recording = true; startedAt = System.currentTimeMillis(); lastError = ""; files = 0; bytes = 0
            for (n in st.notes) toast(n)
            if (audioNote != null) toast(audioNote)
            ActionLog.add("Rekam layar: " + st.w + "x" + st.h + " " + st.fps + "fps " + (st.videoBps / 1_000_000) + "Mbps " + RecLogic.CODEC_NAMES[st.codec] +
                " audio=" + e.audioActive + " split=" + (st.splitBytes / 1_048_576L) + "MB")
            notifyState()
            h.postDelayed(tick, 1000)
        } catch (t: Throwable) {
            // Sistem mewajibkan startForeground() terpanggil setelah startForegroundService(); bila belum, aplikasi akan di-crash
            if (!fg) try {
                if (Build.VERSION.SDK_INT >= 29) startForeground(NID, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(NID, notif())
            } catch (_: Throwable) {}
            // lepaskan encoder / proyeksi yang sudah sempat menyala
            try { started?.stop() } catch (_: Throwable) {}
            fail(t.javaClass.simpleName + (t.message?.let { ": $it" } ?: ""))
        }
    }

    private fun fail(msg: String) {
        lastError = msg
        ActionLog.add("Rekam layar gagal: $msg")
        toast("Perekaman gagal: $msg")
        stopRec()
        stopSelf()
    }

    private fun stopRec() {
        val e = engine
        engine = null
        if (e != null) {
            e.stop()
            toast("Rekaman tersimpan: " + e.files + " file (" + (e.bytes / 1_048_576L) + " MB) di " + RecStorage.label(this))
        }
        recording = false
        h.removeCallbacks(tick)
        notifyState()
    }

    override fun onDestroy() { stopRec(); super.onDestroy() }
}

/** Layar transparan untuk meminta izin rekam layar (dan mikrofon), lalu menyalakan RecorderService. */
class ProjectionActivity : Activity() {
    private val reqProj = 41
    private val reqAudio = 42
    private var noAudio = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (RecorderService.recording) { RecorderService.requestStop(this); finish(); return }
        if (Prefs.bool(this, "rec_audio", true) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), reqAudio)
        else askProjection()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == reqAudio) {
            noAudio = res.isEmpty() || res[0] != PackageManager.PERMISSION_GRANTED
            if (noAudio) Toast.makeText(this, "Izin mikrofon ditolak, merekam tanpa audio", Toast.LENGTH_LONG).show()
            askProjection()
        }
    }

    @Suppress("DEPRECATION")
    private fun askProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try { startActivityForResult(mpm.createScreenCaptureIntent(), reqProj) }
        catch (_: Throwable) { Toast.makeText(this, "Perekaman layar tidak tersedia di perangkat ini", Toast.LENGTH_LONG).show(); finish() }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == reqProj) {
            if (res == RESULT_OK && data != null) {
                startForegroundService(Intent(this, RecorderService::class.java).putExtra("code", res).putExtra("data", data).putExtra("noAudio", noAudio))
            } else {
                Toast.makeText(this, "Izin perekaman layar ditolak", Toast.LENGTH_SHORT).show()
                RecorderService.notifyState()
            }
            finish()
        }
    }
}
