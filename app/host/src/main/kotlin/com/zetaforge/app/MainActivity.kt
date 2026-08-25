package com.zetaforge.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zetaforge.app.permission.ActivityPermissionGateway
import com.zetaforge.app.ui.AppPreferences
import com.zetaforge.app.share.PluginPackages
import com.zetaforge.app.update.AppUpdates
import com.zetaforge.app.ui.HostActions
import com.zetaforge.app.ui.HostViewModel
import com.zetaforge.app.ui.ReadinessItem
import com.zetaforge.app.ui.ZetaForgeScreen
import com.zetaforge.app.ui.screen.PluginScreenActivity
import com.zetaforge.app.ui.theme.ZetaForgeTheme

/**
 * The only Activity of the Host.
 *
 * It owns UI concerns exclusively: window setup, the Storage Access Framework
 * picker, and the Android APIs that only an Activity can reach (permission
 * dialogs, Settings screens). Everything about plugins - validation,
 * installation, class loading, execution, error handling - lives in
 * `ZetaPluginRuntime`, reached through [HostViewModel].
 */
class MainActivity : ComponentActivity() {

    private val viewModel: HostViewModel by viewModels()

    private lateinit var permissionGateway: ActivityPermissionGateway

    /**
     * Registered in onCreate, as the Activity Result API requires; launched
     * later, once the Activity is started.
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var notificationPermissionAsked = false

    /** Key of the folder setting waiting for the picker to come back. */
    private var pendingFolderSetting: String? = null

