# Dependencies

## Declaring

```toml
[dependencies]
retrofit = "com.squareup.retrofit2:retrofit:2.11.0"
okhttp   = "com.squareup.okhttp3:okhttp:4.12.0"
exif     = "androidx.exifinterface:exifinterface:1.3.7"
```

The name on the left is yours — it only labels the line. The coordinate is
`group:artifact:version`, resolved from Maven Central and Google's repository.
Transitive dependencies come along automatically.

Both plain jars and Android `.aar` libraries work: the CLI unwraps the `.aar`
and takes the classes out of it.

## What ends up where

Two categories, and the difference is the whole design:

**Bundled — compiled into the plugin's own DEX.** Everything in
`[dependencies]`. The Host does not have these and does not need them. Two
plugins can use different versions of the same library without either knowing.

**Host-provided — compiled against, never bundled.** The ZetaForge contract, the
Kotlin standard library, and coroutines. The CLI wires these up for you; you
never declare them.

## Why those three must be shared

The Host holds a reference to your plugin as a `ZetaPlugin`. For that cast to
work, the `ZetaPlugin` your code was compiled against and the one the Host knows
have to be the *same class object* — same bytes, same loader chain. If the
package carried its own copy, they would be two unrelated types with the same
name, and loading would fail with a `ClassCastException` that explains nothing.

The same is true of `kotlin.Unit`, and of `Continuation`: a `suspend fun` cannot
even be called across a boundary where coroutines are duplicated.

`zeta build` parses the DEX before packaging and refuses to ship a plugin that
contains `com.zetaforge.sdk.*`, `kotlin.*` or `kotlinx.coroutines.*`. The error
names the offending class.

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

* prefer a focused library over a framework — `converter-scalars` rather than
  the whole of Jackson if all you parse is a string;
* check what you actually shipped: `zeta inspect dist/x.zeta --classes` lists
  every class in the DEX, and it is usually a surprise;
* remember the platform is free. `HttpURLConnection`, `JSONObject`,
  `MediaCodec`, `ExifInterface` and the rest of the framework cost zero bytes,
  because they come from Android.

For reference: an empty plugin is about 5 KB. Retrofit + OkHttp + a converter is
about 420 KB.

## Offline builds

The first build of a project downloads its dependencies; after that they are
cached, and `zeta build --offline` works with no network at all. Useful on a
locked-down machine, and a good way to prove your build is reproducible.
