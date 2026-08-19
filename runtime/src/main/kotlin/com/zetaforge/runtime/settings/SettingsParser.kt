package com.zetaforge.runtime.settings

import com.zetaforge.sdk.ZetaSetting
import com.zetaforge.sdk.ZetaSettingsSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the `settings` block of a package manifest.
 *
 * Declaring the fields in the manifest, rather than only in code, is what lets
 * the Host show and edit a plugin's parameters **without loading its DEX** -
 * before the first run, and even when the plugin itself is broken.
 *
 * ```json
 * "settings": [
 *   { "type": "switch", "key": "videos", "label": "Videos", "default": true },
 *   { "type": "number", "key": "maxFiles", "label": "Files per run",
 *     "min": 0, "max": 100000, "default": 0, "unit": "files" },
 *   { "type": "choice", "key": "videoCodec", "label": "Codec", "default": "hevc",
 *     "options": [ { "value": "hevc", "label": "HEVC" } ] }
 * ]
 * ```
 *
 * Unknown types are ignored rather than rejected: a package built for a newer
 * Host stays usable, minus the fields this Host cannot draw.
 */
object SettingsParser {

    fun parse(array: JSONArray?): ZetaSettingsSpec {
        if (array == null) return ZetaSettingsSpec()
        val settings = mutableListOf<ZetaSetting>()
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            parseOne(obj)?.let(settings::add)
        }
        return ZetaSettingsSpec(settings)
    }

    private fun parseOne(obj: JSONObject): ZetaSetting? {
        val key = obj.optString("key").takeIf { it.isNotBlank() } ?: return null
        val label = obj.optString("label").ifBlank { key }
        val description = obj.optString("description")
        val group = obj.optString("group")
        val advanced = obj.optBoolean("advanced", false)

        return when (obj.optString("type").lowercase()) {
            "switch", "bool", "boolean" -> ZetaSetting.Switch(
                key, label, description, group, advanced,
                default = obj.optBoolean("default", false),
            )

            "number", "int", "long" -> ZetaSetting.Number(
                key, label, description, group, advanced,
                default = obj.optLong("default", 0L),
                min = obj.optLong("min", 0L),
                max = obj.optLong("max", Long.MAX_VALUE),
                step = obj.optLong("step", 1L).coerceAtLeast(1L),
                unit = obj.optString("unit"),
            )

            "decimal", "double", "float" -> ZetaSetting.Decimal(
                key, label, description, group, advanced,
                default = obj.optDouble("default", 0.0),
                min = obj.optDouble("min", 0.0),
                max = obj.optDouble("max", 1.0),
                unit = obj.optString("unit"),
            )

            "text", "string" -> ZetaSetting.Text(
                key, label, description, group, advanced,
                default = obj.optString("default"),
                hint = obj.optString("hint"),
                secret = obj.optBoolean("secret", false),
            )

            "choice", "enum" -> ZetaSetting.Choice(
                key, label, description, group, advanced,
                default = obj.optString("default"),
                options = parseOptions(obj.optJSONArray("options")),
            )

            "multichoice", "multi_choice" -> ZetaSetting.MultiChoice(
                key, label, description, group, advanced,
                default = obj.optJSONArray("default").toStringList(),
                options = parseOptions(obj.optJSONArray("options")),
            )

            "folder", "directory" -> ZetaSetting.Folder(
                key, label, description, group, advanced,
                default = obj.optString("default"),
            )

            "action", "button" -> ZetaSetting.Action(
                key, label, description, group, advanced,
                runningLabel = obj.optString("runningLabel"),
            )

            else -> null
        }
    }

    private fun parseOptions(array: JSONArray?): List<ZetaSetting.Choice.Option> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            when (val entry = array.opt(index)) {
                is String -> ZetaSetting.Choice.Option(entry, entry)
                is JSONObject -> {
                    val value = entry.optString("value").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    ZetaSetting.Choice.Option(
                        value = value,
                        label = entry.optString("label").ifBlank { value },
                        description = entry.optString("description"),
                    )
                }

                else -> null
            }
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
    }
}
