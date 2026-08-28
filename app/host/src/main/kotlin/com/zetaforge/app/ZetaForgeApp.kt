package com.zetaforge.app

import android.app.Application
import android.content.Context
import android.content.Intent
import com.zetaforge.app.notify.ZetaNotifications
import com.zetaforge.app.schedule.KeepAliveAlarms
import com.zetaforge.app.schedule.ScheduleAlarms
import com.zetaforge.app.ui.screen.PluginScreenActivity
import com.zetaforge.runtime.ZetaPluginRuntime
import com.zetaforge.sdk.ZetaHost
import com.zetaforge.sdk.ZetaHostServices
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
        ZetaHost.install(HostServices(this))

        // The process may have been started by an alarm rather than by the user,
        // so the plugin list has to be discovered here rather than in the UI.
        scope.launch {
            runtime.refresh()
            ScheduleAlarms.rescheduleAll(this@ZetaForgeApp, runtime)
        }
    }

    /**
     * The three things a plugin cannot do for itself, done for it.
     *
     * Each one needs a component from an installed manifest - an Activity to
     * open, a receiver to wake - and a plugin has no manifest. See
     * [ZetaHostServices] for why that is a property of Android rather than a
     * restriction the runtime imposes.
     */
    private class HostServices(private val application: Application) : ZetaHostServices {

        override fun screenIntent(context: Context, pluginId: String): Intent? {
            val entry = ZetaForgeApp.instance(application).runtime.plugins.value
                .firstOrNull { it.id == pluginId }
            if (entry != null && entry.installed.manifest.ui == null) return null
            return PluginScreenActivity.intent(application, pluginId)
        }

        override fun keepAlive(pluginId: String, everyMinutes: Int) {
            KeepAliveAlarms.request(application, pluginId, everyMinutes)
        }

        override fun cancelKeepAlive(pluginId: String) {
            KeepAliveAlarms.cancel(application, pluginId)
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
