import com.alya.gamespace.*

var failures2 = 0
fun check2(name: String, ok: Boolean, extra: String = "") {
    println((if (ok) "PASS  " else "FAIL  ") + name + (if (!ok && extra.isNotEmpty()) "   -> $extra" else ""))
    if (!ok) failures2++
}

fun main() {
    // ── warna FPS: 1-29 merah, 30-59 kuning, 60-73 hijau, 74+ biru
    val red = 0xFFFF4D5E.toInt(); val yel = 0xFFFFC53D.toInt(); val grn = 0xFF35E08A.toInt(); val blu = 0xFF4DA8FF.toInt()
    check2("fps 1 & 29 = merah", Logic.fpsColor(1f) == red && Logic.fpsColor(29.9f) == red)
    check2("fps 30 & 59 = kuning", Logic.fpsColor(30f) == yel && Logic.fpsColor(59.9f) == yel)
    check2("fps 60 & 73 = hijau", Logic.fpsColor(60f) == grn && Logic.fpsColor(73.9f) == grn)
    check2("fps 74, 90, 120, 144 = biru", Logic.fpsColor(74f) == blu && Logic.fpsColor(90f) == blu && Logic.fpsColor(144f) == blu)
    check2("fps 0 = abu-abu (tidak diketahui)", Logic.fpsColor(0f) != red && Logic.fpsColor(0f) != blu)

    // ── batas FPS per mode mengikuti layar (jangan asal 120)
    check2("Seimbang: layar 120Hz -> 120, 90Hz -> 90, 60Hz -> 60, 144Hz -> 120",
        Logic.modeCap(1, 120) == 120 && Logic.modeCap(1, 90) == 90 && Logic.modeCap(1, 60) == 60 && Logic.modeCap(1, 144) == 120)
    check2("Performa/Turbo: ikut layar asli (90, 120, 144)", Logic.modeCap(2, 90) == 90 && Logic.modeCap(3, 144) == 144 && Logic.modeCap(2, 120) == 120)
    check2("Hemat: maks 60 (layar 60Hz -> 60)", Logic.modeCap(0, 120) == 60 && Logic.modeCap(0, 60) == 60)
    check2("batas efektif: panas/idle -> 60, tidak pernah menaikkan di atas batas mode",
        Logic.effectiveCap(120, true, false) == 60 && Logic.effectiveCap(120, false, true) == 60 &&
        Logic.effectiveCap(120, false, false) == 120 && Logic.effectiveCap(60, true, true) == 60 && Logic.effectiveCap(90, true, false) == 60)

    // ── histeresis suhu
    // Seimbang: pemicu 38°C, lepas saat <= 35°C
    var lim = false
    val seq = floatArrayOf(36f, 37.9f, 38.0f, 37.5f, 36f, 35.5f, 35.1f, 35.0f, 36f, 38.1f)
    val exp = booleanArrayOf(false, false, true, true, true, true, true, false, false, true)
    var okSeq = true; val got = StringBuilder()
    for (i in seq.indices) { lim = Logic.thermalNext(lim, seq[i], Logic.trigger(1)); got.append(if (lim) "1" else "0"); if (lim != exp[i]) okSeq = false }
    check2("Seimbang: turun di 38°C, baru naik lagi setelah <=35°C (anti plin-plan)", okSeq, got.toString())
    // Performa: pemicu 40°C, lepas saat <= 37°C
    lim = false
    val s2 = floatArrayOf(39.9f, 40f, 39f, 38f, 37.5f, 37f, 38f)
    val e2 = booleanArrayOf(false, true, true, true, true, false, false)
    okSeq = true; got.setLength(0)
    for (i in s2.indices) { lim = Logic.thermalNext(lim, s2[i], Logic.trigger(2)); got.append(if (lim) "1" else "0"); if (lim != e2[i]) okSeq = false }
    check2("Performa: turun di 40°C, naik lagi di <=37°C", okSeq, got.toString())
    check2("Hemat & Turbo tidak punya batas suhu", Logic.trigger(0) > 100f && Logic.trigger(3) > 100f)

    // ── renderer + fallback
    check2("Vulkan didukung -> Vulkan", Logic.resolveRenderer(36, true, 3).first == 3 && Logic.resolveRenderer(36, true, 3).second == null)
    val v1 = Logic.resolveRenderer(36, false, 3)
    check2("Vulkan tak didukung (Android 16) -> SkiaGL + pemberitahuan", v1.first == 2 && v1.second != null && v1.second!!.contains("SkiaGL"))
    val v2 = Logic.resolveRenderer(27, false, 3)
    check2("Vulkan tak didukung (Android 8.1) -> OpenGL + pemberitahuan", v2.first == 1 && v2.second != null && v2.second!!.contains("OpenGL"))
    check2("OpenGL di Android 10+ -> SkiaGL (+catatan); di Android 9 tetap OpenGL",
        Logic.resolveRenderer(36, true, 1).first == 2 && Logic.resolveRenderer(36, true, 1).second != null &&
        Logic.resolveRenderer(28, true, 1).first == 1 && Logic.resolveRenderer(28, true, 1).second == null)
    check2("Bawaan & SkiaGL tidak berubah", Logic.resolveRenderer(36, false, 0) == Pair(0, null) && Logic.resolveRenderer(36, false, 2) == Pair(2, null))
    check2("nama downscale: 90..30, 100 = tanpa", Logic.scaleName(30) == "DOWNSCALE_30" && Logic.scaleName(45) == "DOWNSCALE_45" && Logic.scaleName(100) == null)

    // ── ComboRunner (fake injector)
    val log = java.util.Collections.synchronizedList(ArrayList<String>())
    val runner = ComboRunner { xs, ys, ms -> log.add(xs.indices.joinToString("+") { xs[it].toString() + "," + ys[it] } + "@" + ms); true }
    val pts = listOf(intArrayOf(10, 1), intArrayOf(20, 2), intArrayOf(30, 3))
    runner.start(pts, false, 15, 22)
    Thread.sleep(330)
    runner.stop()
    Thread.sleep(60)
    val n1 = log.size
    Thread.sleep(150)
    check2("bertahap: urutan A,B,C,A,B,C... berulang", n1 >= 9 && (0 until n1).all { log[it].startsWith(listOf("10,1", "20,2", "30,3")[it % 3]) }, log.take(10).toString())
    check2("bertahap: berhenti seketika setelah dilepas", log.size == n1, "sebelum=$n1 sesudah=" + log.size)
    check2("bertahap: setiap tap satu titik, tahan 22 ms", log.all { !it.contains("+") && it.endsWith("@22") })
    check2("kecepatan bisa diatur: jeda 15 ms -> >= 8 tap/330 ms", n1 >= 8)
    log.clear()
    runner.start(listOf(intArrayOf(1, 1), intArrayOf(2, 2), intArrayOf(3, 3), intArrayOf(4, 4)), true, 20, 22)
    Thread.sleep(200); runner.stop(); Thread.sleep(80)
    check2("bersama: semua 4 titik dalam SATU tap (A2+B2+C2+D2)", log.size >= 3 && log.all { it == "1,1+2,2+3,3+4,4@22" }, log.take(3).toString())
    // dua runner jalan bersamaan tidak saling ganggu
    val log2 = java.util.Collections.synchronizedList(ArrayList<String>())
    val ra = ComboRunner { xs, _, _ -> log2.add("A" + xs[0]); true }
    val rb = ComboRunner { xs, _, _ -> log2.add("B" + xs[0]); true }
    ra.start(listOf(intArrayOf(1, 0)), false, 10, 5); rb.start(listOf(intArrayOf(2, 0)), false, 10, 5)
    Thread.sleep(120); ra.stop()
    val cntB = log2.count { it.startsWith("B") }; Thread.sleep(80)
    check2("C1 dan C2 bisa ditahan bersamaan; stop satu tidak menghentikan yang lain", log2.any { it.startsWith("A") } && log2.count { it.startsWith("B") } > cntB)
    rb.stop()

    println(if (failures2 == 0) "\nSEMUA TES BARU LULUS" else "\n$failures2 TES BARU GAGAL")
    System.exit(if (failures2 == 0) 0 else 1)
}
