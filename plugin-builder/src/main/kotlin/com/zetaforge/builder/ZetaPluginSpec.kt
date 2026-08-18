package com.zetaforge.builder

import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * DSL describing the plugin metadata that ends up inside `manifest.json` of the
 * produced `.zeta` archive.
 *
 * ```
 * zetaPlugin {
 *     pluginId.set("com.zetaforge.plugins.retrofitdemo")
 *     displayName.set("Retrofit Demo")
 *     entryPoint.set("com.zetaforge.plugins.retrofitdemo.RetrofitDemoPlugin")
 * }
 * ```
 */
abstract class ZetaPluginSpec {

    /** Unique, reverse-DNS plugin identifier. Also used as installation directory name. */
    abstract val pluginId: Property<String>

    /** Human readable name shown in the Host UI. */
    abstract val displayName: Property<String>

    /** Semantic version of the plugin itself. */
    abstract val version: Property<String>

    /** Short description shown in the plugin details screen. */
    abstract val description: Property<String>

    /** Free form author / vendor string. */
    abstract val author: Property<String>

    /** Fully qualified name of the class implementing `com.zetaforge.sdk.ZetaPlugin`. */
    abstract val entryPoint: Property<String>

    /** Lowest Host API version this plugin can run on. */
    abstract val minHostApi: Property<Int>

    /** Highest Host API version this plugin was tested against. */
    abstract val maxHostApi: Property<Int>

    /** Android permissions the plugin needs the Host to hold. */
    abstract val permissions: ListProperty<String>

    /** Forward-looking: named capabilities the plugin requests from the Host. */
    abstract val capabilities: ListProperty<String>

    /** Base name of the produced artifact, without the `.zeta` extension. */
    abstract val archiveBaseName: Property<String>

    init {
        minHostApi.convention(1)
        maxHostApi.convention(1)
        version.convention("0.1.0")
        description.convention("")
        author.convention("")
        permissions.convention(emptyList())
        capabilities.convention(emptyList())
    }

    internal fun applyProjectDefaults(project: Project) {
        displayName.convention(project.name)
        archiveBaseName.convention(project.name)
    }
}
