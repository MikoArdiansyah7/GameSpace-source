import com.alya.gamespace.*

var f9 = 0
fun c9(name: String, ok: Boolean, extra: String = "") {
    println((if (ok) "PASS  " else "FAIL  ") + name + (if (!ok && extra.isNotEmpty()) "   -> $extra" else ""))
    if (!ok) f9++
}

fun main() {
    val t0 = 1_000_000L
    // batas tunggu audio sebelum muxer mulai
    c9("belum ada format video: tidak pernah kedaluwarsa", !RecStopLogic.audioWaitExpired(0L, t0 + 99_999, false, false))
    c9("format video baru muncul: masih menunggu audio", !RecStopLogic.audioWaitExpired(t0, t0 + 1_000, false, false))
    c9("tepat 1,5 dtk tanpa audio: kedaluwarsa (mulai tanpa suara)", RecStopLogic.audioWaitExpired(t0, t0 + RecStopLogic.AUDIO_WAIT_MS, false, false))
    c9("lewat 1,5 dtk tanpa audio: kedaluwarsa", RecStopLogic.audioWaitExpired(t0, t0 + 5_000, false, false))
    c9("1,499 dtk: belum", !RecStopLogic.audioWaitExpired(t0, t0 + RecStopLogic.AUDIO_WAIT_MS - 1, false, false))
    c9("audio sudah siap: tidak perlu menunggu", !RecStopLogic.audioWaitExpired(t0, t0 + 9_000, true, false))
    c9("muxer sudah mulai: tidak diulang", !RecStopLogic.audioWaitExpired(t0, t0 + 9_000, false, true))
    c9("batas tunggu 1,5 detik", RecStopLogic.AUDIO_WAIT_MS == 1500L)

    // teks alasan berhenti
    c9("alasan: dihentikan dari panel setelah 2 dtk", RecStopLogic.describeStop("dihentikan dari panel", 2) == "dihentikan dari panel setelah 2 dtk", RecStopLogic.describeStop("dihentikan dari panel", 2))
    c9("alasan: Android menghentikan proyeksi", RecStopLogic.describeStop("Android menghentikan proyeksi layar", 0) == "Android menghentikan proyeksi layar setelah 0 dtk")
    c9("alasan: durasi negatif dianggap 0", RecStopLogic.describeStop("x", -5) == "x setelah 0 dtk")
    c9("alasan: durasi panjang", RecStopLogic.describeStop("error: boom", 3725) == "error: boom setelah 3725 dtk")

    println(if (f9 == 0) "\nSEMUA TES v1.9.2 LULUS" else "\n$f9 TES v1.9.2 GAGAL")
    System.exit(if (f9 == 0) 0 else 1)
}
