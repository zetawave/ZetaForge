package com.zetaforge.runtime.permission

import android.os.Build

/**
 * One permission a plugin declares in its `.zeta` manifest.
 *
 * Android splits permissions into three worlds and each needs a different
 * mechanism, so the model carries enough information to route a request
 * correctly without the Host knowing anything about the plugin:
 *
 * * **install-time** (`INTERNET`, `ACCESS_NETWORK_STATE`, ...): granted when the
 *   Host is installed, provided the Host manifest declares them. Nothing to ask.
 * * **runtime / dangerous** (`READ_MEDIA_IMAGES`, `CAMERA`, ...): need the system
 *   dialog, can be denied, can be denied permanently.
 * * **special access** ("all files", overlay, exact alarms, ...): are not
 *   requestable at all, the user must flip a switch in a dedicated Settings
 *   screen; see [SpecialAccess].
 *
 * @param name fully qualified Android permission, e.g. `android.permission.CAMERA`.
 * @param reason shown to the user in the rationale dialog. Write it for a human.
 * @param optional when true the plugin still runs if the permission is denied;
 *   the runtime reports which optional permissions were missing.
 * @param minSdk lowest API level on which the permission exists / is needed.
 * @param maxSdk highest API level on which it applies (e.g. `READ_EXTERNAL_STORAGE`
 *   is superseded by the media permissions from API 33).
 */
data class PermissionRequirement(
    val name: String,
    val reason: String = "",
    val optional: Boolean = false,
    val minSdk: Int = 1,
    val maxSdk: Int = Int.MAX_VALUE,
) {
    /** True when this permission is meaningful on the device we are running on. */
    fun appliesTo(sdkInt: Int): Boolean = sdkInt in minSdk..maxSdk

    val shortName: String get() = name.substringAfterLast('.')
}

/**
 * Capabilities Android deliberately keeps out of `requestPermissions()`: they are
 * granted by the user in a dedicated Settings screen, reached through an Intent,
 * and the result comes back only by re-checking the state afterwards.
 */
enum class SpecialAccess(
    val id: String,
    val label: String,
    val minSdk: Int,
) {
    /** Unfiltered read/write over shared storage (`MANAGE_EXTERNAL_STORAGE`). */
    ALL_FILES_ACCESS("allFilesAccess", "All files access", Build.VERSION_CODES.R),

    /** Draw over other apps (`SYSTEM_ALERT_WINDOW`). */
    DISPLAY_OVER_OTHER_APPS("displayOverOtherApps", "Display over other apps", Build.VERSION_CODES.M),

    /** Schedule exact alarms (`SCHEDULE_EXACT_ALARM`). */
    EXACT_ALARMS("exactAlarms", "Alarms & reminders", Build.VERSION_CODES.S),

    /** Read usage statistics (`PACKAGE_USAGE_STATS`). */
    USAGE_ACCESS("usageAccess", "Usage access", Build.VERSION_CODES.LOLLIPOP),

    /** Read every notification (`BIND_NOTIFICATION_LISTENER_SERVICE`). */
    NOTIFICATION_ACCESS("notificationAccess", "Notification access", Build.VERSION_CODES.JELLY_BEAN_MR2),

    /** Ignore battery optimisations. */
    IGNORE_BATTERY_OPTIMIZATIONS("ignoreBatteryOptimizations", "Unrestricted battery usage", Build.VERSION_CODES.M),

    /** Install other apps (`REQUEST_INSTALL_PACKAGES`). */
    INSTALL_PACKAGES("installPackages", "Install unknown apps", Build.VERSION_CODES.O),

    /** Modify system settings (`WRITE_SETTINGS`). */
    WRITE_SETTINGS("writeSettings", "Modify system settings", Build.VERSION_CODES.M),

    /**
     * Read the location while the app is not in the foreground
     * (`ACCESS_BACKGROUND_LOCATION`).
     *
     * A runtime permission by type, but not by behaviour, which is why it lives
     * here: from API 30 the system dialog no longer offers "Allow all the
     * time", so asking for it through `requestPermissions()` returns denied
     * without showing anything. Worse, bundling it into the same request as the
     * foreground location permissions makes Android deny *all* of them
     * silently. Routed as special access it gets what it actually needs - the
     * foreground permission granted first, then the Settings page, then a
     * re-check on the way back - which is exactly the shape of this flow.
     */
    BACKGROUND_LOCATION("backgroundLocation", "Location all the time", Build.VERSION_CODES.Q);

    companion object {
        fun fromId(id: String): SpecialAccess? =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) || it.name.equals(id, ignoreCase = true) }
    }
}

