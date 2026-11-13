package com.alya.gamespace

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Optimasi dex2oat lewat `cmd package compile`. Berjalan di proses shell TERPISAH (bukan [Sh]) karena kompilasi game bisa
 * berminit-minit, sedangkan [Sh] memakai satu kunci untuk semua perintah (FPS, HUD, governor) sehingga semuanya akan macet.
 */
object Dex2oat {
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    val status = ConcurrentHashMap<String, Dex2oatLogic.Status>()

    @Volatile var running = false
        private set
    @Volatile var stopRequested = false
        private set
    @Volatile var progress = ""
    @Volatile var lastSummary = ""
    @Volatile var statusAt = 0L
        private set

    private const val TIMEOUT_MS = 12 * 60_000L
    private const val RESET_TIMEOUT_MS = 3 * 60_000L

    private fun notifyChange() { for (l in listeners) try { l() } catch (_: Throwable) {} }

    private class Res(val ok: Boolean, val out: String)

    private fun runCmd(cmd: String, timeoutMs: Long): Res {
        val t0 = System.currentTimeMillis()
        val p = try { ShizukuHelper.newProcess(arrayOf("sh", "-c", cmd)) }
        catch (t: Throwable) { return Res(false, "Gagal membuat proses: " + t.javaClass.simpleName) }
        val sb = StringBuffer()
        val rd = Thread {
            try { BufferedReader(InputStreamReader(p.inputStream)).use { r -> while (true) { val l = r.readLine() ?: break; sb.append(l).append('\n') } } } catch (_: Throwable) {}
        }
        rd.isDaemon = true
        rd.start()
        var done = false
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            try { p.exitValue(); done = true; break } catch (_: IllegalThreadStateException) {} catch (_: Throwable) { break }
            try { Thread.sleep(300) } catch (_: InterruptedException) {}
        }
        if (!done) try { p.destroy() } catch (_: Throwable) {}
        try { rd.join(1500) } catch (_: Throwable) {}
        val out = sb.toString()
        return if (done) Res(Dex2oatLogic.ok(out), out) else Res(false, out + "\nwaktu habis")
    }

    /** Baca status dex2oat tiap paket (cepat, lewat shell bersama). Panggil dari thread latar. */
    fun refreshStatus(pkgs: Collection<String>) {
        if (!Sh.granted()) return
        for (pkg in pkgs) {
            val cmd = Dex2oatLogic.statusCmd(pkg) ?: continue
            val s = Dex2oatLogic.parseStatus(Sh.exec(cmd, 8000))
            if (s != null) status[pkg] = s else status.remove(pkg)
        }
        statusAt = System.currentTimeMillis()
        notifyChange()
    }

    fun stop() { if (running) { stopRequested = true; progress = "Berhenti setelah game ini selesai..."; notifyChange() } }

    /** Kompilasi [items] (paket, nama) berurutan dengan filter [flag]. Satu pekerjaan dalam satu waktu. */
    fun start(items: List<Pair<String, String>>, flag: String) {
        if (running || items.isEmpty()) return
        if (!Sh.granted()) { lastSummary = "Butuh Shizuku aktif dan diizinkan"; notifyChange(); return }
        running = true; stopRequested = false; lastSummary = ""
        Thread {
            var ok = 0; var fail = 0
            val notes = ArrayList<String>()
            try {
                for ((i, it) in items.withIndex()) {
                    if (stopRequested) break
                    progress = "Mengoptimasi " + (i + 1) + "/" + items.size + ": " + it.second
                    notifyChange()
                    val cmd = Dex2oatLogic.compileCmd(it.first, flag)
                    if (cmd == null) { fail++; notes.add(it.second + ": nama paket tidak sah"); continue }
                    val res = runCmd(cmd, TIMEOUT_MS)
                    if (res.ok) ok++ else { fail++; notes.add(it.second + ": " + Dex2oatLogic.failText(res.out)) }
                    ActionLog.add("dex2oat " + flag + " " + it.first + ": " + (if (res.ok) "berhasil" else Dex2oatLogic.failText(res.out)))
                    refreshStatus(listOf(it.first))
                }
            } catch (t: Throwable) {
                notes.add("error: " + t.javaClass.simpleName)
            } finally {
                lastSummary = Dex2oatLogic.summary(ok, fail, stopRequested) + (if (notes.isEmpty()) "" else "\n" + notes.take(3).joinToString("\n"))
                progress = ""; running = false; stopRequested = false
                notifyChange()
            }
        }.also { it.name = "gs-dex2oat"; it.isDaemon = true }.start()
    }

    /** Kembalikan ke kompilasi bawaan sistem (`--reset`). */
    fun reset(items: List<Pair<String, String>>) {
        if (running || items.isEmpty()) return
        if (!Sh.granted()) { lastSummary = "Butuh Shizuku aktif dan diizinkan"; notifyChange(); return }
        running = true; stopRequested = false; lastSummary = ""
        Thread {
            var ok = 0; var fail = 0
            try {
                for ((i, it) in items.withIndex()) {
                    if (stopRequested) break
                    progress = "Mengembalikan " + (i + 1) + "/" + items.size + ": " + it.second
                    notifyChange()
                    val cmd = Dex2oatLogic.resetCmd(it.first)
                    if (cmd == null) { fail++; continue }
                    val res = runCmd(cmd, RESET_TIMEOUT_MS)
                    if (res.ok) ok++ else fail++
                    ActionLog.add("dex2oat reset " + it.first + ": " + (if (res.ok) "berhasil" else Dex2oatLogic.failText(res.out)))
                    refreshStatus(listOf(it.first))
                }
            } finally {
                lastSummary = Dex2oatLogic.summary(ok, fail, stopRequested).replace("berhasil", "dikembalikan")
                progress = ""; running = false; stopRequested = false
                notifyChange()
            }
        }.also { it.name = "gs-dex2oat"; it.isDaemon = true }.start()
    }
}
