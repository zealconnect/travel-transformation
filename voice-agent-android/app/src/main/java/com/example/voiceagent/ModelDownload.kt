package com.example.voiceagent

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import java.io.File

/** Downloads the free Gemma 4 brain (about 2.6 GB) once, straight to the phone. No account needed. */
object ModelDownload {

    data class Progress(val running: Boolean, val failed: Boolean, val done: Long, val total: Long)

    private fun part(ctx: Context) = File(Brain.modelFile(ctx).path + ".part")

    fun freeSpace(ctx: Context): Long = try {
        StatFs((ctx.getExternalFilesDir(null) ?: ctx.filesDir).path).availableBytes
    } catch (_: Exception) { Long.MAX_VALUE }

    fun enoughSpace(ctx: Context) = freeSpace(ctx) > Brain.MODEL_BYTES + 300_000_000L

    fun start(ctx: Context, allowMobileData: Boolean): Boolean {
        if (Prefs.downloadId(ctx) >= 0 || Brain.installed(ctx)) return true
        if (!enoughSpace(ctx)) return false
        part(ctx).delete()
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val req = DownloadManager.Request(Uri.parse(Brain.MODEL_URL))
            .setTitle("Voice Agent brain")
            .setDescription("Gemma 4 · runs on your phone")
            .setDestinationUri(Uri.fromFile(part(ctx)))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(allowMobileData)
            .setAllowedOverRoaming(false)
        Prefs.setDownloadId(ctx, dm.enqueue(req))
        return true
    }

    fun progress(ctx: Context): Progress? {
        val id = Prefs.downloadId(ctx)
        if (id < 0) return null
        val dm = ctx.getSystemService(DownloadManager::class.java)
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) { Prefs.setDownloadId(ctx, -1); return null }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 } ?: Brain.MODEL_BYTES
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> { finish(ctx); null }
                DownloadManager.STATUS_FAILED -> { Prefs.setDownloadId(ctx, -1); Progress(false, true, done, total) }
                else -> Progress(true, false, done, total)
            }
        }
        return null
    }

    /** Move the finished download into place. */
    fun finish(ctx: Context) {
        val p = part(ctx)
        if (p.exists() && p.length() > Brain.MIN_BYTES) {
            Brain.modelFile(ctx).delete()
            p.renameTo(Brain.modelFile(ctx))
        }
        Prefs.setDownloadId(ctx, -1)
        AgentService.instance?.let { Brain.preload(it) }
    }

    fun cancel(ctx: Context) {
        val id = Prefs.downloadId(ctx)
        if (id >= 0) ctx.getSystemService(DownloadManager::class.java).remove(id)
        Prefs.setDownloadId(ctx, -1)
        part(ctx).delete()
    }

    fun delete(ctx: Context) {
        cancel(ctx)
        Brain.release()
        Brain.modelFile(ctx).delete()
    }
}

/** Finishes the brain download even if the app is closed when it completes. */
class DownloadDoneReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -2)
        if (id == Prefs.downloadId(ctx)) ModelDownload.progress(ctx)
    }
}