/** A special access request as declared by a plugin. */
data class SpecialAccessRequirement(
    val access: SpecialAccess,
    val reason: String = "",
    val optional: Boolean = false,
)

/** State of one permission for the current process, on this device. */
enum class PermissionState {
    /** Held by the process right now. */
    GRANTED,

    /** Declared by the Host, not granted yet: the system dialog can be shown. */
    REQUESTABLE,

    /** Declared and denied with "don't ask again": only Settings can fix it. */
    PERMANENTLY_DENIED,

    /**
     * Not present in the Host manifest. Android denies it without showing a
     * dialog, so it can only be fixed by rebuilding the Host.
     */
    NOT_DECLARED_BY_HOST,

    /** Does not apply to this API level (e.g. legacy storage on API 33+). */
    NOT_APPLICABLE,
}

/** Per-permission evaluation result. */
data class PermissionStatus(
    val requirement: PermissionRequirement,
    val state: PermissionState,
)

/** Per-special-access evaluation result. */
data class SpecialAccessStatus(
    val requirement: SpecialAccessRequirement,
    val granted: Boolean,
    val applicable: Boolean,
)

/**
 * Everything the Host must do before a given plugin may run.
 *
 * Produced by [PermissionInspector] on every execution: permissions can be
 * revoked from Settings or auto-revoked by Android at any time, so the answer is
 * never cached.
 */
data class PermissionPlan(
    val pluginId: String,
    val permissions: List<PermissionStatus>,
    val specialAccess: List<SpecialAccessStatus>,
) {
    val requestable: List<PermissionRequirement>
        get() = permissions.filter { it.state == PermissionState.REQUESTABLE }.map { it.requirement }

    val permanentlyDenied: List<PermissionRequirement>
        get() = permissions.filter { it.state == PermissionState.PERMANENTLY_DENIED }.map { it.requirement }

    /** Required permissions the Host manifest does not declare: a build-time bug. */
    val undeclared: List<PermissionRequirement>
        get() = permissions.filter { it.state == PermissionState.NOT_DECLARED_BY_HOST }.map { it.requirement }

    val missingSpecialAccess: List<SpecialAccessRequirement>
        get() = specialAccess.filter { it.applicable && !it.granted }.map { it.requirement }

    private fun mandatory(list: List<PermissionRequirement>) = list.filterNot { it.optional }

    /** True when the plugin can run right now. */
    val isSatisfied: Boolean
        get() = mandatory(requestable).isEmpty() &&
            mandatory(permanentlyDenied).isEmpty() &&
            mandatory(undeclared).isEmpty() &&
            missingSpecialAccess.none { !it.optional }

    /** True when showing UI could still fix the situation. */
    val isActionable: Boolean
        get() = requestable.isNotEmpty() || permanentlyDenied.isNotEmpty() || missingSpecialAccess.isNotEmpty()

    /** Optional permissions the plugin will simply have to live without. */
    val missingOptional: List<PermissionRequirement>
        get() = (requestable + permanentlyDenied + undeclared).filter { it.optional }

    fun summary(): String = buildString {
        append(permissions.count { it.state == PermissionState.GRANTED })
        append('/')
        append(permissions.count { it.state != PermissionState.NOT_APPLICABLE })
        append(" permissions granted")
        if (specialAccess.isNotEmpty()) {
            append(", ")
            append(specialAccess.count { it.granted })
            append('/')
            append(specialAccess.count { it.applicable })
            append(" special access")
        }
    }
}

/** Result of asking the user for whatever [PermissionPlan] was missing. */
data class PermissionOutcome(
    val granted: List<String>,
    val denied: List<String>,
    val permanentlyDenied: List<String>,
    val specialAccessGranted: List<SpecialAccess>,
    val specialAccessDenied: List<SpecialAccess>,
    /** True when the user dismissed the flow instead of answering. */
    val cancelled: Boolean = false,
) {
    companion object {
        val NOTHING_ASKED = PermissionOutcome(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

        fun cancelled() = NOTHING_ASKED.copy(cancelled = true)
    }
}
