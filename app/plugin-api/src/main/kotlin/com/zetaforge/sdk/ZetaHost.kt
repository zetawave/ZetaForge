package com.zetaforge.sdk

import android.content.Context
import android.content.Intent

/**
 * The few things only the Host can do, offered to a plugin that needs them.
 *
 * Everything here has the same shape of reason behind it: it needs a component
 * declared in an installed APK's manifest, and a plugin has no manifest. A
 * plugin cannot set an alarm that survives its own process being killed, cannot
 * be woken after a reboot, and cannot name an Activity for a notification to
 * open — not because the runtime withholds it, but because Android resolves all
 * three from an installed package.
 *
 * Installed by the Host at start-up, exactly like [ZetaLog] and [ZetaProgress]:
 * the object lives in the shared SDK, which is always loaded by the Host's own
 * class loader, so both sides see the same instance with no wiring.
 */
interface ZetaHostServices {

    /**
     * An Intent that opens a plugin's screen, or null when it has none.
     *
     * For a plugin whose notification should lead back to itself. The Host owns
     * the container Activity, so it is the only side that can name it.
     */
    fun screenIntent(context: Context, pluginId: String): Intent?

    /**
     * Asks the Host to keep restarting this plugin's `execute()` if it is not
     * running, roughly every [everyMinutes], across process death and reboots.
     *
     * The Host starts a run and nothing more: whether there is anything to do
     * is the plugin's own decision, made from its own state on disk. A run
     * already in flight is left alone.
     */
    fun keepAlive(pluginId: String, everyMinutes: Int)

    /** Stops what [keepAlive] asked for. Safe to call when nothing is armed. */
    fun cancelKeepAlive(pluginId: String)
}

/**
 * The bridge a plugin calls, and the Host fills in.
 *
 * Every call is a no-op when no Host has installed anything - which is what a
 * plugin under a unit test sees - so a plugin never has to check first.
 */
object ZetaHost {

    @Volatile
    private var services: ZetaHostServices? = null

    /** Installed by the Host runtime; `null` makes every call do nothing. */
    fun install(services: ZetaHostServices?) {
        this.services = services
    }

    fun screenIntent(context: Context, pluginId: String): Intent? =
        services?.screenIntent(context, pluginId)

    fun keepAlive(pluginId: String, everyMinutes: Int) {
        services?.keepAlive(pluginId, everyMinutes)
    }

    fun cancelKeepAlive(pluginId: String) {
        services?.cancelKeepAlive(pluginId)
    }
}
