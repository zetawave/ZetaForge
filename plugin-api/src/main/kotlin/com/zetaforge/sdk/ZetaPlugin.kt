package com.zetaforge.sdk

import android.content.Context
import android.os.Bundle

/**
 * The single contract every ZetaForge plugin implements.
 *
 * A plugin is plain Kotlin: it is compiled separately from the Host, shipped as
 * a `.zeta` archive, and executed inside the Host process with the Host's
 * [Context]. Nothing here hides or wraps the Android APIs on purpose - a plugin
 * is expected to use `Context`, the Android framework and its own JVM
 * dependencies exactly as it would inside a normal app module.
 *
 * Implementations must:
 *  - expose a public no-argument constructor (the runtime instantiates them
 *    reflectively);
 *  - be safe to execute more than once;
 *  - never assume they own the process: throwing is caught by the runtime, but
 *    calling `System.exit` or killing threads is not.
 */
interface ZetaPlugin {

    /** Must match `pluginId` in the package manifest. */
    val id: String

    /** Human readable name. */
    val name: String

    /** Plugin version, informational. */
    val version: String

    /**
     * Executes the plugin.
     *
     * Called off the main thread by [com.zetaforge.runtime] on a background
     * dispatcher; implementations are still responsible for their own threading
     * when they start work of their own.
     *
     * @param context the Host application context. Plugin code runs in the
     *   Host's security context: everything the Host may do, the plugin may do.
     * @param input arbitrary parameters supplied by the caller. Keys used by the
     *   demo plugin are documented in its own source.
     */
    suspend fun execute(context: Context, input: Bundle): PluginResult

    /**
     * Optional hook invoked once after the class is instantiated and before the
     * first [execute]. Default implementation does nothing.
     */
    suspend fun onLoad(context: Context) {}

    /**
     * Optional hook invoked when the Host unloads the plugin. Default
     * implementation does nothing.
     */
    suspend fun onUnload() {}
}
