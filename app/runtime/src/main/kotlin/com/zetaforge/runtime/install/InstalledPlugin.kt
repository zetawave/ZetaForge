package com.zetaforge.runtime.install

import com.zetaforge.runtime.manifest.ZetaManifest
import com.zetaforge.sdk.PluginState
import org.json.JSONObject
import java.io.File

/**
 * A plugin that lives in app-private storage.
 *
 * This is the Host-side record: it describes what was installed and where, and
 * is persisted as `metadata/install.json` so installations survive process death
 * without re-reading every archive.
 */
data class InstalledPlugin(
    val manifest: ZetaManifest,
    /** SHA-256 of the archive recorded at install time. */
    val sha256: String,
    val installedAtEpochMs: Long,
    val packageFile: File,
    val codeJar: File,
    val sizeBytes: Long,
    val state: PluginState = PluginState.INSTALLED,
    /** Result summary of the last execution, if any. */
    val lastRun: LastRun? = null,
) {
    val id: String get() = manifest.pluginId
    val displayName: String get() = manifest.name
    val version: String get() = manifest.version

    data class LastRun(
        val successful: Boolean,
        val message: String,
        val durationMs: Long,
        val atEpochMs: Long,
    )
}

/** Serialises [InstalledPlugin] records to disk (JSON, versioned by the manifest). */
internal object InstallRecordStore {

    private const val RECORD_VERSION = 1

    fun write(file: File, manifestJson: String, sha256: String, installedAt: Long, size: Long) {
        file.parentFile?.mkdirs()
        val json = JSONObject().apply {
            put("recordVersion", RECORD_VERSION)
            put("sha256", sha256)
            put("installedAt", installedAt)
            put("sizeBytes", size)
            put("manifest", JSONObject(manifestJson))
        }
        file.writeText(json.toString(2))
    }

    fun read(file: File): Record? {
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            Record(
                sha256 = json.optString("sha256", ""),
                installedAt = json.optLong("installedAt", 0L),
                sizeBytes = json.optLong("sizeBytes", 0L),
                manifestJson = json.getJSONObject("manifest").toString(),
            )
        }.getOrNull()
    }

    data class Record(
        val sha256: String,
        val installedAt: Long,
        val sizeBytes: Long,
        val manifestJson: String,
    )
}
