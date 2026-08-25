package com.zetaforge.app.share

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.zetaforge.runtime.install.InstalledPlugin
import java.io.File

/**
 * Getting an installed plugin back out of the Host, as the `.zeta` it came from.
 *
 * A package is installed once and then only ever read from the app's private
 * storage, where nothing else on the device can reach it. Both ways out below
 * therefore start with a copy: sharing stages one in the cache and lends it to
 * the receiving app, exporting writes one into Downloads and forgets about it.
 *
 * The file on disk is always called `current.zeta`, which says nothing once it
 * leaves. Every copy is renamed to `<id>-<version>.zeta` first.
 */
object PluginPackages {

    /** MIME type: `.zeta` has no registered one, so the generic binary type it is. */
    const val MIME_TYPE = "application/octet-stream"

    private const val STAGING_DIR = "shared-packages"

    fun fileName(plugin: InstalledPlugin): String =
        plugin.id + "-" + plugin.version + ".zeta"

    /**
     * An [Intent] that offers the package to another app.
     *
     * The staged copy replaces any earlier one for the same plugin instead of
     * piling up: the cache is not a place to accumulate megabytes of packages
     * the user shared once.
     */
    fun shareIntent(context: Context, plugin: InstalledPlugin): Intent {
        val staged = File(context.cacheDir, STAGING_DIR).apply { mkdirs() }
            .resolve(fileName(plugin))
        plugin.packageFile.copyTo(staged, overwrite = true)

        val uri = FileProvider.getUriForFile(context, context.packageName + ".packages", staged)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, fileName(plugin))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            null,
        )
    }

    /**
     * Writes the package into the shared Downloads folder.
     *
     * From API 29 this needs no permission at all; below it, writing there does,
     * so the caller is told to ask the user for a destination instead - see
     * [ExportOutcome.NeedsPicker].
     */
    fun exportToDownloads(context: Context, plugin: InstalledPlugin): ExportOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ExportOutcome.NeedsPicker

        val name = fileName(plugin)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            // Hidden from other apps until the bytes are all there, so nothing
            // can pick up a half-written package.
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        return runCatching {
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return ExportOutcome.Failed("Downloads is not writable")
            try {
                copyInto(context, plugin.packageFile, uri)
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                // MediaStore renames on collision, so the name it actually used
                // is the one worth reporting.
                ExportOutcome.Saved(displayName(context, uri) ?: name)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }.getOrElse { ExportOutcome.Failed(it.message ?: it.javaClass.simpleName) }
    }

    /** Writes the package to a destination the user picked themselves. */
    fun exportTo(context: Context, plugin: InstalledPlugin, destination: Uri): ExportOutcome =
        runCatching {
            copyInto(context, plugin.packageFile, destination)
            ExportOutcome.Saved(displayName(context, destination) ?: fileName(plugin))
        }.getOrElse { ExportOutcome.Failed(it.message ?: it.javaClass.simpleName) }

    private fun copyInto(context: Context, source: File, destination: Uri) {
        val stream = context.contentResolver.openOutputStream(destination)
            ?: error("Cannot open the destination for writing")
        stream.use { out -> source.inputStream().use { it.copyTo(out) } }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    /** What came of an export. */
    sealed interface ExportOutcome {
        /** Written, under this name. */
        data class Saved(val displayName: String) : ExportOutcome

        /** This Android version cannot write to Downloads unasked; let the user choose. */
        data object NeedsPicker : ExportOutcome

        data class Failed(val reason: String) : ExportOutcome
    }
}
