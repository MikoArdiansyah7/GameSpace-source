package com.alya.gamespace

import java.nio.ByteBuffer

/** Logika murni perekam layar (tanpa Android) supaya mudah diuji. */
object RecLogic {
    val RES_NAMES = listOf("Asli", "1440p", "1080p", "720p", "540p", "480p", "360p", "Kustom")
    private val RES_SHORT = intArrayOf(0, 1440, 1080, 720, 540, 480, 360)
    const val CUSTOM = 7
    const val MIN_DIM = 160
    const val MAX_DIM = 4096

    val BITRATES = intArrayOf(1, 2, 4, 6, 8, 12, 16, 20, 32, 40)          // Mbps
    val CODEC_NAMES = listOf("H.264", "MPEG_4_SP", "H.265 (HEVC)")
    val CODEC_MIME = arrayOf("video/avc", "video/mp4v-es", "video/hevc")
    val FPS_OPTS = intArrayOf(24, 30, 60, 90, 120)
    val SRC_NAMES = listOf("Internal", "Internal + Mic", "Mic")
    val ABR_OPTS = intArrayOf(64, 96, 128, 192, 256, 320)                  // kbps
    val SR_OPTS = intArrayOf(22050, 44100, 48000)
    const val SPLIT_MIN = 100
    const val SPLIT_MAX = 4000                                              // MB (< 4 GiB, aman untuk FAT32)

    fun even(v: Int) = v and 1.inv()
    fun clampDim(v: Int) = even(v.coerceIn(MIN_DIM, MAX_DIM))

    /**
     * Ukuran video untuk layar dispW x dispH. Preset memakai sisi pendek, rasio layar tetap, hasil genap,
     * dan tidak pernah lebih besar dari layar asli. Kustom: tiap sisi dijepit 160..4096 dan dibuat genap.
     */
    fun size(preset: Int, customW: Int, customH: Int, dispW: Int, dispH: Int): IntArray {
        if (preset == CUSTOM) return intArrayOf(clampDim(customW), clampDim(customH))
        if (preset <= 0 || preset >= RES_SHORT.size) return intArrayOf(even(dispW), even(dispH))
        val shortSide = minOf(dispW, dispH)
        val longSide = maxOf(dispW, dispH)
        val target = RES_SHORT[preset]
        if (target >= shortSide) return intArrayOf(even(dispW), even(dispH))
        val newLong = even(Math.round(longSide * target.toFloat() / shortSide))
        return if (dispW >= dispH) intArrayOf(newLong, even(target)) else intArrayOf(even(target), newLong)
    }

    fun mbPerMin(mbps: Int): Float = mbps * 60f / 8f

    fun bitrateNote(mbps: Int): String {
        val mb = Math.round(mbPerMin(mbps))
        val what = when {
            mbps <= 1 -> "Sangat hemat. Gambar buram saat banyak gerakan. Cocok untuk rekam panjang berkualitas rendah."
            mbps <= 2 -> "Hemat. Cukup untuk 480p-540p."
            mbps <= 4 -> "Seimbang. Layak untuk 720p."
            mbps <= 6 -> "Bagus. Cocok untuk 720p-1080p 30 fps."
            mbps <= 8 -> "Tajam. Untuk 1080p 30-60 fps."
            mbps <= 12 -> "Sangat tajam. Untuk 1080p 60 fps."
            mbps <= 16 -> "Tajam untuk game cepat 1080p 60 fps atau 1440p."
            mbps <= 20 -> "Untuk 1440p/2K 60 fps."
            mbps <= 32 -> "Untuk resolusi sangat tinggi (4K)."
            else -> "Kualitas maksimum, file sangat besar."
        }
        return "$mbps Mbps ≈ $mb MB per menit. $what"
    }

    fun codecNote(i: Int): String = when (i) {
        0 -> "H.264 (AVC): paling kompatibel, bisa diputar dan diedit di hampir semua HP, aplikasi, dan komputer. Ukuran file sedang. Disarankan."
        1 -> "MPEG_4_SP (MPEG-4 Part 2, Simple Profile): codec lama. Untuk kualitas yang sama file jauh lebih besar dibanding H.264. Hanya untuk perangkat lawas, dan tidak semua HP punya encoder-nya."
        else -> "H.265 (HEVC): paling efisien, kualitas sama dengan file sekitar 30-50% lebih kecil. Beban encoder lebih berat dan sebagian pemutar/editor lama belum mendukung. Butuh encoder HEVC di HP."
    }

