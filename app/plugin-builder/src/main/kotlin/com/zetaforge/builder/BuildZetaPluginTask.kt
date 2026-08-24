package com.zetaforge.builder

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
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
    @get:Input abstract val declaredPermissions: ListProperty<PermissionDeclaration>
    @get:Input abstract val specialAccess: ListProperty<SpecialAccessDeclaration>
    @get:Input abstract val homepage: Property<String>
    @get:Input abstract val license: Property<String>
    @get:Input abstract val sourceRoot: Property<String>

    /** Sources shipped inside the package so the user can read what runs. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection
    @get:Input abstract val capabilities: ListProperty<String>

    /** At most one entry: the screen this plugin offers. */
    @get:Input abstract val ui: ListProperty<UiDeclaration>

    /** Screen contract version this build targets (`ZetaSdk.UI_API_VERSION`). */
    @get:Input abstract val uiApiVersion: Property<Int>

    /**
     * Packages whose classes the plugin must never *define*, only reference.
     *
     * These are the types that cross the Host/plugin boundary, so there has to
     * be exactly one copy of each: the Host's. A bundled second copy is not a
     * warning, it is a plugin that loads and then fails with a ClassCastException
     * from somewhere unreadable - so the build stops here instead.
     */
    @get:Input abstract val boundaryPackages: ListProperty<String>
    @get:Input abstract val settings: ListProperty<SettingDeclaration>
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

        verifyBoundary(dexFiles)

        val entry = entryPoint.get()
        val entryHolder = dexFiles.firstOrNull { DexReader.containsType(it, entry) }
            ?: throw GradleException(
                "Entry point " + entry + " was not found in any DEX of the plugin. " +
                    "Check the class name or whether it was stripped by shrinking."
            )

        val out = outputFile.get().asFile
        out.parentFile.mkdirs()
        out.delete()

        val sources = collectSources()
        val manifest = buildManifest(dexFiles, sources)
        writeArchive(out, manifest, dexFiles, sources)

        val zetaSha = out.sha256()
        File(out.parentFile, out.name + ".sha256").writeText(zetaSha + "  " + out.name + "\n")

        report(out, dexFiles, entryHolder, zetaSha)
    }

    /**
     * Fails the build if the plugin compiled a boundary class into its own DEX.
     *
     * The cause is always the same and always mechanical: a dependency declared
     * `implementation` where it had to be `compileOnly`. The message therefore
     * names the offending classes and the fix, because "ClassCastException:
     * androidx.compose.runtime.Composer cannot be cast to
     * androidx.compose.runtime.Composer" is what the developer sees otherwise.
     */
    private fun verifyBoundary(dexFiles: List<File>) {
        val forbidden = boundaryPackages.get()
        if (forbidden.isEmpty()) return
        val offenders = dexFiles.flatMap { DexReader.definedTypesIn(it, forbidden) }
        if (offenders.isEmpty()) return
        throw GradleException(
            "This plugin bundles " + offenders.size + " class(es) the Host must own: " +
                offenders.take(8).joinToString() + (if (offenders.size > 8) ", ..." else "") +
                ". They belong to the shared boundary (" + forbidden.joinToString() + "), so " +
                "the Host and the plugin have to resolve them to the same objects. " +
                "Declare those dependencies as compileOnly in this module's build file."
        )
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

    /** Maps each shipped source file to its path inside the archive. */
    private fun collectSources(): Map<String, File> {
        val root = File(sourceRoot.get())
        return sourceFiles.files
            .filter { it.isFile }
            .sortedBy { it.absolutePath }
            .associateBy { file ->
                val relative = runCatching { file.relativeTo(root).path }.getOrDefault(file.name)
                "source/" + relative.replace(File.separatorChar, '/')
            }
    }

    private fun buildManifest(dexFiles: List<File>, sources: Map<String, File>): JsonObject = JsonObject().apply {
        addProperty("formatVersion", manifestFormatVersion.get())
        addProperty("pluginId", pluginId.get())
        addProperty("name", displayName.get())
        addProperty("version", version.get())
        addProperty("description", pluginDescription.get())
        addProperty("author", author.get())
        addProperty("homepage", homepage.get())
        addProperty("license", license.get())
        addProperty("entryPoint", entryPoint.get())
        addProperty("minHostApi", minHostApi.get())
        addProperty("maxHostApi", maxHostApi.get())
        addProperty("minSdk", minSdk.get())
        add("permissions", buildPermissions())
        add("specialAccess", buildSpecialAccess())
        add("capabilities", buildCapabilities().toJsonArray())
        buildUi()?.let { add("ui", it) }
        add("settings", buildSettings())
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
            add("source", JsonArray().apply {
                sources.forEach { (path, file) ->
                    add(JsonObject().apply {
                        addProperty("path", path)
                        addProperty("displayName", path.removePrefix("source/"))
                        addProperty("language", if (file.extension == "java") "java" else "kotlin")
                        addProperty("size", file.length())
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

    /**
     * The `ui` block, or null when this plugin has no screen.
     *
     * Absent rather than `"enabled": false`, so a package with a screen and a
     * package built before screens existed are told apart by presence alone -
     * which is what every already published `.zeta` relies on.
     */
    private fun buildUi(): JsonObject? {
        val declaration = ui.get().firstOrNull() ?: return null
        return JsonObject().apply {
            addProperty("enabled", true)
            addProperty("uiApi", uiApiVersion.get())
            addProperty("only", declaration.only)
            if (declaration.label.isNotBlank()) addProperty("label", declaration.label)
        }
    }

    /**
     * `capabilities` gains "ui" automatically when a screen is declared, so the
     * two never disagree and nobody has to remember to write both.
     */
    private fun buildCapabilities(): List<String> {
        val declared = capabilities.get()
        if (ui.get().isEmpty() || declared.contains("ui")) return declared
        return declared + "ui"
    }

    /**
     * Permissions are emitted in the v2 object form so the Host can show the
     * reason to the user; plain names declared through `permissions` are folded
     * in with an empty reason.
     */
    private fun buildPermissions(): JsonArray {
        val declared = declaredPermissions.get()
        val plain = permissions.get()
            .filterNot { name -> declared.any { it.name == name } }
            .map { PermissionDeclaration(it) }
        return JsonArray().apply {
            (declared + plain).forEach { permission ->
                add(JsonObject().apply {
                    addProperty("name", permission.name)
                    addProperty("reason", permission.reason)
                    addProperty("optional", permission.optional)
                    addProperty("minSdk", permission.minSdk)
                    if (permission.maxSdk != Int.MAX_VALUE) addProperty("maxSdk", permission.maxSdk)
                })
            }
        }
    }

    /** The schema the Host renders; see SettingsParser on the runtime side. */
    private fun buildSettings(): JsonArray = JsonArray().apply {
        settings.get().forEach { setting ->
            add(JsonObject().apply {
                addProperty("type", setting.type)
                addProperty("key", setting.key)
                addProperty("label", setting.label)
                if (setting.description.isNotBlank()) addProperty("description", setting.description)
                if (setting.group.isNotBlank()) addProperty("group", setting.group)
                if (setting.advanced) addProperty("advanced", true)
                if (setting.unit.isNotBlank()) addProperty("unit", setting.unit)
                if (setting.hint.isNotBlank()) addProperty("hint", setting.hint)
                if (setting.secret) addProperty("secret", true)
                if (setting.runningLabel.isNotBlank()) addProperty("runningLabel", setting.runningLabel)
                setting.min?.let { addProperty("min", it) }
                setting.max?.let { addProperty("max", it) }
                setting.step?.let { addProperty("step", it) }

                when (setting.type) {
                    "switch" -> addProperty("default", setting.defaultValue.toBoolean())
                    "number" -> addProperty("default", setting.defaultValue?.toLongOrNull() ?: 0L)
                    "decimal" -> addProperty("default", setting.defaultValue?.toDoubleOrNull() ?: 0.0)
                    "multichoice" -> add(
                        "default",
                        JsonArray().apply {
                            setting.defaultValue.orEmpty()
                                .split(',')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .forEach { add(it) }
                        },
                    )

                    "action" -> Unit
                    else -> setting.defaultValue?.let { addProperty("default", it) }
                }

                if (setting.options.isNotEmpty()) {
                    add("options", JsonArray().apply {
                        setting.options.forEachIndexed { index, value ->
                            add(JsonObject().apply {
                                addProperty("value", value)
                                addProperty("label", setting.optionLabels.getOrElse(index) { value })
                            })
                        }
                    })
                }
            })
        }
    }

    private fun buildSpecialAccess(): JsonArray = JsonArray().apply {
        specialAccess.get().forEach { access ->
            add(JsonObject().apply {
                addProperty("id", access.id)
                addProperty("reason", access.reason)
                addProperty("optional", access.optional)
            })
        }
    }

    private fun writeArchive(out: File, manifest: JsonObject, dexFiles: List<File>, sources: Map<String, File>) {
        val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putText("manifest.json", gson.toJson(manifest) + "\n")
            dexFiles.forEach { dex -> zip.putFile("dex/" + dex.name, dex.readBytes()) }
            // Sources travel with the package so the Host can show the user what
            // the code actually does before they run it.
            sources.forEach { (path, file) -> zip.putFile(path, file.readBytes()) }
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

    private fun permissionSummary(): String {
        val names = (declaredPermissions.get().map { it.name } + permissions.get()).distinct()
        return names.joinToString { it.substringAfterLast('.') }.ifEmpty { "none" }
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
                "\n  permissions      : " + permissionSummary() +
                "\n  settings         : " + settings.get().joinToString { it.key }.ifEmpty { "none" } +
                "\n  screen           : " + (ui.get().firstOrNull()?.let {
                    "yes (uiApi " + uiApiVersion.get() + (if (it.only) ", screen-only)" else ")")
                } ?: "no") +
                "\n  special access   : " + specialAccess.get().joinToString { it.id }.ifEmpty { "none" } +
                "\n  source files     : " + sourceFiles.files.count { it.isFile } +
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
