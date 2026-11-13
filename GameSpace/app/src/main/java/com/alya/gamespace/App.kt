package com.alya.gamespace

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                File(filesDir, "crash.txt").writeText("Game Space v" + appVersion() + "  " + Date() + "\nthread: " + t.name + "\n\n" + sw)
            } catch (_: Throwable) {}
            if (prev != null) prev.uncaughtException(t, e)
        }
    }
}

fun Context.appVersion(): String = try {
    packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
} catch (_: Throwable) { "?" }
