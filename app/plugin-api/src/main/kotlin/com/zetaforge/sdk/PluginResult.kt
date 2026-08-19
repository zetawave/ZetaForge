package com.zetaforge.sdk

/**
 * Structured outcome of a plugin execution.
 *
 * Deliberately richer than a boolean: the Host renders it, logs it and (later)
 * will persist it, so every field must survive without the Host knowing what
 * the plugin actually did.
 */
sealed class PluginResult {

    /** Human readable summary shown in the Host UI. */
    abstract val message: String

    /** Wall clock duration of the execution, milliseconds. */
    abstract val durationMs: Long

    /** Free-form structured payload; keys are defined by the plugin. */
    abstract val data: Map<String, String>

    /** Machine readable status. */
    abstract val status: Status

    enum class Status { SUCCESS, FAILURE }

    data class Success(
        override val message: String,
        override val durationMs: Long = 0L,
        override val data: Map<String, String> = emptyMap(),
    ) : PluginResult() {
        override val status: Status get() = Status.SUCCESS
    }

    data class Failure(
        override val message: String,
        override val durationMs: Long = 0L,
        override val data: Map<String, String> = emptyMap(),
        /** Stable, plugin-defined error code (e.g. `HTTP_ERROR`, `TIMEOUT`). */
        val errorCode: String = "PLUGIN_ERROR",
        /** Original throwable when the failure came from an exception. */
        val cause: Throwable? = null,
    ) : PluginResult() {
        override val status: Status get() = Status.FAILURE
    }

    companion object {

        /** Convenience builder used by plugins that fail from a caught exception. */
        fun of(throwable: Throwable, durationMs: Long = 0L): Failure = Failure(
            message = throwable.message ?: throwable.javaClass.simpleName,
            durationMs = durationMs,
            errorCode = throwable.javaClass.simpleName,
            cause = throwable,
        )
    }
}
