package com.zetaforge.app.ui

import android.content.Context
import android.text.format.DateFormat
import com.zetaforge.app.R
import com.zetaforge.runtime.schedule.Schedule
import java.util.Calendar
import java.util.Date

/**
 * Turns a [Schedule] into the sentences the UI and the notifications show.
 *
 * One place, because the same schedule is described in four: the card badge, the
 * dialog preview, the banner after saving, and the diagnostics report. Four
 * phrasings of the same rule is how a user stops trusting any of them.
 */
object ScheduleFormatter {

    /** "every day at 03:00", "every 2 hours", "Mon, Thu at 21:30". */
    fun summary(context: Context, schedule: Schedule): String = when (schedule.mode) {
        Schedule.Mode.MANUAL -> context.getString(R.string.schedule_mode_manual)

        Schedule.Mode.ONCE ->
            context.getString(R.string.schedule_summary_once, dateTime(context, schedule.onceAtMillis))

        Schedule.Mode.INTERVAL ->
            context.getString(R.string.schedule_summary_interval, interval(context, schedule.intervalMinutes))

        Schedule.Mode.DAILY ->
            context.getString(R.string.schedule_summary_daily, time(context, schedule.minuteOfDay))

        Schedule.Mode.WEEKLY -> context.getString(
            R.string.schedule_summary_weekly,
            days(context, schedule.daysOfWeek),
            time(context, schedule.minuteOfDay),
        )
    }

    /** "in 4 h", "tomorrow at 03:00" — what the user wants to know at a glance. */
    fun nextRun(context: Context, schedule: Schedule): String {
        val next = schedule.nextRunAfter(System.currentTimeMillis())
            ?: return context.getString(R.string.schedule_next_none)
        return dateTime(context, next)
    }

    fun lastRun(context: Context, schedule: Schedule): String {
        if (schedule.lastRunMillis <= 0) return context.getString(R.string.schedule_never_run)
        val outcome = when (schedule.lastResult) {
            Schedule.LastResult.SUCCESS -> R.string.schedule_result_success
            Schedule.LastResult.FAILURE -> R.string.schedule_result_failure
            Schedule.LastResult.SKIPPED -> R.string.schedule_result_skipped
            Schedule.LastResult.MISSED -> R.string.schedule_result_missed
            Schedule.LastResult.NONE -> null
        }
        val when_ = dateTime(context, schedule.lastRunMillis)
        return if (outcome == null) when_ else "$when_ · ${context.getString(outcome)}"
    }

    fun time(context: Context, minuteOfDay: Int): String {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
        }
        // Honours the user's 12/24-hour choice rather than imposing one.
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        return DateFormat.format(pattern, calendar).toString()
    }

    fun dateTime(context: Context, millis: Long): String {
        val pattern = if (DateFormat.is24HourFormat(context)) "d MMM, HH:mm" else "d MMM, h:mm a"
        return DateFormat.format(pattern, Date(millis)).toString()
    }

    fun date(context: Context, millis: Long): String =
        DateFormat.format("d MMM yyyy", Date(millis)).toString()

    fun interval(context: Context, minutes: Int): String = when {
        minutes < 60 -> context.getString(R.string.schedule_interval_minutes, minutes)
        minutes == 60 -> context.getString(R.string.schedule_interval_hour)
        minutes % (60 * 24) == 0 && minutes / (60 * 24) == 1 ->
            context.getString(R.string.schedule_interval_day)
        minutes % 60 == 0 -> context.getString(R.string.schedule_interval_hours, minutes / 60)
        else -> context.getString(R.string.schedule_interval_minutes, minutes)
    }

    /** Short weekday names, in the order the user's locale starts the week. */
    fun days(context: Context, days: Set<Int>): String {
        if (days.isEmpty()) return ""
        return weekOrder().filter { days.contains(it) }.joinToString(", ") { dayName(it) }
    }

    fun weekOrder(): List<Int> {
        val first = Calendar.getInstance().firstDayOfWeek
        return (0..6).map { offset -> ((first - 1 + offset) % 7) + 1 }
    }

    fun dayName(dayOfWeek: Int): String {
        val calendar = Calendar.getInstance().apply { set(Calendar.DAY_OF_WEEK, dayOfWeek) }
        return DateFormat.format("EEE", calendar).toString()
    }

    fun dayInitial(dayOfWeek: Int): String = dayName(dayOfWeek).take(1).uppercase()
}
