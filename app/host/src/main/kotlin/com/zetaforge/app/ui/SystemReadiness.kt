package com.zetaforge.app.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.zetaforge.app.R
import com.zetaforge.app.schedule.DeviceConditions
import com.zetaforge.app.schedule.ScheduleAlarms

/**
 * Whether Android will actually let a scheduled run happen.
 *
 * Every one of these has been the reason a background task silently did not
 * run on a real phone. They are checked in one place and shown in one place, so
 * "why did nothing happen last night" has an answer the user can act on rather
 * than a shrug.
 */
data class SystemReadiness(
    val notificationsEnabled: Boolean,
    val batteryUnrestricted: Boolean,
    val exactAlarmsAllowed: Boolean,
    val exactAlarmsRelevant: Boolean,
    val manufacturerCaveat: String?,
) {
    /** True when nothing stands between a schedule and its run. */
    val allGood: Boolean
        get() = notificationsEnabled && batteryUnrestricted && (!exactAlarmsRelevant || exactAlarmsAllowed)

    /** Problems worth a badge on the menu. */
    val blockingCount: Int
        get() = listOf(
            !notificationsEnabled,
            !batteryUnrestricted,
            exactAlarmsRelevant && !exactAlarmsAllowed,
        ).count { it }

    companion object {
        /**
         * @param anyScheduleWantsExact whether any schedule asked for exact
         *   timing — the permission is only worth flagging if something needs it.
         */
        fun read(context: Context, anyScheduleWantsExact: Boolean): SystemReadiness = SystemReadiness(
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            batteryUnrestricted = DeviceConditions.isIgnoringBatteryOptimizations(context),
            exactAlarmsAllowed = ScheduleAlarms.canScheduleExact(context),
            exactAlarmsRelevant = anyScheduleWantsExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            manufacturerCaveat = if (DeviceConditions.manufacturerNeedsExtraStep()) {
                Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            } else {
                null
            },
        )
    }
}

/** One row of the readiness panel: what it is, whether it is fine, how to fix it. */
data class ReadinessItem(
    val titleRes: Int,
    val bodyRes: Int,
    val satisfied: Boolean,
    val fix: Fix?,
) {
    /** How the app takes the user to the exact switch, rather than to a home screen. */
    sealed class Fix {
        /** Ask Android for the runtime permission. */
        data object NotificationPermission : Fix()

        /** Open a system screen. */
        data class OpenIntent(val intent: Intent) : Fix()
    }
}

/** The rows to render, in the order they matter. */
fun SystemReadiness.items(context: Context): List<ReadinessItem> = buildList {
    add(
        ReadinessItem(
            titleRes = R.string.readiness_notifications,
            bodyRes = R.string.readiness_notifications_body,
            satisfied = notificationsEnabled,
            fix = ReadinessItem.Fix.NotificationPermission,
        )
    )
    add(
        ReadinessItem(
            titleRes = R.string.readiness_battery,
            bodyRes = R.string.readiness_battery_body,
            satisfied = batteryUnrestricted,
            fix = ReadinessItem.Fix.OpenIntent(
                DeviceConditions.requestIgnoreBatteryOptimizationIntent(context)
            ),
        )
    )
    if (exactAlarmsRelevant) {
        add(
            ReadinessItem(
                titleRes = R.string.readiness_exact,
                bodyRes = R.string.readiness_exact_body,
                satisfied = exactAlarmsAllowed,
                fix = ScheduleAlarms.exactAlarmSettingsIntent(context)
                    ?.let { ReadinessItem.Fix.OpenIntent(it) },
            )
        )
    }
}
