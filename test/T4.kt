import com.alya.gamespace.*
import java.io.File

var f4 = 0
fun c4(name: String, ok: Boolean, extra: String = "") {
    println((if (ok) "PASS  " else "FAIL  ") + name + (if (!ok && extra.isNotEmpty()) "   -> $extra" else ""))
    if (!ok) f4++
}

fun main() {
    // ── file pembaruan (version.json)
    val ok = Updater.parse("""{"versionCode":6,"versionName":"1.6","apkUrl":"https://x/y.apk","sha256":"ABCDEF","notes":"- a\n- b"}""")
    c4("parse: versionCode/versionName/apkUrl/notes", ok.error == null && ok.info!!.code == 6 && ok.info!!.name == "1.6" &&
        ok.info!!.apkUrl == "https://x/y.apk" && ok.info!!.notes.contains("- a") && ok.info!!.notes.contains("- b"))
    c4("parse: sha256 dinormalkan huruf kecil", ok.info!!.sha256 == "abcdef")
    val mini = Updater.parse("""{"versionCode":9}""")
    c4("parse: field opsional boleh kosong", mini.info != null && mini.info!!.name == "?" && mini.info!!.apkUrl == "" && mini.info!!.sha256 == "")
    c4("parse: tanpa versionCode = error jelas", Updater.parse("""{"versionName":"x"}""").let { it.info == null && it.error != null })
    c4("parse: bukan JSON = error, tidak crash", Updater.parse("<html>404</html>").let { it.info == null && it.error != null })
    c4("fetch menolak URL non-https", Updater.fetch("http://contoh.com/v.json").let { it.info == null && it.error!!.contains("https") })

    // ── SHA-256
    val tmp = File.createTempFile("gsa", ".bin"); tmp.writeBytes("abc".toByteArray())
    c4("SHA-256 'abc' sesuai standar", Updater.sha256(tmp) == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Updater.sha256(tmp))
    val big = File.createTempFile("gsb", ".big"); big.writeBytes(ByteArray(200_000) { (it % 251).toByte() })
    c4("SHA-256 file besar konsisten & beda dari file kecil", Updater.sha256(big) == Updater.sha256(big) && Updater.sha256(big) != Updater.sha256(tmp) && Updater.sha256(big).length == 64)
    c4("hex byte", Updater.hex(byteArrayOf(0, 15, -1)) == "000fff")
    tmp.delete(); big.delete()

    // ── mode per game
    c4("mode per game: Auto (-1) ikut global", Logic.pickMode(-1, 2) == 2 && Logic.pickMode(-1, 0) == 0)
    c4("mode per game: pilihan game menang atas global", Logic.pickMode(3, 0) == 3 && Logic.pickMode(0, 3) == 0)
    c4("mode per game: nilai rusak diabaikan, global dijepit 0..3", Logic.pickMode(9, 1) == 1 && Logic.pickMode(-1, 9) == 3)

    // ── ringkasan sesi
    c4("durasi: detik / menit / jam", Logic.fmtDuration(45) == "45 dtk" && Logic.fmtDuration(125) == "2 mnt" && Logic.fmtDuration(3725) == "1 j 2 mnt")
    val t = Logic.sessionText(2520, 58.4f, 39.24f, 78, 72)
    c4("ringkasan lengkap", t == "42 mnt • rata-rata 58 FPS • suhu maks 39.2°C • baterai 78→72% (-6)", t)
    c4("ringkasan: yang tak diketahui dilewati", Logic.sessionText(90, 0f, 0f, -1, -1) == "1 mnt")
    c4("ringkasan: baterai naik (mengisi) bertanda +", Logic.sessionText(600, 0f, 0f, 50, 53).endsWith("baterai 50→53% (+3)"))

    // ── aturan "lewati versi"
    c4("pembaruan: lebih baru -> ditawarkan", Logic.updateAvailable(6, 5, 0))
    c4("pembaruan: sama/lebih lama -> tidak", !Logic.updateAvailable(5, 5, 0) && !Logic.updateAvailable(4, 5, 0))
    c4("pembaruan: versi yang dilewati tidak ditawarkan lagi, versi lebih baru lagi ditawarkan", !Logic.updateAvailable(6, 5, 6) && Logic.updateAvailable(7, 5, 6))

    println(if (f4 == 0) "\nSEMUA TES v1.5 LULUS" else "\n$f4 TES v1.5 GAGAL")
    System.exit(if (f4 == 0) 0 else 1)
}
