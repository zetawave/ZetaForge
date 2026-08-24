---
title: SDK reference
description: Every type in com.zetaforge.sdk — the whole surface a plugin compiles against.
---

# SDK reference

Everything a plugin can see, in one package: `com.zetaforge.sdk`. It is
deliberately small. The SDK is the only artifact shared between the host and
every plugin, so each type in it is a compatibility commitment.

The jar ships inside the CLI (`zetaforge-api-<n>.jar`) and is produced by the
same build as the host that implements it, so the two cannot drift apart.

## ZetaPlugin

The contract every plugin implements.

```kotlin
interface ZetaPlugin {
    val id: String
    val name: String
    val version: String

    suspend fun execute(context: Context, input: Bundle): PluginResult

    suspend fun onLoad(context: Context) {}
    suspend fun onUnload() {}

    suspend fun settings(context: Context, current: Bundle): ZetaSettingsSpec? = null
    suspend fun runAction(context: Context, actionKey: String, current: Bundle): ZetaActionResult
}
```

| Member | |
|---|---|
| `id` | Must match `id` in the descriptor. A mismatch is logged as a warning. |
| `execute` | The work. Called off the main thread. Never called concurrently with itself for the same plugin. |
| `onLoad` | Once, after instantiation, before the first `execute`. |
| `onUnload` | When the plugin is unloaded, uninstalled or replaced. Failures are logged, not propagated. |
| `settings` | Optional fields computed on the device. Time-limited; failures fall back to the declared form. See [Settings](settings.md#computed-at-run-time). |
| `runAction` | Runs an action button. Keep it short — a dialog is waiting. |

The class needs a **public no-argument constructor**: the runtime instantiates
it reflectively from the name in the manifest.

## PluginResult

```kotlin
sealed class PluginResult {
    abstract val message: String
    abstract val durationMs: Long
    abstract val data: Map<String, String>
    abstract val status: Status

    enum class Status { SUCCESS, FAILURE }

    data class Success(
        override val message: String,
        override val durationMs: Long = 0L,
        override val data: Map<String, String> = emptyMap(),
    ) : PluginResult()

    data class Failure(
        override val message: String,
        override val durationMs: Long = 0L,
        override val data: Map<String, String> = emptyMap(),
        val errorCode: String = "PLUGIN_ERROR",
        val cause: Throwable? = null,
    ) : PluginResult()

    companion object {
        fun of(throwable: Throwable, durationMs: Long = 0L): Failure
    }
}
```

See [Lifecycle and results](lifecycle.md#results).

## PluginState

```kotlin
enum class PluginState {
    INSTALLED, LOADING, LOADED, STARTING, RUNNING, SUCCESS, FAILED, STOPPED
}
```

Reported by the host; a plugin does not set it. Documented because it appears in
logs and on the card.

## ZetaLog

```kotlin
object ZetaLog {
    fun debug(pluginId: String?, source: String, message: String)
    fun info(pluginId: String?, source: String, message: String)
    fun warn(pluginId: String?, source: String, message: String)
    fun error(pluginId: String?, source: String, message: String, throwable: Throwable? = null)
}
```

Routed into the host's log console, `zeta logs` and `zeta run`. See
[Logging and progress](logging-and-progress.md).

## ZetaProgress

```kotlin
object ZetaProgress {
    fun report(pluginId: String, current: Long, total: Long?, message: String = "")
    fun report(pluginId: String, update: ZetaProgressUpdate)
    fun status(pluginId: String, message: String)
}

data class ZetaProgressUpdate(
    val current: Long = 0L,
    val total: Long? = null,
    val message: String = "",
) {
    val percent: Int?      // 0..100, or null when total is unknown
}
```

`total = null` produces an indeterminate indicator rather than a bar.

Added in Host API 2, together with the foreground service that keeps a long run
alive when the screen goes off.

## Settings types

```kotlin
sealed class ZetaSetting {
    abstract val key: String
    abstract val label: String
    abstract val description: String
    abstract val group: String
    abstract val advanced: Boolean

    data class Switch(…, val default: Boolean = false) : ZetaSetting()
    data class Number(…, val default: Long, val min: Long, val max: Long, val step: Long, val unit: String) : ZetaSetting()
    data class Decimal(…, val default: Double, val min: Double, val max: Double, val unit: String) : ZetaSetting()
    data class Text(…, val default: String, val hint: String, val secret: Boolean) : ZetaSetting()
    data class Choice(…, val default: String, val options: List<Option>) : ZetaSetting()
    data class MultiChoice(…, val default: List<String>, val options: List<Choice.Option>) : ZetaSetting()
    data class Folder(…, val default: String) : ZetaSetting()
    data class Action(…, val runningLabel: String) : ZetaSetting()
}

data class ZetaSettingsSpec(val settings: List<ZetaSetting> = emptyList()) {
    val isEmpty: Boolean
    fun mergedWith(other: ZetaSettingsSpec): ZetaSettingsSpec
}

data class ZetaActionResult(
    val successful: Boolean,
    val message: String,
    val updatedValues: Map<String, String> = emptyMap(),
) {
    companion object {
        fun ok(message: String, updatedValues: Map<String, String> = emptyMap()): ZetaActionResult
        fun failed(message: String): ZetaActionResult
    }
}
```

You normally declare settings in the descriptor rather than building these by
hand; these types exist for the run-time hook. See [Settings](settings.md).

## Screens — `com.zetaforge.sdk.ui`

```kotlin
interface ZetaUiPlugin : ZetaPlugin {
    @Composable
    fun Content(host: ZetaUiHost)

    // Says "this is a screen" and does nothing. Override to be both.
    override suspend fun execute(context: Context, input: Bundle): PluginResult
}

interface ZetaUiHost {
    val pluginId: String
    val pluginName: String
    val context: Context
    val settings: Bundle
    val scope: CoroutineScope

    fun message(text: String)
    fun setSubtitle(text: String?)
    suspend fun ensurePermissions(): Boolean
    fun close()
}
```

Added in Host API 4. Compose is provided by the host and must be `compileOnly`
in the plugin. See [Screens](screens.md).

## ZetaSdk

```kotlin
object ZetaSdk {
    const val HOST_API_VERSION: Int = 4
    const val MIN_SUPPORTED_PLUGIN_API: Int = 1
    const val UI_API_VERSION: Int = 1
    const val MANIFEST_FORMAT_VERSION: Int = 4
}
```

See [Versioning](versioning.md).

## What is deliberately absent

There is no ZetaForge wrapper around files, the network, notifications or
storage — and that is a decision, not an omission. A plugin gets the host's real
`Context` and uses the Android framework directly, exactly as an app would.

The cost is that a plugin can do anything the host can do. The benefit is that
everything you already know about Android applies unchanged, and no wrapper
stands between you and an API the day it gains a new capability. See
[the security model](security.md).

## Next

[zetaplugin.toml →](manifest-reference.md)
