package com.alya.gamespace

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Tile Pengaturan Cepat "Rekam Layar": mulai atau berhenti merekam dari panel notifikasi tanpa membuka aplikasi. */
class RecTileService : TileService() {
    private val listener: () -> Unit = { refresh() }

    override fun onStartListening() {
        RecorderService.listeners.add(listener)
        refresh()
    }

    override fun onStopListening() {
        RecorderService.listeners.remove(listener)
    }

    override fun onClick() {
        if (isLocked) unlockAndRun { act() } else act()
    }

    private fun act() {
        if (RecorderService.recording) {
            RecorderService.requestStop(this, "tile")
            refresh()
            return
        }
        val i = Intent(this, ProjectionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 11, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION") startActivityAndCollapse(i)
            }
        } catch (_: Throwable) {}
    }

    private fun refresh() {
        val t = qsTile ?: return
        val rec = RecorderService.recording
        t.state = if (rec) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Rekam Layar"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (rec) "Merekam, ketuk untuk berhenti" else "Ketuk untuk mulai"
        t.updateTile()
    }
}
