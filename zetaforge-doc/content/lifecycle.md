---
title: Lifecycle and results
description: The states a plugin moves through, how results are structured, and what happens when a plugin throws.
---

# Lifecycle and results

## The states

```text
INSTALLED ──▶ LOADING ──▶ LOADED ──▶ STARTING ──▶ RUNNING ──▶ SUCCESS
                 │                                   │            │
                 └──────────── FAILED ◀──────────────┘            │
                                                                  ▼
                                                              STOPPED
```

| State | Means |
|---|---|
| `INSTALLED` | On disk, verified, never loaded in this process |
| `LOADING` | Class loader being built, entry point being resolved |
| `LOADED` | Instantiated, `onLoad` returned |
| `STARTING` | Permissions being evaluated and requested |
| `RUNNING` | Inside `execute` |
| `SUCCESS` / `FAILED` | The last run's outcome |
| `STOPPED` | Unloaded; the class loader has been dropped |

The card in the app shows this, which is worth knowing when you are debugging:
a plugin stuck in `STARTING` is waiting on a permission dialog, not on your code.

## Loading

A plugin is loaded on first use and stays loaded. That has two consequences you
should design for.

**`onLoad` runs once**, before the first `execute`. It is the right place for
anything expensive that can be reused: building a Retrofit instance, opening a
database, reading a cache.

```kotlin
override suspend fun onLoad(context: Context) {
    database = openDatabase(context)
}

override suspend fun onUnload() {
    database.close()
}
```

**The same instance is reused** for every subsequent run. Fields survive between
runs, which is useful for caches and a bug waiting to happen for anything that
represents "this particular run". Keep per-run state local to `execute`.

`onUnload` is called when the user unloads the plugin, when it is uninstalled,
and when a newer version is imported over it. Failures there are logged, not
propagated.

## Results

```kotlin
sealed class PluginResult {
    abstract val message: String
    abstract val durationMs: Long
    abstract val data: Map<String, String>
    abstract val status: Status

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
}
```

It is deliberately richer than a boolean because the host renders it, logs it,
notifies with it and stores it — all without knowing what your plugin does.

**`message`** is the one sentence a person reads. Write it for them: *"Compressed
412 photos, saved 1.2 GB"*, not *"OK"*.

**`data`** is free-form structured detail, shown under the message and in the
log. Use it for the numbers behind the sentence.

**`errorCode`** is a stable identifier for a failure mode — `NETWORK_ERROR`,
`TIMEOUT`, `NO_SPACE`. It is what someone greps a log for.

```kotlin
return PluginResult.Failure(
    message = "The server is not reachable from this network",
    errorCode = "NETWORK_ERROR",
    data = mapOf("host" to host, "attempted" to attempts.toString()),
    cause = exception,
)
```

There is a shorthand for the common case:

```kotlin
catch (error: IOException) {
    return PluginResult.of(error)      // message, errorCode and cause from the exception
}
```

## When a plugin throws

Nothing about the host is affected. The runtime catches every `Throwable` around
`execute` and converts it:

```text
Plugin FAILED: java.lang.IllegalStateException: no encoder (after 214 ms)
```

The resulting `Failure` carries the exception type, its message, the first
frames of the stack, the plugin id and the duration. The host stays up, and the
plugin can be run again immediately.

::: tip Prefer returning a Failure
Throwing works and is safe, but the host has to guess: the error code becomes
the exception's class name and the message becomes whatever the exception says,
which is often written for a developer rather than a user. Catch what you expect
and return a `Failure` with words you chose.
:::

Failures the runtime itself produces, before your code runs, use their own
codes: `NOT_INSTALLED`, `LOAD_ERROR`, `PERMISSION_DENIED_PERMANENTLY`,
`PERMISSION_NOT_DECLARED_BY_HOST`.

## Long-running work

The host starts a foreground service for the whole execution. Without one,
Android freezes a process as soon as the screen goes off, and a long transfer
simply stops until the phone is woken.

For anything that takes more than a few seconds, report progress —
[Logging and progress](logging-and-progress.md) covers it:

```kotlin
ZetaProgress.report(id, current = index, total = files.size, message = file.name)
```

Progress reaches the notification and the card, which is what makes a long run
feel like it is working rather than hung.

### Cancellation

`execute` is a suspend function and the host cancels the coroutine when the user
presses Stop. Cooperative cancellation applies: use suspending calls, or check
periodically.

```kotlin
for (file in files) {
    coroutineContext.ensureActive()     // throws CancellationException when stopped
    compress(file)
}
```

Do not catch `CancellationException` and carry on — it is how the host stops you.

### Being resumable

A long plugin should assume it can be interrupted: the user stops it, the phone
reboots, the battery dies. Two habits make that survivable:

* **Keep a ledger.** Record what has already been done, in your own file under
  `context.filesDir`, and skip it on the next run.
* **Write to a temporary file and rename.** A rename is atomic; a half-written
  file that has replaced a good one is not recoverable.

## Being run by a schedule

A scheduled run is identical from your side: same `execute`, same `input`, same
result. There is no flag telling you a person did not press the button, and that
is intentional — a plugin that behaves differently when nobody is watching is
hard to trust and harder to debug.

What does change is the environment. A scheduled run may happen with the screen
off, on a metered connection, at 3 a.m. with the device in Doze. See
[Scheduling](scheduling.md).

## Next

[Settings →](settings.md)
