package com.zetaforge.sdk

/**
 * Lifecycle of a plugin as seen by the Host runtime.
 *
 * ```
 * DISCOVERED -> VALIDATING -> INSTALLING -> INSTALLED
 *                                   |
 *                                   v
 *                             LOADING -> LOADED -> STARTING -> RUNNING -> SUCCESS
 *                                                                     \-> FAILED
 *                                                                     \-> STOPPED
 * ```
 *
 * Not every transition is exercised by the PoC, but the model is complete so the
 * runtime can grow (scheduling, background execution, updates) without changing
 * the vocabulary shared with the UI.
 */
enum class PluginState {
    /** A `.zeta` file has been handed to the runtime but nothing was checked yet. */
    DISCOVERED,

    /** Structure, manifest, checksum and API compatibility are being verified. */
    VALIDATING,

    /** The package is being copied into app-private storage and unpacked. */
    INSTALLING,

    /** Present on disk, verified, not loaded in memory. */
    INSTALLED,

    /** A class loader is being created for the plugin DEX. */
    LOADING,

    /** Entry point class instantiated, ready to run. */
    LOADED,

    /** Execution requested, plugin about to run. */
    STARTING,

    /** `execute` is currently running. */
    RUNNING,

    /** Last execution returned a success result. */
    SUCCESS,

    /** Last execution failed or the plugin could not be loaded. */
    FAILED,

    /** Explicitly unloaded by the Host. */
    STOPPED,
}
