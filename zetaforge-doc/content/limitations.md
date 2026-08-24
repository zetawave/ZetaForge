---
title: Limitations
description: What ZetaForge demonstrably does, what it does not, and what it does not claim. Read this before you commit to it.
---

# Limitations

This page exists to be read *before* you build something on ZetaForge, not
after. Every row is either measured or a known gap.

## What works

| | |
|---|---|
| Dynamic DEX loading from a user-imported file | `DelegateLastClassLoader` over an APK-shaped `code.jar` |
| Kotlin plugins compiled entirely outside the host | Real kotlinc and D8, no interpreter |
| A plugin using the host `Context` | `packageName`, `filesDir`, `contentResolver`, `getSystemService` |
| A plugin using its own libraries | Retrofit, OkHttp, Okio — and they are verified absent from the host APK |
| Real HTTPS from plugin code | |
| Background execution, structured results, structured logs | |
| Error containment when a plugin throws or a network fails | The host survives; the plugin reports `FAILED` |
| A screen that throws, in a click handler or during recomposition | Error report on screen, plugin unloaded, host process untouched |
| Repeated execution, unload, uninstall, re-import | |
| Manifest and package validation, SHA-256, Host API range | |
| Run-time permissions per plugin, with reasons | |
| Special access routed to the exact Settings screen | |
| Scheduling, conditions, re-arming after reboot | |
| Reading a plugin's own source inside the app | Sources travel inside the `.zeta` |
| Plugins that are a screen, drawn with the host's Compose | |
| English and Italian UI | |

## What it does not do

### No Android resources

Plugins ship code only. No `R` class, no layouts, no drawables, no string
resources — none of it is merged into the host, and none of it is reachable.

Ship data in `assets/`. For an *interface*, write a [screen](screens.md):
Compose needs no resources at all, which is exactly why it is the way in.

### No manifest components

An `Activity`, `Service`, `BroadcastReceiver` or `ContentProvider` declared by a
plugin is ignored. Android registers components from the manifest of an
installed APK, frozen at install time, and a plugin has no manifest the system
reads.

A plugin that needs a screen supplies a composable; the host's own container
Activity is the component.

### The host's permission set is the ceiling

A permission absent from the host APK can never be granted, no matter what a
plugin's manifest says — Android refuses it without showing anything. The host
declares a broad superset, and extending it means rebuilding the host.

Grants are also process-wide, not per plugin: once the user grants camera access
for one plugin, every plugin has it.

### AndroidX that needs resources or startup providers

Plain JVM and Android libraries work. Libraries relying on resource merging, or
on `androidx.startup` `ContentProvider` initialisers, will not work unmodified —
they load, and then behave as though they were never initialised.

### No native libraries

`libs/` exists in the package format and the class loader accepts a native
library path, but nothing extracts or validates `.so` files yet. A dependency
shipping native code compiles and fails at `System.loadLibrary`.

### No process isolation

Plugin code runs in the host process with the host UID. This is a trust
boundary, not a sandbox. See [the security model](security.md).

### No signatures

Packages are unsigned. `manifest.signature` is always `null`, and the verifier
reports it as a warning on every import. Trust in a package comes from where you
got it.

### Screen state does not survive process death

A screen cannot use `rememberSaveable` for its own types: saved instance state
is restored with the host's class loader, which cannot see them. Rotation is
handled; anything more durable the plugin must persist itself.

### Scheduling is not a real-time trigger

Granularity is 15 minutes, delivery is through `AlarmManager`, and with battery
optimisation on a run can slip by hours. Anything that must react the moment
something happens is outside what this can do.

### Version conflicts are possible but not fully solved

Delegate-last gives a plugin's own libraries priority, which is the mechanism
that makes divergent versions possible. Exhaustive handling — shared singletons,
static state across loaders, a library that assumes it is process-wide — is not
solved.

### No hot reload, auto-update or marketplace

`zeta dev` reinstalls on save, which is close in practice, but the plugin is
genuinely reloaded rather than patched. There is no update channel and no
directory of plugins.

## What is not claimed

::: warning "Any Android code will work" is not true
Code that needs classes, Android framework APIs and JVM libraries works. Code
that needs resources, manifest components or native libraries does not, yet.
:::

## When ZetaForge is the wrong tool

Being explicit about this saves everyone time.

**You are shipping to end users through a store.** Play does not permit an app
whose purpose is loading arbitrary code. ZetaForge is distributed as an APK for
exactly that reason.

**You need untrusted third-party plugins.** There is no isolation. A plugin
marketplace on this foundation would be a marketplace of things with your app's
permissions.

**You need a UI framework other than Compose.** Views built from code work;
anything wanting XML layouts or resources does not.

**You need it to react instantly to system events.** No manifest receivers, and
scheduling is coarse.

## When it is the right one

**Personal automation with real code.** Backups, media processing, watchers,
reports — written in Kotlin, with real libraries, scheduled, on your own device.

**Internal tooling.** A single app whose behaviour is extended by packages your
own team builds and distributes, without an app-store round trip for each
change.

**Anything where the plugin author and the device owner are the same person, or
trust each other.** That is the assumption the whole design rests on, and where
it holds, the lack of isolation costs nothing.

## Next

[Publishing a plugin →](publishing.md)
