package com.zetaforge.app.permission

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import com.zetaforge.runtime.permission.PermissionGateway
import com.zetaforge.runtime.permission.PermissionInspector
import com.zetaforge.runtime.permission.PermissionOutcome
import com.zetaforge.runtime.permission.PermissionPlan
import com.zetaforge.runtime.permission.SpecialAccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * The bridge between the runtime and Android's permission UI.
 *
 * Only an Activity can show the system dialog or open the Settings screens for
 * special access, so the Activity registers its launchers here and the gateway
 * turns them into suspending calls the runtime can await.
 *
 * Everything is re-checked afterwards by the runtime: this class reports what
 * the user did, it never decides whether a plugin may run.
 */
class ActivityPermissionGateway(
    private val activity: ComponentActivity,
    private val inspector: PermissionInspector,
    /**
     * Shown before the system dialog so the user knows which plugin is asking
     * and why. Returns false to abort without prompting.
     */
    private val onExplain: suspend (PermissionPlan) -> Boolean,
    /** Invoked for special access, which needs its own screen and explanation. */
    private val onSpecialAccess: suspend (SpecialAccess, String) -> Boolean,
) : PermissionGateway {

    private val lock = Mutex()
    private var pending: CompletableDeferred<Map<String, Boolean>>? = null
    private var pendingSpecial: CompletableDeferred<Unit>? = null

    private val permissionLauncher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            pending?.complete(result)
            pending = null
        }

    private val settingsLauncher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // The result code is meaningless for these screens: the only reliable
            // signal is re-reading the state once we are back.
            pendingSpecial?.complete(Unit)
            pendingSpecial = null
        }

    override suspend fun request(plan: PermissionPlan): PermissionOutcome = lock.withLock {
        val granted = mutableListOf<String>()
        val denied = mutableListOf<String>()
        val permanently = mutableListOf<String>()
        val specialGranted = mutableListOf<SpecialAccess>()
        val specialDenied = mutableListOf<SpecialAccess>()

        val toRequest = plan.requestable.map { it.name }
        val permanentlyDenied = plan.permanentlyDenied.map { it.name }

        if (toRequest.isNotEmpty() || permanentlyDenied.isNotEmpty() || plan.missingSpecialAccess.isNotEmpty()) {
            val proceed = onExplain(plan)
            if (!proceed) return@withLock PermissionOutcome.cancelled()
        }

        if (toRequest.isNotEmpty()) {
            val deferred = CompletableDeferred<Map<String, Boolean>>()
            pending = deferred
            withContext(Dispatchers.Main) { permissionLauncher.launch(toRequest.toTypedArray()) }
            val result = deferred.await()

            result.forEach { (permission, isGranted) ->
                if (isGranted) {
                    granted += permission
                } else {
                    // No rationale after a denial means "don't ask again": only
                    // the Settings screen can change it from now on.
                    val rationale = ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
                    inspector.updateRationaleState(permission, rationale)
                    if (rationale) denied += permission else permanently += permission
                }
            }
        }

        plan.missingSpecialAccess.forEach { requirement ->
            val access = requirement.access
            val proceed = onSpecialAccess(access, requirement.reason)
            if (!proceed) {
                specialDenied += access
                return@forEach
            }
            val deferred = CompletableDeferred<Unit>()
            pendingSpecial = deferred
            withContext(Dispatchers.Main) { settingsLauncher.launch(intentFor(access)) }
            deferred.await()
            if (inspector.isSpecialAccessGranted(access)) specialGranted += access else specialDenied += access
        }

        PermissionOutcome(
            granted = granted,
            denied = denied,
            permanentlyDenied = permanently,
            specialAccessGranted = specialGranted,
            specialAccessDenied = specialDenied,
        )
    }

    /** Opens the app's permission page, for permissions denied permanently. */
    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    /**
     * Each special access lives behind its own Settings screen. The
     * package-scoped intents are tried first and fall back to the global screen
     * when an OEM does not implement them.
     */
    private fun intentFor(access: SpecialAccess): Intent {
        val packageUri = Uri.fromParts("package", activity.packageName, null)
        val intent = when (access) {
            SpecialAccess.ALL_FILES_ACCESS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
                } else {
                    appDetails(packageUri)
                }

            SpecialAccess.DISPLAY_OVER_OTHER_APPS ->
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)

            SpecialAccess.EXACT_ALARMS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)
                } else {
                    appDetails(packageUri)
                }

            SpecialAccess.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

            SpecialAccess.NOTIFICATION_ACCESS ->
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")

            SpecialAccess.IGNORE_BATTERY_OPTIMIZATIONS ->
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

            SpecialAccess.INSTALL_PACKAGES ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri)
                } else {
                    appDetails(packageUri)
                }

            SpecialAccess.WRITE_SETTINGS ->
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, packageUri)

            // Android exposes no intent that lands on one permission group, so
            // this is the app page and the user takes the last two steps:
            // Permissions, Location, Allow all the time.
            SpecialAccess.BACKGROUND_LOCATION -> appDetails(packageUri)
        }
        return if (intent.resolveActivity(activity.packageManager) != null) intent else appDetails(packageUri)
    }

    private fun appDetails(packageUri: Uri) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)

    companion object {
        /** True when the Activity is in a state where a dialog can be shown. */
        fun canPrompt(activity: Activity): Boolean = !activity.isFinishing && !activity.isDestroyed
    }
}
