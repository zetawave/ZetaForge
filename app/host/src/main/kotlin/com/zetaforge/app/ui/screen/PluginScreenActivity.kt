package com.zetaforge.app.ui.screen

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.Recomposer
import com.zetaforge.app.R
import com.zetaforge.app.ZetaForgeApp
import com.zetaforge.app.permission.ActivityPermissionGateway
import com.zetaforge.app.ui.AppPreferences
import com.zetaforge.app.ui.theme.ZetaForgeTheme
import com.zetaforge.runtime.UiOpenResult
import com.zetaforge.runtime.ZetaPluginRuntime
import com.zetaforge.runtime.permission.PermissionPlan
import com.zetaforge.runtime.permission.SpecialAccess
import com.zetaforge.runtime.ui.ZetaUiSessions
import com.zetaforge.sdk.ui.ZetaUiHost
import com.zetaforge.sdk.ui.ZetaUiPlugin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The one Activity every plugin screen is drawn inside.
 *
 * Android freezes an app's components at install time from its APK manifest, so
 * an `Activity` class living in a plugin's DEX can never be started: there is
 * nothing for the system to resolve. The Host therefore declares this container
 * once and asks the plugin for *content* instead of a component — which is why
 * the screen contract is a composable and not an `Activity` subclass, and why a
 * plugin needs no resources, no `R` class and no change to the `.zeta` format.
 *
 * ### What this class is actually responsible for
 * Drawing is the easy part. The rest is the reason it is not a few lines:
 *
 * * **Containment.** A plugin that throws must not kill the Host. Failures come
 *   from two places that need two different mechanisms: the view callbacks,
 *   caught by [PluginCrashGuard], and composition, caught by giving the plugin's
 *   content its own [Recomposer] with its own exception handler.
 * * **Identity.** The title bar lives in a *different composition* from the
 *   plugin's, in a different view, above it. A plugin cannot draw over what
 *   tells the user whose screen they are looking at.
 * * **Permissions.** A screen asks through the same runtime path a scheduled run
 *   uses, so a screen and a job can never end up with two different ideas of
 *   what has been granted.
 * * **Lifecycle.** While a screen is open its plugin must not be unloaded, and
 *   an uninstall has to reach it. Both go through [ZetaUiSessions].
 */
class PluginScreenActivity : ComponentActivity() {

    private val runtime: ZetaPluginRuntime by lazy { ZetaForgeApp.runtime(this) }
    private val preferences by lazy { AppPreferences(this) }

    private lateinit var pluginId: String
    private lateinit var gateway: ActivityPermissionGateway

    /** The plugin's own view area, replaced wholesale when it fails. */
    private lateinit var pluginArea: ViewGroup

    private var screen: ZetaUiPlugin? = null
    private var sessionOpen = false

    /** Scope handed to the plugin: cancelled with the screen, failures contained. */
    private var pluginScope: CoroutineScope? = null

    /** The plugin composition's own recomposer, so its failures are ours to catch. */
    private var recomposer: Recomposer? = null
    private var recomposerJob: Job? = null

    // --- state the chrome renders, owned here so both compositions see it ----
    private var phase by mutableStateOf<Phase>(Phase.Loading)
    private var subtitle by mutableStateOf<String?>(null)
    private var messageText by mutableStateOf<String?>(null)
    private var permissionPrompt by mutableStateOf<PermissionPlan?>(null)
    private var specialAccessPrompt by mutableStateOf<Pair<SpecialAccess, String>?>(null)

    private var pendingPermission: CompletableDeferred<Boolean>? = null
    private var pendingSpecialAccess: CompletableDeferred<Boolean>? = null

    /** What the screen area is showing. */
    internal sealed class Phase {
        data object Loading : Phase()
        data object Ready : Phase()
        data class Refused(val reason: String, val errorCode: String) : Phase()
        data class Crashed(val error: Throwable) : Phase()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID).orEmpty()

        // Registered here because the Activity Result API demands it, and used
        // from the plugin's own scope later on.
        gateway = ActivityPermissionGateway(
            activity = this,
            inspector = runtime.permissionInspector,
            onExplain = ::explainPermissions,
            onSpecialAccess = ::explainSpecialAccess,
        )

        refreshTheme()
        setContentView(buildViewTree())

        if (pluginId.isBlank()) {
            phase = Phase.Refused(getString(R.string.screen_error_no_plugin), "NO_PLUGIN_ID")
            return
        }

