package com.zetaforge.runtime.schedule

import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * When a plugin should run on its own.
 *
 * A plugin that only runs when someone presses a button is a tool; one that runs
 * on its own is a service. This is the description of "on its own" — deliberately
 * a plain data class rather than a hierarchy, because it is written to disk,
 * shown in a form, and compared for equality, and all three are simpler when the
 * shape does not change.
 *
 * The single source of truth for "when is the next run" is [nextRunAfter]: the
 * alarm, the UI preview and the missed-run detection all ask the same function.
 */
data class Schedule(
    val pluginId: String,
    val mode: Mode = Mode.MANUAL,
    val enabled: Boolean = false,

    /** Minute of the day, 0..1439. Used by DAILY, WEEKLY and ONCE. */
    val minuteOfDay: Int = 9 * 60,

    /** Days for WEEKLY, as [Calendar.MONDAY]..[Calendar.SUNDAY]. */
    val daysOfWeek: Set<Int> = emptySet(),

    /** Epoch millis of the single run, for ONCE. */
    val onceAtMillis: Long = 0L,

    /** Gap for INTERVAL, in minutes. */
    val intervalMinutes: Int = 60,

    /** Conditions the device must meet before the run is allowed to start. */
    val requiresCharging: Boolean = false,
    val requiresUnmeteredNetwork: Boolean = false,
    val requiresBatteryNotLow: Boolean = true,

    /**
     * Exact alarms wake the device at the minute; inexact ones let Android batch
     * the wake-up and can drift by minutes. Exact costs battery and, from
     * Android 12, a permission — so it is opt-in, per schedule.
     */
    val exact: Boolean = false,

    /** Bookkeeping, written by the runner. */
    val lastRunMillis: Long = 0L,
    val lastResult: LastResult = LastResult.NONE,
    val lastMessage: String = "",
    val runCount: Int = 0,
) {
    enum class Mode {
        /** Only when the user presses START. */
        MANUAL,

        /** Once, at a given date and time. */
        ONCE,

        /** Every N minutes, from the moment it is enabled. */
        INTERVAL,

        /** Every day at a given time. */
        DAILY,

        /** On chosen weekdays, at a given time. */
        WEEKLY,
    }

    enum class LastResult { NONE, SUCCESS, FAILURE, SKIPPED, MISSED }

    val isAutomatic: Boolean get() = enabled && mode != Mode.MANUAL

    val hour: Int get() = minuteOfDay / 60
    val minute: Int get() = minuteOfDay % 60

    /** True when the schedule has enough information to be turned on. */
    val isComplete: Boolean
        get() = when (mode) {
            Mode.MANUAL -> true
            Mode.ONCE -> onceAtMillis > 0
            Mode.INTERVAL -> intervalMinutes >= MIN_INTERVAL_MINUTES
            Mode.DAILY -> true
            Mode.WEEKLY -> daysOfWeek.isNotEmpty()
        }

    /**
     * The next moment this schedule should fire, strictly after [after].
     *
     * @return epoch millis, or null when nothing is due — a disabled schedule, a
     *   manual one, or a one-shot that has already happened.
     */
    fun nextRunAfter(after: Long, calendar: Calendar = Calendar.getInstance()): Long? {
        if (!isAutomatic || !isComplete) return null

        return when (mode) {
            Mode.MANUAL -> null

            Mode.ONCE -> onceAtMillis.takeIf { it > after }

            // Anchored on the last run so that enabling a schedule does not start
            // a run immediately, and a phone that was off does not fire a burst.
            Mode.INTERVAL -> {
                val step = TimeUnit.MINUTES.toMillis(intervalMinutes.toLong())
                val anchor = if (lastRunMillis > 0) lastRunMillis else after
                var next = anchor + step
                if (next <= after) {
                    val missed = (after - anchor) / step
                    next = anchor + (missed + 1) * step
                }
                next
            }

            Mode.DAILY -> atTimeOnOrAfter(after, calendar) { true }

            Mode.WEEKLY -> atTimeOnOrAfter(after, calendar) { day -> daysOfWeek.contains(day) }
        }
    }

    /** Walks forward day by day until [accept] likes the weekday. */
    private fun atTimeOnOrAfter(after: Long, calendar: Calendar, accept: (Int) -> Boolean): Long? {
        val c = calendar.clone() as Calendar
        c.timeInMillis = after
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, minute)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= after) c.add(Calendar.DAY_OF_YEAR, 1)

        repeat(8) {
            if (accept(c.get(Calendar.DAY_OF_WEEK))) return c.timeInMillis
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    /**
     * True when a run was due while the device was unable to fire it — after a
     * reboot, or a long doze. Used to tell the user rather than pretend.
     */
    fun missedSince(now: Long, calendar: Calendar = Calendar.getInstance()): Boolean {
        if (!isAutomatic || lastRunMillis <= 0) return false
        val due = nextRunAfter(lastRunMillis, calendar) ?: return false
        return due < now - MISSED_GRACE_MS
    }

    companion object {
        /** Below this, a schedule is a battery drain rather than a feature. */
        const val MIN_INTERVAL_MINUTES = 15

        /** How late a run has to be before it counts as missed. */
        const val MISSED_GRACE_MS = 30 * 60 * 1000L

        fun manual(pluginId: String) = Schedule(pluginId = pluginId)
    }
}
