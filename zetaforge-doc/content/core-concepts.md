---
title: Core concepts
description: The host, the runtime, the SDK contract, the .zeta package, and the trust boundary between them.
---

# Core concepts

Five things, and the relationships between them. Everything else in these docs
is detail hanging off this page.

## The four pieces

| | What it is | Where it lives |
|---|---|---|
| **The Host** | The installed Android app. Imports, lists, runs and inspects plugins. It contains no plugin-specific code whatsoever. | `app/host` |
| **The runtime** | The part that reads packages, verifies them, loads their DEX, manages lifecycle and contains failures. | `app/runtime` |
| **The SDK** | The versioned contract both sides compile against: `ZetaPlugin`, `PluginResult`, `ZetaLog`, `ZetaUiPlugin`. | `app/plugin-api` |
| **The CLI** | `zeta`. Scaffolds, compiles, packages, installs, runs, tests. | `zeta-cli` |

The important property is what the host *does not* know. It never references a
concrete plugin: it knows ids, manifests, and one interface. A plugin, likewise,
compiles against the SDK and nothing else — it cannot see the host's classes or
the runtime's.

## The `.zeta` package

A plugin ships as one file: a ZIP with a fixed layout.

```text
weather-1.0.0.zeta
├── manifest.json          versioned metadata: id, version, permissions, settings, ui
├── dex/classes.dex        real DEX, produced by D8, stored uncompressed
├── source/                the Kotlin that produced it
├── assets/                optional data files
├── libs/                  reserved for native libraries
└── metadata/build.json    build provenance
```

Two details in there are load-bearing.

**The DEX is real.** Not a script, not bytecode for a custom VM: the same format
Android runs for every installed app, produced by the same compiler. Nothing
interprets your plugin.

**The source travels with it.** The app has a `VIEW CODE` button that shows the
Kotlin inside the package the user is about to run. Given that there is no
sandbox, being able to read the code before running it is not a nicety.

[The .zeta format →](package-format.md)

## How a plugin runs

```text
user picks a file
  → copied into the app's private cache (staging, untrusted)
  → ZIP validated, manifest parsed, per-DEX SHA-256 checked
  → BasicPluginVerifier: structure, entry point, Host API range, minSdk, checksum
  → stored under files/zetaforge/plugins/<id>/
  ─────────────────────────────────────────────────────────────
  → DelegateLastClassLoader over the plugin's own code
  → entry point class found by name, instantiated, onLoad()
  → permissions evaluated and requested — every run, never cached
  → execute() on a background dispatcher
  → PluginResult.Success | PluginResult.Failure
```

Code is only ever executed from app-private storage. The file the user picked is
read once and never used again.

[Architecture →](architecture.md)

## The contract

A plugin implements one interface. This is the whole required surface:

```kotlin
interface ZetaPlugin {
    val id: String
    val name: String
    val version: String

    suspend fun execute(context: Context, input: Bundle): PluginResult

    suspend fun onLoad(context: Context) {}
    suspend fun onUnload() {}
}
```

Everything else — settings, actions, screens — is optional and additive. A
plugin written against Host API 1 still runs today.

The `Context` is the host's own. The `Bundle` carries the plugin's settings,
already merged with its defaults. The `PluginResult` is structured, because the
host renders it, logs it and notifies with it without knowing what your plugin
does.

[Plugin anatomy →](plugin-anatomy.md) · [SDK reference →](sdk-reference.md)

## The three shapes a plugin can take

The same package can be more than one of these.

**A tool.** Implements `execute`. The user presses START, work happens, a result
comes back. This is the base case and everything else builds on it.

**A service.** The same `execute`, but the user attaches a schedule: every night,
every 15 minutes, on chosen weekdays — optionally only while charging, only on
Wi-Fi, only above 20% battery. The plugin does not know or care whether a person
or an alarm started it. [Scheduling →](scheduling.md)

**A screen.** Implements `ZetaUiPlugin` and returns a composable, which the host
draws inside a container Activity of its own. This is how a plugin becomes a
small app rather than a job. [Screens →](screens.md)

## The trust boundary

This is the concept people most often get wrong about ZetaForge, so it is worth
stating without hedging.

::: danger There is no sandbox
A loaded plugin runs **in the host's process, with the host's UID and the host's
permissions**. It can read the host's private files, use its network access, and
touch its `ContentResolver`. It can do anything the app can do.
:::

That is a design decision, and it buys the thing that makes ZetaForge worth
using: a plugin is as capable as a real app, because it *is* running as one.

What it means in practice:

* Installing a `.zeta` is as consequential as installing an APK. Treat it that
  way.
* Permissions declared in a plugin's manifest are a *declaration*, not a grant.
  They tell the runtime what to ask the user for; they do not confer anything.
* A permission the **host's** APK does not declare can never be granted to a
  plugin, no matter what the plugin's manifest says. Android simply refuses.
* The seams for real isolation — a separate process, a signature verifier — exist
  and are named in the code, but they are not implemented.

[Security model →](security.md)

## Two version numbers, and why

**Host API version** is the contract. A plugin declares `minHostApi` (a hard
requirement) and `maxHostApi` (what you tested; a newer host warns rather than
refuses). The major version of the CLI *is* the Host API version it builds for.

**Package format version** is the layout of the `.zeta` file. A package newer
than the host understands is refused outright, with a message naming both
numbers, so an old app never half-reads a new package.

There is a third, `ui.uiApi`, that applies only to plugins with a screen. It
moves with Compose rather than with the SDK, for reasons [versioning](versioning.md)
explains.

## What the host guarantees

Worth knowing before you write anything, because these are the assumptions your
plugin is allowed to make:

1. **`execute` never runs on the main thread.** It is called on a background
   dispatcher. Your own threading is still yours to manage.
2. **Throwing is contained.** Any `Throwable` becomes a structured
   `PluginResult.Failure` carrying the exception type, message, first stack
   frames and duration. The host survives, and the plugin can be run again
   immediately.
3. **A run is never silent.** Started by hand or by an alarm, in the foreground
   or with the screen off, the user is told what happened.
4. **A long run stays alive.** The host holds a foreground service for the whole
   execution, so Android does not freeze the process when the screen goes off.
5. **Permissions are re-checked every time.** Never cached, because Android can
   revoke them between two runs and does so automatically for unused apps.
