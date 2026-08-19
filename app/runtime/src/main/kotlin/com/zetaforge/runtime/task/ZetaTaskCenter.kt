package com.zetaforge.runtime.task

import com.zetaforge.sdk.ZetaProgress
import com.zetaforge.sdk.ZetaProgressSink
import com.zetaforge.sdk.ZetaProgressUpdate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** A plugin execution currently in flight. */
data class RunningTask(
    val pluginId: String,
    val pluginName: String,
    val startedAtEpochMs: Long,
    val progress: ZetaProgressUpdate = ZetaProgressUpdate(),
) {
    val elapsedMs: Long get() = System.currentTimeMillis() - startedAtEpochMs
}

/**
 * Process-wide view of "is a plugin running, and how far along is it".
 *
 * It exists as a singleton on purpose: the foreground service that keeps the
 * process alive lives in the Host and is started and stopped by the system, so
 * it cannot hold a reference to the runtime instance. Both sides talk through
 * this object, exactly like [ZetaProgress] does for plugins.
 *
 * Why a foreground service at all: with the screen off Android freezes a
 * process that has no foreground component, and a long transfer simply stops
 * until the phone wakes up. A process with a foreground service is never
 * frozen, so the work continues with the screen off - measured, not assumed.
 */
object ZetaTaskCenter : ZetaProgressSink {

    private val _current = MutableStateFlow<RunningTask?>(null)

    /** The running execution, or `null` when the runtime is idle. */
    val current: StateFlow<RunningTask?> = _current.asStateFlow()

    private val _cancelRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Cancellation asked for from outside, e.g. the notification action. */
    val cancelRequests: SharedFlow<String> = _cancelRequests.asSharedFlow()

    init {
        ZetaProgress.install(this)
    }

    fun begin(pluginId: String, pluginName: String) {
        _current.value = RunningTask(pluginId, pluginName, System.currentTimeMillis())
    }

    fun end(pluginId: String) {
        if (_current.value?.pluginId == pluginId) _current.value = null
    }

    override fun onProgress(pluginId: String, update: ZetaProgressUpdate) {
        val running = _current.value ?: return
        if (running.pluginId != pluginId) return
        _current.value = running.copy(progress = update)
    }

    /** Asks whoever is running the plugin to stop it. */
    fun requestCancel(pluginId: String) {
        _cancelRequests.tryEmit(pluginId)
    }
}
