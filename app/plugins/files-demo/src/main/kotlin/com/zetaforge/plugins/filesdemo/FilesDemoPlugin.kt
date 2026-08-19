package com.zetaforge.plugins.filesdemo

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.zetaforge.sdk.PluginResult
import com.zetaforge.sdk.ZetaLog
import com.zetaforge.sdk.ZetaPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reads the media library and writes a report, using nothing but the Android
 * framework and the Host's `Context`.
 *
 * The point of this plugin is the permission path: `READ_MEDIA_IMAGES` (or
 * `READ_EXTERNAL_STORAGE` on API <= 32) is a run-time permission, so the Host
 * must ask the user for it before this code can do anything. When the permission
 * is missing the plugin never even starts - the runtime blocks it and reports
 * `PERMISSION_DENIED`.
 *
 * Supported [Bundle] inputs:
 * | key       | type | meaning                                    |
 * |-----------|------|--------------------------------------------|
 * | `limit`   | Int  | how many recent items to list (default 10) |
 * | `report`  | Bool | write the report file (default true)       |
 */
class FilesDemoPlugin : ZetaPlugin {

    override val id: String = "com.zetaforge.plugins.filesdemo"
    override val name: String = "Files Demo"
    override val version: String = "1.0.0"

    override suspend fun execute(context: Context, input: Bundle): PluginResult {
        val startedAt = System.currentTimeMillis()
        ZetaLog.info(id, TAG, "START")

        val limit = input.getInt(KEY_LIMIT, DEFAULT_LIMIT).coerceIn(1, 200)
        val writeReport = input.getBoolean(KEY_REPORT, true)

        return withContext(Dispatchers.IO) {
            val images = queryImages(context, limit)
            ZetaLog.info(id, TAG, "MediaStore: " + images.total + " image(s) visible")

            val reportFile = if (writeReport) writeReport(context, images) else null
            reportFile?.let { ZetaLog.info(id, TAG, "Report written to " + it.absolutePath) }

            val duration = System.currentTimeMillis() - startedAt
            ZetaLog.info(id, TAG, "SUCCESS")

            PluginResult.Success(
                message = "Indexed " + images.total + " image(s) in " + duration + " ms",
                durationMs = duration,
                data = buildMap {
                    put("imageCount", images.total.toString())
                    put("totalBytes", images.totalBytes.toString())
                    put("newest", images.newest.orEmpty().ifEmpty { "-" })
                    put("sample", images.names.take(5).joinToString().ifEmpty { "-" })
                    put("hostPackage", context.packageName)
                    put("hostFilesDir", context.filesDir.absolutePath)
                    put("contentResolver", context.contentResolver.javaClass.name)
                    reportFile?.let { put("report", it.absolutePath) }
                },
            )
        }
    }

    private fun queryImages(context: Context, limit: Int): ImageIndex {
        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val names = mutableListOf<String>()
        var total = 0
        var totalBytes = 0L
        var newest: String? = null

        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            MediaStore.Images.Media.DATE_ADDED + " DESC",
        )?.use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                total++
                totalBytes += cursor.getLong(sizeColumn)
                if (names.size < limit) names += cursor.getString(nameColumn).orEmpty()
                if (newest == null) {
                    newest = DATE_FORMAT.format(Date(cursor.getLong(dateColumn) * 1000L))
                }
            }
        } ?: ZetaLog.warn(id, TAG, "MediaStore query returned no cursor")

        return ImageIndex(total, totalBytes, names, newest)
    }

    private fun writeReport(context: Context, index: ImageIndex): File {
        val dir = File(context.filesDir, "files-demo").apply { mkdirs() }
        val file = File(dir, "media-report.txt")
        file.writeText(
            buildString {
                appendLine("ZetaForge - Files Demo report")
                appendLine("generated: " + DATE_FORMAT.format(Date()))
                appendLine("host package: " + context.packageName)
                appendLine("images: " + index.total)
                appendLine("total bytes: " + index.totalBytes)
                appendLine("newest: " + (index.newest ?: "-"))
                appendLine()
                index.names.forEach { appendLine("  " + it) }
            }
        )
        return file
    }

    private data class ImageIndex(
        val total: Int,
        val totalBytes: Long,
        val names: List<String>,
        val newest: String?,
    )

    private companion object {
        const val TAG = "FilesDemo"
        const val KEY_LIMIT = "limit"
        const val KEY_REPORT = "report"
        const val DEFAULT_LIMIT = 10
        val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    }
}
