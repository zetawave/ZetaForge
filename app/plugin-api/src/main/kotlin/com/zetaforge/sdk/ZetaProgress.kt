package com.zetaforge.sdk

/**
 * Progress of a long running plugin, as the user sees it.
 *
 * @param current work done so far, in the same unit as [total].
 * @param total total amount of work, or `null` when it is not known yet
 *   (the Host then shows an indeterminate indicator).
 * @param message one short line, e.g. `4.812/21.076 files · 12.4 GB`.
 */
data class ZetaProgressUpdate(
    val current: Long = 0L,
    val total: Long? = null,
    val message: String = "",
) {
    /** 0..100, or `null` when [total] is unknown. */
    val percent: Int?
        get() = total?.takeIf { it > 0 }?.let { ((current.toDouble() / it) * 100).toInt().coerceIn(0, 100) }
}

/** Receiver of progress updates. The Host installs an implementation. */
interface ZetaProgressSink {
    fun onProgress(pluginId: String, update: ZetaProgressUpdate)
}

/**
 * Bridge a plugin uses to tell the Host how far along it is.
 *
 * Why it matters beyond cosmetics: the Host keeps a foreground service alive
 * while a plugin runs, and that service needs something to show. A plugin that
 * reports progress gets a notification with a real progress bar, and - more
 * importantly - keeps running with the screen off, because a process with a
 * foreground service is never frozen by Android.
 *
 * Calling this is optional: a plugin that never reports still runs, it just
 * shows an indeterminate notification.
 *
 * ```kotlin
 * ZetaProgress.report(id, current = copied, total = totalFiles, message = "12.4 GB copied")
 * ```
 */
object ZetaProgress {

    @Volatile
    private var sink: ZetaProgressSink? = null

    /** Installed by the Host runtime; `null` discards updates. */
    fun install(sink: ZetaProgressSink?) {
        this.sink = sink
    }

    fun report(pluginId: String, current: Long, total: Long?, message: String = "") {
        sink?.onProgress(pluginId, ZetaProgressUpdate(current, total, message))
    }

    fun report(pluginId: String, update: ZetaProgressUpdate) {
        sink?.onProgress(pluginId, update)
    }

    /** Convenience for plugins that only have a message to show. */
    fun status(pluginId: String, message: String) {
        sink?.onProgress(pluginId, ZetaProgressUpdate(message = message))
    }
}