        // An uninstall or a re-import of this plugin has to be able to reach the
        // screen: the files are about to go, and a composition holding classes
        // from a deleted package is the one state nothing can recover from.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                ZetaUiSessions.closeRequests.collect { id ->
                    if (id != pluginId) return@collect
                    // Torn down here rather than left to onDestroy: what the
                    // uninstall is waiting for is the composition to be gone,
                    // and Android is free to defer destroying a background
                    // Activity for as long as it likes.
                    teardown(crashed = false)
                    finish()
                }
            }
        }

        lifecycleScope.launch { openScreen() }
    }

    override fun onResume() {
        super.onResume()
        // The theme can have been changed from the Host's own settings while
        // this screen sat in the background.
        refreshTheme()
        // The runtime holds one gateway, and only a resumed Activity can show a
        // dialog. Whoever is in front owns it; MainActivity takes it back the
        // same way when this screen goes away.
        runtime.setPermissionGateway(gateway)
    }

    override fun onDestroy() {
        super.onDestroy()
        teardown(crashed = phase is Phase.Crashed)
    }

    // -- opening --------------------------------------------------------------

    private suspend fun openScreen() {
        when (val result = runtime.openUi(pluginId)) {
            is UiOpenResult.Refused -> phase = Phase.Refused(result.reason, result.errorCode)

            is UiOpenResult.Ready -> {
                sessionOpen = true
                screen = result.plugin
                setTitle(result.entry.installed.displayName)
                pluginName = result.entry.installed.displayName
                settingsSnapshot = runtime.settings.toBundle(
                    result.entry.installed.manifest.settings,
                    runtime.settings.effectiveValues(pluginId, result.entry.installed.manifest.settings),
                )
                phase = Phase.Ready
                renderPluginContent(result.plugin)
            }
        }
    }

    /** State, not a plain field: the title bar renders it. */
    private var pluginName by mutableStateOf("")

    /** The one theme decision every composition on this screen reads. */
    private var darkTheme by mutableStateOf(false)
    private var settingsSnapshot: Bundle = Bundle()

    /**
     * Builds the plugin's composition, in its own recomposer.
     *
     * The recomposer is what turns "an exception during recomposition kills the
     * process" into "an exception during recomposition shows an error screen".
     * The Host's own window recomposer would propagate it; this one hands it to
     * [onPluginCrash] instead.
     */
    private fun renderPluginContent(plugin: ZetaUiPlugin) {
        val handler = CoroutineExceptionHandler { _, error -> onPluginCrash(error) }
        val scope = CoroutineScope(
            AndroidUiDispatcher.CurrentThread + SupervisorJob() + handler
        )
        val ownRecomposer = Recomposer(scope.coroutineContext)
        recomposer = ownRecomposer
        recomposerJob = scope.launch { ownRecomposer.runRecomposeAndApplyChanges() }

        // A separate scope for the plugin's own coroutines: it must survive one
        // failed job (SupervisorJob) and report the failure the same way the
        // composition does.
        pluginScope = CoroutineScope(
            AndroidUiDispatcher.CurrentThread + SupervisorJob() + handler
        )

        val host = HostBridge()
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setParentCompositionContext(ownRecomposer)
            setContent {
                ZetaForgeTheme(darkTheme = darkTheme) {
                    plugin.Content(host)
                }
            }
        }
        pluginArea.removeAllViews()
        pluginArea.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    /**
     * A plugin failed, wherever it failed.
     *
     * The plugin's composition is torn down rather than repaired: after an
     * exception its slot table describes a tree that was never finished, and
     * recomposing it again fails again. The plugin is unloaded with the session,
     * so opening the screen a second time starts from a fresh instance.
     */
    private fun onPluginCrash(error: Throwable) {
        if (phase is Phase.Crashed) return
        runtime.logger.error(
            "Screen", pluginId,
            "Screen FAILED: " + error.javaClass.name + ": " + error.message, error,
        )
        phase = Phase.Crashed(error)
        disposePluginComposition()
        renderCrashReport(error)
    }

    private fun renderCrashReport(error: Throwable) {
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                ZetaForgeTheme(darkTheme = darkTheme) {
                    PluginScreenError(
                        title = getString(R.string.screen_crashed_title),
                        body = getString(
                            R.string.screen_crashed_body,
                            pluginName.ifBlank { pluginId },
                        ),
                        detail = error.javaClass.name + ": " + (error.message ?: ""),
                        stackTrace = error.stackTraceToString()
                            .lineSequence().take(12).joinToString("\n"),
                        onClose = ::finish,
                    )
                }
            }
        }
        pluginArea.removeAllViews()
        pluginArea.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun disposePluginComposition() {
        pluginArea.removeAllViews()
        pluginScope?.cancel()
        pluginScope = null
        recomposer?.cancel()
        recomposer = null
        recomposerJob?.cancel()
        recomposerJob = null
    }

    /**
     * Ends the session exactly once.
     *
     * `onDestroy` runs on a configuration change too, and the runtime must see
     * one `closeUi` per `openUi` or the plugin would stay pinned against unload
     * for the rest of the process's life.
     */
    private fun teardown(crashed: Boolean) {
        disposePluginComposition()
        screen = null
        if (!sessionOpen) return
        sessionOpen = false
        // The application scope, not the Activity's: this must complete even
        // though the Activity is already gone.
        ZetaForgeApp.instance(this).scope.launch { runtime.closeUi(pluginId, crashed) }
    }

    // -- the view tree ---------------------------------------------------------

    /**
     * Chrome on top, plugin below, in two separate views.
     *
     * Two views rather than one composition on purpose: the bar that says whose
     * screen this is has to survive the plugin failing, and must not be
     * reachable by the plugin's own drawing. A plugin can still open a `Dialog`
     * over the whole window — Android gives it no way to prevent that — but it
     * cannot quietly repaint the Host's identity.
     */
    private fun buildViewTree(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = false
        }

        val chrome = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                ZetaForgeTheme(darkTheme = darkTheme) {
                    PluginScreenChrome(
                        pluginName = pluginName.ifBlank { pluginId },
                        subtitle = subtitle,
                        phase = phase,
                        message = messageText,
                        permissionPrompt = permissionPrompt,
                        specialAccessPrompt = specialAccessPrompt,
                        onClose = ::finish,
                        onMessageShown = { messageText = null },
                        onPermissionResult = ::onPermissionPromptResult,
                        onSpecialAccessResult = ::onSpecialAccessPromptResult,
                    )
                }
            }
        }
        root.addView(
            chrome,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        pluginArea = PluginCrashGuard(this, ::onPluginCrash)
        root.addView(
            pluginArea,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        // targetSdk 35 draws behind the system bars whether we ask or not, so
        // the insets are applied here once and the plugin gets a plain
        // rectangle it never has to think about.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // Loading is what the first frame shows: openUi reads the manifest and
        // may have to load a DEX, and a blank window would look like a hang.
        pluginArea.addView(
            ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    ZetaForgeTheme(darkTheme = darkTheme) { PluginScreenPlaceholder(phase) }
                }
            },
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        return root
    }

    /**
     * Decided once, for all three compositions.
     *
     * The screen is drawn by more than one composition - chrome, plugin, error -
     * and each one asking Compose for the theme separately is how they end up
     * disagreeing: they are created at different moments, and the plugin's runs
     * under its own recomposer. One value, read by all of them, cannot diverge.
     */
    private fun refreshTheme() {
        val night = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        darkTheme = when (preferences.state.value.theme) {
            AppPreferences.Theme.SYSTEM -> night
            AppPreferences.Theme.LIGHT -> false
            AppPreferences.Theme.DARK -> true
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshTheme()
    }

    // -- permission prompts ----------------------------------------------------

    private suspend fun explainPermissions(plan: PermissionPlan): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingPermission = deferred
        permissionPrompt = plan
        return deferred.await()
    }

    private fun onPermissionPromptResult(accepted: Boolean) {
        permissionPrompt = null
        pendingPermission?.complete(accepted)
        pendingPermission = null
    }

    private suspend fun explainSpecialAccess(access: SpecialAccess, reason: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingSpecialAccess = deferred
        specialAccessPrompt = access to reason
        return deferred.await()
    }

    private fun onSpecialAccessPromptResult(accepted: Boolean) {
        specialAccessPrompt = null
        pendingSpecialAccess?.complete(accepted)
        pendingSpecialAccess = null
    }

    // -- what the plugin is given ----------------------------------------------

    /**
     * The Host as the plugin sees it.
     *
     * An inner class rather than the Activity itself: handing over the Activity
     * would let a plugin finish it, replace its content view or start its own
     * permission request behind the runtime's back.
     */
    private inner class HostBridge : ZetaUiHost {
        override val pluginId: String get() = this@PluginScreenActivity.pluginId
        override val pluginName: String get() = this@PluginScreenActivity.pluginName
        override val context: Context get() = this@PluginScreenActivity
        override val settings: Bundle get() = settingsSnapshot
        override val scope: CoroutineScope
            get() = pluginScope ?: CoroutineScope(SupervisorJob())

        override fun message(text: String) {
            messageText = text
        }

        override fun setSubtitle(text: String?) {
            subtitle = text
        }

        override suspend fun ensurePermissions(): Boolean {
            val entry = runtime.plugins.value.firstOrNull { it.id == pluginId } ?: return false
            return runtime.permissions.ensureGranted(entry.installed.manifest) is
                com.zetaforge.runtime.permission.PermissionDecision.Allowed
        }

        override fun close() {
            finish()
        }
    }

    companion object {
        const val EXTRA_PLUGIN_ID = "pluginId"

        /** Opens the screen of an installed plugin. */
        fun intent(context: Context, pluginId: String): Intent =
            Intent(context, PluginScreenActivity::class.java)
                .putExtra(EXTRA_PLUGIN_ID, pluginId)
    }
}
