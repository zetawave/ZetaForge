package com.zetaforge.sdk

/** Severity of a log record, shared by Host and plugins. */
enum class ZetaLogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Receiver of log records. The Host installs an implementation into [ZetaLog]
 * at startup so plugin output shows up in the same stream as runtime output.
 */
interface ZetaLogSink {
    fun log(
        level: ZetaLogLevel,
        source: String,
        pluginId: String?,
        message: String,
        throwable: Throwable? = null,
    )
}

/**
 * Logging bridge between plugins and the Host.
 *
 * This object lives in the shared SDK, which is always loaded by the Host
 * class loader (never bundled into a plugin DEX), so both sides observe the
 * very same instance and the plugin needs no extra wiring to be visible in the
 * Host log view.
 */
object ZetaLog {

    @Volatile
    private var sink: ZetaLogSink? = null

    /** Installed by the Host runtime; `null` silently discards records. */
    fun install(sink: ZetaLogSink?) {
        this.sink = sink
    }

    fun debug(pluginId: String?, source: String, message: String) =
        log(ZetaLogLevel.DEBUG, source, pluginId, message, null)

    fun info(pluginId: String?, source: String, message: String) =
        log(ZetaLogLevel.INFO, source, pluginId, message, null)

    fun warn(pluginId: String?, source: String, message: String) =
        log(ZetaLogLevel.WARN, source, pluginId, message, null)

    fun error(pluginId: String?, source: String, message: String, throwable: Throwable? = null) =
        log(ZetaLogLevel.ERROR, source, pluginId, message, throwable)

    fun log(
        level: ZetaLogLevel,
        source: String,
        pluginId: String?,
        message: String,
        throwable: Throwable?,
    ) {
        sink?.log(level, source, pluginId, message, throwable)
    }
}
