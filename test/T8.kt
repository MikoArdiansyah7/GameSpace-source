import com.alya.gamespace.*

var f8 = 0
fun c8(name: String, ok: Boolean, extra: String = "") {
    println((if (ok) "PASS  " else "FAIL  ") + name + (if (!ok && extra.isNotEmpty()) "   -> $extra" else ""))
    if (!ok) f8++
}
fun net(wifi: Boolean = false, cell: Boolean = false, eth: Boolean = false, vpn: Boolean = false, inet: Boolean = true, valid: Boolean = true) = NetLogic.Net(wifi, cell, eth, vpn, inet, valid)

fun main() {
    // ── tap otomatis: jeda
    c8("batas jeda: minimal 30 ms, maksimal 60 dtk", AutoTapLogic.clampMs(1) == 30 && AutoTapLogic.clampMs(500) == 500 && AutoTapLogic.clampMs(999_999) == 60_000)
    c8("tanpa acak: jeda tetap", AutoTapLogic.nextDelay(500, 0, 0.3) == 500 && AutoTapLogic.nextDelay(500, 0, 0.99) == 500)
    c8("acak 20%: rnd 0 -> 400, rnd 0.5 -> 500, rnd 1 -> 600", AutoTapLogic.nextDelay(500, 20, 0.0) == 400 && AutoTapLogic.nextDelay(500, 20, 0.5) == 500 && AutoTapLogic.nextDelay(500, 20, 1.0) == 600,
        listOf(0.0, 0.5, 1.0).joinToString { AutoTapLogic.nextDelay(500, 20, it).toString() })
    c8("acak besar tidak pernah di bawah 30 ms", (0..100).all { AutoTapLogic.nextDelay(40, 90, it / 100.0) >= 30 })
    c8("acak di luar rentang rnd dibatasi", AutoTapLogic.nextDelay(500, 20, -5.0) == 400 && AutoTapLogic.nextDelay(500, 20, 9.0) == 600)
    c8("acak berlebih dipotong 90%", AutoTapLogic.nextDelay(1000, 500, 1.0) == 1900)

    // ── tap otomatis: berhenti sendiri
    val m = 60_000L
    c8("lanjut bila semua aman", AutoTapLogic.stopReason(5 * m, 0, 10, 0, true, true) == null)
    c8("layar mati menghentikan", AutoTapLogic.stopReason(0, 0, 0, 0, false, true) == "layar mati")
    c8("keluar game menghentikan", AutoTapLogic.stopReason(0, 0, 0, 0, true, false) == "keluar dari game")
    c8("batas waktu: 4m59d lanjut, 5m berhenti", AutoTapLogic.stopReason(5 * m - 1000, 5, 0, 0, true, true) == null && AutoTapLogic.stopReason(5 * m, 5, 0, 0, true, true) == "batas waktu 5 menit")
    c8("batas 0 = tanpa batas", AutoTapLogic.stopReason(10_000 * m, 0, 99_999, 0, true, true) == null)
    c8("batas jumlah tap", AutoTapLogic.stopReason(0, 0, 99, 100, true, true) == null && AutoTapLogic.stopReason(0, 0, 100, 100, true, true) == "batas 100 tap")
    c8("prioritas: layar mati menang atas yang lain", AutoTapLogic.stopReason(99 * m, 5, 999, 10, false, false) == "layar mati")

    // ── tap otomatis: teks & tumpang tindih
    c8("label selang: 500 ms, 1 dtk, 1,5 dtk, 2 dtk", AutoTapLogic.intervalLabel(500) == "500 ms" && AutoTapLogic.intervalLabel(1000) == "1 dtk" && AutoTapLogic.intervalLabel(1500) == "1,5 dtk" && AutoTapLogic.intervalLabel(2000) == "2 dtk")
    c8("laju: 10, 2, 3,3 tap/dtk dan 1 tap tiap 2 dtk", AutoTapLogic.rateText(100) == "10 tap/dtk" && AutoTapLogic.rateText(500) == "2 tap/dtk" && AutoTapLogic.rateText(300) == "3,3 tap/dtk" && AutoTapLogic.rateText(2000) == "1 tap tiap 2 dtk" && AutoTapLogic.rateText(1000) == "1 tap/dtk",
        listOf(100, 500, 300, 2000, 1000).joinToString { AutoTapLogic.rateText(it) })
    c8("tumpang tindih tombol kontrol dengan titik tap", AutoTapLogic.overlaps(100, 100, 80, 80, 60, 40, 0) && !AutoTapLogic.overlaps(300, 100, 80, 80, 60, 40, 0) && AutoTapLogic.overlaps(145, 100, 80, 80, 60, 40, 10) && !AutoTapLogic.overlaps(145, 100, 80, 80, 60, 40, 0))
    c8("pilihan bawaan masuk akal", AutoTapLogic.PRESETS.all { it in 30..60_000 } && AutoTapLogic.PRESETS.toList() == AutoTapLogic.PRESETS.sorted() && 500 in AutoTapLogic.PRESETS && 0 in AutoTapLogic.LIMITS && 0 in AutoTapLogic.JITTERS)

    // ── dex2oat: pagar nama paket
    c8("nama paket sah", Dex2oatLogic.validPkg("com.mobile.legends") && Dex2oatLogic.validPkg("com.tencent.ig") && Dex2oatLogic.validPkg("com.Game_1.v2"))
    c8("nama paket aneh ditolak (anti suntik perintah)", listOf("a", "com..x", "com.x;rm -rf /", "com.x y", " com.x.y", "com.x.y\n", "1com.x", "com.x$(id)", "").none { Dex2oatLogic.validPkg(it) })
    c8("perintah kompilasi", Dex2oatLogic.compileCmd("com.a.b", "speed") == "cmd package compile -m speed -f com.a.b 2>&1" && Dex2oatLogic.compileCmd("com.a;b.c", "speed") == null && Dex2oatLogic.compileCmd("com.a.b", "turbo; reboot") == null)
    c8("perintah reset & status", Dex2oatLogic.resetCmd("com.a.b") == "cmd package compile --reset com.a.b 2>&1" && Dex2oatLogic.statusCmd("com.a.b")!!.contains("Dexopt state") && Dex2oatLogic.statusCmd("x y") == null)

    // ── dex2oat: baca status
    val a13 = "Dexopt state:\n  [com.mobile.legends]\n    path: /data/app/~~Xy==/com.mobile.legends-ab==/base.apk\n      arm64: [status=speed-profile] [reason=bg-dexopt] [primary-abi]\n        [location is /data/app/x/oat/arm64/base.odex]\n"
    val a14 = "Dexopt state:\n  [com.a.b]\n    path: /data/app/~~Q/base.apk\n      arm64: [status=speed] [reason=cmdline] [primary-abi]\n        [location is /data/app/x/oat/arm64/base.odex]\n"
    val old = "Dexopt state:\n  [com.a.b]\n    path: /data/app/com.a.b-1/base.apk\n      arm: [status=verify] [reason=install]\n"
    val s13 = Dex2oatLogic.parseStatus(a13); val s14 = Dex2oatLogic.parseStatus(a14); val s8 = Dex2oatLogic.parseStatus(old)
    c8("baca status Android 13: speed-profile / bg-dexopt", s13 != null && s13.filter == "speed-profile" && s13.reason == "bg-dexopt")
    c8("baca status Android 14+: speed / cmdline", s14 != null && s14.filter == "speed" && s14.reason == "cmdline")
    c8("baca status lama: verify / install", s8 != null && s8.filter == "verify" && s8.reason == "install")
    c8("keluaran kosong/tak dikenal = null", Dex2oatLogic.parseStatus("") == null && Dex2oatLogic.parseStatus("Unable to find package: x") == null)
    c8("tingkat: speed 2, speed-profile 1, verify 0", Dex2oatLogic.level("speed") == 2 && Dex2oatLogic.level("everything") == 2 && Dex2oatLogic.level("speed-profile") == 1 && Dex2oatLogic.level("verify") == 0 && Dex2oatLogic.level("run-from-apk") == 0)
    c8("teks status", Dex2oatLogic.statusText(s14) == "Speed • manual" && Dex2oatLogic.statusText(s13) == "Speed Profile • otomatis sistem" && Dex2oatLogic.statusText(s8) == "Belum (verify) • saat dipasang" && Dex2oatLogic.statusText(null) == "belum diketahui",
        listOf(s14, s13, s8, null).joinToString(" | ") { Dex2oatLogic.statusText(it) })

    // ── dex2oat: hasil kompilasi & peringatan
    c8("hasil kompilasi: Success = ok; Failure/Error = gagal", Dex2oatLogic.ok("Success\n") && Dex2oatLogic.ok("  Success") && !Dex2oatLogic.ok("Failure") && !Dex2oatLogic.ok("Error: package not found") && !Dex2oatLogic.ok(""))
    c8("pesan gagal: baris pertama yang bukan Success", Dex2oatLogic.failText("\n  Failure: bad\nmore") == "Failure: bad" && Dex2oatLogic.failText("") == "tidak ada pesan dari sistem" && Dex2oatLogic.failText("x".repeat(500)).length == 120)
    c8("peringatan: sedang main, baterai rendah, aman", Dex2oatLogic.warning(80, true, true) != null && Dex2oatLogic.warning(15, false, false)!!.contains("15%") && Dex2oatLogic.warning(15, true, false) == null && Dex2oatLogic.warning(20, false, false) == null && Dex2oatLogic.warning(-1, false, false) == null)
    c8("ringkasan", Dex2oatLogic.summary(3, 0, false) == "Selesai. 3 berhasil" && Dex2oatLogic.summary(2, 1, true) == "Dihentikan. 2 berhasil, 1 gagal")
    c8("mode: tiga pilihan, speed ada", Dex2oatLogic.MODES.size == 3 && Dex2oatLogic.MODES.any { it.flag == "speed" } && Dex2oatLogic.MODES.map { it.flag }.toSet().size == 3)

    // ── jaringan
    c8("bawaan Wi-Fi -> WIFI", NetLogic.label(listOf(net(wifi = true), net(cell = true)), net(wifi = true)) == NetLogic.WIFI)
    c8("bawaan data -> DATA", NetLogic.label(listOf(net(cell = true)), net(cell = true)) == NetLogic.CELL)
    c8("bawaan tersembunyi (null) tapi Wi-Fi ada -> WIFI (bug OFF)", NetLogic.label(listOf(net(wifi = true)), null) == NetLogic.WIFI)
    c8("bawaan tersembunyi, hanya data -> DATA", NetLogic.label(listOf(net(cell = true)), null) == NetLogic.CELL)
    c8("tanpa bawaan: yang tervalidasi menang (Wi-Fi tanpa internet vs data valid)", NetLogic.label(listOf(net(wifi = true, valid = false), net(cell = true, valid = true)), null) == NetLogic.CELL)
    c8("tanpa bawaan: dua-duanya valid -> Wi-Fi", NetLogic.label(listOf(net(cell = true), net(wifi = true)), null) == NetLogic.WIFI)
    c8("VPN bawaan membawa transport Wi-Fi -> WIFI", NetLogic.label(listOf(net(wifi = true, vpn = true)), net(wifi = true, vpn = true)) == NetLogic.WIFI)
    c8("VPN bawaan tanpa transport, Wi-Fi terlihat -> WIFI", NetLogic.label(listOf(net(vpn = true), net(wifi = true)), net(vpn = true)) == NetLogic.WIFI)
    c8("hanya VPN yang terlihat -> VPN", NetLogic.label(listOf(net(vpn = true)), net(vpn = true)) == NetLogic.VPN && NetLogic.label(listOf(net(vpn = true)), null) == NetLogic.VPN)
    c8("kabel LAN -> LAN", NetLogic.label(listOf(net(eth = true)), net(eth = true)) == NetLogic.LAN)
    c8("bawaan tanpa internet diabaikan, pakai jaringan lain", NetLogic.label(listOf(net(wifi = true, inet = false), net(cell = true)), net(wifi = true, inet = false)) == NetLogic.CELL)
    c8("tidak ada jaringan = NONE", NetLogic.label(emptyList(), null) == NetLogic.NONE && NetLogic.label(listOf(net(wifi = true, inet = false)), null) == NetLogic.NONE)
    c8("keterangan: WIFI/DATA/LAN/VPN", NetLogic.caption(1, -1) == "WIFI" && NetLogic.caption(2, 30) == "DATA" && NetLogic.caption(3, -1) == "LAN" && NetLogic.caption(4, -1) == "VPN")
    c8("keterangan: ping berhasil berarti online (NET), OFF hanya bila ping juga gagal", NetLogic.caption(0, 25) == "NET" && NetLogic.caption(0, 0) == "NET" && NetLogic.caption(0, -1) == "OFF")

    println(if (f8 == 0) "\nSEMUA TES v1.9 LULUS" else "\n$f8 TES v1.9 GAGAL")
    System.exit(if (f8 == 0) 0 else 1)
}
