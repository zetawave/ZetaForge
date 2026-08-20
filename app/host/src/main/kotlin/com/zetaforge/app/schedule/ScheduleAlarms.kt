package com.zetaforge.app.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.zetaforge.runtime.ZetaPluginRuntime
import com.zetaforge.runtime.schedule.Schedule

/**
 * Turns schedules into alarms.
 *
 * One alarm per plugin, always for the *next* run only: a repeating alarm cannot
 * express "every Tuesday and Friday at 03:00, unless it already ran", and it
 * survives changes to the schedule badly. After each run the next alarm is set
 * from the same [Schedule.nextRunAfter] the UI uses to show the preview, so what
 * the user is promised and what the system will do cannot diverge.
 *
 * ### Exactness
 * `setExactAndAllowWhileIdle` fires at the minute even in Doze, and from Android
 * 12 needs a permission the user can revoke. `setAndAllowWhileIdle` needs
 * nothing and may drift by minutes, which for "back up my photos at night" is
 * entirely fine. So exactness is opt-in per schedule, and a schedule that asks
 * for it without the permission degrades to inexact rather than silently never
 * firing.
 */
object ScheduleAlarms {

    const val ACTION_FIRE = "com.zetaforge.app.action.SCHEDULE_FIRE"
    const val EXTRA_PLUGIN_ID = "pluginId"

    /** Re-arms every automatic schedule. Safe to call repeatedly. */
    fun rescheduleAll(context: Context, runtime: ZetaPluginRuntime) {
        runtime.schedules.reload()
        runtime.schedules.automatic().forEach { schedule -> schedule(context, schedule) }
    }

    /**
     * Arms (or disarms) the alarm for one schedule.
     * @return the moment it will fire, or null when nothing is due.
     */
    fun schedule(context: Context, schedule: Schedule): Long? {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return null
        val pending = pendingIntent(context, schedule.pluginId)

        val next = schedule.nextRunAfter(System.currentTimeMillis())
        if (next == null) {
            manager.cancel(pending)
            return null
        }

        val wantsExact = schedule.exact && canScheduleExact(context)
        if (wantsExact) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
        } else {
            // Allowed while idle, so Doze delays it rather than dropping it.
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
        }
        return next
    }

    fun cancel(context: Context, pluginId: String) {
        context.getSystemService(AlarmManager::class.java)
            ?.cancel(pendingIntent(context, pluginId))
    }

    private fun pendingIntent(context: Context, pluginId: String): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_PLUGIN_ID, pluginId)
            // The action is shared, so the data uri is what keeps one plugin's
            // alarm from replacing another's: PendingIntent equality ignores extras.
            .setData(android.net.Uri.parse("zetaforge://schedule/$pluginId"))

        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    // -- exact alarms ----------------------------------------------------------

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
    }

    /** The Settings page where the user grants exact alarms, if there is one. */
    fun exactAlarmSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
    }
}
