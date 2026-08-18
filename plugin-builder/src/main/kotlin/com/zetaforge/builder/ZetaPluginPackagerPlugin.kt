package com.zetaforge.builder

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

/**
 * Gradle plugin that adds `buildZetaPlugin` to an Android module, producing a
 * single distributable `.zeta` archive from the module's DEX output.
 *
 * The plugin module is a normal `com.android.application` module: that is how we
 * get the real Android build pipeline (Kotlin -> class files -> D8 -> DEX,
 * including desugaring and the plugin's own external dependencies). The APK
 * itself is never installed; it is only the container we harvest DEX from.
 */
class ZetaPluginPackagerPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val spec = project.extensions.create("zetaPlugin", ZetaPluginSpec::class.java)
        spec.applyProjectDefaults(project)

        var wired = false
        project.plugins.withId("com.android.application") {
            wired = true
            val androidComponents =
                project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)

            androidComponents.onVariants(
                androidComponents.selector().withBuildType(PACKAGING_BUILD_TYPE)
            ) { variant ->
                val apkDir = variant.artifacts.get(SingleArtifact.APK)

                project.tasks.register<BuildZetaPluginTask>(TASK_NAME) {
                    group = "zetaforge"
                    description =
                        "Packages this module into a single .zeta plugin archive."

                    apkDirectory.set(apkDir)
                    pluginId.set(spec.pluginId)
                    displayName.set(spec.displayName)
                    version.set(spec.version)
                    pluginDescription.set(spec.description)
                    author.set(spec.author)
                    entryPoint.set(spec.entryPoint)
                    minHostApi.set(spec.minHostApi)
                    maxHostApi.set(spec.maxHostApi)
                    permissions.set(spec.permissions)
                    declaredPermissions.set(spec.declaredPermissions)
                    specialAccess.set(spec.specialAccess)
                    homepage.set(spec.homepage)
                    license.set(spec.license)
                    sourceRoot.set(spec.sourceRoot)
                    sourceFiles.from(spec.sourceFiles)
                    capabilities.set(spec.capabilities)
                    manifestFormatVersion.convention(2)
                    minSdk.convention(26)
                    bundledDependencies.convention(emptyList())
                    hostProvidedDependencies.convention(emptyList())
                    outputFile.convention(
                        project.layout.buildDirectory
                            .dir(OUTPUT_DIR)
                            .map { dir -> dir.file(spec.archiveBaseName.get() + ".zeta") }
                    )
                }
            }
        }

        project.afterEvaluate {
            if (!wired) {
                throw GradleException(
                    "com.zetaforge.zeta-plugin must be applied to a module that also " +
                        "applies com.android.application."
                )
            }
        }
    }

    private companion object {
        const val TASK_NAME = "buildZetaPlugin"
        const val OUTPUT_DIR = "zetaforge"

        /**
         * Release is used on purpose: it produces an unsigned APK (no debug
         * keystore needed) and it is the variant whose DEX we ship.
         */
        const val PACKAGING_BUILD_TYPE = "release"
    }
}
