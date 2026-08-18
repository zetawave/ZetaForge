package com.zetaforge.runtime.permission

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.zetaforge.runtime.manifest.ZetaManifest

/**
 * Answers "may this plugin run right now, and if not, what has to happen first?".
 *
 * Deliberately split in two: [evaluate] is pure logic over injected facts and is
 * unit tested without a device, while [PermissionInspector] gathers those facts
 * from the platform.
 */
object PermissionRules {

    /**
     * @param declaredByHost permissions present in the Host's merged manifest.
     * @param granted permissions currently held by the process.
     * @param permanentlyDenied permissions the user denied with "don't ask again".
     * @param specialAccessGranted special accesses currently enabled.
     */
    fun evaluate(
        manifest: ZetaManifest,
        sdkInt: Int,
        declaredByHost: Set<String>,
        granted: Set<String>,
        permanentlyDenied: Set<String>,
        specialAccessGranted: Set<SpecialAccess>,
    ): PermissionPlan {
        val permissions = manifest.permissions.map { requirement ->
            val state = when {
                !requirement.appliesTo(sdkInt) -> PermissionState.NOT_APPLICABLE
                granted.contains(requirement.name) -> PermissionState.GRANTED
                !declaredByHost.contains(requirement.name) -> PermissionState.NOT_DECLARED_BY_HOST
                permanentlyDenied.contains(requirement.name) -> PermissionState.PERMANENTLY_DENIED
                else -> PermissionState.REQUESTABLE
            }
            PermissionStatus(requirement, state)
        }

        val special = manifest.specialAccess.map { requirement ->
            val applicable = sdkInt >= requirement.access.minSdk
            SpecialAccessStatus(
                requirement = requirement,
                granted = !applicable || specialAccessGranted.contains(requirement.access),
                applicable = applicable,
            )
        }

        return PermissionPlan(manifest.pluginId, permissions, special)
    }
}

/**
 * Platform-backed inspector.
 *
 * "Permanently denied" cannot be read directly on Android: the signal is
 * `shouldShowRequestPermissionRationale() == false` *after* at least one denial.
 * The runtime therefore records the permissions it has already asked for
 * ([markAsked]) and the Host feeds back the rationale answer, so a first-time
 * request is never mistaken for a permanent denial.
 */
class PermissionInspector(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {

    private val asked = mutableSetOf<String>()
    private val rationaleUnavailable = mutableSetOf<String>()

    /** Permissions declared in the Host's merged manifest (the hard ceiling). */
    val declaredByHost: Set<String> by lazy {
        runCatching {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS,
            )
            info.requestedPermissions?.toSet().orEmpty()
        }.getOrDefault(emptySet())
    }

    fun inspect(manifest: ZetaManifest): PermissionPlan = PermissionRules.evaluate(
        manifest = manifest,
        sdkInt = sdkInt,
        declaredByHost = declaredByHost,
        granted = manifest.permissions.map { it.name }.filter { isGranted(it) }.toSet(),
        permanentlyDenied = manifest.permissions.map { it.name }
            .filter { asked.contains(it) && rationaleUnavailable.contains(it) && !isGranted(it) }
            .toSet(),
        specialAccessGranted = SpecialAccess.entries.filter { isSpecialAccessGranted(it) }.toSet(),
    )

    fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Records that the system dialog has been shown for these permissions. */
    fun markAsked(permissions: Collection<String>) {
        asked += permissions
    }

    /**
     * Feeds back `shouldShowRequestPermissionRationale` from the Activity, which
     * is the only place where it can be evaluated.
     */
    fun updateRationaleState(permission: String, rationaleAvailable: Boolean) {
        if (rationaleAvailable) rationaleUnavailable -= permission else rationaleUnavailable += permission
    }

    fun isSpecialAccessGranted(access: SpecialAccess): Boolean {
        if (sdkInt < access.minSdk) return true
        return when (access) {
            SpecialAccess.ALL_FILES_ACCESS ->
                if (sdkInt >= Build.VERSION_CODES.R) android.os.Environment.isExternalStorageManager() else true

            SpecialAccess.DISPLAY_OVER_OTHER_APPS -> Settings.canDrawOverlays(context)

            SpecialAccess.EXACT_ALARMS -> if (sdkInt >= Build.VERSION_CODES.S) {
                val alarms = context.getSystemService(android.app.AlarmManager::class.java)
                alarms?.canScheduleExactAlarms() ?: false
            } else {
                true
            }

            SpecialAccess.USAGE_ACCESS -> hasAppOp("android:get_usage_stats")

            SpecialAccess.NOTIFICATION_ACCESS -> {
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners",
                ).orEmpty()
                enabled.contains(context.packageName)
            }

            SpecialAccess.IGNORE_BATTERY_OPTIMIZATIONS -> {
                val power = context.getSystemService(android.os.PowerManager::class.java)
                power?.isIgnoringBatteryOptimizations(context.packageName) ?: false
            }

            SpecialAccess.INSTALL_PACKAGES ->
                if (sdkInt >= Build.VERSION_CODES.O) context.packageManager.canRequestPackageInstalls() else true

            SpecialAccess.WRITE_SETTINGS -> Settings.System.canWrite(context)
        }
    }

    private fun hasAppOp(op: String): Boolean = runCatching {
        val appOps = context.getSystemService(android.app.AppOpsManager::class.java) ?: return false
        val mode = if (sdkInt >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(op, android.os.Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(op, android.os.Process.myUid(), context.packageName)
        }
        mode == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
}
