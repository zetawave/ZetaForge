---
title: Plugin anatomy
description: The shape of a plugin project, the contract you implement, and what a plugin can and cannot do.
---

# Plugin anatomy

## The project

```text
weather/
├── zetaplugin.toml        identity, permissions, settings, dependencies
├── src/                   Kotlin
├── test/                  JVM tests, no device needed
├── assets/                optional data shipped inside the package
└── dist/weather-1.0.0.zeta
```

No Android module, no `AndroidManifest.xml`, no resources, no Gradle file to
maintain. `zeta build` generates a build into a throwaway `.zeta/` directory,
compiles, and deletes nothing you wrote.

The descriptor is the whole configuration; see
[zetaplugin.toml](manifest-reference.md) for every field.

## The contract

```kotlin
interface ZetaPlugin {
    /** Must match `id` in the descriptor. */
    val id: String

    /** Human readable name, shown on the card. */
    val name: String

    /** Your plugin's version, informational. */
    val version: String

    suspend fun execute(context: Context, input: Bundle): PluginResult

    suspend fun onLoad(context: Context) {}
    suspend fun onUnload() {}

    suspend fun settings(context: Context, current: Bundle): ZetaSettingsSpec? = null
    suspend fun runAction(context: Context, actionKey: String, current: Bundle): ZetaActionResult
}
```

Three requirements the runtime relies on:

1. **A public no-argument constructor.** The runtime instantiates the class
   reflectively, by the name in the manifest.
2. **Safe to execute more than once.** The same instance is reused across runs
   until it is unloaded. Do not keep single-use state in fields.
3. **Do not assume you own the process.** Throwing is fine and contained;
   `System.exit` and killing threads are not.

## `execute`

```kotlin
override suspend fun execute(context: Context, input: Bundle): PluginResult {
    val quality = input.getInt("quality", 82)

    val processed = withContext(Dispatchers.IO) {
        // your work
    }

    return PluginResult.Success(
        message = "Compressed $processed photos",
        data = mapOf("saved" to "412 MB", "skipped" to "3"),
    )
}
```

`context` is the host's own Android `Context`, not a wrapper. Files, network,
`MediaStore`, `MediaCodec`, `getSystemService` — everything an app can reach.

`input` carries every setting you declared, typed as declared, with saved values
merged over your defaults. Pass a fallback anyway: it makes the function
testable without a host.

The call is made on a background dispatcher and is never on the main thread.
Your own threading is still yours to manage.

## What you can do

Anything an Android app can. The host lends you its whole capability surface —
that is the point of the trust boundary, and the reason to be careful about what
you install.

Any JVM or Android library, compiled into your own DEX. See
[dependencies](dependencies.md).

Work that runs for hours: the host holds a foreground service for the duration
of a run, so the process is not frozen when the screen goes off.

## What you cannot do

| | |
|---|---|
| **Resources** | No `R` class, no layouts, no XML, no string resources. Ship data in `assets/`. For an interface, write a [screen](screens.md) — Compose needs no resources, which is exactly why it is the way in. |
| **Manifest components** | An `Activity`, `Service`, `BroadcastReceiver` or `ContentProvider` declared by a plugin is ignored. Android resolves components from an installed APK's manifest, frozen at install time. |
| **Native `.so` libraries** | The format reserves `libs/` for them; nothing loads them yet. |
| **Permissions the host does not declare** | Android refuses them silently — no dialog, no error. See [permissions](permissions.md). |
| **AndroidX that needs resources** | Libraries relying on resource merging or `androidx.startup` `ContentProvider` initialisers will not work unmodified. |

## The boundary

Four things must be **shared** between host and plugin rather than duplicated:

- the ZetaForge contract (`com.zetaforge.sdk.*`)
- the Kotlin standard library
- coroutines
- Compose — only for a plugin that has a screen

If a plugin carried its own copy of any of them, the host could not even cast it
to `ZetaPlugin`, because the two `ZetaPlugin` classes would be different types.

`zeta build` refuses to package a plugin that does, and it checks by reading the
**class definitions** in the DEX it just produced rather than trusting the
descriptor. [Class loading](class-loading.md) explains the mechanism.

::: note You get this for free
The generated build already declares all four as `compileOnly`. You only run
into it by adding one of them to `[dependencies]` by hand.
:::

## A worked example

```kotlin title="src/com/example/weather/WeatherPlugin.kt"
package com.example.weather

import android.content.Context
import android.os.Bundle
import com.zetaforge.sdk.PluginResult
import com.zetaforge.sdk.ZetaLog
import com.zetaforge.sdk.ZetaPlugin
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class WeatherPlugin : ZetaPlugin {

    override val id = "com.example.weather"
    override val name = "Weather"
    override val version = "1.0.0"

    // Built once, in onLoad, rather than on every run: the instance is reused.
    private lateinit var api: WeatherApi

    override suspend fun onLoad(context: Context) {
        api = Retrofit.Builder()
            .baseUrl("https://api.example.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WeatherApi::class.java)
    }

    override suspend fun execute(context: Context, input: Bundle): PluginResult {
        val city = input.getString("city") ?: "Rome"
        ZetaLog.info(id, "Weather", "Fetching the forecast for $city")

        return try {
            val forecast = api.forecast(city)
            PluginResult.Success(
                message = "${forecast.temperature}° and ${forecast.summary} in $city",
                data = mapOf(
                    "city" to city,
                    "temperature" to forecast.temperature.toString(),
                ),
            )
        } catch (error: Exception) {
            // Returning a Failure rather than throwing lets you choose the
            // error code and the sentence the user reads.
            PluginResult.Failure(
                message = "Could not reach the weather service",
                errorCode = "NETWORK_ERROR",
                cause = error,
            )
        }
    }
}
```

## Next

[Lifecycle and results →](lifecycle.md)
