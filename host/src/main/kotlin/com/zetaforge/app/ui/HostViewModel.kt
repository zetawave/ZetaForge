package com.zetaforge.app.ui

import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zetaforge.runtime.ImportResult
import com.zetaforge.runtime.PluginEntry
import com.zetaforge.runtime.ZetaPluginRuntime
import com.zetaforge.runtime.log.ZetaLogRecord
import com.zetaforge.sdk.ZetaLogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the Host screen renders. */
data class HostUiState(
    val plugins: List<PluginEntry> = emptyList(),
    val logs: List<ZetaLogRecord> = emptyList(),
    val minLevel: ZetaLogLevel = ZetaLogLevel.DEBUG,
    val importing: Boolean = false,
    val banner: Banner? = null,
    val details: DetailsState? = null,
) {
    val filteredLogs: List<ZetaLogRecord>
        get() = logs.filter { it.level.ordinal >= minLevel.ordinal }

    data class Banner(val message: String, val kind: Kind) {
        enum class Kind { SUCCESS, ERROR, INFO }
    }

    data class DetailsState(
        val entry: PluginEntry,
        val verification: List<String> = emptyList(),
    )
}

/**
 * Bridges the Compose UI and [ZetaPluginRuntime].
 *
 * The view model holds no plugin-specific knowledge: it forwards ids and input
 * bundles. All loading, class loading and error containment lives in the
 * runtime, and no composable ever touches it directly.
 */
class HostViewModel(application: Application) : AndroidViewModel(application) {

    val runtime = ZetaPluginRuntime(application)

    private val ui = MutableStateFlow(HostUiState())

    val state: StateFlow<HostUiState> = combine(
        ui,
        runtime.plugins,
        runtime.logger.records,
    ) { base, plugins, logs ->
        base.copy(
            plugins = plugins.sortedBy { it.installed.displayName },
            logs = logs,
            details = base.details?.let { details ->
                plugins.firstOrNull { it.id == details.entry.id }
                    ?.let { details.copy(entry = it) } ?: details
            },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HostUiState())

    init {
        viewModelScope.launch { runtime.refresh() }
    }

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
                    banner = HostUiState.Banner("Import failed: cannot read the selected file", HostUiState.Banner.Kind.ERROR),
                )
                return@launch
            }
            val banner = when (val result = runtime.importPlugin(stream, name)) {
                is ImportResult.Success -> HostUiState.Banner(
                    "Installed ${result.entry.installed.displayName} v${result.entry.installed.version}",
                    HostUiState.Banner.Kind.SUCCESS,
                )

                is ImportResult.Failure -> HostUiState.Banner(
                    "Import failed (${result.stage}): ${result.reason}",
                    HostUiState.Banner.Kind.ERROR,
                )
            }
            ui.value = ui.value.copy(importing = false, banner = banner)
        }
    }

    /**
     * Imports a `.zeta` from a plain filesystem path.
     *
     * Used by the adb-driven developer loop (`run.sh`), where the archive has
     * been copied into the app's own cache directory: there is no SAF picker
     * involved and no content URI to resolve.
     */
    fun importPluginFile(path: String) {
        viewModelScope.launch {
            val file = java.io.File(path)
            if (!file.isFile) {
                ui.value = ui.value.copy(
                    banner = HostUiState.Banner("Import failed: no file at $path", HostUiState.Banner.Kind.ERROR),
                )
                return@launch
            }
            ui.value = ui.value.copy(importing = true, banner = null)
            val banner = when (val result = runtime.importPlugin(file.inputStream(), file.name)) {
                is ImportResult.Success -> HostUiState.Banner(
                    "Installed " + result.entry.installed.displayName + " v" + result.entry.installed.version,
                    HostUiState.Banner.Kind.SUCCESS,
                )

                is ImportResult.Failure -> HostUiState.Banner(
                    "Import failed (" + result.stage + "): " + result.reason,
                    HostUiState.Banner.Kind.ERROR,
                )
            }
            ui.value = ui.value.copy(importing = false, banner = banner)
        }
    }

    /** Runs a plugin. [input] is passed through untouched. */
    fun start(pluginId: String, input: Bundle = Bundle()) {
        viewModelScope.launch { runtime.execute(pluginId, input) }
    }

    /** Failure scenario used by the PoC: unreachable host, so the plugin fails. */
    fun startFailing(pluginId: String) {
        val input = Bundle().apply {
            putString("baseUrl", "https://zetaforge-unreachable.invalid/")
        }
        start(pluginId, input)
    }

    /** Failure scenario: the plugin throws inside `execute`. */
    fun startThrowing(pluginId: String) {
        val input = Bundle().apply { putBoolean("throwOnPurpose", true) }
        start(pluginId, input)
    }

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

    fun uninstall(pluginId: String) {
        viewModelScope.launch {
            runtime.uninstall(pluginId)
            ui.value = ui.value.copy(
                details = null,
                banner = HostUiState.Banner("Uninstalled $pluginId", HostUiState.Banner.Kind.INFO),
            )
        }
    }

    fun unload(pluginId: String) {
        viewModelScope.launch { runtime.unload(pluginId) }
    }

    fun setMinLevel(level: ZetaLogLevel) {
        ui.value = ui.value.copy(minLevel = level)
    }

    fun clearLogs() {
        runtime.logger.clear()
    }

    fun dismissBanner() {
        ui.value = ui.value.copy(banner = null)
    }

    val uiFlow: StateFlow<HostUiState> get() = ui.asStateFlow()
}
