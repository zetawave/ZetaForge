package com.zetaforge.sdk.ui

import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.Composable
import com.zetaforge.sdk.PluginResult
import com.zetaforge.sdk.ZetaPlugin

/**
 * A plugin that owns a screen.
 *
 * `ZetaPlugin` describes work that starts, runs and ends. This describes the
 * other half of what a plugin can be: something the user opens, looks at and
 * interacts with — a mini app inside the Host.
 *
 * ### Why the Host draws it
 * Android freezes an application's components at install time from its APK
 * manifest, so an `Activity` living in a dynamically loaded DEX can never be
 * started: the system has nothing to look up. The Host therefore declares one
 * container Activity and asks the plugin for its *content*, which is a
 * composable rather than a component. This has a second, larger benefit: Compose
 * needs no resources, so a plugin needs no `resources.arsc`, no `R` class and no
 * change to the `.zeta` format.
 *
 * ### The rules that keep it stable
 * * Compose is **provided by the Host**, exactly like the Kotlin stdlib: declare
 *   it `compileOnly` and never bundle it. A bundled copy resolves to a different
 *   class object than the Host's and the screen dies with `ClassCastException`;
 *   the packaging task and the runtime both refuse a package that does it.
 * * The composable runs on the main thread, inside the Host's own composition
 *   and theme. Do slow work in [ZetaUiHost.scope], never during composition.
 * * Throwing is contained: a failure during composition, in a touch handler or
 *   in [ZetaUiHost.scope] replaces the screen with an error report and unloads
 *   the plugin. It does not take the Host down.
 *
 * ### Versioning
 * The UI contract has its own version ([com.zetaforge.sdk.ZetaSdk.UI_API_VERSION])
 * because it evolves with Compose, not with the rest of the SDK. A package
 * records the one it was built against and the Host refuses a newer one with an
 * explicit message instead of a `NoSuchMethodError`.
 */
interface ZetaUiPlugin : ZetaPlugin {

    /**
     * The plugin's screen.
     *
     * Called on the main thread, inside the Host's Material theme, in the area
     * below the Host's own title bar — which cannot be drawn over, so the user
     * always knows whose screen they are looking at.
     *
     * Composition must be cheap and free of side effects: it can run many times
     * per second. Anything that reads a file, touches the network or takes more
     * than a frame belongs in [ZetaUiHost.scope].
     */
    @Composable
    fun Content(host: ZetaUiHost)

    /**
     * What happens when a screen plugin is *run* rather than opened — from the
     * plugin list, a schedule or the CLI.
     *
     * The default says so and does nothing, which is the honest answer for a
     * plugin that is only a screen. Override it to be both: a screen when
     * opened, a job when scheduled.
     */
    override suspend fun execute(context: Context, input: Bundle): PluginResult =
        PluginResult.Success(
            message = "$name is a screen: open it from the plugin card.",
            data = mapOf("ui" to "true"),
        )
}
