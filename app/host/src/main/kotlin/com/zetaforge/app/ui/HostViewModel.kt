package com.zetaforge.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zetaforge.app.R
import com.zetaforge.app.ZetaForgeApp
import com.zetaforge.app.notify.ZetaNotifications
import com.zetaforge.app.schedule.ScheduleAlarms
import com.zetaforge.app.service.PluginExecutionService
import com.zetaforge.app.share.PluginPackages
import com.zetaforge.app.update.AppUpdates
import com.zetaforge.runtime.ImportResult
import com.zetaforge.runtime.PluginEntry
import com.zetaforge.runtime.UnloadOutcome
import com.zetaforge.runtime.ZetaPluginRuntime
import com.zetaforge.runtime.log.ZetaLogRecord
import com.zetaforge.runtime.permission.PermissionCoordinator
import com.zetaforge.runtime.permission.PermissionPlan
import com.zetaforge.runtime.permission.SpecialAccess
import com.zetaforge.runtime.schedule.Schedule
import com.zetaforge.runtime.pkg.PluginSourceFile
import com.zetaforge.runtime.pkg.PluginSourceReader
import com.zetaforge.runtime.settings.PluginSettingsStore
import com.zetaforge.runtime.task.RunningTask
import com.zetaforge.runtime.task.ZetaTaskCenter
import com.zetaforge.sdk.ZetaActionResult
import com.zetaforge.sdk.ZetaSettingsSpec
import com.zetaforge.sdk.PluginResult
import com.zetaforge.sdk.ZetaLogLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Everything the Host screen renders. */
data class HostUiState(
    val plugins: List<PluginEntry> = emptyList(),
    val logs: List<ZetaLogRecord> = emptyList(),
    val minLevel: ZetaLogLevel = ZetaLogLevel.DEBUG,
    val query: String = "",
    val expandedPlugins: Set<String> = emptySet(),
    /** The run in flight, from the process-wide task centre. Null when idle. */
    val runningTask: RunningTask? = null,
    /**
     * How many log records had been written the last time the user looked at
     * the Activity tab. What is newer than this is what the tab badges.
     */
    val logsSeenCount: Int = 0,
    val importing: Boolean = false,
    val banner: Banner? = null,
    val details: DetailsState? = null,
    val codeViewer: CodeViewerState? = null,
    val permissionPrompt: PermissionPrompt? = null,
    val specialAccessPrompt: SpecialAccessPrompt? = null,
    val blockedDialog: BlockedDialog? = null,
    val settingsDialog: SettingsState? = null,
    val scheduleDialog: ScheduleState? = null,
    val schedules: Map<String, Schedule> = emptyMap(),
    val readiness: SystemReadiness? = null,
    val route: Route = Route.PLUGINS,
    /**
     * Where "back" goes, one entry per screen pushed on top of a tab.
     *
     * Tabs themselves never enter it: switching tab is not a step to undo, and
     * a bottom bar that has to be un-navigated is one nobody trusts.
     */
    val backStack: List<Route> = emptyList(),
    val onboarding: Boolean = false,
    val preferences: AppPreferences.Settings = AppPreferences.Settings(),
    val update: UpdateState = UpdateState(),
) {
    val filteredLogs: List<ZetaLogRecord>
        get() = logs.filter { it.level.ordinal >= minLevel.ordinal }

    /**
     * Warnings and errors written since the Activity tab was last opened.
     *
     * The number the tab badges, and deliberately only the two levels worth
     * interrupting somebody for: a badge that counts every debug line is a
     * badge people learn to ignore within a minute.
     */
    val unseenIssues: Int
        get() = logs.drop(logsSeenCount).count { it.level.ordinal >= ZetaLogLevel.WARN.ordinal }

    /** True while a plugin run is in flight. */
    val isRunning: Boolean get() = runningTask != null

    /**
     * Plugins matching the search box, filtered on the text a user would think
     * of: name, author, id and description. Empty query means everything.
     */
    val visiblePlugins: List<PluginEntry>
        get() {
            val needle = query.trim()
            if (needle.isEmpty()) return plugins
            return plugins.filter { entry ->
                val manifest = entry.installed.manifest
                listOf(
                    manifest.name,
                    manifest.author,
                    manifest.pluginId,
                    manifest.description,
                    manifest.version,
                ).any { it.contains(needle, ignoreCase = true) }
            }
        }

    fun isExpanded(pluginId: String): Boolean = expandedPlugins.contains(pluginId)

    fun scheduleOf(pluginId: String): Schedule = schedules[pluginId] ?: Schedule.manual(pluginId)

    /** Screens reachable from the menu. The plugin list is always the root. */
    /**
     * Where the app is in updating itself.
     *
     * [message] is only ever set by a check the user asked for. The automatic
     * one stays silent unless it has something to offer: an app that reports
     * "no network" every time it is opened is an app people stop reading.
     */
    data class UpdateState(
        val checking: Boolean = false,
        val available: AppUpdates.Update? = null,
        val downloading: Boolean = false,
        val progress: Float = 0f,
        val downloaded: File? = null,
        val message: String? = null,
        /** Set when Android will not let the app install until the user allows it. */
        val needsPermission: Boolean = false,
    )

    /**
     * Every place the app can be.
     *
     * The first three are tabs and sit side by side; the rest are pushed on top
     * of a tab and come back with the arrow. Keeping both in one enum means the
     * top bar and the back handler each read one value rather than two.
     */
    enum class Route(val isTab: Boolean = false) {
        PLUGINS(isTab = true),
        ACTIVITY(isTab = true),
        APP_SETTINGS(isTab = true),
        HELP,
        DIAGNOSTICS,
        ABOUT,
        ;

        companion object {
            /** The tabs, in the order the bottom bar shows them. */
            val tabs: List<Route> = entries.filter { it.isTab }
        }
    }

    /**
     * The schedule being edited. Held apart from the saved one so that closing
     * the dialog discards the edit rather than half-applying it.
     */
    data class ScheduleState(
        val pluginId: String,
        val pluginName: String,
        val draft: Schedule,
        val exactAllowed: Boolean = true,
    )

    data class Banner(val message: String, val kind: Kind) {
        enum class Kind { SUCCESS, ERROR, INFO }
    }

    data class DetailsState(
        val entry: PluginEntry,
        val verification: List<String> = emptyList(),
    )

    /** Sources read out of the installed package, shown by the code viewer. */
    data class CodeViewerState(
        val pluginName: String,
        val files: List<PluginSourceFile>,
    )

    /** Rationale shown before Android's own permission dialog. */
    data class PermissionPrompt(val pluginName: String, val plan: PermissionPlan)

    /** Explanation shown before jumping to a Settings screen. */
    data class SpecialAccessPrompt(val access: SpecialAccess, val reason: String)

    /**
     * The settings form of one plugin: the schema it ships, the values as they
     * are being edited, and the result of the last action button.
     */
    data class SettingsState(
        val pluginId: String,
        val pluginName: String,
        val spec: ZetaSettingsSpec,
        val values: Map<String, Any> = emptyMap(),
        val runningAction: String? = null,
        val actionResult: ZetaActionResult? = null,
        val loading: Boolean = false,
    )

    /** Terminal state: nothing can be asked, only Settings (or a new build) helps. */
    data class BlockedDialog(
        @StringRes val titleRes: Int,
        val body: String,
        val canOpenSettings: Boolean,
    )
}

