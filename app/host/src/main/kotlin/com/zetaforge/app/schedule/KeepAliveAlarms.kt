package com.zetaforge.app.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.zetaforge.app.service.PluginExecutionService

/**
 * The watchdog that puts a long-running plugin back on its feet.
 *
 * ### What it is for
 * A plugin that is meant to run for hours - sharing a position across a ride,
 * say - has three ways of being stopped that have nothing to do with it: the
 * phone reboots, Android reclaims the process under memory pressure, or the
 * manufacturer's own battery software kills it, which several of them do
 * aggressively and none of them announces. Every one of those leaves the user
 * believing something is still running when it is not, and that is the failure
 * that matters, because it is silent.
 *
 * So the Host keeps a repeating alarm per plugin that asked for one, and each
 * time it fires it simply starts the plugin. The plugin decides whether there
 * is anything to do; a run already in flight is ignored by the service, so the
 * normal case costs nothing.
 *
 * ### Why inexact, and why `AndWhileIdle`
 * `setAndAllowWhileIdle` is the pair of properties that matter: it survives
 * Doze, which is where an idle phone spends the night, and it is inexact, so
 * Android is free to batch it with whatever else it was about to wake for. A
 * watchdog that insists on a precise minute buys nothing - being fifteen
 * minutes late to notice a dead run is fine - and would spend real battery
 * waking the phone alone.
 *
 * ### Why it is written down
 * An alarm does not survive a reboot or an app update. The set of plugins that
 * asked for one is therefore kept in preferences, and [restoreAll] puts them
 * back when [ScheduleReceiver] sees the boot broadcast.
 */
object KeepAliveAlarms {

    const val ACTION_KEEP_ALIVE = "com.zetaforge.app.action.KEEP_ALIVE"
    const val EXTRA_PLUGIN_ID = "pluginId"

    private const val PREFERENCES = "zetaforge.keepalive"

    /** Below a quarter of an hour Android ignores the request anyway. */
    private const val MIN_INTERVAL_MINUTES = 15
    private const val MAX_INTERVAL_MINUTES = 240

    fun request(context: Context, pluginId: String, everyMinutes: Int) {
        val minutes = everyMinutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
        preferences(context).edit().putInt(pluginId, minutes).apply()
        arm(context, pluginId, minutes)
    }

    fun cancel(context: Context, pluginId: String) {
        preferences(context).edit().remove(pluginId).apply()
        alarms(context)?.cancel(intentFor(context, pluginId))
    }

    /** Called after a reboot or an app update, when every alarm has been dropped. */
    fun restoreAll(context: Context) {
        preferences(context).all.forEach { (pluginId, value) ->
            val minutes = (value as? Int) ?: return@forEach
            arm(context, pluginId, minutes)
        }
    }

    /**
     * Re-arms after firing.
     *
     * One alarm at a time rather than a repeating one, because
     * `setAndAllowWhileIdle` has no repeating form - and because re-arming from
     * the receiver means the chain stops on its own if the plugin is
     * uninstalled while an alarm is pending.
     */
    fun rearm(context: Context, pluginId: String) {
        val minutes = preferences(context).getInt(pluginId, 0)
        if (minutes <= 0) return
        arm(context, pluginId, minutes)
    }

    fun isArmed(context: Context, pluginId: String): Boolean =
        preferences(context).getInt(pluginId, 0) > 0

    /** Starts the plugin if nothing of it is running; the service decides. */
    fun fire(context: Context, pluginId: String, needsLocation: Boolean) {
        PluginExecutionService.runManual(context, pluginId, needsLocation)
    }

    private fun arm(context: Context, pluginId: String, minutes: Int) {
        val manager = alarms(context) ?: return
        val triggerAt = System.currentTimeMillis() + minutes * 60_000L
        runCatching {
            manager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                intentFor(context, pluginId),
            )
        }
    }

    private fun alarms(context: Context): AlarmManager? =
        context.getSystemService(AlarmManager::class.java)

    private fun intentFor(context: Context, pluginId: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        pluginId.hashCode(),
        Intent(context, ScheduleReceiver::class.java)
            .setAction(ACTION_KEEP_ALIVE)
            .putExtra(EXTRA_PLUGIN_ID, pluginId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
