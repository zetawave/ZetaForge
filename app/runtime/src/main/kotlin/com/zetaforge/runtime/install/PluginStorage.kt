package com.zetaforge.runtime.install

import android.content.Context
import java.io.File

/**
 * Owns every path the runtime touches.
 *
 * Plugin code is only ever executed from app-private storage
 * (`filesDir/zetaforge/plugins/<pluginId>/`): never from the location the user
 * picked, which is outside our control and potentially world-writable.
 *
 * ```
 * files/
 * +-- zetaforge/
 *     |-- plugins/
 *     |   +-- com.zetaforge.plugins.retrofitdemo/
 *     |       |-- current.zeta        (the imported archive, kept for re-verification)
 *     |       |-- extracted/code.jar  (DEX container handed to the class loader)
 *     |       |-- oat/                (ART optimised output, private to the plugin)
 *     |       +-- metadata/install.json
 *     |-- logs/
 *     +-- cache/
 * ```
 */
class PluginStorage(private val context: Context) {

    val root: File = File(context.filesDir, ROOT_DIR)
    val pluginsDir: File = File(root, "plugins")
    val logsDir: File = File(root, "logs")
    val cacheDir: File = File(context.cacheDir, ROOT_DIR)

    init {
        listOf(root, pluginsDir, logsDir, cacheDir).forEach { it.mkdirs() }
    }

    fun pluginDir(pluginId: String): File = File(pluginsDir, sanitize(pluginId))

    fun packageFile(pluginId: String): File = File(pluginDir(pluginId), "current.zeta")

    fun extractedDir(pluginId: String): File = File(pluginDir(pluginId), "extracted")

    fun codeJar(pluginId: String): File = File(extractedDir(pluginId), "code.jar")

    /** Private ART output directory; never shared between plugins. */
    fun oatDir(pluginId: String): File = File(pluginDir(pluginId), "oat")

    fun metadataDir(pluginId: String): File = File(pluginDir(pluginId), "metadata")

    fun installRecordFile(pluginId: String): File = File(metadataDir(pluginId), "install.json")

    /** Staging area for an archive being imported, before it is trusted. */
    fun newStagingFile(): File = File(cacheDir, "import-" + System.nanoTime() + ".zeta")

    fun installedPluginDirs(): List<File> =
        pluginsDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()

    fun delete(pluginId: String): Boolean = pluginDir(pluginId).deleteRecursively()

    /**
     * A plugin id comes from an untrusted archive: reject anything that could
     * escape the plugins directory.
     */
    private fun sanitize(pluginId: String): String {
        require(pluginId.isNotBlank()) { "Empty pluginId" }
        require(!pluginId.contains('/') && !pluginId.contains('\\') && !pluginId.contains("..")) {
            "Illegal pluginId: $pluginId"
        }
        return pluginId
    }

    private companion object {
        const val ROOT_DIR = "zetaforge"
    }
}
