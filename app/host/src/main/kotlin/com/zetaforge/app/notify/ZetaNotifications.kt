package com.zetaforge.app.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.zetaforge.app.MainActivity
import com.zetaforge.app.R
import com.zetaforge.runtime.task.RunningTask

/**
 * Every notification the app posts, in one place.
 *
 * The rule this file exists to enforce: **a run is never silent**. Whether the
 * user started it or an alarm did, whether it reports progress or not, whether
 * the app is on screen or was closed hours ago — something happened on their
 * device with their permissions, and they get told. Anything less makes a
 * background task indistinguishable from a bug.
 *
 * Four channels, because they answer four different questions and a user should
 * be able to silence one without losing the others:
 *
 * | channel | question |
 * |---|---|
 * | runs | is something running right now? |
 * | results | how did it end? |
 * | schedule | what is going to happen, and what did not happen? |
 * | attention | something needs me before it can work |
 */
object ZetaNotifications {

    const val CHANNEL_RUNS = "zetaforge.runs"
    const val CHANNEL_RESULTS = "zetaforge.results"
    const val CHANNEL_SCHEDULE = "zetaforge.schedule"
    const val CHANNEL_ATTENTION = "zetaforge.attention"

    /** The ongoing run: one id, reused, because only one run happens at a time. */
    const val ID_RUNNING = 4201
    const val ID_TIME_LIMIT = 4202

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RUNS,
                context.getString(R.string.channel_runs),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_runs_description)
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULTS,
                context.getString(R.string.channel_results),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.channel_results_description)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SCHEDULE,
                context.getString(R.string.channel_schedule),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_schedule_description)
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ATTENTION,
                context.getString(R.string.channel_attention),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_attention_description)
            }
        )
    }

    // -- the ongoing run -------------------------------------------------------

    /**
     * The notification the foreground service holds up for the whole run.
     *
     * [scheduled] changes the wording rather than the shape: a run the user did
     * not start has to say so, otherwise it reads as the app doing things behind
     * their back.
     */
    fun running(
        context: Context,
        task: RunningTask?,
        scheduled: Boolean,
        stopIntent: PendingIntent,
    ): Notification {
        val title = task?.pluginName ?: context.getString(R.string.app_name)
        val text = task?.progress?.message?.takeIf { it.isNotBlank() }
            ?: context.getString(
                if (scheduled) R.string.notification_running_scheduled else R.string.notification_running
            )

        val builder = NotificationCompat.Builder(context, CHANNEL_RUNS)
            .setSmallIcon(R.drawable.ic_stat_zeta)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.notification_stop), stopIntent)

        if (scheduled) builder.setSubText(context.getString(R.string.notification_subtext_scheduled))

        val percent = task?.progress?.percent
        if (percent != null) {
            builder.setProgress(100, percent, false)
            builder.setSubText("$percent%")
        } else {
            // A plugin that reports no progress still gets a bar: "indeterminate"
            // is honest, an absent bar looks stuck.
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    // -- outcomes --------------------------------------------------------------

    /**
     * Posted when a run ends. Always — including a run the user watched start,
     * because they may well have locked the phone in the meantime.
     */
    fun result(
        context: Context,
        pluginId: String,
        pluginName: String,
        success: Boolean,
        message: String,
        durationMs: Long,
        scheduled: Boolean,
    ) {
        val duration = formatDuration(context, durationMs)
        val title = context.getString(
            if (success) R.string.notification_result_success else R.string.notification_result_failure,
            pluginName,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_stat_zeta)
            .setContentTitle(title)
            .setContentText(message.ifBlank { context.getString(R.string.notification_result_no_message) })
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSubText(
                if (scheduled) context.getString(R.string.notification_subtext_scheduled_done, duration)
                else duration
            )
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (success) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .build()
        post(context, idFor(pluginId, OFFSET_RESULT), notification)
    }

    /** A scheduled run that could not start, and why. */
    fun skipped(context: Context, pluginId: String, pluginName: String, reason: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_SCHEDULE)
            .setSmallIcon(R.drawable.ic_stat_zeta)
            .setContentTitle(context.getString(R.string.notification_skipped, pluginName))
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        post(context, idFor(pluginId, OFFSET_SKIPPED), notification)
    }

    /**
     * A scheduled run that needs the user before it can happen at all — a
     * permission was revoked, or exact alarms were turned off.
     */
    fun needsAttention(
        context: Context,
        pluginId: String,
        pluginName: String,
        message: String,
        action: PendingIntent? = null,
        actionLabel: String? = null,
    ) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_stat_zeta)
            .setContentTitle(context.getString(R.string.notification_attention, pluginName))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(action ?: openApp(context))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (action != null && actionLabel != null) builder.addAction(0, actionLabel, action)
        post(context, idFor(pluginId, OFFSET_ATTENTION), builder.build())
    }

    /** The run was stopped by Android's foreground-service time budget. */
    fun timeLimited(context: Context, pluginName: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_stat_zeta)
            .setContentTitle(pluginName)
            .setContentText(context.getString(R.string.notification_time_limit))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notification_time_limit)))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        post(context, ID_TIME_LIMIT, notification)
    }

    fun cancel(context: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    // -- plumbing --------------------------------------------------------------

    private fun post(context: Context, id: Int, notification: Notification) {
        // Posting without POST_NOTIFICATIONS throws on Android 13+; the run must
        // not fail because of it.
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Stable per plugin, so a second run replaces the first one's notice. */
    private fun idFor(pluginId: String, offset: Int): Int =
        (pluginId.hashCode() and 0x0000FFFF) + offset

    private fun formatDuration(context: Context, ms: Long): String = when {
        ms < 1000 -> context.getString(R.string.duration_ms, ms)
        ms < 60_000 -> context.getString(R.string.duration_s, ms / 1000.0)
        else -> context.getString(R.string.duration_m, ms / 60_000, (ms % 60_000) / 1000)
    }

    private const val OFFSET_RESULT = 100_000
    private const val OFFSET_SKIPPED = 200_000
    private const val OFFSET_ATTENTION = 300_000
}
