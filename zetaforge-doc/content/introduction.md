---
title: Introduction
description: ZetaForge loads real Kotlin plugins into a running Android app — their own libraries, their own permissions, their own screens.
---

# ZetaForge

**ZetaForge is an Android app that runs code it was never compiled against.**

You write an ordinary Kotlin class, compile it on your own machine, and ship it
as a single `.zeta` file. The app loads it at run time, hands it the Android
framework, and gets out of the way. No app store, no rebuild of the host, no
review queue.

```kotlin title="WeatherPlugin.kt"
class WeatherPlugin : ZetaPlugin {
    override val id = "com.example.weather"
    override val name = "Weather"
    override val version = "1.0.0"

    override suspend fun execute(context: Context, input: Bundle): PluginResult {
        val city = input.getString("city") ?: "Rome"     // from the settings form
        val degrees = api.forecast(city).temperature      // your own Retrofit
        return PluginResult.Success("It is $degrees° in $city")
    }
}
```

That is a complete plugin. It has its own Retrofit — a copy the host app does
not contain and has never seen — and it runs inside the host process with the
host's permissions.

::: cards
- [Install](installation.md) — Get the CLI and the app onto your machine and your phone.
- [Quick start](quick-start.md) — From nothing to a plugin running on a device, in about five minutes.
- [Core concepts](core-concepts.md) — The host, the runtime, the package, and the trust boundary.
- [Architecture](architecture.md) — How dynamic loading actually works, and what it costs.
:::

## What a plugin can be

A plugin is not one shape. It is three, and the same package can be more than
one of them.

| Shape | What it is | |
|---|---|---|
| **A tool** | Something you run. Press START, it works, it reports a result. | [Lifecycle](lifecycle.md) |
| **A service** | Something that runs on its own — every night, only on Wi-Fi, only while charging. | [Scheduling](scheduling.md) |
| **A screen** | Something you *open*: a real interface, drawn by the plugin, rendered by the host. | [Screens](screens.md) |

## What makes it different

Android has had "plugin" systems before. Most of them are one of two things: a
scripting language pretending to be an app, or a WebView with a bridge. This is
neither.

**It is real Kotlin, compiled by the real toolchain.** Your sources go through
kotlinc and D8, exactly as an app's would. What ships is a DEX file — the same
bytecode format Android runs everywhere else. There is no interpreter, no
transpilation, and no subset of the language you have to stay inside.

**It brings its own libraries.** A plugin can depend on Retrofit, OkHttp, Okio,
or anything else on Maven Central, and those libraries are compiled into the
plugin's own DEX. The host app does not contain them. Two plugins can even use
*different versions of the same library* without either one breaking, because
each gets its own class loader. See [class loading](class-loading.md).

**It asks for its own permissions, with its own reasons.** A plugin declares
what it needs and why, in words a person can read, and the runtime asks for them
at the moment they are needed — every time, because Android can revoke them
between two runs. See [permissions](permissions.md).

**It ships its own source.** Every `.zeta` carries the Kotlin that produced it,
and the app has a **VIEW CODE** button. You can read what a plugin does before
you let it do it.

## What it is honest about

::: warning The trust boundary
A plugin runs **inside the host process, with the host's UID and the host's
permissions**. There is no sandbox between them. This is what makes the system
useful — a plugin is as capable as the app itself — and it is exactly why
importing a `.zeta` is as consequential as installing an app.

Install what you trust. Read the source; it travels inside the package.
:::

That is a deliberate design decision, not an oversight, and
[the security model](security.md) explains what it does and does not protect
against. Real isolation would need a separate process and an IPC contract; the
seams for it exist and are named.

## Where to go next

If you want to *use* it, start with [installation](installation.md) and then the
[quick start](quick-start.md).

If you want to understand it before you install anything, read
[core concepts](core-concepts.md) and then [architecture](architecture.md).

If you are evaluating whether it fits your problem, the most useful page is
probably [limitations](limitations.md), which is written to be read before you
commit rather than after.
