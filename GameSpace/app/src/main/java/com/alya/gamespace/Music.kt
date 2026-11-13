package com.alya.gamespace

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri

/** Pemutar musik dashboard: 5 slot lagu pilihan pengguna. */
object Music {
    const val SLOTS = 5
    private var mp: MediaPlayer? = null
    @Volatile var current = -1
    @Volatile var userPaused = false

    fun name(c: Context, i: Int) = Prefs.str(c, "mus_n$i")
    fun filled(c: Context, i: Int) = Prefs.str(c, "mus_u$i").isNotEmpty()
    fun set(c: Context, i: Int, u: Uri, n: String) { Prefs.setStr(c, "mus_u$i", u.toString()); Prefs.setStr(c, "mus_n$i", n) }
    fun clear(c: Context, i: Int) {
        if (current == i) stop()
        Prefs.setStr(c, "mus_u$i", ""); Prefs.setStr(c, "mus_n$i", "")
    }
    fun selected(c: Context) = Prefs.int(c, "mus_sel", 0).coerceIn(0, SLOTS - 1)
    val playing: Boolean get() = try { mp?.isPlaying == true } catch (_: Throwable) { false }

    fun firstFilled(c: Context): Int { for (i in 0 until SLOTS) if (filled(c, i)) return i; return -1 }

    fun play(c: Context, i: Int): Boolean {
        stopPlayer()
        if (i < 0 || !filled(c, i)) return false
        return try {
            val p = MediaPlayer()
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            p.setDataSource(c, Uri.parse(Prefs.str(c, "mus_u$i")))
            val v = Prefs.int(c, "mus_vol", 70) / 100f
            p.setVolume(v, v)
            p.setOnCompletionListener { next(c) }
            p.prepare(); p.start()
            mp = p; current = i; userPaused = false
            Prefs.setInt(c, "mus_sel", i)
            true
        } catch (_: Throwable) { stopPlayer(); current = -1; false }
    }

    /** Lagu berikutnya yang terisi (berputar). */
    fun next(c: Context) {
        val start = if (current >= 0) current else selected(c)
        for (k in 1..SLOTS) {
            val i = (start + k) % SLOTS
            if (filled(c, i)) { play(c, i); return }
        }
    }

    fun prev(c: Context) {
        val start = if (current >= 0) current else selected(c)
        for (k in 1..SLOTS) {
            val i = ((start - k) % SLOTS + SLOTS) % SLOTS
            if (filled(c, i)) { play(c, i); return }
        }
    }

    fun toggle(c: Context) {
        if (playing) { pause(); userPaused = true }
        else if (mp != null && current >= 0) { resume(); userPaused = false }
        else { val i = if (filled(c, selected(c))) selected(c) else firstFilled(c); play(c, i) }
    }

    fun pause() { try { mp?.pause() } catch (_: Throwable) {} }
    fun resume() { try { mp?.start() } catch (_: Throwable) {} }
    fun stop() { stopPlayer(); current = -1 }
    fun setVolume(pct: Int) { val v = pct / 100f; try { mp?.setVolume(v, v) } catch (_: Throwable) {} }

    private fun stopPlayer() {
        try { mp?.stop() } catch (_: Throwable) {}
        try { mp?.release() } catch (_: Throwable) {}
        mp = null
    }
}
