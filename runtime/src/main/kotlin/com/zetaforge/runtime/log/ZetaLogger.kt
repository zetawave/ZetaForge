package com.zetaforge.runtime.log

import android.util.Log
import com.zetaforge.sdk.ZetaLog
import com.zetaforge.sdk.ZetaLogLevel
import com.zetaforge.sdk.ZetaLogSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One structured log record produced by the Host or by a plugin. */
data class ZetaLogRecord(
    val timestampMs: Long,
    val level: ZetaLogLevel,
    val source: String,
    val pluginId: String?,
    val message: String,
    val throwable: Throwable? = null,
) {
    fun formatted(): String {
        val time = TIME_FORMAT.get()!!.format(Date(timestampMs))
        val level = level.name.padEnd(5)
        return "$time $level ${source.padEnd(11)} $message"
    }

    private companion object {
        val TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        }
    }
}

/**
 * Structured logger shared by the runtime and by plugins.
 *
 * Keeps a bounded in-memory ring buffer exposed as a [StateFlow] (what the UI
 * renders) and mirrors everything to logcat. It implements [ZetaLogSink], so
 * installing it into [ZetaLog] is all a plugin needs to appear in the same
 * stream as the Host.
 *
 * Persistence is intentionally a single seam ([persister]): the PoC keeps logs
 * in memory, a file/Room implementation can be dropped in without touching call
 * sites.
 */
class ZetaLogger(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val persister: ((ZetaLogRecord) -> Unit)? = null,
) : ZetaLogSink {

    private val _records = MutableStateFlow<List<ZetaLogRecord>>(emptyList())
    val records: StateFlow<List<ZetaLogRecord>> = _records.asStateFlow()

    fun debug(source: String, pluginId: String? = null, message: String) =
        record(ZetaLogLevel.DEBUG, source, pluginId, message, null)

    fun info(source: String, pluginId: String? = null, message: String) =
        record(ZetaLogLevel.INFO, source, pluginId, message, null)

    fun warn(source: String, pluginId: String? = null, message: String) =
        record(ZetaLogLevel.WARN, source, pluginId, message, null)

    fun error(source: String, pluginId: String? = null, message: String, throwable: Throwable? = null) =
        record(ZetaLogLevel.ERROR, source, pluginId, message, throwable)

    override fun log(
        level: ZetaLogLevel,
        source: String,
        pluginId: String?,
        message: String,
        throwable: Throwable?,
    ) = record(level, source, pluginId, message, throwable)

    fun clear() {
        _records.value = emptyList()
    }

    private fun record(
        level: ZetaLogLevel,
        source: String,
        pluginId: String?,
        message: String,
        throwable: Throwable?,
    ) {
        val entry = ZetaLogRecord(System.currentTimeMillis(), level, source, pluginId, message, throwable)
        synchronized(this) {
            val current = _records.value
            val next = if (current.size >= capacity) {
                current.subList(current.size - capacity + 1, current.size) + entry
            } else {
                current + entry
            }
            _records.value = next
        }
        persister?.invoke(entry)
        mirrorToLogcat(entry)
    }

    private fun mirrorToLogcat(entry: ZetaLogRecord) {
        val tag = LOGCAT_TAG + "/" + entry.source
        val message = entry.pluginId?.let { "[$it] ${entry.message}" } ?: entry.message
        when (entry.level) {
            ZetaLogLevel.DEBUG -> Log.d(tag, message, entry.throwable)
            ZetaLogLevel.INFO -> Log.i(tag, message, entry.throwable)
            ZetaLogLevel.WARN -> Log.w(tag, message, entry.throwable)
            ZetaLogLevel.ERROR -> Log.e(tag, message, entry.throwable)
        }
    }

    companion object {
        const val LOGCAT_TAG = "ZetaForge"
        const val DEFAULT_CAPACITY = 500
    }
}
