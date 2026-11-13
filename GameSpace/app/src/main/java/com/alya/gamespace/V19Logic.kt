package com.alya.gamespace

import java.util.Locale

/** Logika murni v1.9 (tap otomatis, dex2oat, status jaringan). Tanpa API Android supaya bisa dites di JVM. */

object AutoTapLogic {
    const val MIN_MS = 30
    const val MAX_MS = 60_000
    val PRESETS = intArrayOf(100, 200, 300, 500, 800, 1000, 1500, 2000, 3000)
    val JITTERS = intArrayOf(0, 10, 25, 40)      // persen acak jeda
    val DOWN_MS = intArrayOf(20, 30, 50, 80)     // lama sentuh tiap ketukan
    val LIMITS = intArrayOf(0, 5, 15, 30, 60)    // batas waktu (menit), 0 = tanpa batas

    fun clampMs(v: Int): Int = v.coerceIn(MIN_MS, MAX_MS)

    /** Jeda ke ketukan berikutnya: dasar +/- acak [jitterPct]% (rnd 0..1). Tidak pernah di bawah [MIN_MS]. */
    fun nextDelay(baseMs: Int, jitterPct: Int, rnd: Double): Int {
        val b = clampMs(baseMs)
        val j = jitterPct.coerceIn(0, 90)
        if (j == 0) return b
        val span = b * j / 100.0
        val d = b + (rnd.coerceIn(0.0, 1.0) * 2.0 - 1.0) * span
        return Math.max(MIN_MS, Math.round(d).toInt())
    }

    /** Alasan berhenti otomatis, atau null bila boleh lanjut. Urutan: layar mati, keluar game, batas waktu, batas tap. */
    fun stopReason(elapsedMs: Long, limitMin: Int, taps: Int, maxTaps: Int, screenOn: Boolean, inTargetApp: Boolean): String? = when {
        !screenOn -> "layar mati"
        !inTargetApp -> "keluar dari game"
        limitMin > 0 && elapsedMs >= limitMin * 60_000L -> "batas waktu $limitMin menit"
        maxTaps > 0 && taps >= maxTaps -> "batas $maxTaps tap"
        else -> null
    }

    /** "500 ms", "1,5 dtk", "2 dtk". */
    fun intervalLabel(ms: Int): String {
        val v = clampMs(ms)
        return if (v < 1000) "$v ms"
        else if (v % 1000 == 0) (v / 1000).toString() + " dtk"
        else String.format(Locale.US, "%.1f", v / 1000.0).replace('.', ',') + " dtk"
    }

    /** "2 tap/dtk", "3,3 tap/dtk", "1 tap tiap 2 dtk". */
    fun rateText(ms: Int): String {
        val v = clampMs(ms)
        if (v > 1000) return "1 tap tiap " + intervalLabel(v)
        val r = 1000.0 / v
        val s = if (r == Math.floor(r)) r.toInt().toString() else String.format(Locale.US, "%.1f", r).replace('.', ',')
        return "$s tap/dtk"
    }

    /** Tombol kontrol tidak boleh menutupi titik tap (ketukan bisa mengenai tombolnya sendiri). */
    fun overlaps(tx: Int, ty: Int, left: Int, top: Int, w: Int, h: Int, margin: Int): Boolean =
        tx >= left - margin && tx <= left + w + margin && ty >= top - margin && ty <= top + h + margin
}

object Dex2oatLogic {
    class Mode(val flag: String, val label: String, val note: String)

    val MODES = listOf(
        Mode("speed-profile", "Speed Profile", "Cepat dan hemat ruang: hanya kode yang sering dipakai yang dikompilasi. Efeknya ringan."),
        Mode("speed", "Speed", "Seluruh kode dikompilasi di muka. Pilihan umum untuk game: loading lebih cepat dan patah-patah di awal berkurang."),
        Mode("everything", "Everything", "Maksimal: semuanya dikompilasi, termasuk kode yang jarang dipakai. Paling lama dan paling banyak memakai ruang."))

    private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    /** Nama paket sah; dipakai juga sebagai pagar agar tidak ada karakter aneh masuk ke perintah shell. */
    fun validPkg(p: String): Boolean = PKG.matches(p)

    fun compileCmd(pkg: String, flag: String): String? =
        if (!validPkg(pkg) || MODES.none { it.flag == flag }) null else "cmd package compile -m $flag -f $pkg 2>&1"

    fun resetCmd(pkg: String): String? = if (!validPkg(pkg)) null else "cmd package compile --reset $pkg 2>&1"

