package com.zetaforge.runtime

import android.content.Context
import android.os.Build
import android.os.Bundle
import com.zetaforge.runtime.install.InstallOutcome
import com.zetaforge.runtime.install.InstalledPlugin
import com.zetaforge.runtime.install.PluginInstaller
import com.zetaforge.runtime.install.PluginStorage
import com.zetaforge.runtime.loader.PluginClassLoader
import com.zetaforge.runtime.loader.PluginClassLoaderFactory
import com.zetaforge.runtime.loader.SharedContract
import com.zetaforge.runtime.log.ZetaLogger
import com.zetaforge.runtime.permission.PermissionCoordinator
import com.zetaforge.runtime.permission.PermissionDecision
import com.zetaforge.runtime.permission.PermissionGateway
import com.zetaforge.runtime.permission.PermissionInspector
import com.zetaforge.runtime.permission.PermissionPlan
import com.zetaforge.runtime.task.ZetaTaskCenter
import com.zetaforge.runtime.verify.BasicPluginVerifier
import com.zetaforge.runtime.verify.PluginVerifier
import com.zetaforge.sdk.PluginResult
import com.zetaforge.sdk.PluginState
import com.zetaforge.sdk.ZetaLog
import com.zetaforge.sdk.ZetaPlugin
import com.zetaforge.sdk.ZetaSdk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * One installed plugin as seen by the UI: what is on disk plus its live state.
 */
data class PluginEntry(
    val installed: InstalledPlugin,
    val state: PluginState,
    val lastResult: PluginResult? = null,
    val loaderStrategy: String? = null,
    /** Permission picture of the last evaluation, for the UI to render. */
    val permissionPlan: PermissionPlan? = null,
) {
    val id: String get() = installed.id
    val isBusy: Boolean
        get() = state == PluginState.LOADING || state == PluginState.STARTING || state == PluginState.RUNNING
}

/** Outcome of an import triggered from the UI. */
sealed class ImportResult {
    data class Success(val entry: PluginEntry, val warnings: List<String>) : ImportResult()
    data class Failure(val stage: String, val reason: String) : ImportResult()
}

/**
 * The heart of ZetaForge: import, verification, installation, class loading,
 * lifecycle, execution, logging and error containment for dynamically loaded
 * plugins.
 *
 * The Host UI only ever talks to this class; nothing plugin-specific exists
 * anywhere else, and the runtime knows nothing about what any given plugin does.
 *
 * ### Trust boundary
 * A loaded plugin runs **inside the Host process, with the Host's UID and the
 * Host's permissions**. This is a trust boundary (only install packages you
 * trust), not a sandbox. Isolation would require a separate `:isolated`
 * process; see docs/architecture.md.
 */
