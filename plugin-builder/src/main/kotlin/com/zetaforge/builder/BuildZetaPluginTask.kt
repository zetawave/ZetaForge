package com.zetaforge.builder

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Turns the DEX output of a normal Android build into a `.zeta` plugin package.
 *
 * The task deliberately does not re-implement any part of the Android toolchain:
 * the plugin module is a real Android module, so AGP/D8 already produced real,
 * verified DEX. We take that DEX, describe it in a versioned manifest and seal
 * everything into a single archive.
 *
 * Archive layout:
 *
 *     <name>.zeta
 *     |-- manifest.json
 *     |-- dex/classes.dex[, classes2.dex, ...]
 *     |-- libs/          (reserved: native libraries / future artifacts)
 *     |-- assets/        (optional plugin assets)
 *     +-- metadata/build.json
 */
abstract class BuildZetaPluginTask : DefaultTask() {

    /** Directory holding the APK produced by AGP for the plugin module. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    /** Optional directory whose content is copied into `assets/` of the package. */
    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val assetsDirectory: DirectoryProperty

    @get:Input abstract val pluginId: Property<String>
    @get:Input abstract val displayName: Property<String>
    @get:Input abstract val version: Property<String>
    @get:Input abstract val pluginDescription: Property<String>
    @get:Input abstract val author: Property<String>
    @get:Input abstract val entryPoint: Property<String>
    @get:Input abstract val minHostApi: Property<Int>
    @get:Input abstract val maxHostApi: Property<Int>
    @get:Input abstract val permissions: ListProperty<String>
    @get:Input abstract val capabilities: ListProperty<String>
    @get:Input abstract val manifestFormatVersion: Property<Int>
    @get:Input abstract val minSdk: Property<Int>

    /** Coordinates of the libraries bundled into the plugin DEX (documentation only). */
    @get:Input abstract val bundledDependencies: ListProperty<String>

    /** Coordinates the plugin compiled against but expects the Host to provide. */
    @get:Input abstract val hostProvidedDependencies: ListProperty<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun packagePlugin() {
        val apk = findApk()
        val workDir = File(temporaryDir, "dex").apply { deleteRecursively(); mkdirs() }
        val dexFiles = extractDex(apk, workDir)

        if (dexFiles.isEmpty()) {
            throw GradleException("No classes*.dex found inside " + apk.name + ": the plugin has no code.")
        }
        dexFiles.forEach { dex ->
            if (!DexReader.isDex(dex)) {
                throw GradleException(dex.name + " is not a valid DEX file.")
            }
        }

        val entry = entryPoint.get()
        val entryHolder = dexFiles.firstOrNull { DexReader.containsType(it, entry) }
            ?: throw GradleException(
                "Entry point " + entry + " was not found in any DEX of the plugin. " +
                    "Check the class name or whether it was stripped by shrinking."
            )

        val out = outputFile.get().asFile
        out.parentFile.mkdirs()
        out.delete()

        val manifest = buildManifest(dexFiles)
        writeArchive(out, manifest, dexFiles)

        val zetaSha = out.sha256()
        File(out.parentFile, out.name + ".sha256").writeText(zetaSha + "  " + out.name + "\n")

        report(out, dexFiles, entryHolder, zetaSha)
    }

    private fun findApk(): File {
        val dir = apkDirectory.get().asFile
        return dir.walkTopDown().filter { it.isFile && it.extension == "apk" }.firstOrNull()
            ?: throw GradleException("No APK found under " + dir)
    }

    private fun extractDex(apk: File, target: File): List<File> = ZipFile(apk).use { zip ->
        zip.entries().asSequence()
            .filter { !it.isDirectory && Regex("classes\\d*\\.dex").matches(it.name) }
            .sortedWith(compareBy({ it.name.length }, { it.name }))
            .map { entry ->
                val dest = File(target, entry.name)
                zip.getInputStream(entry).use { input -> dest.outputStream().use { input.copyTo(it) } }
                dest
            }
            .toList()
    }

