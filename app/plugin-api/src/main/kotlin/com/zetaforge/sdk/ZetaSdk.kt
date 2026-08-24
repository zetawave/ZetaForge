package com.zetaforge.sdk

/**
 * Version marker of the Host <-> plugin contract.
 *
 * Every `.zeta` manifest declares `minHostApi` / `maxHostApi`. The runtime
 * refuses to load a plugin whose declared range does not contain
 * [ZetaSdk.HOST_API_VERSION], so the contract can evolve without silently
 * breaking already published plugins.
 *
 * Bump [HOST_API_VERSION] whenever anything in this module changes in a way an
 * already compiled plugin could observe (signatures, semantics, new required
 * members on [ZetaPlugin], ...).
 */
object ZetaSdk {

    /**
     * Current Host API version implemented by this SDK build.
     *
     * 1 - initial contract: ZetaPlugin, PluginResult, ZetaLog.
     * 2 - adds ZetaProgress, and the Host keeps a foreground service alive for
     *     the whole execution, so a plugin survives the screen going off.
     * 3 - adds settings: parameters declared in the manifest and edited in the
     *     Host, optionally refined at run time by ZetaPlugin.settings(), with
     *     ZetaSetting.Action buttons served by ZetaPlugin.runAction().
     * 4 - adds screens: com.zetaforge.sdk.ui.ZetaUiPlugin, drawn by the Host in
     *     a container Activity with Compose it provides. See [UI_API_VERSION].
     *
     * Additions are backwards compatible: a plugin built against API 1 runs
     * unchanged on a Host implementing API 2 (see `maxHostApi` handling in the
     * runtime, which warns instead of refusing).
     */
    const val HOST_API_VERSION: Int = 4

    /** Lowest Host API version this SDK still knows how to talk to. */
    const val MIN_SUPPORTED_PLUGIN_API: Int = 1

    /**
     * Version of the *screen* contract ([com.zetaforge.sdk.ui.ZetaUiPlugin]).
     *
     * Kept apart from [HOST_API_VERSION] because it moves for a different
     * reason: the screen ABI is Compose's ABI, and a Host that upgrades Compose
     * can break an already compiled screen without changing anything in the rest
     * of the SDK. A package records the version it was built against and the
     * Host refuses a newer one with an explicit message, rather than letting it
     * die on a NoSuchMethodError halfway through the first frame.
     *
     * 1 - initial: ZetaUiPlugin.Content(ZetaUiHost), Compose provided by the Host.
     */
    const val UI_API_VERSION: Int = 1

    /**
     * Version of the `.zeta` package/manifest format understood by the runtime.
     *
     * 1 - initial format.
     * 2 - structured permissions (reason/optional/sdk range), special access and
     *     bundled source files. Version 1 packages keep working.
     * 3 - declared settings (`settings` array), rendered by the Host.
     * 4 - the `ui` block: whether the plugin has a screen, the UI contract
     *     version it was built against, and whether the screen is all it is.
     */
    const val MANIFEST_FORMAT_VERSION: Int = 4
}
