package com.zetaforge.runtime.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which plugin screens are open right now, process-wide.
 *
 * A screen is the first thing in ZetaForge that keeps a plugin instance alive
 * with nobody executing it, and that breaks two assumptions the runtime used to
 * be able to make:
 *
 * * **unload is not free any more.** Dropping the class loader of a plugin whose
 *   screen is on display leaves a composition holding objects whose classes no
 *   longer resolve. So an unload asked for while a screen is open is refused,
 *   and the caller is told why.
 * * **uninstall has to reach the UI.** Deleting the files under a live screen is
 *   worse still, so uninstall does not race with it: it asks the screen to close
 *   through [closeRequests] and waits for the session to end.
 *
 * It is an object rather than a field of the runtime for the same reason
 * `ZetaTaskCenter` is: the Activity that hosts a screen is created by Android,
 * not by us, and cannot be handed a reference to anything.
 */
object ZetaUiSessions {

    private val _open = MutableStateFlow<Set<String>>(emptySet())

    /** Plugin ids with a screen currently on display. */
    val open: StateFlow<Set<String>> = _open.asStateFlow()

    private val _closeRequests = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /**
     * Plugins whose screens must close now — the plugin is being uninstalled or
     * replaced. The Host's screen Activity collects this and finishes itself.
     */
    val closeRequests: SharedFlow<String> = _closeRequests.asSharedFlow()

    fun isOpen(pluginId: String): Boolean = _open.value.contains(pluginId)

    fun begin(pluginId: String) {
        _open.value = _open.value + pluginId
    }

    fun end(pluginId: String) {
        _open.value = _open.value - pluginId
    }

    /** Asks any open screen of this plugin to close. Returns false if none was. */
    fun requestClose(pluginId: String): Boolean {
        if (!isOpen(pluginId)) return false
        _closeRequests.tryEmit(pluginId)
        return true
    }
}
