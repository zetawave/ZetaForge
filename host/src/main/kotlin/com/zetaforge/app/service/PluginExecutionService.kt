package com.zetaforge.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.zetaforge.app.MainActivity
import com.zetaforge.app.R
import com.zetaforge.runtime.task.RunningTask
import com.zetaforge.runtime.task.ZetaTaskCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps a plugin run alive and visible.
 *
 * The problem it solves is not cosmetic: with the screen off Android freezes any
 * process without a foreground component, so a long transfer stops until the
 * phone wakes up (measured on a real device: an 8-minute stall mid-backup). A
 * process running a foreground service is never frozen, so the work continues
 * with the screen off and even if the app is no longer on screen.
 *
 * The service owns nothing about plugins: it mirrors [ZetaTaskCenter], which the
 * runtime updates, and offers a Stop action that asks the runtime to cancel.
 */
class PluginExecutionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ZetaTaskCenter.current.value?.let { ZetaTaskCenter.requestCancel(it.pluginId) }
            return START_NOT_STICKY
        }

        // Android requires the notification to be posted immediately, before the
        // first progress update ever arrives.
        startForegroundCompat(buildNotification(ZetaTaskCenter.current.value))

        if (observer == null) {
            observer = scope.launch {
                // The service is started just *before* the runtime publishes the
                // task, so the first value observed is null. Stopping on it would
                // kill the service before the plugin even begins - which is
                // exactly what used to happen, leaving no notification and no
                // protection from the process being frozen.
                var started = false
                launch {
                    delay(STARTUP_GRACE_MS)
                    if (!started) stopSelf()
                }

                ZetaTaskCenter.current.collectLatest { task ->
                    when {
                        task != null -> {
                            started = true
                            NotificationManagerCompat.from(this@PluginExecutionService)
                                .notify(NOTIFICATION_ID, buildNotification(task))
                        }

                        started -> stopSelf()
                        else -> Unit // still waiting for the run to be published
                    }
                }
            }
        }

        // Not sticky: a run cannot be resumed by restarting the service, and the
        // plugin's own state is what makes work resumable.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(task: RunningTask?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, PluginExecutionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(task?.pluginName ?: getString(R.string.app_name))
            .setContentText(task?.progress?.message?.takeIf { it.isNotBlank() } ?: getString(R.string.notification_running))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.notification_stop), stop)

        val percent = task?.progress?.percent
        if (percent != null) {
            builder.setProgress(100, percent, false)
            builder.setSubText("$percent%")
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_runs),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_runs_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "zetaforge.plugin.runs"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_STOP = "com.zetaforge.app.action.STOP_PLUGIN"

        /** How long to wait for the run to be published before giving up. */
        private const val STARTUP_GRACE_MS = 20_000L

        /**
         * Started while the app is in the foreground - which it is, since a run
         * begins with the user tapping START - as Android requires.
         */
        fun start(context: Context) {
            val intent = Intent(context, PluginExecutionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PluginExecutionService::class.java))
        }
    }
}
