---
title: Class loading
description: One loader per plugin, delegate-last lookup, and the shared-contract rule that makes the whole thing work.
---

# Class loading

This is the mechanism the rest of ZetaForge is built on. It is worth
understanding even if you only write plugins, because two of the errors you can
hit make no sense without it.

## One loader per plugin

Each plugin gets its own class loader. That buys two properties:

* **A plugin can be dropped as a unit.** Unloading means releasing the loader;
  its classes go with it.
* **Two plugins never see each other's classes.** They are unrelated worlds that
  happen to share a process.

## Which loader, and why

```kotlin
val loader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
    DelegateLastClassLoader(dexPath, nativeLibraryPath, parent)
} else {
    DexClassLoader(dexPath, optimizedDir, nativeLibraryPath, parent)
}
```

**`DexClassLoader` is parent-first.** Any class the host already has wins.
Simple, but it means a plugin can never use a different version of a library the
host also ships.

**`DelegateLastClassLoader` (API 27+) looks up bootclasspath → its own DEX →
parent.** That is exactly what a plugin system wants: the plugin's own bundled
dependencies win over anything the host happens to have.

So ZetaForge uses delegate-last where it exists and falls back to parent-first
on API 26. Which one was used is logged on every load:

```text
Class loader: DELEGATE_LAST over code.jar (1438208 bytes)
```

### What delegate-last buys

```text
Host        ──▶ (no Retrofit at all)
Plugin A    ──▶ Retrofit 2.9   ← its own copy wins
Plugin B    ──▶ Retrofit 2.11  ← so does its own
```

All three coexist in one process. This is the property that makes "bring your
own libraries" real rather than theoretical.

`code.jar` is an APK-shaped container — `classes.dex`, `classes2.dex`, … — which
is the layout `BaseDexClassLoader` is designed for, so multi-DEX plugins need no
special handling.

## The shared-contract rule

::: danger The one rule
Types that cross the boundary must resolve to **one class object** on both
sides.
:::

Those types are:

- `com.zetaforge.sdk.*` — the contract
- `kotlin.*` — the standard library
- `kotlinx.coroutines.*` — including `Continuation`
- `androidx.compose.*` — only for a plugin with a [screen](screens.md)

The host loads your plugin and casts it to `ZetaPlugin`. If the package carried
its own copy of `ZetaPlugin`, the cast would compare two unrelated types that
merely share a name, and fail with:

```text
ClassCastException: com.zetaforge.sdk.ZetaPlugin cannot be cast to
                    com.zetaforge.sdk.ZetaPlugin
```

which tells nobody anything. The same is true of `kotlin.Unit`, and of
`Continuation`: a `suspend fun` cannot even be called across a boundary where
coroutines are duplicated.

Under delegate-last the risk is real rather than theoretical — the plugin's own
DEX is searched *first*, so a bundled copy genuinely does win.

### How it is prevented, three times over

**1. At build time, structurally.** Those artifacts are declared `compileOnly`,
so they are never compiled into the plugin's DEX and delegate-last has nothing
local to find. This is the actual mechanism; the other two are safety nets.

**2. At build time, verified.** `buildZetaPlugin` and `zeta build` parse the DEX
they just produced and fail if it *defines* any boundary class. The check reads
the class-definition table, not the string table — a plugin references the
contract constantly and that is fine; what fails a build is compiling a copy in.

```text
This plugin bundles 3 class(es) the Host must own:
  androidx.compose.runtime.Composer, …
They belong to the shared boundary, so the Host and the plugin have to resolve
them to the same objects. Declare those dependencies as compileOnly.
```

**3. At load time.** `SharedContract.conflicts()` runs immediately after the
loader is created and compares a handful of canary classes as seen by both
loaders. A mismatch fails the load with an explicit message instead of an opaque
`ClassCastException` later.

## Entry point resolution

```kotlin
val clazz = loader.loadClass(manifest.entryPoint)
if (!ZetaPlugin::class.java.isAssignableFrom(clazz)) error(…)
val instance = clazz.getDeclaredConstructor().newInstance() as ZetaPlugin
instance.onLoad(appContext)
```

By name, from the manifest — which is why the entry point is checked against the
produced DEX at build time, with the closest match suggested when it is wrong.
A typo caught in a two-second build beats one caught on a phone.

If the instance reports an `id` that differs from the manifest's, that is logged
as a warning: it is almost always a copy-paste mistake, and it makes the logs
lie about which plugin did what.

## What is not solved

**Static state across loaders.** Two plugins each get their own copy of a
library, and therefore their own statics. That is usually what you want; it is
occasionally surprising when a library assumes it is a process-wide singleton.

**Host singletons touched by plugins.** Anything a plugin reaches through the
host's `Context` — shared preferences, a `ContentResolver`, a file — is genuinely
shared. Two plugins writing to the same place will collide, and nothing prevents
it.

**Native libraries.** The loader accepts a native library path and the package
format reserves `libs/`, but nothing extracts or validates `.so` files yet. A
dependency shipping native code will compile and fail at `System.loadLibrary`.

## Proving it, on a real artifact

The reference network plugin's DEX defines 548 classes — OkHttp, Okio, Retrofit
and the plugin itself. `com.zetaforge.sdk.*`, `kotlin.*` and
`kotlinx.coroutines.*` appear only as *references*, never as definitions.

You can check any package yourself:

```bash
zeta inspect dist/weather-1.0.0.zeta --classes
```

And on the host side, the release build is verified not to contain the plugin's
libraries: a Gradle task inspects the APK's DEX for `retrofit2/`, `okhttp3/`
and `okio/` and fails the build if any of them appears.

## Next

[Security model →](security.md)
