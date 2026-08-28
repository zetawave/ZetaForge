package com.zetaforge.app.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zetaforge.app.ZetaForgeApp
import com.zetaforge.app.notify.ZetaNotifications
import com.zetaforge.app.service.PluginExecutionService
import com.zetaforge.runtime.schedule.Schedule
import kotlinx.coroutines.launch

/**
 * Where a scheduled run begins.
 *
 * A broadcast receiver gets a few seconds and a process that may have just been
 * created for it, so this does the least possible: work out whether the run is
 * allowed, hand it to the foreground service, and arm the next alarm. Everything
 * slow happens in the service, which Android lets run.
 *
 * Also handles boot and app-update, where every alarm the system forgot has to
 * be set again — an alarm does not survive either event, and a backup that
 * silently stops after a reboot is worse than one that never worked.
 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = ZetaForgeApp.instance(context)

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            -> {
                val pending = goAsync()
                app.scope.launch {
                    try {
                        app.runtime.refresh()
                        ScheduleAlarms.rescheduleAll(context, app.runtime)
                        // A reboot drops these too, and a plugin that was
                        // sharing a live position when the phone restarted is
                        // exactly the one that must come back by itself.
                        KeepAliveAlarms.restoreAll(context)
                        reportMissed(context, app)
                    } finally {
                        pending.finish()
                    }
                }
            }

            KeepAliveAlarms.ACTION_KEEP_ALIVE -> {
                val pluginId = intent.getStringExtra(KeepAliveAlarms.EXTRA_PLUGIN_ID) ?: return
                val pending = goAsync()
                app.scope.launch {
                    try {
                        if (app.runtime.plugins.value.isEmpty()) app.runtime.refresh()
                        val entry = app.runtime.plugins.value.firstOrNull { it.id == pluginId }
                        if (entry == null) {
                            // Uninstalled while an alarm was pending: let the
                            // chain end here rather than waking the phone for
                            // something that no longer exists.
                            KeepAliveAlarms.cancel(context, pluginId)
                            return@launch
                        }
                        KeepAliveAlarms.rearm(context, pluginId)
                        val needsLocation = entry.installed.manifest.permissions
                            .any { it.name.endsWith("_LOCATION") }
                        KeepAliveAlarms.fire(context, pluginId, needsLocation)
                    } finally {
                        pending.finish()
                    }
                }
            }

            ScheduleAlarms.ACTION_FIRE -> {
                val pluginId = intent.getStringExtra(ScheduleAlarms.EXTRA_PLUGIN_ID) ?: return
                val pending = goAsync()
                app.scope.launch {
                    try {
                        fire(context, app, pluginId)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    private suspend fun fire(context: Context, app: ZetaForgeApp, pluginId: String) {
        if (app.runtime.plugins.value.isEmpty()) app.runtime.refresh()

        val schedule = app.runtime.schedules.get(pluginId)
        val entry = app.runtime.plugins.value.firstOrNull { it.id == pluginId }
        val name = entry?.installed?.displayName ?: pluginId

        if (entry == null) {
            // The plugin was uninstalled while the alarm was pending.
            app.runtime.schedules.delete(pluginId)
            ScheduleAlarms.cancel(context, pluginId)
            return
        }
        if (!schedule.isAutomatic) {
            ScheduleAlarms.cancel(context, pluginId)
            return
        }

        // Conditions are checked here rather than left to AlarmManager, because
        // an alarm has no notion of "only while charging" and a user who asked
        // for that deserves to be told when it is why nothing happened.
        val blocker = DeviceConditions.unmet(context, schedule)
        if (blocker != null) {
            app.runtime.logger.warn(SOURCE, pluginId, "Scheduled run skipped: $blocker")
            app.runtime.schedules.recordRun(pluginId, Schedule.LastResult.SKIPPED, blocker)
            ZetaNotifications.skipped(context, pluginId, name, blocker)
            ScheduleAlarms.schedule(context, app.runtime.schedules.get(pluginId))
            return
        }

        app.runtime.logger.info(SOURCE, pluginId, "Scheduled run starting")
        val needsLocation = app.runtime.plugins.value.firstOrNull { it.id == pluginId }
            ?.installed?.manifest?.permissions
            ?.any { it.name.endsWith("_LOCATION") } == true
        PluginExecutionService.runScheduled(context, pluginId, needsLocation)

        // A one-shot has now happened; anything else gets its next alarm. The
        // service records the outcome, which is what INTERVAL anchors on, so it
        // re-arms again when it finishes.
        if (schedule.mode == Schedule.Mode.ONCE) {
            app.runtime.schedules.save(schedule.copy(enabled = false))
            ScheduleAlarms.cancel(context, pluginId)
        } else {
            ScheduleAlarms.schedule(context, schedule)
        }
    }

    /**
     * After a reboot, tells the user about runs that were due while the device
     * was off. Silence here is what makes people stop trusting a scheduler.
     */
    private fun reportMissed(context: Context, app: ZetaForgeApp) {
        val now = System.currentTimeMillis()
        app.runtime.schedules.automatic().forEach { schedule ->
            if (!schedule.missedSince(now)) return@forEach
            val name = app.runtime.plugins.value
                .firstOrNull { it.id == schedule.pluginId }?.installed?.displayName
                ?: schedule.pluginId
            app.runtime.schedules.recordRun(
                schedule.pluginId,
                Schedule.LastResult.MISSED,
                context.getString(com.zetaforge.app.R.string.schedule_missed_reason),
            )
            ZetaNotifications.skipped(
                context,
                schedule.pluginId,
                name,
                context.getString(com.zetaforge.app.R.string.schedule_missed_reason),
            )
        }
    }

    private companion object {
        const val SOURCE = "Scheduler"
    }
}
