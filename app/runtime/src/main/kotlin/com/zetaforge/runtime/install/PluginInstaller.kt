package com.zetaforge.runtime.install

import com.zetaforge.runtime.log.ZetaLogger
import com.zetaforge.runtime.manifest.ZetaManifest
import com.zetaforge.runtime.pkg.ZetaPackage
import com.zetaforge.runtime.pkg.ZetaPackageException
import com.zetaforge.runtime.pkg.ZetaPackageReader
import com.zetaforge.runtime.pkg.sha256
import com.zetaforge.runtime.verify.PluginVerifier
import com.zetaforge.runtime.verify.VerificationResult
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Result of an import/installation attempt. */
sealed class InstallOutcome {
    data class Success(
        val plugin: InstalledPlugin,
        val verification: VerificationResult,
    ) : InstallOutcome()

    data class Failure(
        val stage: String,
        val reason: String,
        val cause: Throwable? = null,
    ) : InstallOutcome()
}

/**
 * Copies an imported `.zeta` into app-private storage, verifies it and prepares
 * the DEX container the class loader will use.
 *
 * Import pipeline:
 * ```
 * stream -> staging file -> ZIP validation -> manifest validation
 *        -> checksum -> verification -> app-private install dir -> code.jar
 * ```
 * Nothing is executed and no code path touches the user-selected location after
 * the initial copy.
 */
