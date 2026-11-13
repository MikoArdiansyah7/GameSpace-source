import com.alya.gamespace.*
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

var f7 = 0
fun c7(name: String, ok: Boolean, extra: String = "") {
    println((if (ok) "PASS  " else "FAIL  ") + name + (if (!ok && extra.isNotEmpty()) "   -> $extra" else ""))
    if (!ok) f7++
}
fun at(tz: TimeZone, y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
    val c = Calendar.getInstance(tz); c.clear(); c.set(y, mo, d, h, mi, 0); return c.timeInMillis
}
fun hourOf(tz: TimeZone, t: Long): String { val c = Calendar.getInstance(tz); c.timeInMillis = t; return c.get(Calendar.HOUR_OF_DAY).toString() + ":" + c.get(Calendar.MINUTE) }

fun main() {
    val jkt = TimeZone.getTimeZone("Asia/Jakarta")
    val ny = TimeZone.getTimeZone("America/New_York")

    // ── hari
    val now = at(jkt, 2026, Calendar.NOVEMBER, 11, 15, 30)
    val s = PlayLogic.dayStarts(now, 7, jkt)
    c7("7 awal hari, terakhir = hari ini 00:00", s.size == 7 && s[6] == at(jkt, 2026, Calendar.NOVEMBER, 11, 0, 0))
    c7("selisih tiap hari tepat 24 jam (tanpa DST)", (1 until 7).all { s[it] - s[it - 1] == PlayLogic.DAY_MS })
    c7("nama hari: Kam Jum Sab Min Sen Sel Rab", (0 until 7).joinToString(" ") { PlayLogic.dayName(s[it], jkt) } == "Kam Jum Sab Min Sen Sel Rab",
        (0 until 7).joinToString(" ") { PlayLogic.dayName(s[it], jkt) })
    val nyNow = at(ny, 2026, Calendar.NOVEMBER, 3, 10, 0)
    val sn = PlayLogic.dayStarts(nyNow, 7, ny)
    c7("DST: semua awal hari tetap pukul 00:00 lokal", sn.all { hourOf(ny, it) == "0:0" }, sn.joinToString { hourOf(ny, it) })
    c7("DST: ada hari 25 jam saat jam mundur, tidak ada yang rusak", (1 until 7).any { sn[it] - sn[it - 1] == 25 * 3_600_000L } && (1 until 7).all { sn[it] > sn[it - 1] })

    // ── format durasi
    c7("fmt: 0, <1m, 1m, 45m", PlayLogic.fmt(0) == "0m" && PlayLogic.fmt(30_000) == "<1m" && PlayLogic.fmt(60_000) == "1m" && PlayLogic.fmt(45 * 60_000L) == "45m")
    c7("fmt: 1j, 2j 14m, 25j", PlayLogic.fmt(60 * 60_000L) == "1j" && PlayLogic.fmt(134 * 60_000L) == "2j 14m" && PlayLogic.fmt(25 * 60 * 60_000L) == "25j")
    c7("fmt: negatif = 0m", PlayLogic.fmt(-5) == "0m")

    // ── pembangunan data hari
    val games = setOf("a", "b", "c", "big")
    val d = PlayLogic.buildDay(s[6], mapOf("a" to 3 * 3_600_000L, "b" to 3_600_000L, "x" to 5 * 3_600_000L, "c" to -5L, "big" to 30 * 3_600_000L), games)
    c7("hanya game, buang <=0, batasi 24 jam", d.perPkg.keys == setOf("a", "b", "big") && d.perPkg["big"] == PlayLogic.DAY_MS && d.total == 4 * 3_600_000L + PlayLogic.DAY_MS)
    c7("hari kosong: total 0", PlayLogic.buildDay(s[0], emptyMap(), games).total == 0L)

    // ── game teratas
    val h = 3_600_000L
    val wk = listOf(
        PlayLogic.buildDay(s[0], mapOf("a" to h, "b" to 2 * h), games),
        PlayLogic.buildDay(s[1], mapOf("a" to 2 * h, "c" to h), games),
        PlayLogic.buildDay(s[2], mapOf("b" to h), games))
    val t = PlayLogic.top(wk, 5)
    c7("top: jumlahkan lintas hari, urut turun (a dan b sama 3 jam -> menurut nama)", t.map { it.first } == listOf("a", "b", "c") && t[0].second == 3 * h && t[1].second == 3 * h && t[2].second == h,
        t.joinToString { it.first + "=" + it.second })
    c7("top: waktu sama diurutkan menurut nama (a sebelum b)", PlayLogic.top(wk, 5).let { it[0].first == "a" && it[1].first == "b" })
    c7("top: dibatasi n", PlayLogic.top(wk, 2).size == 2 && PlayLogic.top(emptyList(), 3).isEmpty())
    c7("total 7 hari", PlayLogic.weekTotal(wk) == 7 * h)

    // ── batang grafik
    val fr = PlayLogic.barFractions(listOf(PlayLogic.buildDay(s[0], mapOf("a" to 4 * h), games), PlayLogic.buildDay(s[1], mapOf("a" to 2 * h), games),
        PlayLogic.buildDay(s[2], emptyMap(), games), PlayLogic.buildDay(s[3], mapOf("a" to 60_000L), games)))
    c7("batang: terbanyak = 1, setengah = 0.5, kosong = 0, sangat kecil minimal 0.06", fr[0] == 1f && fr[1] == 0.5f && fr[2] == 0f && fr[3] == 0.06f, fr.joinToString())
    c7("batang: semua kosong = nol semua", PlayLogic.barFractions(listOf(PlayLogic.buildDay(s[0], emptyMap(), games))).all { it == 0f })

    // ── pengingat istirahat
    val m60 = 60 * 60_000L
    c7("pengingat: 59m 59d belum, 60m satu kali, 125m dua kali", PlayLogic.breaksDue(m60 - 1000, m60) == 0 && PlayLogic.breaksDue(m60, m60) == 1 && PlayLogic.breaksDue(125 * 60_000L, m60) == 2)
    c7("pengingat: selang 0 / waktu negatif tidak pernah", PlayLogic.breaksDue(m60, 0) == 0 && PlayLogic.breaksDue(-5, m60) == 0 && PlayLogic.breaksDue(0, m60) == 0)
    val m40 = 40 * 60_000L
    c7("jeda 2 menit: hitungan dilanjutkan", PlayLogic.resume(m40, 2 * 60_000L) == m40)
    c7("jeda 4m59d dilanjutkan, 5m mulai dari nol", PlayLogic.resume(m40, 299_000L) == m40 && PlayLogic.resume(m40, 300_000L) == 0L)
    c7("tidak ada waktu sebelumnya / jeda negatif = nol", PlayLogic.resume(0, 1000L) == 0L && PlayLogic.resume(m40, -1L) == 0L && PlayLogic.resume(m40, Long.MAX_VALUE) == 0L)
    c7("teks pengingat memuat durasi", PlayLogic.breakText(m60).contains("1j") && PlayLogic.breakText(75 * 60_000L).contains("1j 15m"))

    // ── daftar rekaman
    c7("hanya berkas Game Space", RecLibLogic.isOurs("GameSpace_20261110_233500.mp4") && RecLibLogic.isOurs("GameSpace_20261110_233500_part2.MP4") &&
        !RecLibLogic.isOurs("video.mp4") && !RecLibLogic.isOurs("GameSpace_1.mkv") && !RecLibLogic.isOurs("xGameSpace_1.mp4"))
    c7("ukuran: B, KB, MB, GB", RecLibLogic.sizeText(512, Locale.US) == "512 B" && RecLibLogic.sizeText(2048, Locale.US) == "2 KB" &&
        RecLibLogic.sizeText(5_767_168, Locale.US) == "5.5 MB" && RecLibLogic.sizeText(1_073_741_824L, Locale.US) == "1.00 GB",
        listOf(512L, 2048L, 5_767_168L, 1_073_741_824L).joinToString { RecLibLogic.sizeText(it, Locale.US) })
    c7("durasi: kosong, 59 dtk, 1 mnt, lebih dari 1 jam", RecLibLogic.durText(0) == "--:--" && RecLibLogic.durText(59_400) == "00:59" &&
        RecLibLogic.durText(60_000) == "01:00" && RecLibLogic.durText(3_725_000) == "1:02:05")
    c7("judul dari nama file", RecLibLogic.title("GameSpace_20261110_233500.mp4") == "10 Nov 2026  23:35" &&
        RecLibLogic.title("GameSpace_20260305_080102_part12.mp4") == "5 Mar 2026  08:01  •  bagian 12",
        RecLibLogic.title("GameSpace_20261110_233500.mp4") + " | " + RecLibLogic.title("GameSpace_20260305_080102_part12.mp4"))
    c7("judul: nama tak dikenal dikembalikan apa adanya", RecLibLogic.title("lain.mp4") == "lain.mp4")

    println(if (f7 == 0) "\nSEMUA TES v1.8 LULUS" else "\n$f7 TES v1.8 GAGAL")
    System.exit(if (f7 == 0) 0 else 1)
}