class ZetaPluginRuntime(
    context: Context,
    val logger: ZetaLogger = ZetaLogger(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val appContext: Context = context.applicationContext
    private val storage = PluginStorage(appContext)

    /** Exposed so the Host can feed back Activity-only signals (rationale state). */
    val permissionInspector = PermissionInspector(appContext)
    private val inspector get() = permissionInspector

    /** Evaluates and requests plugin permissions before every execution. */
    val permissions = PermissionCoordinator(inspector, logger)

    private val hostPermissions: Set<String> get() = inspector.declaredByHost

    private val installer = PluginInstaller(
        storage = storage,
        verifierFactory = { pkg -> defaultVerifier(pkg.sha256) },
        logger = logger,
    )

    private val loaded = mutableMapOf<String, LoadedPlugin>()
    private val mutex = Mutex()

    private val _plugins = MutableStateFlow<List<PluginEntry>>(emptyList())
    val plugins: StateFlow<List<PluginEntry>> = _plugins.asStateFlow()

    /**
     * Installs the component that can actually show permission UI. The Host does
     * this from its Activity; without it the runtime denies instead of hanging.
     */
    fun setPermissionGateway(gateway: PermissionGateway) {
        permissions.gateway = gateway
    }

    /** Re-evaluates the permissions of an installed plugin without running it. */
    fun inspectPermissions(pluginId: String): PermissionPlan? =
        _plugins.value.firstOrNull { it.id == pluginId }
            ?.let { inspector.inspect(it.installed.manifest) }

    init {
        // Plugins log through the shared SDK object; route it into our logger.
        ZetaLog.install(logger)
        logger.info(SOURCE, null, "Runtime ready (Host API ${ZetaSdk.HOST_API_VERSION}, device API ${Build.VERSION.SDK_INT})")
    }

    /** Reloads the installed plugin list from app-private storage. */
    suspend fun refresh() = withContext(dispatcher) {
        val installed = installer.listInstalled()
        mutex.withLock {
            val previous = _plugins.value.associateBy { it.id }
            _plugins.value = installed.map { plugin ->
                previous[plugin.id]?.copy(installed = plugin)
                    ?: PluginEntry(plugin, PluginState.INSTALLED)
            }
        }
        logger.info(SOURCE, null, "Discovered ${installed.size} installed plugin(s)")
    }

    /**
     * Imports a `.zeta` archive from an arbitrary source (SAF stream, test
     * asset, ...). The stream is consumed and closed by the runtime.
     */
    suspend fun importPlugin(stream: InputStream, displayHint: String? = null): ImportResult =
        withContext(dispatcher) {
            when (val outcome = installer.install(stream, displayHint)) {
                is InstallOutcome.Failure -> {
                    ImportResult.Failure(outcome.stage, outcome.reason)
                }

                is InstallOutcome.Success -> {
                    // Re-installing replaces the previous version: drop any loader.
                    unload(outcome.plugin.id)
                    val entry = PluginEntry(outcome.plugin, PluginState.INSTALLED)
                    mutex.withLock {
                        _plugins.value = _plugins.value.filterNot { it.id == entry.id } + entry
                    }
                    ImportResult.Success(
                        entry = entry,
                        warnings = outcome.verification.warnings.map { "${it.name}: ${it.detail}" },
                    )
                }
            }
        }

    /**
     * Executes a plugin, loading it first if necessary.
     *
     * Any throwable raised by plugin code is caught here: a broken plugin must
     * never take the Host down.
     */
    suspend fun execute(pluginId: String, input: Bundle = Bundle.EMPTY): PluginResult =
        withContext(dispatcher) {
            val entry = _plugins.value.firstOrNull { it.id == pluginId }
                ?: return@withContext PluginResult.Failure(
                    message = "Plugin $pluginId is not installed",
                    errorCode = "NOT_INSTALLED",
                )

            val started = System.currentTimeMillis()
            val plugin = try {
                load(entry)
            } catch (t: Throwable) {
                val duration = System.currentTimeMillis() - started
                logger.error(SOURCE, pluginId, "Plugin FAILED to load: ${t.javaClass.simpleName}: ${t.message}", t)
                val failure = PluginResult.Failure(
                    message = t.message ?: t.javaClass.simpleName,
                    durationMs = duration,
                    errorCode = "LOAD_ERROR",
                    cause = t,
                    data = mapOf("exception" to t.javaClass.name),
                )
                updateState(pluginId, PluginState.FAILED, failure)
                return@withContext failure
            }

            // Permissions are re-checked on every run: they can be revoked from
            // Settings, or auto-revoked by Android, between two executions.
            when (val decision = permissions.ensureGranted(entry.installed.manifest)) {
                is PermissionDecision.Blocked -> {
                    val duration = System.currentTimeMillis() - started
                    val failure = PluginResult.Failure(
                        message = decision.message,
                        durationMs = duration,
                        errorCode = decision.errorCode,
                        data = mapOf(
                            "missing" to decision.plan.let { plan ->
                                (plan.requestable + plan.permanentlyDenied + plan.undeclared)
                                    .joinToString { it.name }
                            },
                            "specialAccess" to decision.plan.missingSpecialAccess.joinToString { it.access.label },
                        ).filterValues { it.isNotBlank() },
                    )
                    updateState(pluginId, PluginState.FAILED, failure, permissionPlan = decision.plan)
                    ZetaTaskCenter.end(pluginId)
                    return@withContext failure
                }

                is PermissionDecision.Allowed -> {
                    decision.missingOptional.forEach {
                        logger.warn(SOURCE, pluginId, "Running without optional permission " + it.shortName)
                    }
                    updateState(pluginId, PluginState.RUNNING, permissionPlan = decision.plan)
                }
            }

            updateState(pluginId, PluginState.STARTING)
            logger.info(SOURCE, pluginId, "START")
            updateState(pluginId, PluginState.RUNNING)

            // Publishes the run process-wide so the Host can keep a foreground
            // service alive for its whole duration: without it, Android freezes
            // the process as soon as the screen goes off.
            ZetaTaskCenter.begin(pluginId, entry.installed.displayName)

            val runStarted = System.currentTimeMillis()
            val result = try {
                plugin.instance.execute(appContext, input)
            } catch (t: Throwable) {
                // Includes RuntimeException thrown deliberately by a plugin.
                val duration = System.currentTimeMillis() - runStarted
                logger.error(
                    SOURCE, pluginId,
                    "Plugin FAILED: ${t.javaClass.name}: ${t.message} (after ${duration} ms)", t,
                )
                PluginResult.Failure(
                    message = t.message ?: t.javaClass.simpleName,
                    durationMs = duration,
                    errorCode = t.javaClass.simpleName,
                    cause = t,
                    data = mapOf(
                        "exception" to t.javaClass.name,
                        "stackTrace" to t.stackTraceToString().lineSequence().take(12).joinToString("\n"),
                    ),
                )
            }

            ZetaTaskCenter.end(pluginId)

            when (result) {
                is PluginResult.Success -> {
                    logger.info(SOURCE, pluginId, "SUCCESS - ${result.message} (${result.durationMs} ms)")
                    result.data.forEach { (k, v) -> logger.debug(SOURCE, pluginId, "  $k = $v") }
                    updateState(pluginId, PluginState.SUCCESS, result)
                }

                is PluginResult.Failure -> {
                    logger.warn(SOURCE, pluginId, "FAILED - [${result.errorCode}] ${result.message}")
                    updateState(pluginId, PluginState.FAILED, result)
                }
            }
            result
        }

    /** Drops the class loader and instance of a plugin, keeping it installed. */
    suspend fun unload(pluginId: String) {
        val plugin = mutex.withLock { loaded.remove(pluginId) } ?: return
        runCatching { plugin.instance.onUnload() }
            .onFailure { logger.warn(SOURCE, pluginId, "onUnload threw: ${it.message}") }
        logger.info(SOURCE, pluginId, "Unloaded")
        updateState(pluginId, PluginState.STOPPED)
    }

    /** Removes a plugin from disk. */
    suspend fun uninstall(pluginId: String) {
        unload(pluginId)
        withContext(dispatcher) { installer.uninstall(pluginId) }
        mutex.withLock { _plugins.value = _plugins.value.filterNot { it.id == pluginId } }
    }

    /** Re-runs verification against the archive currently on disk. */
    suspend fun verifyInstalled(pluginId: String): List<String> = withContext(dispatcher) {
        val entry = _plugins.value.firstOrNull { it.id == pluginId } ?: return@withContext emptyList()
        val result = installer.reverify(entry.installed, defaultVerifier(entry.installed.sha256))
        result.checks.map { check ->
            val mark = when {
                check.warning -> "warn"
                check.passed -> "ok"
                else -> "FAIL"
            }
            "[$mark] ${check.name}: ${check.detail}"
        }
    }

    // -- internals ----------------------------------------------------------

    private suspend fun load(entry: PluginEntry): LoadedPlugin = mutex.withLock {
        loaded[entry.id]?.let { return@withLock it }

        val pluginId = entry.id
        updateState(pluginId, PluginState.LOADING)
        val manifest = entry.installed.manifest

        if (!manifest.isCompatibleWith(ZetaSdk.HOST_API_VERSION)) {
            error(
                "Plugin incompatible with this Host: requires API " +
                    "[${manifest.minHostApi}..${manifest.maxHostApi}], Host implements ${ZetaSdk.HOST_API_VERSION}"
            )
        }

        val missingPermissions = manifest.permissionNames.filterNot { hostPermissions.contains(it) }
        if (missingPermissions.isNotEmpty()) {
            logger.warn(
                SOURCE, pluginId,
                "Permission mismatch: plugin requests ${missingPermissions.joinToString()} " +
                    "but the Host APK does not declare it, so it can never be granted.",
            )
        }

        val hostLoader = javaClass.classLoader ?: ClassLoader.getSystemClassLoader()
        val loader: PluginClassLoader = PluginClassLoaderFactory.create(
            plugin = entry.installed,
            parent = hostLoader,
            optimizedDir = storage.oatDir(pluginId),
        )
        logger.info(
            SOURCE, pluginId,
            "Class loader: ${loader.strategy} over ${entry.installed.codeJar.name} " +
                "(${entry.installed.codeJar.length()} bytes)",
        )

        val conflicts = SharedContract.conflicts(loader.delegate, hostLoader)
        if (conflicts.isNotEmpty()) {
            error(
                "Plugin bundles shared contract classes (${conflicts.joinToString()}); " +
                    "declare them as compileOnly in the plugin build."
            )
        }

        val clazz = loader.loadClass(manifest.entryPoint)
        if (!ZetaPlugin::class.java.isAssignableFrom(clazz)) {
            error("${manifest.entryPoint} does not implement com.zetaforge.sdk.ZetaPlugin")
        }
        val instance = clazz.getDeclaredConstructor().newInstance() as ZetaPlugin
        logger.info(SOURCE, pluginId, "Entry point instantiated: ${manifest.entryPoint}")

        if (instance.id != manifest.pluginId) {
            logger.warn(
                SOURCE, pluginId,
                "Entry point reports id '${instance.id}' but the manifest says '${manifest.pluginId}'",
            )
        }

        instance.onLoad(appContext)
        val result = LoadedPlugin(instance, loader)
        loaded[pluginId] = result
        updateState(pluginId, PluginState.LOADED, loaderStrategy = loader.strategy.name)
        logger.info(SOURCE, pluginId, "Plugin loaded")
        result
    }

    private fun defaultVerifier(expectedSha256: String?): PluginVerifier = BasicPluginVerifier(
        hostApiVersion = ZetaSdk.HOST_API_VERSION,
        hostPermissions = hostPermissions,
        hostSdkInt = Build.VERSION.SDK_INT,
        expectedSha256 = expectedSha256,
    )

    private fun updateState(
        pluginId: String,
        state: PluginState,
        result: PluginResult? = null,
        loaderStrategy: String? = null,
        permissionPlan: PermissionPlan? = null,
    ) {
        _plugins.value = _plugins.value.map { entry ->
            if (entry.id != pluginId) entry else entry.copy(
                state = state,
                lastResult = result ?: entry.lastResult,
                loaderStrategy = loaderStrategy ?: entry.loaderStrategy,
                permissionPlan = permissionPlan ?: entry.permissionPlan,
            )
        }
    }

    private data class LoadedPlugin(
        val instance: ZetaPlugin,
        val loader: PluginClassLoader,
    )

    private companion object {
        const val SOURCE = "Runtime"
    }
}