class PluginInstaller(
    private val storage: PluginStorage,
    private val verifierFactory: (ZetaPackage) -> PluginVerifier,
    private val logger: ZetaLogger,
) {

    fun install(source: InputStream, displayHint: String? = null): InstallOutcome {
        val staging = storage.newStagingFile()
        return try {
            logger.info(SOURCE, null, "Import started" + (displayHint?.let { " ($it)" } ?: ""))
            source.use { input -> staging.outputStream().buffered().use { input.copyTo(it) } }
            logger.debug(SOURCE, null, "Copied ${staging.length()} bytes to staging area")
            installFromStaging(staging)
        } catch (e: Exception) {
            logger.error(SOURCE, null, "Import failed while reading the file: ${e.message}", e)
            InstallOutcome.Failure("read", e.message ?: e.javaClass.simpleName, e)
        } finally {
            staging.delete()
        }
    }

    fun install(file: File): InstallOutcome = file.inputStream().use { install(it, file.name) }

    private fun installFromStaging(staging: File): InstallOutcome {
        logger.info(SOURCE, null, "Validation started")
        val pkg = try {
            ZetaPackageReader.read(staging)
        } catch (e: ZetaPackageException) {
            logger.error(SOURCE, null, "Import failed: ${e.message}")
            return InstallOutcome.Failure("validate", e.message ?: "Invalid package", e)
        }

        val manifest = pkg.manifest
        logger.info(SOURCE, manifest.pluginId, "Manifest valid: ${manifest.pluginId} v${manifest.version}")
        logger.info(SOURCE, manifest.pluginId, "DEX found: ${pkg.dexEntries.joinToString()}")
        logger.info(SOURCE, manifest.pluginId, "Checksum calculated: sha256=${pkg.sha256}")

        val verification = verifierFactory(pkg).verify(pkg)
        verification.warnings.forEach { logger.warn(SOURCE, manifest.pluginId, "${it.name}: ${it.detail}") }
        if (!verification.isValid) {
            val reason = verification.failureMessage()
            logger.error(SOURCE, manifest.pluginId, "Import failed: $reason")
            return InstallOutcome.Failure("verify", reason)
        }
        verification.checks.filter { it.passed && !it.warning }
            .forEach { logger.debug(SOURCE, manifest.pluginId, "check ${it.name} OK - ${it.detail}") }

        return try {
            logger.info(SOURCE, manifest.pluginId, "Installing")
            val installed = materialise(staging, pkg)
            logger.info(SOURCE, manifest.pluginId, "Installed in ${installed.packageFile.parentFile?.name}")
            InstallOutcome.Success(installed, verification)
        } catch (e: Exception) {
            logger.error(SOURCE, manifest.pluginId, "Install failed: ${e.message}", e)
            storage.delete(manifest.pluginId)
            InstallOutcome.Failure("install", e.message ?: e.javaClass.simpleName, e)
        }
    }

    /** Moves the verified archive into its private directory and unpacks the DEX. */
    private fun materialise(staging: File, pkg: ZetaPackage): InstalledPlugin {
        val id = pkg.manifest.pluginId
        val dir = storage.pluginDir(id)
        dir.mkdirs()
        storage.extractedDir(id).apply { deleteRecursively(); mkdirs() }
        storage.oatDir(id).apply { deleteRecursively(); mkdirs() }
        storage.metadataDir(id).mkdirs()

        val target = storage.packageFile(id)
        staging.copyTo(target, overwrite = true)

        val codeJar = storage.codeJar(id)
        buildCodeJar(target, pkg, codeJar)

        val manifestJson = readManifestJson(target)
        InstallRecordStore.write(
            file = storage.installRecordFile(id),
            manifestJson = manifestJson,
            sha256 = pkg.sha256,
            installedAt = System.currentTimeMillis(),
            size = target.length(),
        )

        return InstalledPlugin(
            manifest = pkg.manifest,
            sha256 = pkg.sha256,
            installedAtEpochMs = System.currentTimeMillis(),
            packageFile = target,
            codeJar = codeJar,
            sizeBytes = target.length(),
        )
    }

    /**
     * Repacks the plugin DEX into a `code.jar` laid out like an APK
     * (`classes.dex`, `classes2.dex`, ...). That is the container shape
     * `BaseDexClassLoader` is designed for, and it keeps multi-DEX plugins
     * working without any extra handling.
     */
    private fun buildCodeJar(zeta: File, pkg: ZetaPackage, target: File) {
        target.parentFile?.mkdirs()
        ZipFile(zeta).use { zip ->
            ZipOutputStream(target.outputStream().buffered()).use { out ->
                pkg.dexEntries.forEachIndexed { index, entryName ->
                    val entry = zip.getEntry(entryName) ?: error("Missing $entryName")
                    val name = if (index == 0) "classes.dex" else "classes${index + 1}.dex"
                    out.putNextEntry(ZipEntry(name).apply { time = 0 })
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
        target.setReadOnly()
    }

    private fun readManifestJson(zeta: File): String = ZipFile(zeta).use { zip ->
        val entry = zip.getEntry(ZetaPackageReader.MANIFEST_ENTRY) ?: error("manifest.json vanished")
        zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** Rebuilds the list of installed plugins from disk. */
    fun listInstalled(): List<InstalledPlugin> = storage.installedPluginDirs().mapNotNull { dir ->
        val record = InstallRecordStore.read(File(File(dir, "metadata"), "install.json")) ?: return@mapNotNull null
        val manifest = runCatching { ZetaManifest.parse(record.manifestJson) }.getOrNull() ?: return@mapNotNull null
        val packageFile = File(dir, "current.zeta")
        val codeJar = File(File(dir, "extracted"), "code.jar")
        if (!packageFile.isFile || !codeJar.isFile) return@mapNotNull null
        InstalledPlugin(
            manifest = manifest,
            sha256 = record.sha256,
            installedAtEpochMs = record.installedAt,
            packageFile = packageFile,
            codeJar = codeJar,
            sizeBytes = record.sizeBytes,
        )
    }

    /** Re-reads an installed archive and re-verifies it against the stored checksum. */
    fun reverify(plugin: InstalledPlugin, verifier: PluginVerifier): VerificationResult {
        val pkg = ZetaPackageReader.read(plugin.packageFile)
        return verifier.verify(pkg.copy(sha256 = plugin.packageFile.sha256()))
    }

    fun uninstall(pluginId: String): Boolean {
        logger.info(SOURCE, pluginId, "Uninstalling")
        return storage.delete(pluginId)
    }

    private companion object {
        const val SOURCE = "Installer"
    }
}
