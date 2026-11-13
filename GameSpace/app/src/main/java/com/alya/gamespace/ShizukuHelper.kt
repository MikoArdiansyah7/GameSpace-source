package com.alya.gamespace

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object ShizukuHelper {
    fun running(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun granted(): Boolean = try {
        running() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    fun request() {
        try { if (running() && !granted()) Shizuku.requestPermission(100) } catch (_: Throwable) {}
    }

    /** 2000 = ADB/shell, 0 = root. */
    fun serverUid(): Int = try { Shizuku.getUid() } catch (_: Throwable) { -1 }

    // newProcess() private di Shizuku 13+, dipanggil lewat reflection
    fun newProcess(cmd: Array<String>): Process {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, cmd, null, null) as Process
    }
}

/** Satu proses `sh` yang hidup terus: perintah dikirim lewat stdin, hasil dibaca sampai penanda. */
class ShellSession(private val proc: Process) {
    private val w = OutputStreamWriter(proc.outputStream)
    private val r = BufferedReader(InputStreamReader(proc.inputStream))

    fun alive(): Boolean = try { proc.exitValue(); false } catch (_: IllegalThreadStateException) { true } catch (_: Throwable) { false }

    fun run(cmd: String): String {
        val tag = "__GS" + System.nanoTime() + "__"
        w.write(cmd); w.write("\necho\necho " + tag + "\n"); w.flush()
        val sb = StringBuilder()
        while (true) {
            val line = r.readLine() ?: break
            if (line.contains(tag)) break
            sb.append(line).append('\n')
        }
        return sb.toString()
    }

    fun close() {
        try { w.close() } catch (_: Throwable) {}
        try { proc.destroy() } catch (_: Throwable) {}
    }

    companion object {
        fun start(priv: Boolean): ShellSession {
            val p = if (priv) ShizukuHelper.newProcess(arrayOf("sh")) else Runtime.getRuntime().exec(arrayOf("sh"))
            val s = ShellSession(p)
            s.run("exec 2>&1")
            return s
        }
    }
}

/** Pintu tunggal untuk semua perintah shell (Shizuku bila diizinkan, kalau tidak shell biasa). */
object Sh {
    private val lock = Any()
    private val pool = Executors.newCachedThreadPool()
    private var session: ShellSession? = null
    private var sessionPriv = false

    fun granted() = ShizukuHelper.granted()

    fun exec(cmd: String, timeoutMs: Long = 10_000): String = synchronized(lock) { execLocked(cmd, timeoutMs) }

    private fun execLocked(cmd: String, timeoutMs: Long): String {
        val priv = ShizukuHelper.granted()
        var s = session
        if (s == null || sessionPriv != priv || !s.alive()) {
            s?.close()
            s = try { ShellSession.start(priv) } catch (_: Throwable) { null }
            session = s
            sessionPriv = priv
        }
        if (s == null) return ""
        val sess = s
        val f = pool.submit(Callable { sess.run(cmd) })
        return try {
            f.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            sess.close(); session = null; f.cancel(true)
            ActionLog.add("Shell timeout: " + cmd.take(50))
            ""
        }
    }

    /** Perintah lewat su (hanya dipakai untuk lock CPU/GPU bila perangkat di-root). */
    fun su(cmd: String): String = exec("su -c '" + cmd.replace("'", "'\\''") + "' 2>&1", 20_000)

    fun close() = synchronized(lock) { session?.close(); session = null }
}
