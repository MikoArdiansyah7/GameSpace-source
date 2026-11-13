package com.alya.gamespace

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class UpdateInfo(val code: Int, val name: String, val notes: String, val apkUrl: String, val sha256: String)

/**
 * Pusat pembaruan. Sumbernya file JSON (https) yang kamu host sendiri, mis. di GitHub:
 * {"versionCode":6,"versionName":"1.6","apkUrl":"https://.../GameSpace-v1.6.apk","sha256":"(opsional)","notes":"- fitur A"}
 * APK diunduh dari dalam aplikasi, dicek SHA-256 (bila ada), lalu dipasang lewat PackageInstaller.
 * Android sendiri menolak APK yang tidak ditandatangani kunci yang sama, jadi file palsu tidak bisa menimpa aplikasi ini.
 */
object Updater {
    class Fetch(val info: UpdateInfo?, val error: String?)
    class Dl(val file: File?, val error: String?)

    /** Murni (tanpa jaringan) supaya bisa diuji. */
    fun parse(body: String): Fetch = try {
        val j = JSONObject(body)
        Fetch(UpdateInfo(j.getInt("versionCode"), j.optString("versionName", "?"), j.optString("notes", ""),
            j.optString("apkUrl", ""), j.optString("sha256", "").trim().lowercase()), null)
    } catch (t: Throwable) { Fetch(null, "Format file pembaruan salah (" + t.javaClass.simpleName + ")") }

    fun hex(b: ByteArray): String { val sb = StringBuilder(); for (x in b) sb.append("%02x".format(x)); return sb.toString() }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(32 * 1024)
            while (true) { val n = ins.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        return hex(md.digest())
    }

    fun fetch(url: String): Fetch {
        if (!url.startsWith("https://")) return Fetch(null, "URL pembaruan harus diawali https://")
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode != 200) Fetch(null, "Server membalas kode " + c.responseCode)
            else parse(c.inputStream.bufferedReader().use { it.readText() })
        } catch (t: Throwable) { Fetch(null, "Gagal memeriksa: " + t.javaClass.simpleName + " " + (t.message ?: "")) }
    }

    fun download(ctx: Context, info: UpdateInfo, onProgress: (Int) -> Unit): Dl {
        if (!info.apkUrl.startsWith("https://")) return Dl(null, "Alamat APK harus diawali https://")
        val f = File(ctx.cacheDir, "update.apk")
        return try {
            val c = URL(info.apkUrl).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 20_000
            if (c.responseCode != 200) return Dl(null, "Server membalas kode " + c.responseCode)
            val total = c.contentLength
            c.inputStream.use { ins ->
                FileOutputStream(f).use { out ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) onProgress((done * 100 / total).toInt())
                    }
                }
            }
            if (info.sha256.isNotEmpty() && sha256(f) != info.sha256) { f.delete(); Dl(null, "SHA-256 tidak cocok, file ditolak") }
            else Dl(f, null)
        } catch (t: Throwable) { f.delete(); Dl(null, "Unduhan gagal: " + t.javaClass.simpleName + " " + (t.message ?: "")) }
    }

    /** Mulai pemasangan. Mengembalikan pesan error, atau null bila sesi berhasil dibuat (lalu konfirmasi di layar sistem). */
    fun install(ctx: Context, f: File): String? = try {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            f.inputStream().use { ins -> s.openWrite("update", 0, f.length()).use { out -> ins.copyTo(out); s.fsync(out) } }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pend = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java).setPackage(ctx.packageName), flags)
            s.commit(pend.intentSender)
        }
        null
    } catch (t: Throwable) { t.javaClass.simpleName + " " + (t.message ?: "") }

    fun canInstall(ctx: Context): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()
}

/** Menerima hasil sesi pemasangan: munculkan layar konfirmasi sistem, atau beri tahu kalau gagal. */
class InstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { ctx.startActivity(confirm) } catch (_: Throwable) {}
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> Toast.makeText(ctx, "Pemasangan gagal: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "tidak diketahui"),
                Toast.LENGTH_LONG).show()
        }
    }
}

/** Opsional: nyalakan layanan Game Space otomatis setelah HP hidup. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.bool(c, "boot_start", false) || !Settings.canDrawOverlays(c)) return
        try { c.startForegroundService(Intent(c, AlyaAiService::class.java)) } catch (_: Throwable) {}
    }
}
