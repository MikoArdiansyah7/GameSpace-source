import com.alya.gamespace.*
import java.nio.ByteBuffer

var f6 = 0
fun c6(name: String, ok: Boolean, extra: String = "") {
    println((if (ok) "PASS  " else "FAIL  ") + name + (if (!ok && extra.isNotEmpty()) "   -> $extra" else ""))
    if (!ok) f6++
}
fun sz(a: IntArray) = a[0].toString() + "x" + a[1]

class FakeSink : MuxSink {
    class W(val part: Int, val video: Boolean, val t: Long, val flags: Int)
    val log = ArrayList<W>(); val events = ArrayList<String>(); var part = 0
    override fun open(part: Int) { this.part = part; events.add("open$part") }
    override fun write(video: Boolean, data: ByteBuffer, offset: Int, size: Int, ptsUs: Long, flags: Int) { log.add(W(part, video, ptsUs, flags)) }
    override fun close() { events.add("close$part") }
}

fun main() {
    // ── ukuran video
    c6("Asli: sama dengan layar", sz(RecLogic.size(0, 0, 0, 2400, 1080)) == "2400x1080")
    c6("1080p di layar 1080 sisi pendek: tidak diperbesar", sz(RecLogic.size(2, 0, 0, 2400, 1080)) == "2400x1080")
    c6("720p landscape 2400x1080 -> 1600x720 (rasio tetap)", sz(RecLogic.size(3, 0, 0, 2400, 1080)) == "1600x720", sz(RecLogic.size(3, 0, 0, 2400, 1080)))
    c6("360p landscape -> 800x360", sz(RecLogic.size(6, 0, 0, 2400, 1080)) == "800x360")
    c6("720p portrait 1080x2400 -> 720x1600", sz(RecLogic.size(3, 0, 0, 1080, 2400)) == "720x1600")
    c6("preset > layar asli (1440p di layar 1080) tidak membesarkan", sz(RecLogic.size(1, 0, 0, 2400, 1080)) == "2400x1080")
    c6("ukuran ganjil dibuat genap", sz(RecLogic.size(0, 0, 0, 1081, 2403)) == "1080x2402" && sz(RecLogic.size(4, 0, 0, 2403, 1081)).split("x").all { it.toInt() % 2 == 0 })
    c6("kustom: genap & dijepit 160..4096", sz(RecLogic.size(7, 1281, 721, 2400, 1080)) == "1280x720" && sz(RecLogic.size(7, 10, 10, 1, 1)) == "160x160" && sz(RecLogic.size(7, 9999, 9999, 1, 1)) == "4096x4096")

    // ── bit rate, codec, audio
    c6("6 Mbps = 45 MB/menit; 1 Mbps = 7,5", RecLogic.mbPerMin(6) == 45f && RecLogic.mbPerMin(1) == 7.5f)
    c6("pilihan bit rate memuat 1, 2, 4, 6", RecLogic.BITRATES.toList().containsAll(listOf(1, 2, 4, 6)))
    c6("penjelasan bit rate memuat MB/menit", RecLogic.bitrateNote(6).contains("45 MB per menit") && RecLogic.bitrateNote(40).contains("300"), RecLogic.bitrateNote(6))
    c6("tiga codec: H.264, MPEG_4_SP, H.265 (HEVC) + penjelasan", RecLogic.CODEC_NAMES == listOf("H.264", "MPEG_4_SP", "H.265 (HEVC)") && (0..2).all { RecLogic.codecNote(it).length > 40 })
    c6("codec tak didukung -> otomatis H.264", RecLogic.pickCodec(2, booleanArrayOf(true, false, false)) == 0 && RecLogic.pickCodec(1, booleanArrayOf(true, false, true)) == 0 && RecLogic.pickCodec(2, booleanArrayOf(true, true, true)) == 2)
    c6("audio Internal: Android 10+ internal, di bawahnya jatuh ke mic (ditandai)", RecLogic.audioPlan(0, 29).toList() == listOf(1, 0, 0) && RecLogic.audioPlan(0, 28).toList() == listOf(0, 1, 1))
    c6("audio Internal+Mic & Mic", RecLogic.audioPlan(1, 33).toList() == listOf(1, 1, 0) && RecLogic.audioPlan(1, 27).toList() == listOf(0, 1, 1) && RecLogic.audioPlan(2, 36).toList() == listOf(0, 1, 0))

    // ── batas file otomatis: min 100, maks 4000
    c6("batas ukuran dijepit 100..4000 MB (tidak boleh kurang/lebih)", RecLogic.clampSplitMb(50) == 100 && RecLogic.clampSplitMb(99999) == 4000 && RecLogic.clampSplitMb(2500) == 2500 && RecLogic.clampSplitMb(-5) == 100)
    c6("4000 MB < 4 GiB (aman FAT32)", RecLogic.splitBytes(4000) < 4L * 1024 * 1024 * 1024 && RecLogic.splitBytes(100) == 104_857_600L)
    c6("perkiraan menit per file: 1000 MB @ 8 Mbps = 17 mnt", RecLogic.minutesPerFile(1000, 8) == 17, RecLogic.minutesPerFile(1000, 8).toString())
    val th = RecLogic.rotateAt(RecLogic.splitBytes(1000), 8_000_000L)
    c6("ambang pindah file di bawah batas tapi dekat (>90%)", th < RecLogic.splitBytes(1000) && th > RecLogic.splitBytes(1000) * 9 / 10, th.toString())
    c6("ambang tidak pernah di bawah separuh batas", RecLogic.rotateAt(1000L, 800L) == 500L)
    c6("nama file & part", RecLogic.fileName("20261108_153012", 1) == "GameSpace_20261108_153012.mp4" && RecLogic.fileName("20261108_153012", 3) == "GameSpace_20261108_153012_part3.mp4")

    // ── penulis dengan pindah file di keyframe
    val sink = FakeSink(); var keyReq = 0
    val w = SplitWriter(sink, 1000L, 800L) { keyReq++ }
    val buf = ByteBuffer.allocate(256)
    w.begin()
    w.audio(buf, 0, 50, 100_000L, 0)                                      // sebelum video: dibuang
    for (i in 0 until 12) {
        val key = i % 3 == 0 || (i >= 7 && i % 3 == 1)                     // keyframe tiap ~3 frame; frame 7 juga key
        val t = 1_000_000L + i * 1_000_000L
        w.video(buf, 0, 200, t, if (key) 1 else 0)
        w.audio(buf, 0, 50, t + 500_000L, 0)
    }
    w.finish()
    val parts = sink.log.map { it.part }.distinct()
    c6("terjadi pindah file (>=2 part), perekaman tetap berlanjut", parts.size >= 2, parts.toString() + " " + sink.events)
    c6("open/close berpasangan & berurutan", sink.events.filter { it.startsWith("open") }.size == sink.events.filter { it.startsWith("close") }.size && sink.events.first() == "open1")
    var ok1 = true; var ok2 = true; var ok3 = true
    for (p in parts) {
        val ws = sink.log.filter { it.part == p }
        val firstV = ws.firstOrNull { it.video }
        if (firstV == null || firstV.t != 0L || (firstV.flags and 1) == 0) ok1 = false          // video pertama = keyframe, waktu 0
        if (ws.first().video.not()) ok2 = false                                                    // tidak ada audio sebelum video
        for (kind in listOf(true, false)) { val ts = ws.filter { it.video == kind }.map { it.t }; if (ts != ts.sorted() || ts.any { it < 0 }) ok3 = false }
    }
    c6("setiap file diawali keyframe pada waktu 0", ok1)
    c6("tidak ada audio sebelum video pertama di tiap file", ok2)
    c6("waktu tiap track naik & tidak negatif", ok3)
    c6("keyframe diminta saat ambang tercapai (sekali per perpindahan)", keyReq >= 1 && keyReq <= parts.size, keyReq.toString())
    val sink2 = FakeSink(); val w2 = SplitWriter(sink2, 0L, 800L) { }   // fitur mati: satu file saja
    w2.begin(); for (i in 0 until 50) w2.video(buf, 0, 200, 1_000_000L + i * 33_000L, if (i % 10 == 0) 1 else 0); w2.finish()
    c6("fitur dimatikan: hanya satu file, tidak pernah pindah", sink2.log.map { it.part }.distinct() == listOf(1) && sink2.events == listOf("open1", "close1"))

    // ── ping berwarna seperti FPS
    val g = 0xFF35E08A.toInt(); val y = 0xFFFFC53D.toInt(); val r = 0xFFFF4D5E.toInt()
    c6("ping <=50 hijau, 51-100 kuning, >100 merah", Logic.pingColor(1) == g && Logic.pingColor(50) == g && Logic.pingColor(51) == y && Logic.pingColor(100) == y && Logic.pingColor(101) == r && Logic.pingColor(999) == r)
    c6("ping tak diketahui abu-abu", Logic.pingColor(-1) != g && Logic.pingColor(-1) != y && Logic.pingColor(-1) != r)
    c6("teks ping: 1ms, 999ms, dibatasi 999ms, tak ada = --", Logic.pingText(1) == "1ms" && Logic.pingText(999) == "999ms" && Logic.pingText(4321) == "999ms" && Logic.pingText(-1) == "--")

    println(if (f6 == 0) "\nSEMUA TES v1.7 LULUS" else "\n$f6 TES v1.7 GAGAL")
    System.exit(if (f6 == 0) 0 else 1)
}
