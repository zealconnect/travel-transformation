package com.example.voiceagent

import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import kotlin.math.max

enum class FileKind { ANY, PDF, IMAGE, SCREENSHOT, VIDEO, AUDIO, DOCUMENT }

/** What to look for: a type, words from the file name, and whether you meant the newest one. */
data class FileQuery(val kind: FileKind, val keywords: List<String>, val latest: Boolean)

data class FoundFile(val path: String, val name: String, val mime: String)

/** Finds files on the phone (Downloads, WhatsApp media, camera, documents…). Needs "All files access". */
object FileFinder {

    fun hasAccess() = try { Environment.isExternalStorageManager() } catch (_: Exception) { false }

    private val DOC_MIMES = listOf(
        "application/pdf", "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "text/plain", "text/csv", "application/rtf"
    )

    private fun selection(kind: FileKind): Pair<String, Array<String>> {
        val m = MediaStore.Files.FileColumns.MIME_TYPE
        val path = MediaStore.Files.FileColumns.RELATIVE_PATH
        val base = "${MediaStore.Files.FileColumns.SIZE} > 0"
        return when (kind) {
            FileKind.PDF -> "$base AND $m = ?" to arrayOf("application/pdf")
            FileKind.IMAGE -> "$base AND $m LIKE ?" to arrayOf("image/%")
            FileKind.SCREENSHOT -> "$base AND $m LIKE ? AND $path LIKE ?" to arrayOf("image/%", "%Screenshot%")
            FileKind.VIDEO -> "$base AND $m LIKE ?" to arrayOf("video/%")
            FileKind.AUDIO -> "$base AND $m LIKE ?" to arrayOf("audio/%")
            FileKind.DOCUMENT -> "$base AND $m IN (${DOC_MIMES.joinToString(",") { "?" }})" to DOC_MIMES.toTypedArray()
            FileKind.ANY -> "$base AND $m IS NOT NULL" to emptyArray()
        }
    }

    private fun score(fileName: String, keywords: List<String>): Double {
        val base = Text.fold(fileName.substringBeforeLast('.'))
        val words = base.split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return 0.0
        return keywords.map { k ->
            val fk = Text.fold(k)
            when {
                fk.isEmpty() -> 1.0
                base.contains(fk) -> 1.0
                else -> words.maxOf { w -> max(Text.sim(fk, w), Text.sim(Text.skeleton(fk), Text.skeleton(w)) * 0.9) }
            }
        }.average()
    }

    fun find(ctx: Context, q: FileQuery): FoundFile? {
        if (!hasAccess()) return null
        val (sel, args) = selection(q.kind)
        val query = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Files.FileColumns.DATE_MODIFIED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, 4000)
        }
        @Suppress("DEPRECATION")
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE
        )
        var best: FoundFile? = null
        var bestScore = 0.0
        ctx.contentResolver.query(MediaStore.Files.getContentUri("external"), projection, query, null)?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(0) ?: continue
                if ("/Android/data/" in path || "/Android/obb/" in path || "/." in path) continue
                val name = c.getString(1) ?: File(path).name
                val mime = c.getString(2) ?: "application/octet-stream"
                if (q.keywords.isEmpty()) {
                    if (File(path).exists()) return FoundFile(path, name, mime)
                    continue
                }
                val s = score(name, q.keywords)
                if (s > bestScore && File(path).exists()) {
                    bestScore = s
                    best = FoundFile(path, name, mime)
                    if (s >= 0.999) break     // newest perfect match
                }
            }
        }
        return if (bestScore >= 0.7) best else null
    }

    /** A short way to say the file out loud ("IMG_2026…jpg" is not worth reading). */
    fun spokenName(f: FoundFile, ctx: Context): String {
        val n = f.name.substringBeforeLast('.')
        val generated = Regex("^(img|vid|pxl|screenshot|doc|aud|ptt|wa)[-_ ]?\\d", RegexOption.IGNORE_CASE).containsMatchIn(n) ||
            n.count { it.isDigit() } > n.length / 2
        if (!generated) return n.replace('_', ' ').take(40)
        return when {
            f.mime.startsWith("image/") -> ctx.tr("the photo", "फोटो")
            f.mime.startsWith("video/") -> ctx.tr("the video", "व्हिडिओ")
            f.mime == "application/pdf" -> ctx.tr("the PDF", "पीडीएफ")
            else -> ctx.tr("the file", "फाईल")
        }
    }
}
