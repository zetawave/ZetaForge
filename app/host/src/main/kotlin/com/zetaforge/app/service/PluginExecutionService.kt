package com.zetaforge.app.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import com.zetaforge.app.R
import com.zetaforge.app.ZetaForgeApp
import com.zetaforge.app.notify.ZetaNotifications
import com.zetaforge.app.schedule.ScheduleAlarms
import com.zetaforge.runtime.schedule.Schedule
import com.zetaforge.runtime.task.RunningTask
import com.zetaforge.runtime.task.ZetaTaskCenter
import com.zetaforge.sdk.PluginResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Where every plugin run happens, whoever asked for it.
 *
 * Two reasons it owns execution rather than merely accompanying it:
 *
 * * **the screen goes off.** A process without a foreground component is frozen
 *   by Android within seconds; measured on a real device, a backup stalled for
 *   eight minutes mid-transfer until the phone was woken. A foreground service
 *   is never frozen.
 * * **there may be no UI.** A scheduled run arrives as a broadcast in a process
 *   that has no Activity and no view model. The run has to live somewhere that
 *   does not assume anyone is watching.
 *
 * A manual run still starts here, through [runManual], so both paths produce the
 * same notifications and the same bookkeeping.
 */
class PluginExecutionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var runJob: Job? = null

    /** Whether the current run was started by an alarm; changes the wording. */
    private var scheduled = false

    /**
     * Whether this run reads the device location, which decides the type the
     * foreground service is started with. See [startForegroundCompat].
     */
    private var needsLocation = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ZetaNotifications.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ZetaTaskCenter.current.value?.let { ZetaTaskCenter.requestCancel(it.pluginId) }
                runJob?.cancel()
                return START_NOT_STICKY
            }

            ACTION_RUN -> {
                scheduled = intent.getBooleanExtra(EXTRA_SCHEDULED, false)
                val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID)
                needsLocation = intent.getBooleanExtra(EXTRA_NEEDS_LOCATION, false) ||
                    pluginId?.let { declaresLocation(it) } == true
                startForegroundCompat(ZetaNotifications.running(this, ZetaTaskCenter.current.value, scheduled, stopIntent()))
                observeTask()
                pluginId?.let { execute(it) }
                return START_NOT_STICKY
            }

            else -> {
                // Accompanying a run the caller drives itself (the UI path before
                // the plugin id is known).
                scheduled = false
                needsLocation = intent?.getBooleanExtra(EXTRA_NEEDS_LOCATION, false) ?: false
                startForegroundCompat(ZetaNotifications.running(this, ZetaTaskCenter.current.value, false, stopIntent()))
                observeTask()
                return START_NOT_STICKY
            }
        }
    }

    /**
     * Runs the plugin and reports the outcome — to the log, to the schedule
     * bookkeeping, and to the user as a notification.
     *
     * Every path posts a notification. A background run that finishes silently is
     * indistinguishable from one that never started.
     */
    private fun execute(pluginId: String) {
        if (runJob?.isActive == true) return

        val app = ZetaForgeApp.instance(this)
        val runtime = app.runtime
        val started = System.currentTimeMillis()

        runJob = scope.launch {
            if (runtime.plugins.value.isEmpty()) runtime.refresh()
            val entry = runtime.plugins.value.firstOrNull { it.id == pluginId }
            val name = entry?.installed?.displayName ?: pluginId

            if (entry == null) {
                ZetaNotifications.needsAttention(
                    this@PluginExecutionService, pluginId, name,
                    getString(R.string.notification_plugin_missing),
                )
                stopSelf()
                return@launch
            }

            val result = try {
                runtime.execute(pluginId, Bundle())
            } catch (t: Throwable) {
                PluginResult.Failure(
                    message = t.message ?: t.javaClass.simpleName,
                    errorCode = "SERVICE_ERROR",
                )
            } finally {
                ZetaTaskCenter.end(pluginId)
            }

            val duration = System.currentTimeMillis() - started
            report(pluginId, name, result, duration)
            stopSelf()
        }
    }

    private fun report(pluginId: String, name: String, result: PluginResult, duration: Long) {
        val runtime = ZetaForgeApp.runtime(this)
        val success = result is PluginResult.Success
        val message = when (result) {
            is PluginResult.Success -> result.message
            is PluginResult.Failure -> result.message
        }

        ZetaNotifications.result(
            context = this,
            pluginId = pluginId,
            pluginName = name,
            success = success,
            message = message,
            durationMs = if (result.durationMs > 0) result.durationMs else duration,
            scheduled = scheduled,
        )

        // Only a scheduled run touches the schedule's bookkeeping: a manual run
        // must not shift the anchor an INTERVAL schedule counts from.
        if (scheduled) {
            runtime.schedules.recordRun(
                pluginId = pluginId,
                result = if (success) Schedule.LastResult.SUCCESS else Schedule.LastResult.FAILURE,
                message = message,
            )
            // The next INTERVAL run is measured from the end of this one.
            ScheduleAlarms.schedule(this, runtime.schedules.get(pluginId))
        }
    }

    /** Mirrors the runtime's progress into the ongoing notification. */
    private fun observeTask() {
        if (observer != null) return
        observer = scope.launch {
            var seen = false
            launch {
                delay(STARTUP_GRACE_MS)
                // Nothing was ever published: the caller failed before starting.
                if (!seen && runJob?.isActive != true) stopSelf()
            }
            ZetaTaskCenter.current.collectLatest { task ->
                when {
                    task != null -> {
                        seen = true
                        notify(task)
                    }
                    // A run that ended while this service was only accompanying it.
                    seen && runJob?.isActive != true -> stopSelf()
                    else -> Unit
                }
            }
        }
    }

    private fun notify(task: RunningTask?) {
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(ZetaNotifications.ID_RUNNING, ZetaNotifications.running(this, task, scheduled, stopIntent()))
        }
    }

    /**
     * Android 15+ caps a `dataSync` foreground service at roughly six hours a
     * day. When the budget runs out the system calls this and expects the service
     * to be gone within seconds — not complying crashes the app.
     *
     * The run is asked to stop the same way the Stop button does: the plugin's
     * state is already on disk, so the work resumes next time instead of being
     * lost.
     */
    override fun onTimeout(startId: Int) = handleTimeout()

    override fun onTimeout(startId: Int, fgsType: Int) = handleTimeout()

    private fun handleTimeout() {
        val task = ZetaTaskCenter.current.value
        if (task != null) {
            ZetaTaskCenter.requestCancel(task.pluginId)
            ZetaNotifications.timeLimited(this, task.pluginName)
        }
        runJob?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, PluginExecutionService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Starts the service with the narrowest type the run actually needs.
     *
     * `location` rather than `dataSync` for a plugin that reads the position,
     * for two reasons that both bite only on recent Android: from API 34 a
     * service typed `dataSync` throws the moment it touches the location APIs,
     * and from API 35 a `dataSync` service is stopped after roughly six hours a
     * day while a `location` one runs for as long as it is needed.
     *
     * The type is downgraded when the location permission is not actually held:
     * declaring a type the app has no permission for is itself a crash.
     */
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(ZetaNotifications.ID_RUNNING, notification)
            return
        }
        val type = if (needsLocation && hasLocationPermission()) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        startForeground(ZetaNotifications.ID_RUNNING, notification, type)
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Whether the plugin about to run declares a location permission.
     *
     * Read from what the runtime already holds in memory: this sits on the path
     * to `startForeground`, which Android gives about five seconds, so it must
     * not wait for a disk scan. When the answer is not known yet, the hint the
     * caller passed (`EXTRA_NEEDS_LOCATION`) is what decides.
     */
    private fun declaresLocation(pluginId: String): Boolean = runCatching {
        ZetaForgeApp.instance(this).runtime.plugins.value
            .firstOrNull { it.id == pluginId }
            ?.installed?.manifest?.permissions
            ?.any { it.name.endsWith("_LOCATION") } == true
    }.getOrDefault(false)

    companion object {
        private const val ACTION_STOP = "com.zetaforge.app.action.STOP_PLUGIN"
        private const val ACTION_RUN = "com.zetaforge.app.action.RUN_PLUGIN_SERVICE"
        private const val EXTRA_PLUGIN_ID = "pluginId"
        private const val EXTRA_SCHEDULED = "scheduled"
        private const val EXTRA_NEEDS_LOCATION = "needsLocation"

        /** How long to wait for a run to be published before giving up. */
        private const val STARTUP_GRACE_MS = 20_000L

        /**
         * Started from the UI, which drives the run itself.
         *
         * @param needsLocation the run reads the device position, so the
         *   service has to be typed `location` rather than `dataSync`.
         */
        fun start(context: Context, needsLocation: Boolean = false) {
            launch(
                context,
                Intent(context, PluginExecutionService::class.java)
                    .putExtra(EXTRA_NEEDS_LOCATION, needsLocation),
            )
        }

        /** Started from the UI, with the service running the plugin. */
        fun runManual(context: Context, pluginId: String, needsLocation: Boolean = false) {
            launch(
                context,
                Intent(context, PluginExecutionService::class.java)
                    .setAction(ACTION_RUN)
                    .putExtra(EXTRA_PLUGIN_ID, pluginId)
                    .putExtra(EXTRA_SCHEDULED, false)
                    .putExtra(EXTRA_NEEDS_LOCATION, needsLocation),
            )
        }

        /** Started from an alarm, with no UI anywhere. */
        fun runScheduled(context: Context, pluginId: String, needsLocation: Boolean = false) {
            launch(
                context,
                Intent(context, PluginExecutionService::class.java)
                    .setAction(ACTION_RUN)
                    .putExtra(EXTRA_PLUGIN_ID, pluginId)
                    .putExtra(EXTRA_SCHEDULED, true)
                    .putExtra(EXTRA_NEEDS_LOCATION, needsLocation),
            )
        }

        /**
         * Asks the running plugin to stop, exactly as the notification Stop
         * button does: the run is cancelled and the service goes away with it.
         * Stopping the service outright would leave the job running unattended.
         */
        fun stopRun(context: Context) {
            launch(
                context,
                Intent(context, PluginExecutionService::class.java).setAction(ACTION_STOP),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PluginExecutionService::class.java))
        }

        private fun launch(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
