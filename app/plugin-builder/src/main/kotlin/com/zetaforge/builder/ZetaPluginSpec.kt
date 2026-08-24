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

/**
 * One configurable parameter, as declared in the plugin's build file and shipped
 * in `manifest.json`. The Host turns these into a form, so the plugin never
 * draws UI and the Host never learns what any of them mean.
 */
data class SettingDeclaration(
    val type: String,
    val key: String,
    val label: String,
    val description: String = "",
    val group: String = "",
    val advanced: Boolean = false,
    val defaultValue: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Long? = null,
    val unit: String = "",
    val hint: String = "",
    val secret: Boolean = false,
    val options: List<String> = emptyList(),
    val optionLabels: List<String> = emptyList(),
    val runningLabel: String = "",
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

/** Receiver of the `switchSetting { }`, `choiceSetting { }`, ... blocks. */
open class SettingSpec {
    var label: String = ""
    var description: String = ""
    var group: String = ""
    var advanced: Boolean = false
    var unit: String = ""
    var hint: String = ""
    var secret: Boolean = false
    var min: Double? = null
    var max: Double? = null
    var step: Long? = null
    var runningLabel: String = ""

    /** Options for choice / multiChoice, as `value to label` pairs. */
    var options: List<Pair<String, String>> = emptyList()

    /** Shortcut for when value and label are the same. */
    fun options(vararg values: String) {
        options = values.map { it to it }
    }
}

/**
 * The screen a plugin offers, declared in `zetaPlugin { ui { ... } }`.
 *
 * Declared rather than detected: the Host reads it before loading a single byte
 * of the plugin's DEX, so it can show OPEN - or explain why it cannot - without
 * running any plugin code.
 */
data class UiDeclaration(
    /** True when the screen is the whole plugin: no useful RUN, no schedule. */
    val only: Boolean = false,
    /** Label of the button that opens it; blank means the Host's default. */
    val label: String = "",
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

/** Receiver of the `ui { }` block. */
open class UiSpec {
    /** True when the plugin is nothing but its screen. */
    var only: Boolean = false

    /** Overrides the label of the button that opens the screen. */
    var label: String = ""
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

    /** Parameters the Host shows in its settings dialog. */
    abstract val settings: ListProperty<SettingDeclaration>

    /** Forward-looking: named capabilities the plugin requests from the Host. */
    abstract val capabilities: ListProperty<String>

    /**
     * The screen this plugin offers, or empty when it has none.
     *
     * A list holding at most one element rather than a nullable property,
     * because Gradle's lazy properties have no "explicitly unset" state and this
     * has to survive configuration caching. See [ui].
     */
    abstract val uiDeclaration: ListProperty<UiDeclaration>

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
        settings.convention(emptyList())
        uiDeclaration.convention(emptyList())
    }

    // --- settings DSL -------------------------------------------------------

    /** A yes/no switch. */
    @JvmOverloads
    fun switchSetting(key: String, default: Boolean = false, configure: Action<SettingSpec>? = null) =
        addSetting("switch", key, default.toString(), configure)

    /** A whole number, optionally bounded with min/max. */
    @JvmOverloads
    fun numberSetting(key: String, default: Long = 0, configure: Action<SettingSpec>? = null) =
        addSetting("number", key, default.toString(), configure)

    /** A decimal, for thresholds and factors. */
    @JvmOverloads
    fun decimalSetting(key: String, default: Double = 0.0, configure: Action<SettingSpec>? = null) =
        addSetting("decimal", key, default.toString(), configure)

    /** Free text; set `secret = true` in the block to mask it. */
    @JvmOverloads
    fun textSetting(key: String, default: String = "", configure: Action<SettingSpec>? = null) =
        addSetting("text", key, default, configure)

    /** One value out of a list; fill `options` in the block. */
    @JvmOverloads
    fun choiceSetting(key: String, default: String = "", configure: Action<SettingSpec>? = null) =
        addSetting("choice", key, default, configure)

    /** Zero or more values out of a list. */
    @JvmOverloads
    fun multiChoiceSetting(
        key: String,
        default: List<String> = emptyList(),
        configure: Action<SettingSpec>? = null,
    ) = addSetting("multichoice", key, default.joinToString(","), configure)

    /** A folder chosen with the system picker; delivered as a URI string. */
    @JvmOverloads
    fun folderSetting(key: String, default: String = "", configure: Action<SettingSpec>? = null) =
        addSetting("folder", key, default, configure)

    /** A button that calls `runAction(key)` on the plugin. */
    @JvmOverloads
    fun actionSetting(key: String, configure: Action<SettingSpec>? = null) =
        addSetting("action", key, null, configure)

    private fun addSetting(type: String, key: String, default: String?, configure: Action<SettingSpec>?) {
        val spec = SettingSpec().also { configure?.execute(it) }
        settings.add(
            SettingDeclaration(
                type = type,
                key = key,
                label = spec.label.ifBlank { key },
                description = spec.description,
                group = spec.group,
                advanced = spec.advanced,
                defaultValue = default,
                min = spec.min,
                max = spec.max,
                step = spec.step,
                unit = spec.unit,
                hint = spec.hint,
                secret = spec.secret,
                options = spec.options.map { it.first },
                optionLabels = spec.options.map { it.second },
                runningLabel = spec.runningLabel,
            )
        )
    }

    /**
     * Declares that the entry point also implements
     * `com.zetaforge.sdk.ui.ZetaUiPlugin`, so the Host can open it as a screen.
     *
     * ```
     * ui { only = true }        // this plugin is a screen and nothing else
     * ```
     */
    @JvmOverloads
    fun ui(configure: Action<UiSpec>? = null) {
        val spec = UiSpec().also { configure?.execute(it) }
        uiDeclaration.set(listOf(UiDeclaration(only = spec.only, label = spec.label)))
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
