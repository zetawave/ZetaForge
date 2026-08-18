package com.zetaforge.app.ui

import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zetaforge.app.R
import com.zetaforge.app.service.PluginExecutionService
import com.zetaforge.runtime.ImportResult
import com.zetaforge.runtime.PluginEntry
import com.zetaforge.runtime.ZetaPluginRuntime
import com.zetaforge.runtime.log.ZetaLogRecord
import com.zetaforge.runtime.permission.PermissionCoordinator
import com.zetaforge.runtime.permission.PermissionPlan
import com.zetaforge.runtime.permission.SpecialAccess
import com.zetaforge.runtime.pkg.PluginSourceFile
import com.zetaforge.runtime.pkg.PluginSourceReader
import com.zetaforge.runtime.task.ZetaTaskCenter
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
    val logsExpanded: Boolean = false,
    val importing: Boolean = false,
    val banner: Banner? = null,
    val details: DetailsState? = null,
    val codeViewer: CodeViewerState? = null,
    val permissionPrompt: PermissionPrompt? = null,
    val specialAccessPrompt: SpecialAccessPrompt? = null,
    val blockedDialog: BlockedDialog? = null,
) {
    val filteredLogs: List<ZetaLogRecord>
        get() = logs.filter { it.level.ordinal >= minLevel.ordinal }

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

    val runtime = ZetaPluginRuntime(application)

    private val ui = MutableStateFlow(HostUiState())

    private var pendingPermissionPrompt: CompletableDeferred<Boolean>? = null
    private var pendingSpecialAccess: CompletableDeferred<Boolean>? = null

    /** The job of the plugin currently running, so the notification can stop it. */
    private var runningJob: Job? = null

    val state: StateFlow<HostUiState> = combine(
        ui,
        runtime.plugins,
        runtime.logger.records,
    ) { base, plugins, logs ->
        base.copy(
            plugins = plugins.sortedBy { it.installed.displayName },
            expandedPlugins = base.expandedPlugins.intersect(plugins.map { it.id }.toSet()),
            logs = logs,
            details = base.details?.let { details ->
                plugins.firstOrNull { it.id == details.entry.id }
                    ?.let { details.copy(entry = it) } ?: details
            },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HostUiState())

    init {
        viewModelScope.launch { runtime.refresh() }

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
        runningJob = viewModelScope.launch {
            PluginExecutionService.start(application)
            try {
                val result = runtime.execute(pluginId, input)
                (result as? PluginResult.Failure)?.let(::showPermissionBlockIfNeeded)
            } finally {
                ZetaTaskCenter.end(pluginId)
                PluginExecutionService.stop(application)
                runningJob = null
            }
        }
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

    fun unload(pluginId: String) {
        viewModelScope.launch { runtime.unload(pluginId) }
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
        ui.value = ui.value.copy(minLevel = level)
    }

    fun toggleLogsExpanded() {
        ui.value = ui.value.copy(logsExpanded = !ui.value.logsExpanded)
    }

    fun clearLogs() {
        runtime.logger.clear()
    }

    fun dismissBanner() {
        ui.value = ui.value.copy(banner = null)
    }

    private fun string(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)
}
