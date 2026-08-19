package com.zetaforge.builder

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import java.io.File

/**
 * Injects the permission superset from `zetaforge.permissions` into the Host's
 * merged manifest.
 *
 * Why this exists: Android freezes an app's permissions at install time from the
 * manifest of the APK. A plugin therefore cannot obtain anything the Host does
 * not already declare. Rather than making the developer maintain a wall of XML,
 * the list lives in one plain text file and the build merges it in.
 *
 * Declaring is not granting: the runtime asks the user for a permission only
 * when a plugin actually declares it in its own `.zeta` manifest.
 */
class HostPermissionsPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.plugins.withId("com.android.application") {
            val androidComponents =
                project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)

            androidComponents.onVariants { variant ->
                val task = project.tasks.register<InjectHostPermissionsTask>(
                    "inject" + variant.name.replaceFirstChar { it.uppercase() } + "HostPermissions"
                ) {
                    group = "zetaforge"
                    description = "Adds the permissions listed in zetaforge.permissions to the merged manifest."
                    permissionList.set(project.rootProject.file(PERMISSIONS_FILE))
                }

                variant.artifacts.use(task)
                    .wiredWithFiles(
                        InjectHostPermissionsTask::mergedManifest,
                        InjectHostPermissionsTask::updatedManifest,
                    )
                    .toTransform(SingleArtifact.MERGED_MANIFEST)
            }
        }
    }

    private companion object {
        const val PERMISSIONS_FILE = "zetaforge.permissions"
    }
}

/** Rewrites a merged manifest with the extra `<uses-permission>` entries. */
abstract class InjectHostPermissionsTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mergedManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val permissionList: RegularFileProperty

    @get:OutputFile
    abstract val updatedManifest: RegularFileProperty

    @TaskAction
    fun inject() {
        val listFile = permissionList.get().asFile
        if (!listFile.isFile) {
            throw GradleException("Permission list not found: " + listFile.absolutePath)
        }

        val declarations = parse(listFile)
        val manifest = mergedManifest.get().asFile.readText()

        val alreadyPresent = PERMISSION_REGEX.findAll(manifest).map { it.groupValues[1] }.toSet()
        val missing = declarations.filterNot { alreadyPresent.contains(it.name) }

        val output = if (missing.isEmpty()) {
            manifest
        } else {
            val block = missing.joinToString(separator = "\n", postfix = "\n") { it.toXml() }
            val insertAt = manifest.lastIndexOf("</manifest>")
            if (insertAt < 0) throw GradleException("Merged manifest has no </manifest> element.")
            manifest.substring(0, insertAt) + block + manifest.substring(insertAt)
        }

        updatedManifest.get().asFile.writeText(output)
        logger.lifecycle(
            "ZetaForge: " + declarations.size + " permission(s) declared for plugins (" +
                missing.size + " injected, " + (declarations.size - missing.size) + " already present)"
        )
    }

    private fun parse(file: File): List<Declaration> = file.readLines()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val parts = line.split(Regex("\\s+"))
            val name = parts.first()
            if (!name.contains('.')) {
                throw GradleException("Invalid permission '" + name + "' in " + file.name)
            }
            val maxSdk = parts.drop(1)
                .firstOrNull { it.startsWith("maxSdkVersion=") }
                ?.substringAfter('=')
                ?.toIntOrNull()
            Declaration(name, maxSdk)
        }
        .distinctBy { it.name }

    private data class Declaration(val name: String, val maxSdkVersion: Int?) {
        fun toXml(): String = buildString {
            append("    <uses-permission android:name=\"").append(name).append('"')
            if (maxSdkVersion != null) {
                append(" android:maxSdkVersion=\"").append(maxSdkVersion).append('"')
            }
            append(" />")
        }
    }

    private companion object {
        val PERMISSION_REGEX = Regex("<uses-permission[^>]*android:name=\"([^\"]+)\"")
    }
}