    /**
     * Back from the "install unknown apps" page in Settings.
     *
     * There is no result to read - the switch is the answer - so the state is
     * asked for again, and the download resumes if it was allowed.
     */
    private val installPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.onInstallPermissionResult(::installUpdate)
        }

    /** Plugin whose package is waiting for an export destination. */
    private var pendingExportPlugin: String? = null

    /**
     * Only reached on versions that will not let the app write to Downloads on
     * its own; everywhere else the export lands there without asking.
     */
    private val packageExportPicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument(PluginPackages.MIME_TYPE)) { uri ->
            val pluginId = pendingExportPlugin ?: return@registerForActivityResult
            pendingExportPlugin = null
            if (uri == null) return@registerForActivityResult
            viewModel.exportPackageTo(pluginId, uri)
        }

    /**
     * Folder picker for `ZetaSetting.Folder`. The permission is taken
     * persistably, otherwise the plugin would lose access to the folder the
     * moment the phone reboots.
     */
    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val key = pendingFolderSetting ?: return@registerForActivityResult
            pendingFolderSetting = null
            if (uri == null) return@registerForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.updateSetting(key, uri.toString())
        }

    /**
     * Hands a downloaded update to the system package installer.
     *
     * From here on it is Android's dialog, not ours: it shows what is being
     * replaced and asks for confirmation, which is exactly the guarantee that
     * makes self-updating acceptable in the first place.
     */
    private fun installUpdate(apk: java.io.File) {
        AppUpdates.install(this, apk).onFailure { error ->
            viewModel.onInstallFailed(error.message ?: error.javaClass.simpleName)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Installed before super.onCreate, as the API requires: it replaces the
        // blank window Android shows between the launcher and the first frame.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // The runtime decides *whether* a permission is needed; this gateway is
        // the only thing that can actually ask the user for it.
        permissionGateway = ActivityPermissionGateway(
            activity = this,
            inspector = viewModel.runtime.permissionInspector,
            onExplain = viewModel::explainPermissions,
            onSpecialAccess = viewModel::explainSpecialAccess,
        )
        viewModel.runtime.setPermissionGateway(permissionGateway)

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val dark = when (state.preferences.theme) {
                AppPreferences.Theme.SYSTEM -> isSystemInDarkTheme()
                AppPreferences.Theme.LIGHT -> false
                AppPreferences.Theme.DARK -> true
            }

            ZetaForgeTheme(darkTheme = dark) {

                // SAF: the user picks any file; .zeta has no registered MIME type,
                // so we accept everything and let the runtime reject non-packages.
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri: Uri? -> uri?.let(viewModel::importPlugin) }

                val actions = remember {
                    HostActions(
                        onImport = { picker.launch(arrayOf("*/*")) },
                        onStart = { viewModel.start(it.id) },
                        onDetails = viewModel::openDetails,
                        onViewCode = viewModel::openCode,
                        onCloseDetails = viewModel::closeDetails,
                        onCloseCode = viewModel::closeCode,
                        onRunFailing = { viewModel.startFailing(it.id) },
                        onRunThrowing = { viewModel.startThrowing(it.id) },
                        onShare = { entry -> viewModel.sharePackage(entry.id, ::startActivity) },
                        onExport = { entry ->
                            viewModel.exportPackage(entry.id) { name ->
                                pendingExportPlugin = entry.id
                                packageExportPicker.launch(name)
                            }
                        },
                        onCheckUpdates = { viewModel.checkForUpdates(manual = true) },
                        onDownloadUpdate = { viewModel.downloadUpdate(::installUpdate) },
                        onDismissUpdate = viewModel::dismissUpdate,
                        onOpenInstallSettings = {
                            viewModel.dismissInstallPermission()
                            installPermission.launch(AppUpdates.unknownSourcesSettings(this@MainActivity))
                        },
                        onDismissInstallPermission = viewModel::dismissInstallPermission,
                        onCheckUpdatesOnLaunch = viewModel.preferences::setCheckUpdatesOnLaunch,
                        onUnload = { viewModel.unload(it.id) },
                        onUninstall = { viewModel.uninstall(it.id) },
                        onLevelChange = viewModel::setMinLevel,
                        onClearLogs = viewModel::clearLogs,
                        onToggleLogs = viewModel::toggleLogsExpanded,
                        onQueryChange = viewModel::setQuery,
                        onToggleCard = { viewModel.togglePluginExpanded(it.id) },
                        onSettings = viewModel::openSettings,
                        onSettingChange = viewModel::updateSetting,
                        onSettingsAction = viewModel::runSettingsAction,
                        onPickFolder = { key ->
                            pendingFolderSetting = key
                            folderPicker.launch(null)
                        },
                        onSaveSettings = viewModel::saveSettings,
                        onResetSettings = viewModel::resetSettings,
                        onCloseSettings = viewModel::closeSettings,
                        onDismissBanner = viewModel::dismissBanner,
                        onPermissionPromptResult = viewModel::onPermissionPromptResult,
                        onSpecialAccessResult = viewModel::onSpecialAccessResult,
                        onDismissBlocked = viewModel::dismissBlockedDialog,
                        onOpenAppSettings = {
                            viewModel.dismissBlockedDialog()
                            permissionGateway.openAppSettings()
                        },
                        onSchedule = viewModel::openSchedule,
                        onOpenScreen = { openPluginScreen(it.id) },
                        onScheduleEdit = viewModel::editSchedule,
                        onScheduleSave = viewModel::saveSchedule,
                        onScheduleClose = viewModel::closeSchedule,
                        onFixReadiness = ::applyReadinessFix,
                        onNavigate = viewModel::navigate,
                        onBack = viewModel::back,
                        onFinishOnboarding = viewModel::finishOnboarding,
                        onReplayOnboarding = viewModel::replayOnboarding,
                        onTheme = viewModel::setTheme,
                        onNotifyResults = viewModel::setNotifyManualResults,
                    )
                }

                ZetaForgeScreen(state = state, actions = actions)
            }
        }

        handleIncomingPackage(intent)
    }

    /**
     * Opens a plugin's screen in the Host's container Activity.
     *
     * Nothing about the plugin is resolved here: the container is given an id
     * and asks the runtime, which is what keeps the Host free of any knowledge
     * of a specific plugin.
     */
    private fun openPluginScreen(pluginId: String) {
        startActivity(PluginScreenActivity.intent(this, pluginId))
    }

    override fun onResume() {
        super.onResume()
        // A plugin screen installs its own gateway while it is in front, since
        // only the Activity on top can show a dialog. Whoever resumes takes it
        // back, so a permission request never targets a dead Activity.
        viewModel.runtime.setPermissionGateway(permissionGateway)
    }

    override fun onStart() {
        super.onStart()
        ensureNotificationPermission()
        // The user may have changed a system setting while away; the readiness
        // panel is only useful if it tells the truth on the way back.
        viewModel.refreshReadiness()
    }

    /**
     * Takes the user to the exact switch a readiness item needs, and nowhere
     * else. A settings screen the user has to navigate is a step where most
     * people give up.
     */
    private fun applyReadinessFix(fix: ReadinessItem.Fix) {
        when (fix) {
            ReadinessItem.Fix.NotificationPermission -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // Already asked and refused, or an older Android: the app's
                    // notification page is the only place left to change it.
                    runCatching {
                        startActivity(
                            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                        )
                    }.onFailure { permissionGateway.openAppSettings() }
                }
            }

            is ReadinessItem.Fix.OpenIntent -> runCatching { startActivity(fix.intent) }
                .onFailure { permissionGateway.openAppSettings() }
        }
    }

    /**
     * The progress notification is how a long run stays visible - and on
     * Android 13+ posting it needs the user's consent. Asked once, quietly: a
     * refusal only costs the notification, the run itself is unaffected.
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notificationPermissionAsked) return
        notificationPermissionAsked = true
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingPackage(intent)
    }

    /** Supports opening a `.zeta` straight from a file manager or via adb. */
    private fun handleIncomingPackage(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.let(viewModel::importPlugin)
            return
        }
        handleDeveloperIntent(intent)
    }

    /**
     * Developer hooks used by `run.sh` to drive the whole loop from the shell:
     *
     * ```
     * adb shell am start -n com.zetaforge.app/.MainActivity \
     *     -a com.zetaforge.app.action.IMPORT_FILE --es path <app-private path>
     * adb shell am start -n com.zetaforge.app/.MainActivity \
     *     -a com.zetaforge.app.action.RUN_PLUGIN --es pluginId <id>
     * ```
     *
     * Debug builds only: in a release build these actions do nothing, so no
     * externally triggerable code path can install or run a plugin.
     */
    private fun handleDeveloperIntent(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        when (intent.action) {
            ACTION_IMPORT_FILE -> intent.getStringExtra(EXTRA_PATH)?.let(viewModel::importPluginFile)
            ACTION_OPEN_SCREEN -> intent.getStringExtra(EXTRA_PLUGIN_ID)?.let(::openPluginScreen)
            ACTION_RUN_PLUGIN -> intent.getStringExtra(EXTRA_PLUGIN_ID)?.let { pluginId ->
                when (intent.getStringExtra(EXTRA_SCENARIO)) {
                    SCENARIO_THROW -> viewModel.startThrowing(pluginId)
                    SCENARIO_UNREACHABLE -> viewModel.startFailing(pluginId)
                    else -> viewModel.start(pluginId)
                }
            }
        }
    }

    private companion object {
        const val ACTION_IMPORT_FILE = "com.zetaforge.app.action.IMPORT_FILE"
        const val ACTION_RUN_PLUGIN = "com.zetaforge.app.action.RUN_PLUGIN"
        const val ACTION_OPEN_SCREEN = "com.zetaforge.app.action.OPEN_SCREEN"
        const val EXTRA_PATH = "path"
        const val EXTRA_PLUGIN_ID = "pluginId"
        const val EXTRA_SCENARIO = "scenario"
        const val SCENARIO_THROW = "throw"
        const val SCENARIO_UNREACHABLE = "unreachable"
    }
}
