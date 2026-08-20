package com.zetaforge.app

import android.app.Application
import android.content.Context
import com.zetaforge.app.notify.ZetaNotifications
import com.zetaforge.app.schedule.ScheduleAlarms
import com.zetaforge.runtime.ZetaPluginRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Holds the one runtime the whole app shares.
 *
 * Before scheduling existed, the runtime could live in the view model: the only
 * way to run a plugin was to tap START, and that always happened with the UI on
 * screen. A scheduled run arrives as a broadcast in a process that may have no
 * Activity at all, so the runtime has to outlive the UI — and there must be
 * exactly one of it, or two copies would each hold their own class loaders and
 * their own idea of what is installed.
 */
class ZetaForgeApp : Application() {

    /** Lives as long as the process; used for work that must not be cancelled. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val runtime: ZetaPluginRuntime by lazy { ZetaPluginRuntime(this) }

    override fun onCreate() {
        super.onCreate()
        ZetaNotifications.createChannels(this)

        // The process may have been started by an alarm rather than by the user,
        // so the plugin list has to be discovered here rather than in the UI.
        scope.launch {
            runtime.refresh()
            ScheduleAlarms.rescheduleAll(this@ZetaForgeApp, runtime)
        }
    }

    companion object {
        /** The shared runtime, from anywhere holding a Context. */
        fun runtime(context: Context): ZetaPluginRuntime =
            (context.applicationContext as ZetaForgeApp).runtime

        fun instance(context: Context): ZetaForgeApp =
            context.applicationContext as ZetaForgeApp
    }
}