    /** Codec yang diminta; kalau tidak didukung perangkat, otomatis H.264. */
    fun pickCodec(wanted: Int, supported: BooleanArray): Int =
        if (wanted in supported.indices && supported[wanted]) wanted else 0

    /** Hasil: [pakai audio internal (1/0), pakai mic (1/0), diganti paksa (1/0)]. Audio internal butuh Android 10+. */
    fun audioPlan(source: Int, sdk: Int): IntArray {
        val internalOk = sdk >= 29
        return when (source) {
            0 -> if (internalOk) intArrayOf(1, 0, 0) else intArrayOf(0, 1, 1)
            1 -> if (internalOk) intArrayOf(1, 1, 0) else intArrayOf(0, 1, 1)
            else -> intArrayOf(0, 1, 0)
        }
    }

    fun clampSplitMb(v: Int): Int = v.coerceIn(SPLIT_MIN, SPLIT_MAX)
    fun splitBytes(mb: Int): Long = clampSplitMb(mb) * 1024L * 1024L

    /** Berapa menit satu file pada bitrate video tertentu (perkiraan, video saja). */
    fun minutesPerFile(mb: Int, videoMbps: Int): Int = Math.max(1, Math.round(mb * 8f / (videoMbps * 60f)))

    /** Ambang ukuran untuk mulai pindah file: sisakan ruang beberapa detik data agar tidak melewati batas. */
    fun rotateAt(limitBytes: Long, videoBps: Long): Long {
        val safety = Math.max(2_000_000L, 3L * videoBps / 8L) + limitBytes / 100L
        return Math.max(limitBytes / 2L, limitBytes - safety)
    }

    fun fileName(stamp: String, part: Int): String = "GameSpace_" + stamp + (if (part > 1) "_part$part" else "") + ".mp4"
}

interface MuxSink {
    /** Buat file baru + muxer, tambahkan track, lalu start. */
    fun open(part: Int)
    fun write(video: Boolean, data: ByteBuffer, offset: Int, size: Int, ptsUs: Long, flags: Int)
    /** Hentikan muxer & selesaikan file. */
    fun close()
}

/**
 * Menulis sampel ke muxer. Bila batas ukuran file tercapai, minta keyframe lalu pindah ke file baru TEPAT di keyframe itu
 * sehingga perekaman tidak berhenti. Waktu tiap file dimulai dari 0; audio sebelum video pertama dibuang.
 */
class SplitWriter(private val sink: MuxSink, private val limitBytes: Long, private val videoBps: Long, private val requestKey: () -> Unit) {
    var part = 1
        private set
    private var base = -1L
    private var written = 0L
    private var rotateWanted = false
    private var open = false

    @Synchronized fun begin() { if (!open) { sink.open(part); open = true } }

    @Synchronized fun video(data: ByteBuffer, off: Int, size: Int, ptsUs: Long, flags: Int) {
        if (!open) return
        val key = (flags and 1) != 0
        if (rotateWanted && key) {
            sink.close(); part++; sink.open(part)
            base = -1L; written = 0L; rotateWanted = false
        }
        if (base < 0L) {
            if (!key) return                // setiap file harus diawali keyframe
            base = ptsUs
        }
        val t = ptsUs - base
        if (t < 0L) return
        sink.write(true, data, off, size, t, flags and 1)
        written += size
        if (limitBytes > 0L && !rotateWanted && written >= RecLogic.rotateAt(limitBytes, videoBps)) {
            rotateWanted = true
            requestKey()
        }
    }

    @Synchronized fun audio(data: ByteBuffer, off: Int, size: Int, ptsUs: Long, flags: Int) {
        if (!open || base < 0L) return
        val t = ptsUs - base
        if (t < 0L) return
        sink.write(false, data, off, size, t, 0)
        written += size
    }

    @Synchronized fun finish() { if (open) { sink.close(); open = false } }
}
