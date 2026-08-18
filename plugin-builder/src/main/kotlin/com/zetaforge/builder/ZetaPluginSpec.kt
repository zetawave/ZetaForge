package com.zetaforge.builder

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import java.io.Serializable

/**
 * One Android permission the plugin needs, with everything the Host requires to
 * ask for it well: a human reason, whether it is optional, and the API range it
 * applies to.
 */
data class PermissionDeclaration(
    val name: String,
    val reason: String = "",
    val optional: Boolean = false,
    val minSdk: Int = 1,
    val maxSdk: Int = Int.MAX_VALUE,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

/**
 * A capability Android grants only through a dedicated Settings screen
 * (all files access, display over other apps, exact alarms, ...).
 *
 * Use the ids of `com.zetaforge.runtime.permission.SpecialAccess`:
 * `allFilesAccess`, `displayOverOtherApps`, `exactAlarms`, `usageAccess`,
 * `notificationAccess`, `ignoreBatteryOptimizations`, `installPackages`,
 * `writeSettings`.
 */
data class SpecialAccessDeclaration(
    val id: String,
    val reason: String = "",
    val optional: Boolean = false,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

/** Mutable builder used by the `permission { }` / `specialAccess { }` DSL blocks. */
open class PermissionSpec {
    var reason: String = ""
    var optional: Boolean = false
    var minSdk: Int = 1
    var maxSdk: Int = Int.MAX_VALUE
}

/**
 * DSL describing the plugin metadata that ends up inside `manifest.json` of the
 * produced `.zeta` archive.
 *
 * ```
 * zetaPlugin {
 *     pluginId.set("com.example.myplugin")
 *     displayName.set("My Plugin")
 *     author.set("Jane Doe")
 *     version.set("1.0.0")
 *     entryPoint.set("com.example.myplugin.MyPlugin")
 *
 *     permission("android.permission.READ_MEDIA_IMAGES") {
 *         reason = "Reads your photos to build the backup index"
 *     }
 *     specialAccess("allFilesAccess") { reason = "Writes the archive anywhere you choose" }
 * }
 * ```
 */
abstract class ZetaPluginSpec {

    /** Unique, reverse-DNS plugin identifier. Also used as installation directory name. */
    abstract val pluginId: Property<String>

    /** Human readable name shown in the Host UI. */
    abstract val displayName: Property<String>

    /** Semantic version of the plugin itself, shown next to the name. */
    abstract val version: Property<String>

    /** Short description shown in the plugin details screen. */
    abstract val description: Property<String>

    /** Author or vendor, shown in the Host UI. */
    abstract val author: Property<String>

    /** Optional project/support URL. */
    abstract val homepage: Property<String>

    /** Optional licence identifier, e.g. `Apache-2.0`. */
    abstract val license: Property<String>

    /** Fully qualified name of the class implementing `com.zetaforge.sdk.ZetaPlugin`. */
    abstract val entryPoint: Property<String>

    /** Lowest Host API version this plugin can run on. */
    abstract val minHostApi: Property<Int>

    /** Highest Host API version this plugin was tested against. */
    abstract val maxHostApi: Property<Int>

    /** Plain permission names (kept for the simple case and for v1 compatibility). */
    abstract val permissions: ListProperty<String>

    /** Permissions declared with reason/optionality/API range. */
    abstract val declaredPermissions: ListProperty<PermissionDeclaration>

    /** Special accesses the plugin needs the user to enable in Settings. */
    abstract val specialAccess: ListProperty<SpecialAccessDeclaration>

    /** Forward-looking: named capabilities the plugin requests from the Host. */
    abstract val capabilities: ListProperty<String>

    /**
     * Source files shipped inside the package so the user can read, in the app,
     * exactly what the plugin does before running it. Defaults to the module's
     * Kotlin and Java sources.
     */
    abstract val sourceFiles: ConfigurableFileCollection

    /** Root the source paths are made relative to (defaults to the module dir). */
    abstract val sourceRoot: Property<String>

    /** Base name of the produced artifact, without the `.zeta` extension. */
    abstract val archiveBaseName: Property<String>

    init {
        minHostApi.convention(1)
        maxHostApi.convention(1)
        version.convention("0.1.0")
        description.convention("")
        author.convention("")
        homepage.convention("")
        license.convention("")
        permissions.convention(emptyList())
        declaredPermissions.convention(emptyList())
        specialAccess.convention(emptyList())
        capabilities.convention(emptyList())
    }

    /** Declares a permission with a reason the user will actually read. */
    @JvmOverloads
    fun permission(name: String, configure: Action<PermissionSpec>? = null) {
        val spec = PermissionSpec().also { configure?.execute(it) }
        declaredPermissions.add(
            PermissionDeclaration(
                name = name,
                reason = spec.reason,
                optional = spec.optional,
                minSdk = spec.minSdk,
                maxSdk = spec.maxSdk,
            )
        )
    }

    /** Declares a Settings-granted capability, by [SpecialAccessDeclaration] id. */
    @JvmOverloads
    fun specialAccess(id: String, configure: Action<PermissionSpec>? = null) {
        val spec = PermissionSpec().also { configure?.execute(it) }
        specialAccess.add(SpecialAccessDeclaration(id, spec.reason, spec.optional))
    }

    internal fun applyProjectDefaults(project: Project) {
        displayName.convention(project.name)
        archiveBaseName.convention(project.name)
        sourceRoot.convention(project.projectDir.absolutePath)
        sourceFiles.from(
            project.fileTree(project.projectDir).matching {
                include("src/main/kotlin/**/*.kt", "src/main/java/**/*.java")
            }
        )
    }
}
