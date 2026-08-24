---
title: Dependencies
description: Use any JVM or Android library. What gets bundled, what is shared, and why the difference matters.
---

# Dependencies

## Declaring

```toml
[dependencies]
retrofit = "com.squareup.retrofit2:retrofit:2.11.0"
gson     = "com.squareup.retrofit2:converter-gson:2.11.0"
okhttp   = "com.squareup.okhttp3:okhttp:4.12.0"
exif     = "androidx.exifinterface:exifinterface:1.3.7"
```

The name on the left is yours — it only labels the line. The coordinate is
`group:artifact:version`, resolved from Maven Central and Google's repository.
Transitive dependencies come along automatically.

Both plain jars and Android `.aar` libraries work: the CLI unwraps the `.aar`
and takes the classes out of it.

## Two categories, and the whole design

**Bundled — compiled into the plugin's own DEX.** Everything in
`[dependencies]`. The host does not have these and does not need them.

**Host-provided — compiled against, never bundled.** The ZetaForge contract, the
Kotlin standard library, coroutines, and Compose if the plugin has a
[screen](screens.md). The CLI wires these up; you never declare them.

The first category is what makes ZetaForge worth using. Your plugin genuinely
carries its own Retrofit, and:

```text
Host        ──▶ (no Retrofit at all)
Plugin A    ──▶ Retrofit 2.9
Plugin B    ──▶ Retrofit 2.11
```

All three coexist. Each plugin gets its own class loader, and a delegate-last
lookup order means a plugin's own copy of a library wins over anything the host
happens to ship. [Class loading](class-loading.md) explains the mechanism and
its limits.

## Why the shared four must be shared

The host holds your plugin as a `ZetaPlugin`. For that cast to work, the
`ZetaPlugin` your code was compiled against and the one the host knows have to
be the *same class object* — same bytes, same loader chain. Two classes with the
same name from different loaders are unrelated types, and the failure is a
`ClassCastException` saying that `ZetaPlugin` cannot be cast to `ZetaPlugin`,
which explains nothing to anybody.

The same is true of `kotlin.Unit`, and of `Continuation`: a `suspend fun` cannot
be called across a boundary where coroutines are duplicated.

`zeta build` parses the DEX before packaging and refuses to ship a plugin that
*defines* anything under `com.zetaforge.sdk`, `kotlin.`, `kotlinx.coroutines.`
or `androidx.compose.`. The error names the offending classes and the fix.

::: note Defined, not referenced
The check looks at the DEX's class-definition table, not its string table. A
plugin *mentions* `ZetaPlugin` constantly and that is fine — what fails a build
is compiling a copy of it in.
:::

## When a library drags Kotlin in

Kotlin-based libraries — OkHttp, Okio, many AndroidX artifacts — depend on the
standard library, and would pull a second copy into your DEX. The CLI excludes
the `org.jetbrains.kotlin` group from every declared dependency by default.

If you ever need the opposite:

```toml
[dependencies]
something = { module = "com.example:thing:1.0", excludeKotlin = false }
```

You almost certainly do not.

## Keeping the package small

Package size is import time and disk on someone's phone. A few habits:

* **Prefer a focused library over a framework.** `converter-scalars` rather than
  the whole of Jackson, if all you parse is a string.
* **Check what you actually shipped.** `zeta inspect dist/x.zeta --classes`
  lists every class in the DEX, and it is usually a surprise.
* **Remember the platform is free.** `HttpURLConnection`, `JSONObject`,
  `MediaCodec`, `ExifInterface`, `DocumentFile` and the rest of the framework
  cost zero bytes, because they are already on the device.

For scale: the reference network plugin, with Retrofit, OkHttp, Okio and Gson
compiled in, is about 1.5 MB. The calculator, which uses only the framework and
the host's Compose, is 64 KB.

## What will not work

**Libraries that need resources.** Anything expecting `R`, a merged manifest, or
Android resource resolution. A plugin has none of those. This rules out most UI
libraries — but not Compose, which is provided by the host precisely because it
needs no resources.

**Libraries with `ContentProvider` initialisers.** `androidx.startup` and
everything built on it registers a provider in the manifest, and a plugin's
manifest is never read. The library will load and then behave as though it was
never initialised.

**Native code.** A dependency shipping `.so` files will compile, and fail at run
time when it tries to `System.loadLibrary`. The `libs/` directory exists in the
package format for this and nothing extracts it yet.

## Offline builds

```bash
zeta build --offline
```

Uses only what is already in the Gradle cache. Useful on a plane, and useful in
CI when you want a build to fail rather than silently pick up a new transitive
version.

## Next

[Scheduling →](scheduling.md)
