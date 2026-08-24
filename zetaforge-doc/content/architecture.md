---
title: Architecture
description: The modules, the direction of their dependencies, and what happens between picking a file and running its code.
---

# Architecture

## Modules and the direction of dependencies

```text
                     plugin-api  (com.zetaforge.sdk)
                    /           \
                   /             \
            runtime               plugins/*
        (com.zetaforge.runtime)   (compiled separately, compileOnly on plugin-api)
                   |
                 host
          (com.zetaforge.app, UI only)
```

| Module | Type | Contains |
|---|---|---|
| `plugin-api` | Android library | The contract: `ZetaPlugin`, `PluginResult`, `PluginState`, `ZetaLog`, `ui.ZetaUiPlugin`. Depends on nothing — stdlib, coroutines and Compose are all `compileOnly`. |
| `runtime` | Android library | Manifest parsing, package reading, verification, installation, class loading, lifecycle, execution, logging. |
| `host` | Android app | Compose UI, the view model, and `PluginScreenActivity`. |
| `plugin-builder` | Included build | The Gradle plugin that packages a module into a `.zeta`, and the DEX reader that verifies it. |

Three rules the build enforces:

* a plugin never sees `com.zetaforge.app.*` or `com.zetaforge.runtime.*` — it
  compiles against `plugin-api` and nothing else;
* the host never references a concrete plugin: it knows ids, manifests and one
  interface;
* the shared types are `compileOnly` on the plugin side, so exactly one copy of
  each exists at run time.

## How a plugin is built

The plugin module is a normal `com.android.application` module, which is a
deliberate choice: it gives the real Android pipeline.

```text
Kotlin sources + external jars
   → kotlinc / javac
   → D8 (desugaring, min-api 26)
   → classes.dex
```

The resulting APK is never installed. `buildZetaPlugin` opens it, extracts
`classes*.dex`, checks the DEX header, verifies the entry point class is really
defined inside, verifies that **no boundary class is defined** in it, writes the
manifest with per-DEX SHA-256, and seals the archive.

The `zeta` CLI does the same thing along a shorter path — Kotlin/JVM → D8, with
no AGP, because a plugin has no resources and no manifest to merge.

## Import and installation

```text
user picks a file (SAF)
   → copy to cache/zetaforge/import-<n>.zeta        staging, untrusted
   → ZIP validation
   → manifest.json parsing + semantic validation
   → per-DEX magic + SHA-256 check
   → SHA-256 of the whole archive
   → BasicPluginVerifier
   → files/zetaforge/plugins/<pluginId>/current.zeta
   → files/zetaforge/plugins/<pluginId>/extracted/code.jar
   → install.json record
```

Code is only ever executed from app-private storage. The user-selected location
is read once and never used again.

## Execution

```text
Runtime.execute(pluginId, Bundle)
  → load (LOADING → LOADED)      class loader, entry point, onLoad
  → permissions evaluated and requested
  → STARTING → RUNNING           on Dispatchers.IO, never the main thread
  → plugin.execute(context, input)
  → SUCCESS | FAILED             every Throwable is caught and converted
```

A plugin exception becomes a `PluginResult.Failure` carrying the exception type,
message, first stack frames, plugin id and duration. The host process is never
affected — the acceptance test re-runs a plugin successfully immediately after a
deliberate crash.

[Class loading](class-loading.md) covers the loader itself in detail.

## Where each concern lives

**The one runtime, in the Application.** Before scheduling existed, the runtime
could live in the view model: the only way to run a plugin was to press START,
which always happened with the UI on screen. A scheduled run arrives as a
broadcast in a process that may have no Activity at all, so the runtime has to
outlive the UI — and there must be exactly one, or two copies would each hold
their own class loaders and their own idea of what is installed.

**Execution in a service.** `PluginExecutionService` owns execution rather than
merely accompanying it, for the same reason: a run has to be possible with
nothing on screen. It is also what keeps the process from being frozen when the
screen goes off.

**Process-wide singletons for what Android constructs.** `ZetaTaskCenter` (is
something running, how far along) and `ZetaUiSessions` (which screens are open)
are objects rather than fields, because the service and the Activity are created
by Android and cannot be handed a reference to anything.

## Scheduling

```text
AlarmManager  ──▶  ScheduleReceiver  ──▶  PluginExecutionService  ──▶  runtime.execute
                        │
                        └── conditions unmet? notify and re-arm instead
```

One alarm per plugin, always for the *next* run only: a repeating alarm cannot
express "every Tuesday and Friday, unless it already ran", and it survives an
edit badly. Schedules live beside the plugin on disk, so uninstalling takes the
schedule with it. The receiver re-arms everything on `BOOT_COMPLETED` and
`MY_PACKAGE_REPLACED`, because Android drops alarms on both.

[Scheduling →](scheduling.md)

## Screens

A plugin's `Activity` can never be started — Android resolves components from an
installed APK's manifest, frozen at install time. The host declares one
container Activity and the plugin supplies a composable.

```text
touch / key / measure / layout / draw   ──▶  PluginCrashGuard (a FrameLayout)   ─┐
composition / recomposition / effects   ──▶  the plugin's own Recomposer,        ├─▶ error screen
                                             with its own exception handler     ─┘   + unload
```

The plugin's composition deliberately does **not** share the window recomposer:
that one propagates exceptions, and propagating is what would kill the host.

The bar naming the plugin is a separate composition in a separate view above the
plugin's own, so a screen cannot repaint the host's identity.

[Screens →](screens.md)

## Extension points already in place

| Seam | Today | Next |
|---|---|---|
| `PluginVerifier` | `BasicPluginVerifier` | `SignaturePluginVerifier` chained via `CompositePluginVerifier` |
| `manifest.signature` | always `null` | certificate + signature over the DEX hashes |
| `ZetaLogger.persister` | in-memory ring buffer | file or database persistence |
| `capabilities[]` | recorded and displayed | capability-based host API surface |
| `libs/` in the package | reserved, empty | native `.so` support |
| `ZetaUiHost` | screen, settings, permissions, messages | a durable place for screen state |

## The trust boundary, in architectural terms

Dynamically loaded DEX runs **inside the host process, with the host UID and the
host permissions**. Consequences:

* a plugin can do anything the host can do — read `filesDir`, use the network,
  touch the `ContentResolver`;
* plugin permissions in the manifest are a *declaration*, not a grant: the
  runtime compares them with the host's and logs mismatches;
* installing a `.zeta` is exactly as trusted an act as installing an app.

Real isolation would need a separate process (`android:process=":isolated"`)
with an IPC contract. That is out of scope today, and it is why `PluginVerifier`
and the `signature` block exist already.

[Security model →](security.md)

## Next

[Class loading →](class-loading.md)
