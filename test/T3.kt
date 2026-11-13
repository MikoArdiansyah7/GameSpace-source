import com.alya.gamespace.*

var f3 = 0
fun c3(name: String, ok: Boolean, extra: String = "") {
    println((if (ok) "PASS  " else "FAIL  ") + name + (if (!ok && extra.isNotEmpty()) "   -> $extra" else ""))
    if (!ok) f3++
}
const val MIN = 60_000L

fun main() {
    val cfg = ChargeCfg(true, 90, true, 40f, true)
    val ev = { m: ChargeMemo, lv: Int, ch: Boolean, t: Float, g: Boolean, now: Long -> ChargeLogic.evaluate(m, lv, ch, t, g, cfg, now) }

    // ── cabut charger
    var m = ChargeMemo()
    c3("mengisi 85%: belum ada peringatan", ev(m, 85, true, 33f, false, 0) == ChargeLogic.NONE)
    c3("mencapai 90%: ingatkan cabut", ev(m, 90, true, 33f, false, 1 * MIN) == ChargeLogic.UNPLUG)
    c3("91% semenit kemudian: tidak spam (cooldown)", ev(m, 91, true, 33f, false, 2 * MIN) == ChargeLogic.NONE)
    c3("masih terpasang 10 menit kemudian: diingatkan lagi", ev(m, 93, true, 33f, false, 11 * MIN + 1000) == ChargeLogic.UNPLUG)
    c3("100%: peringatan penuh (sekali)", ev(m, 100, true, 33f, false, 12 * MIN) == ChargeLogic.FULL && ev(m, 100, true, 33f, false, 12 * MIN + 20_000) == ChargeLogic.NONE)
    c3("dicabut lalu dicolok lagi di 95%: langsung diingatkan", run {
        ev(m, 95, false, 33f, false, 13 * MIN); ev(m, 95, true, 33f, false, 14 * MIN) == ChargeLogic.UNPLUG })
    val off = ChargeCfg(false, 90, false, 40f, false)
    val m0 = ChargeMemo()
    c3("semua peringatan dimatikan: tidak ada keluaran",
        ChargeLogic.evaluate(m0, 100, true, 45f, true, off, 0) == ChargeLogic.NONE && ChargeLogic.evaluate(m0, 10, false, 30f, true, off, 5 * MIN) == ChargeLogic.NONE)

    // ── panas saat mengisi sambil main
    m = ChargeMemo()
    c3("mengisi+main, 41°C: peringatan panas", ev(m, 50, true, 41f, true, 0) == ChargeLogic.HOT)
    c3("semenit kemudian: tidak spam", ev(m, 50, true, 41.5f, true, 1 * MIN) == ChargeLogic.NONE)
    c3("3 menit kemudian masih panas: diingatkan lagi", ev(m, 50, true, 41.5f, true, 3 * MIN + 1000) == ChargeLogic.HOT)
    c3("44°C: eskalasi ke BAHAYA seketika", ev(m, 50, true, 44f, true, 3 * MIN + 20_000) == ChargeLogic.DANGER)
    c3("bahaya tidak spam dalam 90 dtk, ulang setelahnya",
        ev(m, 50, true, 44f, true, 3 * MIN + 60_000) == ChargeLogic.NONE && ev(m, 50, true, 44f, true, 3 * MIN + 115_000) == ChargeLogic.DANGER)
    m = ChargeMemo()
    c3("tidak sedang main: tidak ada peringatan panas", ev(m, 50, true, 45f, false, 0) == ChargeLogic.NONE)
    m = ChargeMemo()
    c3("main tanpa mengisi: tidak ada peringatan panas pengisian", ev(m, 50, false, 45f, true, 0) == ChargeLogic.NONE)

    // ── suhu naik cepat (di bawah batas)
    m = ChargeMemo()
    var first = -1; var early = false
    for (i in 0..30) {
        val t = i * 10_000L
        val temp = 36f + 0.0133f * (t / 1000f)
        val r = ev(m, 50, true, temp, true, t)
        if (r == ChargeLogic.RISE && first < 0) first = i * 10
        if (r != ChargeLogic.NONE && i * 10 < 140) early = true
    }
    c3("naik >=2°C dalam ~3 menit: peringatan RISE (tidak lebih awal dari 140 dtk)", first in 140..220 && !early, "first=$first early=$early")
    m = ChargeMemo()
    var any = false
    for (i in 0..30) if (ev(m, 50, true, 36f + 0.003f * i * 10, true, i * 10_000L) != ChargeLogic.NONE) any = true
    c3("naik pelan (<2°C/3 mnt): tidak ada peringatan", !any)

    // ── baterai rendah
    m = ChargeMemo()
    c3("15% tidak mengisi: peringatan rendah (sekali)", ev(m, 15, false, 30f, false, 0) == ChargeLogic.LOW && ev(m, 14, false, 30f, false, 20_000) == ChargeLogic.NONE)
    c3("naik >20% lalu turun lagi: diingatkan lagi", run { ev(m, 25, false, 30f, false, MIN); ev(m, 14, false, 30f, false, 2 * MIN) == ChargeLogic.LOW })

    // ── appops
    c3("parse query-op kosong", AppOpsLogic.parsePackages("No operations.\n").isEmpty())
    c3("parse query-op berisi", AppOpsLogic.parsePackages("com.a.b\ncom.c.d (uid=1000)\nNo operations.") == setOf("com.a.b", "com.c.d"))
    val t = AppOpsLogic.targets(listOf("com.a.b", "com.game.x", "com.keyboard", "moe.shizuku", "com.user.restricted", "bad", "com.a.b"),
        setOf("com.game.x", "com.keyboard", "moe.shizuku"), setOf("com.user.restricted"))
    c3("target: dilindungi, sudah dibatasi pengguna, tanpa titik, duplikat dikeluarkan", t == listOf("com.a.b"), t.toString())

    println(if (f3 == 0) "\nSEMUA TES v1.3 LULUS" else "\n$f3 TES v1.3 GAGAL")
    System.exit(if (f3 == 0) 0 else 1)
}
