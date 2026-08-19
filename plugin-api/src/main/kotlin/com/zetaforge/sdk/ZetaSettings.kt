package com.zetaforge.sdk

import android.os.Bundle

/**
 * One configurable parameter of a plugin.
 *
 * The Host renders these into a form and hands the resulting values back to
 * [ZetaPlugin.execute] in its input [Bundle]. The plugin therefore never draws
 * UI and the Host never knows what any parameter means - the description that
 * travels with the plugin is the whole contract.
 *
 * Fields are declared in two places, on purpose:
 *
 * * **the package manifest**, so the Host can show and edit them without ever
 *   loading the plugin's code - even before its first run, and even if the
 *   plugin fails to load;
 * * **[ZetaPlugin.settings]**, optionally, when the choices only exist on the
 *   device: the encoders this chip supports, the folders actually present, a
 *   value that depends on another field.
 */
sealed class ZetaSetting {

    /** Key used in the input Bundle. Stable: renaming it resets the value. */
    abstract val key: String

    /** Short label shown next to the control. */
    abstract val label: String

    /** One line explaining what it does, shown under the control. */
    abstract val description: String

    /** Optional section title, to group related fields. */
    abstract val group: String

    /** Hidden behind "Advanced" - for what most people never touch. */
    abstract val advanced: Boolean

    data class Switch(
        override val key: String,
        override val label: String,
        override val description: String = "",
        override val group: String = "",
        override val advanced: Boolean = false,
        val default: Boolean = false,
    ) : ZetaSetting()

    data class Number(
        override val key: String,
        override val label: String,
        override val description: String = "",
        override val group: String = "",
        override val advanced: Boolean = false,
        val default: Long = 0,
        val min: Long = 0,
        val max: Long = Long.MAX_VALUE,
        val step: Long = 1,
        /** Appended to the value in the UI, e.g. "MB", "%". */
        val unit: String = "",
    ) : ZetaSetting()

    data class Decimal(
        override val key: String,
        override val label: String,
        override val description: String = "",
        override val group: String = "",
        override val advanced: Boolean = false,
        val default: Double = 0.0,
        val min: Double = 0.0,
        val max: Double = 1.0,
        val unit: String = "",
    ) : ZetaSetting()

    data class Text(
        override val key: String,
        override val label: String,
        override val description: String = "",
        override val group: String = "",
        override val advanced: Boolean = false,
        val default: String = "",
        val hint: String = "",
        /** Masked in the UI. Note: values are not encrypted at rest yet. */
        val secret: Boolean = false,
    ) : ZetaSetting()

    /** One value out of a fixed list. */
    data class Choice(
        override val key: String,
        override val label: String,
        override val description: String = "",
        override val group: String = "",
        override val advanced: Boolean = false,
        val default: String = "",
        val options: List<Option> = emptyList(),
    ) : ZetaSetting() {
        data class Option(val value: String, val label: String, val description: String = "")
    }

    /** Zero or more values out of a list; delivered as a String array. */
    data class MultiChoice(
        override val key: String,
        override val label: String,
        override val description: String = "",
        override val group: String = "",
        override val advanced: Boolean = false,
        val default: List<String> = emptyList(),
        val options: List<Choice.Option> = emptyList(),
    ) : ZetaSetting()

    /**
     * A folder picked through the system picker.
     *
     * The Host takes persistable permission on the chosen tree, so the plugin
     * keeps access across reboots, and passes the URI as a string.
     */
    data class Folder(
        override val key: String,
        override val label: String,
        override val description: String = "",
        override val group: String = "",
        override val advanced: Boolean = false,
        val default: String = "",
    ) : ZetaSetting()

    /**
     * A button rather than a value: the Host calls [ZetaPlugin.runAction] and
     * shows whatever comes back. Meant for "test the connection", "estimate the
     * result", "reset my state" - work that is short and answers a question.
     */
    data class Action(
        override val key: String,
        override val label: String,
        override val description: String = "",
        override val group: String = "",
        override val advanced: Boolean = false,
        /** Shown while the action runs. */
        val runningLabel: String = "",
    ) : ZetaSetting()
}

/** The full set of fields a plugin exposes. */
data class ZetaSettingsSpec(
    val settings: List<ZetaSetting> = emptyList(),
) {
    val isEmpty: Boolean get() = settings.isEmpty()

    /** Merges another spec over this one, matching by key. */
    fun mergedWith(other: ZetaSettingsSpec): ZetaSettingsSpec {
        if (other.isEmpty) return this
        val byKey = LinkedHashMap<String, ZetaSetting>()
        settings.forEach { byKey[it.key] = it }
        other.settings.forEach { byKey[it.key] = it }
        return ZetaSettingsSpec(byKey.values.toList())
    }
}

/** What a [ZetaSetting.Action] produced, shown in the settings dialog. */
data class ZetaActionResult(
    val successful: Boolean,
    val message: String,
    /** Values the action wants to write back into the form, by key. */
    val updatedValues: Map<String, String> = emptyMap(),
) {
    companion object {
        fun ok(message: String, updatedValues: Map<String, String> = emptyMap()) =
            ZetaActionResult(true, message, updatedValues)

        fun failed(message: String) = ZetaActionResult(false, message)
    }
}
