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
     *
     * Additions are backwards compatible: a plugin built against API 1 runs
     * unchanged on a Host implementing API 2 (see `maxHostApi` handling in the
     * runtime, which warns instead of refusing).
     */
    const val HOST_API_VERSION: Int = 2

    /** Lowest Host API version this SDK still knows how to talk to. */
    const val MIN_SUPPORTED_PLUGIN_API: Int = 1

    /**
     * Version of the `.zeta` package/manifest format understood by the runtime.
     *
     * 1 - initial format.
     * 2 - structured permissions (reason/optional/sdk range), special access and
     *     bundled source files. Version 1 packages keep working.
     */
    const val MANIFEST_FORMAT_VERSION: Int = 2
}
