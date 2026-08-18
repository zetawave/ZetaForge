package com.zetaforge.runtime.pkg

import com.zetaforge.runtime.install.InstalledPlugin
import com.zetaforge.runtime.manifest.SourceEntry
import java.util.zip.ZipFile

/** One source file of a plugin, ready to be displayed. */
data class PluginSourceFile(
    val entry: SourceEntry,
    val content: String,
    val truncated: Boolean,
)

/**
 * Reads the sources shipped inside a `.zeta`.
 *
 * Packages carry their own code so the user can answer "what is this thing going
 * to do?" before running it, without leaving the app and without trusting a
 * description. What is shown comes from the same archive that is executed, so it
 * cannot drift from the shipped binary the way a README can - although it is not
 * a proof that the DEX was compiled from it (that needs reproducible builds and
 * signatures, both future work).
 */
object PluginSourceReader {

    /** Hard cap per file so a pathological package cannot exhaust memory. */
    const val MAX_FILE_BYTES = 512 * 1024

    fun read(plugin: InstalledPlugin): List<PluginSourceFile> {
        val entries = plugin.manifest.source
        if (entries.isEmpty()) return emptyList()

        return ZipFile(plugin.packageFile).use { zip ->
            entries.mapNotNull { entry ->
                val zipEntry = zip.getEntry(entry.path) ?: return@mapNotNull null
                val bytes = zip.getInputStream(zipEntry).use { input ->
                    input.readBytes().let { if (it.size > MAX_FILE_BYTES) it.copyOf(MAX_FILE_BYTES) else it }
                }
                PluginSourceFile(
                    entry = entry,
                    content = bytes.toString(Charsets.UTF_8),
                    truncated = zipEntry.size > MAX_FILE_BYTES,
                )
            }
        }
    }
}
