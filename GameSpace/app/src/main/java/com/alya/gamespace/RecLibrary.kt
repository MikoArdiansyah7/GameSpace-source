package com.alya.gamespace

import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

class RecItem(val uri: Uri, val name: String, val size: Long, val dateMs: Long, var durMs: Long, val file: File? = null)

/** Daftar video hasil rekam layar Game Space. Lokasi mengikuti pengaturan: folder pilihan, Movies/GameSpace, atau folder app (Android 9 ke bawah). */
object RecLibrary {
    private val durCache = HashMap<String, Long>()

    /** Panggil dari thread latar. Terbaru dulu, maksimal [max] berkas. */
    fun list(ctx: Context, max: Int = 30): List<RecItem> {
        val out = ArrayList<RecItem>()
        try {
            val dir = Prefs.str(ctx, "rec_dir")
            if (dir.isNotEmpty()) listTree(ctx, Uri.parse(dir), out)
            else if (Build.VERSION.SDK_INT >= 29) listStore(ctx, out)
            else listLegacy(ctx, out)
        } catch (_: Throwable) {}
        out.sortByDescending { it.dateMs }
        val res = if (out.size > max) ArrayList(out.subList(0, max)) else out
        for (r in res) if (r.durMs <= 0L) r.durMs = duration(ctx, r)
        return res
    }

    private fun listStore(ctx: Context, out: MutableList<RecItem>) {
        val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DURATION)
        val sel = MediaStore.Video.Media.DISPLAY_NAME + " LIKE ? AND " + MediaStore.Video.Media.RELATIVE_PATH + " LIKE ? AND " +
            MediaStore.Video.Media.IS_PENDING + " = 0"
        ctx.contentResolver.query(base, proj, sel, arrayOf("GameSpace_%", "Movies/GameSpace%"), MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (!RecLibLogic.isOurs(name)) continue
                out.add(RecItem(ContentUris.withAppendedId(base, c.getLong(0)), name, c.getLong(2), c.getLong(3) * 1000L, c.getLong(4)))
            }
        }
    }

    private fun listTree(ctx: Context, tree: Uri, out: MutableList<RecItem>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (!RecLibLogic.isOurs(name)) continue
                out.add(RecItem(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), name, c.getLong(2), c.getLong(3), 0L))
            }
        }
    }

    private fun listLegacy(ctx: Context, out: MutableList<RecItem>) {
        val d = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: return
        for (f in d.listFiles() ?: return) if (f.isFile && RecLibLogic.isOurs(f.name)) out.add(RecItem(Uri.fromFile(f), f.name, f.length(), f.lastModified(), 0L, f))
    }

    private fun duration(ctx: Context, r: RecItem): Long {
        val key = r.uri.toString() + "#" + r.size
        synchronized(durCache) { durCache[key]?.let { return it } }
        val ms = try {
            val m = MediaMetadataRetriever()
            try {
                if (r.file != null) m.setDataSource(r.file.path) else m.setDataSource(ctx, r.uri)
                m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally { try { m.release() } catch (_: Throwable) {} }
        } catch (_: Throwable) { 0L }
        if (ms > 0L) synchronized(durCache) { durCache[key] = ms }
        return ms
    }

    /** Intent putar; null bila berkas ada di folder app (Android 9 ke bawah) karena tidak bisa dibagikan sebagai content://. */
    fun openIntent(r: RecItem): Intent? {
        if (r.file != null) return null
        return Intent(Intent.ACTION_VIEW).setDataAndType(r.uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun shareIntent(r: RecItem): Intent? {
        if (r.file != null) return null
        val i = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, r.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri(r.name, r.uri)
        return Intent.createChooser(i, "Bagikan rekaman")
    }

    fun delete(ctx: Context, r: RecItem): Boolean = try {
        when {
            r.file != null -> r.file.delete()
            r.uri.authority == MediaStore.AUTHORITY -> ctx.contentResolver.delete(r.uri, null, null) > 0
            else -> DocumentsContract.deleteDocument(ctx.contentResolver, r.uri)
        }
    } catch (_: Throwable) { false }
}
