package com.zetaforge.runtime.settings

import android.os.Bundle
import com.zetaforge.runtime.install.PluginStorage
import com.zetaforge.sdk.ZetaSetting
import com.zetaforge.sdk.ZetaSettingsSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Saved values for a plugin's settings, and their conversion to the [Bundle]
 * every plugin already receives.
 *
 * Two rules keep upgrades painless:
 *
 * * a key the current schema does not know is **kept**, not dropped - so
 *   downgrading a plugin, or a field appearing only on some devices, never
 *   silently erases what the user configured;
 * * a key the schema declares but the file lacks falls back to its **default**,
 *   so a new parameter simply starts at its intended value.
 */
class PluginSettingsStore(private val storage: PluginStorage) {

    fun file(pluginId: String): File = File(storage.metadataDir(pluginId), FILE_NAME)

    /** Raw saved values, without applying any schema. */
    fun load(pluginId: String): Map<String, Any> {
        val file = file(pluginId)
        if (!file.isFile) return emptyMap()
        return runCatching {
            val root = JSONObject(file.readText()).optJSONObject("values") ?: return emptyMap()
            root.keys().asSequence().associateWith { key -> root.get(key) }
        }.getOrDefault(emptyMap())
    }

    fun save(pluginId: String, values: Map<String, Any?>) {
        val file = file(pluginId)
        file.parentFile?.mkdirs()
        val payload = JSONObject().apply {
            put("version", VERSION)
            put("updatedAt", System.currentTimeMillis())
            put("values", JSONObject().also { obj ->
                values.forEach { (key, value) ->
                    when (value) {
                        null -> Unit
                        is List<*> -> obj.put(key, JSONArray(value))
                        else -> obj.put(key, value)
                    }
                }
            })
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(payload.toString(2))
        if (file.exists()) file.delete()
        temp.renameTo(file)
    }

    fun clear(pluginId: String) {
        file(pluginId).delete()
    }

    /** Saved values with schema defaults filled in, ready to show in a form. */
    fun effectiveValues(pluginId: String, spec: ZetaSettingsSpec): Map<String, Any> {
        val saved = load(pluginId)
        val result = LinkedHashMap<String, Any>(saved)
        spec.settings.forEach { setting ->
            if (result.containsKey(setting.key)) return@forEach
            defaultOf(setting)?.let { result[setting.key] = it }
        }
        return result
    }

    /** The Bundle handed to `execute`, typed according to the schema. */
    fun toBundle(spec: ZetaSettingsSpec, values: Map<String, Any>): Bundle {
        val bundle = Bundle()
        spec.settings.forEach { setting ->
            val value = values[setting.key] ?: defaultOf(setting) ?: return@forEach
            when (setting) {
                is ZetaSetting.Switch -> bundle.putBoolean(setting.key, value.asBoolean())
                is ZetaSetting.Number -> bundle.putInt(setting.key, value.asLong().toInt())
                is ZetaSetting.Decimal -> bundle.putDouble(setting.key, value.asDouble())
                is ZetaSetting.Text -> bundle.putString(setting.key, value.toString())
                is ZetaSetting.Choice -> bundle.putString(setting.key, value.toString())
                is ZetaSetting.Folder -> bundle.putString(setting.key, value.toString())
                is ZetaSetting.MultiChoice ->
                    bundle.putStringArray(setting.key, value.asStringList().toTypedArray())

                is ZetaSetting.Action -> Unit // a button carries no value
            }
        }
        // Values with no matching field (an older plugin, or a key set from the
        // developer loop) still travel: dropping them would silently change
        // behaviour the user configured on purpose.
        values.forEach { (key, value) ->
            if (spec.settings.none { it.key == key } && !bundle.containsKey(key)) {
                when (value) {
                    is Boolean -> bundle.putBoolean(key, value)
                    is Int -> bundle.putInt(key, value)
                    is Long -> bundle.putLong(key, value)
                    is Double -> bundle.putDouble(key, value)
                    is Float -> bundle.putDouble(key, value.toDouble())
                    else -> bundle.putString(key, value.toString())
                }
            }
        }
        return bundle
    }

    private fun defaultOf(setting: ZetaSetting): Any? = when (setting) {
        is ZetaSetting.Switch -> setting.default
        is ZetaSetting.Number -> setting.default
        is ZetaSetting.Decimal -> setting.default
        is ZetaSetting.Text -> setting.default
        is ZetaSetting.Choice -> setting.default.ifBlank { setting.options.firstOrNull()?.value.orEmpty() }
        is ZetaSetting.MultiChoice -> setting.default
        is ZetaSetting.Folder -> setting.default
        is ZetaSetting.Action -> null
    }

    private fun Any.asBoolean(): Boolean = when (this) {
        is Boolean -> this
        is Number -> toInt() != 0
        else -> toString().equals("true", ignoreCase = true)
    }

    private fun Any.asLong(): Long = when (this) {
        is Number -> toLong()
        else -> toString().toLongOrNull() ?: 0L
    }

    private fun Any.asDouble(): Double = when (this) {
        is Number -> toDouble()
        else -> toString().toDoubleOrNull() ?: 0.0
    }

    private fun Any.asStringList(): List<String> = when (this) {
        is List<*> -> mapNotNull { it?.toString() }
        is JSONArray -> (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
        else -> toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private companion object {
        const val FILE_NAME = "settings.json"
        const val VERSION = 1
    }
}