/**
 * Bridges the Compose UI and [ZetaPluginRuntime].
 *
 * The view model holds no plugin-specific knowledge: it forwards ids and input
 * bundles, and turns the runtime's permission requests into dialog state. All
 * loading, class loading and error containment lives in the runtime, and no
 * composable ever touches it directly.
 */
class HostViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * The one runtime the process shares. A scheduled run happens in a service
     * with no view model, so ownership cannot live here any more.
     */
    val runtime: ZetaPluginRuntime = ZetaForgeApp.runtime(application)

    val preferences = AppPreferences(application)

    private val ui = MutableStateFlow(HostUiState())

    private var pendingPermissionPrompt: CompletableDeferred<Boolean>? = null
    private var pendingSpecialAccess: CompletableDeferred<Boolean>? = null

    /** The job of the plugin currently running, so the notification can stop it. */
    private var runningJob: Job? = null

    val state: StateFlow<HostUiState> = combine(
        ui,
        runtime.plugins,
        runtime.logger.records,
        runtime.schedules.schedules,
        preferences.state,
    ) { base, plugins, logs, schedules, prefs ->
        base.copy(
            plugins = plugins.sortedBy { it.installed.displayName },
            expandedPlugins = base.expandedPlugins.intersect(plugins.map { it.id }.toSet()),
            logs = logs,
            schedules = schedules,
            preferences = prefs,
            minLevel = prefs.minLogLevel,
            onboarding = base.onboarding || !prefs.onboardingDone,
            details = base.details?.let { details ->
                plugins.firstOrNull { it.id == details.entry.id }
                    ?.let { details.copy(entry = it) } ?: details
            },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HostUiState())

    init {
        viewModelScope.launch { runtime.refresh() }
        refreshReadiness()

        // Every launch, unless the user turned it off. Silent: it only speaks
        // when there is something newer to install.
        if (preferences.state.value.checkUpdatesOnLaunch) checkForUpdates(manual = false)

        // What is running, and how far along, for the Activity tab. It comes
        // from the process-wide task centre rather than from this view model
        // because a scheduled run happens in a process that may have no UI at
        // all - and the screen has to be able to show one it did not start.
        viewModelScope.launch {
            ZetaTaskCenter.current.collect { task ->
                ui.value = ui.value.copy(runningTask = task)
            }
        }

        // "Stop" in the notification asks the runtime to cancel; the job lives
        // here, so this is where the request is honoured.
        viewModelScope.launch {
            ZetaTaskCenter.cancelRequests.collectLatest { pluginId ->
                runtime.logger.warn("Runtime", pluginId, "Stop requested by the user")
                runningJob?.cancel()
            }
        }
    }

    // -- import -------------------------------------------------------------

    /** Imports a `.zeta` selected through the Storage Access Framework. */
    fun importPlugin(uri: Uri) {
        viewModelScope.launch {
            ui.value = ui.value.copy(importing = true, banner = null)
            val resolver = getApplication<Application>().contentResolver
            val name = uri.lastPathSegment?.substringAfterLast('/')
            val stream = runCatching { resolver.openInputStream(uri) }.getOrNull()
            if (stream == null) {
                ui.value = ui.value.copy(
                    importing = false,
                    banner = HostUiState.Banner(string(R.string.banner_import_unreadable), HostUiState.Banner.Kind.ERROR),
                )
                return@launch
            }
            finishImport(runtime.importPlugin(stream, name))
        }
    }

    /**
     * Imports a `.zeta` from a plain filesystem path, used by the adb-driven
     * developer loop (`run.sh`) where the archive already sits in the app cache.
     */
    fun importPluginFile(path: String) {
        viewModelScope.launch {
            val file = File(path)
            if (!file.isFile) {
                ui.value = ui.value.copy(
                    banner = HostUiState.Banner(string(R.string.banner_import_missing, path), HostUiState.Banner.Kind.ERROR),
                )
                return@launch
            }
            ui.value = ui.value.copy(importing = true, banner = null)
            finishImport(runtime.importPlugin(file.inputStream(), file.name))
        }
    }

    private fun finishImport(result: ImportResult) {
        val banner = when (result) {
            is ImportResult.Success -> HostUiState.Banner(
                string(
                    R.string.banner_installed,
                    result.entry.installed.displayName,
                    result.entry.installed.version,
                ),
                HostUiState.Banner.Kind.SUCCESS,
            )

            is ImportResult.Failure -> HostUiState.Banner(
                string(R.string.banner_import_failed, result.stage, result.reason),
                HostUiState.Banner.Kind.ERROR,
            )
        }
        ui.value = ui.value.copy(importing = false, banner = banner)
    }

    // -- execution ----------------------------------------------------------

    /**
     * Runs a plugin.
     *
     * A foreground service is started for the whole execution: a process without
     * one is frozen by Android as soon as the screen goes off, which would stall
     * any long-running plugin. It is stopped as soon as the run ends, so short
     * plugins barely show a notification.
     */
    fun start(pluginId: String, input: Bundle = Bundle()) {
        val application = getApplication<Application>()
        val started = System.currentTimeMillis()
        // A plugin that reads the position needs a service typed `location`:
        // from API 34 a `dataSync` one throws as soon as it does, and from API
        // 35 it is stopped after about six hours a day.
        val needsLocation = state.value.plugins.firstOrNull { it.id == pluginId }
            ?.installed?.manifest?.permissions
            ?.any { it.name.endsWith("_LOCATION") } == true
        runningJob = viewModelScope.launch {
            PluginExecutionService.start(application, needsLocation)
            try {
                val result = runtime.execute(pluginId, input)
                (result as? PluginResult.Failure)?.let(::showPermissionBlockIfNeeded)
                notifyResult(pluginId, result, System.currentTimeMillis() - started)
            } finally {
                ZetaTaskCenter.end(pluginId)
                PluginExecutionService.stop(application)
                runningJob = null
            }
        }
    }

    /**
     * A run started by hand still ends somewhere the user may not be looking:
     * they lock the phone, switch apps, or the run takes an hour. So it is
     * reported the same way a scheduled one is - unless they turned that off.
     */
    private fun notifyResult(pluginId: String, result: PluginResult, elapsed: Long) {
        if (!preferences.state.value.notifyManualResults) return
        val name = state.value.plugins.firstOrNull { it.id == pluginId }
            ?.installed?.displayName ?: pluginId
        ZetaNotifications.result(
            context = getApplication(),
            pluginId = pluginId,
            pluginName = name,
            success = result is PluginResult.Success,
            message = when (result) {
                is PluginResult.Success -> result.message
                is PluginResult.Failure -> result.message
            },
            durationMs = if (result.durationMs > 0) result.durationMs else elapsed,
            scheduled = false,
        )
    }

    /** Failure scenario used by the PoC: unreachable host, so the plugin fails. */
    fun startFailing(pluginId: String) {
        start(pluginId, Bundle().apply { putString("baseUrl", "https://zetaforge-unreachable.invalid/") })
    }

    /** Failure scenario: the plugin throws inside `execute`. */
    fun startThrowing(pluginId: String) {
        start(pluginId, Bundle().apply { putBoolean("throwOnPurpose", true) })
    }

    /**
     * Turns a permission-related failure into a dialog that tells the user what
     * to do next, instead of leaving them with an error code.
     */
    private fun showPermissionBlockIfNeeded(failure: PluginResult.Failure) {
        val dialog = when (failure.errorCode) {
            PermissionCoordinator.ERROR_PERMANENTLY_DENIED -> HostUiState.BlockedDialog(
                titleRes = R.string.permissions_denied_title,
                body = string(R.string.permissions_denied_body),
                canOpenSettings = true,
            )

            PermissionCoordinator.ERROR_NOT_DECLARED -> HostUiState.BlockedDialog(
                titleRes = R.string.permissions_undeclared_title,
                body = string(R.string.permissions_undeclared_body, failure.data["missing"].orEmpty()),
                canOpenSettings = false,
            )

            else -> null
        }
        if (dialog != null) ui.value = ui.value.copy(blockedDialog = dialog)
    }

    // -- permission dialogs (driven by the runtime through the gateway) ------

    /** Called by the gateway before Android's dialog; suspends until answered. */
    suspend fun explainPermissions(plan: PermissionPlan): Boolean {
        val pluginName = state.value.plugins.firstOrNull { it.id == plan.pluginId }
            ?.installed?.displayName
            ?: plan.pluginId
        val deferred = CompletableDeferred<Boolean>()
        pendingPermissionPrompt = deferred
        ui.value = ui.value.copy(permissionPrompt = HostUiState.PermissionPrompt(pluginName, plan))
        return deferred.await()
    }

    fun onPermissionPromptResult(accepted: Boolean) {
        ui.value = ui.value.copy(permissionPrompt = null)
        pendingPermissionPrompt?.complete(accepted)
        pendingPermissionPrompt = null
    }

    /** Called by the gateway before opening a Settings screen. */
    suspend fun explainSpecialAccess(access: SpecialAccess, reason: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingSpecialAccess = deferred
        ui.value = ui.value.copy(specialAccessPrompt = HostUiState.SpecialAccessPrompt(access, reason))
        return deferred.await()
    }

    fun onSpecialAccessResult(accepted: Boolean) {
        ui.value = ui.value.copy(specialAccessPrompt = null)
        pendingSpecialAccess?.complete(accepted)
        pendingSpecialAccess = null
    }

    fun dismissBlockedDialog() {
        ui.value = ui.value.copy(blockedDialog = null)
    }

    // -- details, code, housekeeping ----------------------------------------

    fun openDetails(entry: PluginEntry) {
        ui.value = ui.value.copy(details = HostUiState.DetailsState(entry))
        viewModelScope.launch {
            val checks = runtime.verifyInstalled(entry.id)
            val current = ui.value.details ?: return@launch
            if (current.entry.id == entry.id) {
                ui.value = ui.value.copy(details = current.copy(verification = checks))
            }
        }
    }

    fun closeDetails() {
        ui.value = ui.value.copy(details = null)
    }

    /** Loads the sources shipped inside the package and shows the code viewer. */
    fun openCode(entry: PluginEntry) {
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) {
                runCatching { PluginSourceReader.read(entry.installed) }.getOrDefault(emptyList())
            }
            ui.value = ui.value.copy(
                codeViewer = HostUiState.CodeViewerState(entry.installed.displayName, files),
            )
        }
    }

    fun closeCode() {
        ui.value = ui.value.copy(codeViewer = null)
    }

    fun uninstall(pluginId: String) {
        viewModelScope.launch {
            runtime.uninstall(pluginId)
            ui.value = ui.value.copy(
                details = null,
                banner = HostUiState.Banner(string(R.string.banner_uninstalled, pluginId), HostUiState.Banner.Kind.INFO),
            )
        }
    }

    /**
     * Dropping a plugin from memory changes nothing the user can see, so the
     * outcome is reported explicitly: silence here is indistinguishable from a
     * button that does not work.
     */
    fun unload(pluginId: String) {
        viewModelScope.launch {
            val name = state.value.plugins.firstOrNull { it.id == pluginId }
                ?.installed?.displayName ?: pluginId
            val banner = when (runtime.unload(pluginId)) {
                UnloadOutcome.UNLOADED -> HostUiState.Banner(
                    string(R.string.banner_unloaded, name),
                    HostUiState.Banner.Kind.INFO,
                )

                UnloadOutcome.NOT_LOADED -> HostUiState.Banner(
                    string(R.string.banner_unload_not_loaded, name),
                    HostUiState.Banner.Kind.INFO,
                )

                UnloadOutcome.SCREEN_OPEN -> HostUiState.Banner(
                    string(R.string.banner_unload_screen_open, name),
                    HostUiState.Banner.Kind.ERROR,
                )
            }
            ui.value = ui.value.copy(details = null, banner = banner)
        }
    }

    // -- updating the app itself ---------------------------------------------

    /**
     * Asks GitHub whether a newer Host release exists.
     *
     * @param manual true when the user pressed the button, which is what
     *   decides whether "you are up to date" and failures are worth showing.
     */
    fun checkForUpdates(manual: Boolean = true) {
        if (ui.value.update.checking || ui.value.update.downloading) return
        viewModelScope.launch {
            ui.value = ui.value.copy(update = ui.value.update.copy(checking = true, message = null))
            val result = AppUpdates.check()
            ui.value = ui.value.copy(
                update = when (result) {
                    is AppUpdates.Result.Available -> HostUiState.UpdateState(available = result.update)

                    AppUpdates.Result.UpToDate -> HostUiState.UpdateState(
                        message = if (manual) string(R.string.update_up_to_date) else null,
                    )

                    is AppUpdates.Result.Failed -> HostUiState.UpdateState(
                        message = if (manual) string(R.string.update_check_failed, result.reason) else null,
                    )
                },
            )
        }
    }

    /**
     * Downloads the update, then asks to install it.
     *
     * Permission is checked before the download rather than after: making
     * somebody wait for thirty megabytes only to be sent to Settings is a poor
     * way to ask a question that could have been asked first.
     */
    fun downloadUpdate(install: (java.io.File) -> Unit) {
        val update = ui.value.update.available ?: return
        if (!AppUpdates.canInstall(getApplication())) {
            ui.value = ui.value.copy(update = ui.value.update.copy(needsPermission = true))
            return
        }

        viewModelScope.launch {
            ui.value = ui.value.copy(
                update = ui.value.update.copy(downloading = true, progress = 0f, message = null),
            )
            val result = AppUpdates.download(getApplication(), update) { progress ->
                ui.value = ui.value.copy(update = ui.value.update.copy(progress = progress))
            }
            result.fold(
                onSuccess = { file ->
                    ui.value = ui.value.copy(
                        update = ui.value.update.copy(downloading = false, downloaded = file),
                    )
                    install(file)
                },
                onFailure = { error ->
                    ui.value = ui.value.copy(
                        update = ui.value.update.copy(
                            downloading = false,
                            message = string(
                                R.string.update_download_failed,
                                error.message ?: error.javaClass.simpleName,
                            ),
                        ),
                    )
                },
            )
        }
    }

    /** Called once the user has been to Settings, to try the download again. */
    fun onInstallPermissionResult(install: (java.io.File) -> Unit) {
        ui.value = ui.value.copy(update = ui.value.update.copy(needsPermission = false))
        if (AppUpdates.canInstall(getApplication())) downloadUpdate(install)
    }

    fun dismissInstallPermission() {
        ui.value = ui.value.copy(update = ui.value.update.copy(needsPermission = false))
    }

    /** The session could not even be created; the user never saw a dialog. */
    fun onInstallFailed(reason: String) {
        ui.value = ui.value.copy(
            update = ui.value.update.copy(message = string(R.string.update_download_failed, reason)),
        )
    }

    /** Puts the update card away until the next check. */
    fun dismissUpdate() {
        ui.value = ui.value.copy(update = HostUiState.UpdateState())
    }

    // -- getting a package back out ------------------------------------------

    /**
     * Hands the plugin's `.zeta` to another app.
     *
     * Staging the copy touches the disk, so it happens off the main thread and
     * the chooser is launched only once there is a file to offer - otherwise a
     * large package would freeze the frame the button was tapped on.
     */
    fun sharePackage(pluginId: String, launch: (Intent) -> Unit) {
        val entry = state.value.plugins.firstOrNull { it.id == pluginId } ?: return
        viewModelScope.launch {
            val intent = withContext(Dispatchers.IO) {
                runCatching { PluginPackages.shareIntent(getApplication(), entry.installed) }
            }
            intent.fold(
                onSuccess = launch,
                onFailure = { failure ->
                    ui.value = ui.value.copy(
                        banner = HostUiState.Banner(
                            string(R.string.banner_export_failed, failure.message ?: failure.javaClass.simpleName),
                            HostUiState.Banner.Kind.ERROR,
                        ),
                    )
                },
            )
        }
    }

    /**
     * Writes the plugin's `.zeta` into Downloads.
     *
     * Where Android will not allow that unasked, [pickDestination] is called
     * with the file name to propose, and the answer comes back to
     * [exportPackageTo].
     */
    fun exportPackage(pluginId: String, pickDestination: (String) -> Unit) {
        val entry = state.value.plugins.firstOrNull { it.id == pluginId } ?: return
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                PluginPackages.exportToDownloads(getApplication(), entry.installed)
            }
            if (outcome is PluginPackages.ExportOutcome.NeedsPicker) {
                pickDestination(PluginPackages.fileName(entry.installed))
                return@launch
            }
            // The banner is drawn behind the details sheet the button lives in,
            // so the sheet gets out of the way - otherwise the confirmation is
            // written somewhere nobody can see it.
            ui.value = ui.value.copy(details = null, banner = exportBanner(outcome))
        }
    }

    /** Completes an export the user gave a destination for. */
    fun exportPackageTo(pluginId: String, destination: Uri) {
        val entry = state.value.plugins.firstOrNull { it.id == pluginId } ?: return
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                PluginPackages.exportTo(getApplication(), entry.installed, destination)
            }
            ui.value = ui.value.copy(details = null, banner = exportBanner(outcome))
        }
    }

    private fun exportBanner(outcome: PluginPackages.ExportOutcome): HostUiState.Banner = when (outcome) {
        is PluginPackages.ExportOutcome.Saved -> HostUiState.Banner(
            string(R.string.banner_exported, outcome.displayName),
            HostUiState.Banner.Kind.SUCCESS,
        )

        is PluginPackages.ExportOutcome.Failed -> HostUiState.Banner(
            string(R.string.banner_export_failed, outcome.reason),
            HostUiState.Banner.Kind.ERROR,
        )

        // Handled by the caller, which asks for a destination instead.
        PluginPackages.ExportOutcome.NeedsPicker -> HostUiState.Banner(
            string(R.string.banner_export_failed, "no destination"),
            HostUiState.Banner.Kind.ERROR,
        )
    }

    // -- settings ------------------------------------------------------------

    /**
     * Opens the settings form. The schema comes from the plugin's manifest and,
     * when the plugin implements it, from its own `settings()` hook - so the
     * dialog can offer choices that only exist on this device.
     */
    fun openSettings(entry: PluginEntry) {
        ui.value = ui.value.copy(
            settingsDialog = HostUiState.SettingsState(
                pluginId = entry.id,
                pluginName = entry.installed.displayName,
                spec = entry.installed.manifest.settings,
                values = runtime.settings.effectiveValues(entry.id, entry.installed.manifest.settings),
                loading = true,
            )
        )
        viewModelScope.launch {
            val spec = runtime.settingsSpec(entry.id)
            val current = ui.value.settingsDialog ?: return@launch
            if (current.pluginId != entry.id) return@launch
            ui.value = ui.value.copy(
                settingsDialog = current.copy(
                    spec = spec,
                    values = runtime.settings.effectiveValues(entry.id, spec) + current.values,
                    loading = false,
                )
            )
        }
    }

    fun updateSetting(key: String, value: Any) {
        val current = ui.value.settingsDialog ?: return
        ui.value = ui.value.copy(settingsDialog = current.copy(values = current.values + (key to value)))
    }

    fun saveSettings() {
        val current = ui.value.settingsDialog ?: return
        runtime.settings.save(current.pluginId, current.values)
        runtime.logger.info("Runtime", current.pluginId, "Settings saved: " + current.values.keys.joinToString())
        ui.value = ui.value.copy(settingsDialog = null)
    }

    fun resetSettings() {
        val current = ui.value.settingsDialog ?: return
        runtime.settings.clear(current.pluginId)
        ui.value = ui.value.copy(
            settingsDialog = current.copy(
                values = runtime.settings.effectiveValues(current.pluginId, current.spec),
                actionResult = null,
            )
        )
    }

    fun closeSettings() {
        ui.value = ui.value.copy(settingsDialog = null)
    }

    /** Runs a settings action button and shows whatever the plugin answers. */
    fun runSettingsAction(actionKey: String) {
        val current = ui.value.settingsDialog ?: return
        ui.value = ui.value.copy(settingsDialog = current.copy(runningAction = actionKey, actionResult = null))
        viewModelScope.launch {
            // The values being edited are saved first: an action like "test the
            // connection" must see what is on screen, not what was saved before.
            runtime.settings.save(current.pluginId, current.values)
            val result = runtime.runSettingsAction(current.pluginId, actionKey)
            val open = ui.value.settingsDialog ?: return@launch
            ui.value = ui.value.copy(
                settingsDialog = open.copy(
                    runningAction = null,
                    actionResult = result,
                    values = open.values + result.updatedValues,
                )
            )
        }
    }

    fun setQuery(query: String) {
        ui.value = ui.value.copy(query = query)
    }

    /** Expand/collapse one card; several can stay open at once. */
    fun togglePluginExpanded(pluginId: String) {
        val current = ui.value.expandedPlugins
        ui.value = ui.value.copy(
            expandedPlugins = if (current.contains(pluginId)) current - pluginId else current + pluginId,
        )
    }

    fun setMinLevel(level: ZetaLogLevel) {
        preferences.setMinLogLevel(level)
    }

    fun clearLogs() {
        runtime.logger.clear()
        ui.value = ui.value.copy(logsSeenCount = 0)
    }

    /**
     * Stops whatever is running, wherever it was started from.
     *
     * Both halves are needed and neither is redundant: a run this view model
     * started is cancelled through its own job, and one started by an alarm
     * lives in the foreground service, which only answers its stop intent.
     */
    fun stopRun() {
        val pluginId = ui.value.runningTask?.pluginId ?: return
        ZetaTaskCenter.requestCancel(pluginId)
        PluginExecutionService.stopRun(getApplication())
    }

    fun dismissBanner() {
        ui.value = ui.value.copy(banner = null)
    }

    // -- scheduling ----------------------------------------------------------

    fun openSchedule(entry: PluginEntry) {
        ui.value = ui.value.copy(
            scheduleDialog = HostUiState.ScheduleState(
                pluginId = entry.id,
                pluginName = entry.installed.displayName,
                draft = runtime.schedules.get(entry.id),
                exactAllowed = ScheduleAlarms.canScheduleExact(getApplication()),
            )
        )
    }

    /** Every edit in the dialog goes through here, on the draft only. */
    fun editSchedule(transform: (Schedule) -> Schedule) {
        val current = ui.value.scheduleDialog ?: return
        ui.value = ui.value.copy(scheduleDialog = current.copy(draft = transform(current.draft)))
    }

    fun closeSchedule() {
        ui.value = ui.value.copy(scheduleDialog = null)
    }

    /**
     * Saves the schedule and arms the alarm in the same breath: a schedule the
     * user was shown but that never reached AlarmManager is the worst outcome,
     * because it looks like it worked.
     */
    fun saveSchedule() {
        val current = ui.value.scheduleDialog ?: return
        val draft = current.draft
        runtime.schedules.save(draft)

        val next = ScheduleAlarms.schedule(getApplication(), draft)
        runtime.logger.info(
            "Scheduler",
            draft.pluginId,
            if (next != null) {
                "Scheduled: " + ScheduleFormatter.summary(getApplication(), draft) +
                    ", next " + ScheduleFormatter.dateTime(getApplication(), next)
            } else {
                "Schedule cleared"
            },
        )

        val banner = if (draft.isAutomatic && next != null) {
            HostUiState.Banner(
                string(
                    R.string.schedule_saved,
                    current.pluginName,
                    ScheduleFormatter.summary(getApplication(), draft),
                ),
                HostUiState.Banner.Kind.SUCCESS,
            )
        } else {
            HostUiState.Banner(
                string(R.string.schedule_cleared, current.pluginName),
                HostUiState.Banner.Kind.INFO,
            )
        }

        ui.value = ui.value.copy(scheduleDialog = null, banner = banner)
        refreshReadiness()
    }

    /** Turns a schedule off from the card, without opening the dialog. */
    fun disableSchedule(pluginId: String) {
        val current = runtime.schedules.get(pluginId)
        runtime.schedules.save(current.copy(enabled = false))
        ScheduleAlarms.cancel(getApplication(), pluginId)
        refreshReadiness()
    }

    // -- system readiness -----------------------------------------------------

    /** Cheap enough to re-read whenever the app comes back to the foreground. */
    fun refreshReadiness() {
        val wantsExact = runtime.schedules.automatic().any { it.exact }
        ui.value = ui.value.copy(
            readiness = SystemReadiness.read(getApplication(), wantsExact)
        )
    }

    // -- navigation and preferences -------------------------------------------

    /**
     * Goes somewhere.
     *
     * Switching tab replaces where you are and clears the stack; opening a
     * screen from a tab pushes, so the arrow returns to the tab that opened it
     * rather than to a fixed home. Diagnostics is reachable from two places,
     * and this is what makes both of them come back correctly.
     */
    fun navigate(route: HostUiState.Route) {
        val current = ui.value
        if (route == current.route) return
        val stack = if (route.isTab) emptyList() else current.backStack + current.route
        ui.value = current.copy(route = route, backStack = stack)
        // Marked on the way in *and* on the way out: everything written while
        // the tab was open has been seen by definition, and counting it as new
        // the moment you leave would badge the app for its own log lines.
        if (route == HostUiState.Route.ACTIVITY || current.route == HostUiState.Route.ACTIVITY) {
            markLogsSeen()
        }
    }

    fun back() {
        val current = ui.value
        val previous = current.backStack.lastOrNull() ?: HostUiState.Route.PLUGINS
        ui.value = current.copy(route = previous, backStack = current.backStack.dropLast(1))
    }

    /**
     * The badge is about what happened while you were not looking, so it is
     * cleared by looking - not by a button, and not on a timer.
     */
    fun markLogsSeen() {
        ui.value = ui.value.copy(logsSeenCount = ui.value.logs.size)
    }

    fun finishOnboarding() {
        preferences.setOnboardingDone(true)
        ui.value = ui.value.copy(onboarding = false)
        refreshReadiness()
    }

    fun replayOnboarding() {
        preferences.setOnboardingDone(false)
        ui.value = ui.value.copy(onboarding = true, route = HostUiState.Route.PLUGINS)
    }

    fun setTheme(theme: AppPreferences.Theme) = preferences.setTheme(theme)

    fun setNotifyManualResults(enabled: Boolean) = preferences.setNotifyManualResults(enabled)

    private fun string(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)
}