    private fun buildManifest(dexFiles: List<File>): JsonObject = JsonObject().apply {
        addProperty("formatVersion", manifestFormatVersion.get())
        addProperty("pluginId", pluginId.get())
        addProperty("name", displayName.get())
        addProperty("version", version.get())
        addProperty("description", pluginDescription.get())
        addProperty("author", author.get())
        addProperty("entryPoint", entryPoint.get())
        addProperty("minHostApi", minHostApi.get())
        addProperty("maxHostApi", maxHostApi.get())
        addProperty("minSdk", minSdk.get())
        add("permissions", permissions.get().toJsonArray())
        add("capabilities", capabilities.get().toJsonArray())
        add("dependencies", JsonObject().apply {
            add("bundled", bundledDependencies.get().toJsonArray())
            add("hostProvided", hostProvidedDependencies.get().toJsonArray())
        })
        add("code", JsonObject().apply {
            add("dex", JsonArray().apply {
                dexFiles.forEach { dex ->
                    add(JsonObject().apply {
                        addProperty("path", "dex/" + dex.name)
                        addProperty("size", dex.length())
                        addProperty("sha256", dex.sha256())
                        addProperty("dexVersion", DexReader.version(dex))
                    })
                }
            })
        })
        // Reserved for a future SignaturePluginVerifier: the Host already reads
        // this block and simply reports "unsigned" while it is null.
        add("signature", JsonNull.INSTANCE)
        add("display", JsonObject().apply {
            addProperty("category", "demo")
            addProperty("icon", "")
        })
    }

    private fun writeArchive(out: File, manifest: JsonObject, dexFiles: List<File>) {
        val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putText("manifest.json", gson.toJson(manifest) + "\n")
            dexFiles.forEach { dex -> zip.putFile("dex/" + dex.name, dex.readBytes()) }
            zip.putText("libs/.keep", "reserved for native libraries and future artifacts\n")
            zip.putText(
                "metadata/build.json",
                gson.toJson(JsonObject().apply {
                    addProperty("builtAt", Instant.now().toString())
                    addProperty("builder", "zetaforge-plugin-builder")
                    addProperty("gradle", project.gradle.gradleVersion)
                }) + "\n"
            )
            val assets = assetsDirectory.orNull?.asFile
            if (assets != null && assets.isDirectory) {
                assets.walkTopDown().filter { it.isFile }.forEach { file ->
                    val rel = file.relativeTo(assets).path.replace('\\', '/')
                    zip.putFile("assets/" + rel, file.readBytes())
                }
            }
        }
    }

    private fun report(out: File, dexFiles: List<File>, entryHolder: File, sha: String) {
        val strings = DexReader.readStrings(entryHolder)
        val hasRetrofit = strings.any { it.startsWith("Lretrofit2/") }
        val hasOkHttp = strings.any { it.startsWith("Lokhttp3/") }
        val dexSummary = dexFiles.joinToString {
            it.name + " (" + it.length() + " B, dex " + DexReader.version(it) + ")"
        }
        logger.lifecycle(
            "\nZetaForge plugin packaged" +
                "\n  artifact         : " + out.absolutePath +
                "\n  size             : " + out.length() + " bytes" +
                "\n  sha256           : " + sha +
                "\n  pluginId         : " + pluginId.get() +
                "\n  version          : " + version.get() +
                "\n  entryPoint       : " + entryPoint.get() + " (found in " + entryHolder.name + ")" +
                "\n  dex files        : " + dexSummary +
                "\n  retrofit2 in dex : " + hasRetrofit +
                "\n  okhttp3 in dex   : " + hasOkHttp + "\n"
        )
    }
}

private fun List<String>.toJsonArray(): JsonArray = JsonArray().also { arr -> forEach(arr::add) }

internal fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { String.format("%02x", it) }
}

private fun ZipOutputStream.putText(name: String, text: String) = putFile(name, text.toByteArray())

private fun ZipOutputStream.putFile(name: String, bytes: ByteArray) {
    val entry = ZipEntry(name)
    // Deterministic timestamps keep the archive reproducible.
    entry.time = 0
    if (name.endsWith(".dex")) {
        // DEX is stored uncompressed so the Host can map it directly.
        entry.method = ZipEntry.STORED
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        val crc = CRC32()
        crc.update(bytes)
        entry.crc = crc.value
    }
    putNextEntry(entry)
    write(bytes)
    closeEntry()
}
