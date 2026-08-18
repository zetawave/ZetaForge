package com.zetaforge.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zetaforge.app.permission.ActivityPermissionGateway
import com.zetaforge.app.ui.HostActions
import com.zetaforge.app.ui.HostViewModel
import com.zetaforge.app.ui.ZetaForgeScreen
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

    override fun onCreate(savedInstanceState: Bundle?) {
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
            ZetaForgeTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()

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
                        onUnload = { viewModel.unload(it.id) },
                        onUninstall = { viewModel.uninstall(it.id) },
                        onLevelChange = viewModel::setMinLevel,
                        onClearLogs = viewModel::clearLogs,
                        onToggleLogs = viewModel::toggleLogsExpanded,
                        onDismissBanner = viewModel::dismissBanner,
                        onPermissionPromptResult = viewModel::onPermissionPromptResult,
                        onSpecialAccessResult = viewModel::onSpecialAccessResult,
                        onDismissBlocked = viewModel::dismissBlockedDialog,
                        onOpenAppSettings = {
                            viewModel.dismissBlockedDialog()
                            permissionGateway.openAppSettings()
                        },
                    )
                }

                ZetaForgeScreen(state = state, actions = actions)
            }
        }

        handleIncomingPackage(intent)
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
        const val EXTRA_PATH = "path"
        const val EXTRA_PLUGIN_ID = "pluginId"
        const val EXTRA_SCENARIO = "scenario"
        const val SCENARIO_THROW = "throw"
        const val SCENARIO_UNREACHABLE = "unreachable"
    }
}