    fun statusCmd(pkg: String): String? = if (!validPkg(pkg)) null else "dumpsys package $pkg 2>&1 | grep -A 14 'Dexopt state'"

    class Status(val filter: String, val reason: String)

    /** Baca [status=...] dan [reason=...] dari keluaran `dumpsys package <pkg>` (format sama di Android 8 sampai 16). */
    fun parseStatus(out: String): Status? {
        val f = Regex("\\[status=([^\\]]+)\\]").find(out) ?: return null
        val r = Regex("\\[reason=([^\\]]+)\\]").find(out)?.groupValues?.get(1) ?: ""
        return Status(f.groupValues[1].trim(), r.trim())
    }

    /** 0 = belum dioptimasi, 1 = sebagian (berbasis profil), 2 = penuh. */
    fun level(filter: String): Int = when (filter) {
        "speed", "everything", "everything-profile" -> 2
        "speed-profile", "space-profile", "space" -> 1
        else -> 0
    }

    private fun reasonLabel(r: String): String = when (r) {
        "cmdline" -> "manual"
        "bg-dexopt" -> "otomatis sistem"
        "install", "install-fast", "install-bulk", "install-bulk-secondary", "install-bulk-downgraded" -> "saat dipasang"
        "first-boot", "boot-after-ota", "boot-after-mainline-update" -> "saat boot"
        "inactive" -> "jarang dipakai"
        else -> r
    }

    fun statusText(s: Status?): String {
        if (s == null) return "belum diketahui"
        val base = when (level(s.filter)) {
            2 -> if (s.filter == "everything") "Everything" else "Speed"
            1 -> "Speed Profile"
            else -> "Belum (" + s.filter + ")"
        }
        return if (s.reason.isEmpty()) base else base + " • " + reasonLabel(s.reason)
    }

    fun ok(out: String): Boolean {
        val t = out.trim()
        return t.contains("Success", true) && !t.contains("Failure", true) && !t.contains("Error", true)
    }

    fun failText(out: String): String =
        out.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.equals("Success", true) }?.take(120) ?: "tidak ada pesan dari sistem"

    /** Peringatan sebelum mulai, atau null bila aman. */
    fun warning(battery: Int, charging: Boolean, inGame: Boolean): String? = when {
        inGame -> "Game sedang dimainkan. Kompilasi memakai banyak CPU dan bisa membuat game patah-patah."
        !charging && battery in 0..19 -> "Baterai tinggal $battery% dan tidak sedang dicas. Kompilasi cukup menguras baterai."
        else -> null
    }

    fun summary(ok: Int, fail: Int, stopped: Boolean): String =
        (if (stopped) "Dihentikan. " else "Selesai. ") + ok + " berhasil" + (if (fail > 0) ", $fail gagal" else "")
}

object NetLogic {
    const val NONE = 0
    const val WIFI = 1
    const val CELL = 2
    const val LAN = 3
    const val VPN = 4

    class Net(val wifi: Boolean, val cell: Boolean, val ethernet: Boolean, val vpn: Boolean, val internet: Boolean, val validated: Boolean)

    private fun kind(n: Net): Int = if (n.wifi) WIFI else if (n.cell) CELL else if (n.ethernet) LAN else NONE

    /**
     * Jenis jaringan untuk ditampilkan. Dipilih dari jaringan bawaan bila ada; kalau sistem menyembunyikannya
     * (pembatasan latar, penghemat data), dipilih dari semua jaringan yang punya internet: yang tervalidasi dulu,
     * lalu Wi-Fi > data > LAN. VPN tanpa jaringan dasar yang terlihat ditampilkan sebagai VPN.
     */
    fun label(all: List<Net>, def: Net?): Int {
        if (def != null && def.internet) { val k = kind(def); if (k != NONE) return k }
        var best = NONE
        var bestScore = -1
        for (n in all) {
            if (!n.internet) continue
            val k = kind(n)
            if (k == NONE) continue
            val score = (if (n.validated) 10 else 0) + (if (k == WIFI) 3 else if (k == CELL) 2 else 1)
            if (score > bestScore) { bestScore = score; best = k }
        }
        if (best != NONE) return best
        if ((def != null && def.internet && def.vpn) || all.any { it.vpn && it.internet }) return VPN
        return NONE
    }

    /** Keterangan di bawah angka ping. "OFF" hanya bila benar-benar tidak ada jaringan DAN ping juga gagal. */
    fun caption(net: Int, ping: Int): String = when {
        net == WIFI -> "WIFI"
        net == CELL -> "DATA"
        net == LAN -> "LAN"
        net == VPN -> "VPN"
        ping >= 0 -> "NET"
        else -> "OFF"
    }
}
