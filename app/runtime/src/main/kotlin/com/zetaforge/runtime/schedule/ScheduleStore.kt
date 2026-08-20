package com.zetaforge.runtime.schedule

import com.zetaforge.runtime.install.PluginStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Where schedules live.
 *
 * One file per plugin, beside its settings, for the same reason: uninstalling a
 * plugin takes its schedule with it, and no central index can go stale or
 * disagree with what is actually installed.
 *
 * The in-memory [schedules] flow is what the UI observes; disk is the source of
 * truth across process deaths, which matters here more than usual because the
 * alarm receiver runs in a fresh process with no view model in sight.
 */
class ScheduleStore(private val storage: PluginStorage) {

    private val cache = MutableStateFlow<Map<String, Schedule>>(emptyMap())

    /** Every schedule currently on disk, keyed by plugin id. */
    val schedules: StateFlow<Map<String, Schedule>> = cache.asStateFlow()

    init {
        reload()
    }

    fun file(pluginId: String): File = File(storage.metadataDir(pluginId), FILE_NAME)

    /** Re-reads every schedule from disk. Cheap: a handful of small files. */
    fun reload() {
        val loaded = storage.installedPluginDirs().mapNotNull { dir ->
            val file = File(dir, "metadata/$FILE_NAME")
            if (!file.isFile) return@mapNotNull null
            runCatching { fromJson(JSONObject(file.readText())) }.getOrNull()
        }
        cache.value = loaded.associateBy { it.pluginId }
    }

    fun get(pluginId: String): Schedule =
        cache.value[pluginId] ?: Schedule.manual(pluginId)

    /** Every schedule that should be producing alarms. */
    fun automatic(): List<Schedule> = cache.value.values.filter { it.isAutomatic }

    fun save(schedule: Schedule) {
        val file = file(schedule.pluginId)
        file.parentFile?.mkdirs()

        // Written to a temporary file and renamed: a schedule half-written by a
        // process that died would be read back as "no schedule", silently.
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(toJson(schedule).toString(2))
        if (file.exists()) file.delete()
        temp.renameTo(file)

        cache.value = cache.value + (schedule.pluginId to schedule)
    }

    fun delete(pluginId: String) {
        file(pluginId).delete()
        cache.value = cache.value - pluginId
    }

    /** Records the outcome of a run, leaving the user's settings untouched. */
    fun recordRun(
        pluginId: String,
        result: Schedule.LastResult,
        message: String,
        atMillis: Long = System.currentTimeMillis(),
    ) {
        val current = cache.value[pluginId] ?: return
        save(
            current.copy(
                lastRunMillis = atMillis,
                lastResult = result,
                lastMessage = message.take(MAX_MESSAGE),
                runCount = current.runCount + if (result == Schedule.LastResult.SUCCESS) 1 else 0,
            )
        )
    }

    private fun toJson(schedule: Schedule) = JSONObject().apply {
        put("version", VERSION)
        put("pluginId", schedule.pluginId)
        put("mode", schedule.mode.name)
        put("enabled", schedule.enabled)
        put("minuteOfDay", schedule.minuteOfDay)
        put("daysOfWeek", JSONArray(schedule.daysOfWeek.sorted()))
        put("onceAtMillis", schedule.onceAtMillis)
        put("intervalMinutes", schedule.intervalMinutes)
        put("requiresCharging", schedule.requiresCharging)
        put("requiresUnmeteredNetwork", schedule.requiresUnmeteredNetwork)
        put("requiresBatteryNotLow", schedule.requiresBatteryNotLow)
        put("exact", schedule.exact)
        put("lastRunMillis", schedule.lastRunMillis)
        put("lastResult", schedule.lastResult.name)
        put("lastMessage", schedule.lastMessage)
        put("runCount", schedule.runCount)
    }

    private fun fromJson(json: JSONObject): Schedule {
        val days = json.optJSONArray("daysOfWeek")
        return Schedule(
            pluginId = json.getString("pluginId"),
            mode = enumOf(json.optString("mode"), Schedule.Mode.MANUAL),
            enabled = json.optBoolean("enabled", false),
            minuteOfDay = json.optInt("minuteOfDay", 9 * 60).coerceIn(0, 24 * 60 - 1),
            daysOfWeek = buildSet {
                for (i in 0 until (days?.length() ?: 0)) add(days!!.getInt(i))
            },
            onceAtMillis = json.optLong("onceAtMillis", 0L),
            intervalMinutes = json.optInt("intervalMinutes", 60),
            requiresCharging = json.optBoolean("requiresCharging", false),
            requiresUnmeteredNetwork = json.optBoolean("requiresUnmeteredNetwork", false),
            requiresBatteryNotLow = json.optBoolean("requiresBatteryNotLow", true),
            exact = json.optBoolean("exact", false),
            lastRunMillis = json.optLong("lastRunMillis", 0L),
            lastResult = enumOf(json.optString("lastResult"), Schedule.LastResult.NONE),
            lastMessage = json.optString("lastMessage", ""),
            runCount = json.optInt("runCount", 0),
        )
    }

    /** Unknown names come from a newer build; they fall back rather than crash. */
    private inline fun <reified T : Enum<T>> enumOf(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    private companion object {
        const val FILE_NAME = "schedule.json"
        const val VERSION = 1
        const val MAX_MESSAGE = 200
    }
}
