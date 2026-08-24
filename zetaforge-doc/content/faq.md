---
title: FAQ
description: Straight answers to the questions people ask before deciding whether to use ZetaForge.
---

# FAQ

## Is this actually running real Kotlin?

Yes. Your sources go through kotlinc and D8 — the same compilers an Android app
uses — and what ships is a DEX file, the same bytecode format the platform runs
everywhere else. There is no interpreter, no transpilation, and no subset of the
language you must stay inside.

## How is it different from a scripting plugin system?

Those give you a sandboxed language with a curated API surface: safe, and
limited to whatever someone thought to expose. ZetaForge gives you the whole
Android framework and no sandbox. The trade is capability for isolation, in that
direction, deliberately. See [the security model](security.md).

## Can a plugin really use its own libraries?

Yes, and it is the feature the design is built around. Declare a Maven
coordinate and it is compiled into your plugin's DEX. The host does not have it.
Two plugins can even use different versions of the same library, because each
gets its own class loader with delegate-last lookup. See
[class loading](class-loading.md).

## Is it sandboxed?

No. A plugin runs in the host process with the host's UID and permissions.
Installing a `.zeta` is as consequential as installing an app. This is stated
everywhere in these docs because it is the single most important thing to
understand. [Security model →](security.md)

## Can I put this on Google Play?

The host app, no. An app whose purpose is loading arbitrary code is against
policy, which is why it is distributed as an APK.

Your own plugins are not apps and are not distributed through a store at all —
they are files.

## Does it work without root?

Yes. Nothing here needs root, a custom ROM, or any system-level privilege. It is
an ordinary app using ordinary APIs: `DexClassLoader` has been part of Android
since API 1.

## What Android versions?

API 26 (Android 8.0) and up. On API 27+ the runtime uses
`DelegateLastClassLoader`, which is what lets a plugin's libraries win over the
host's; on API 26 it falls back to parent-first, so divergent library versions
do not work there.

## Can a plugin have a user interface?

Yes, with Compose supplied by the host. A plugin implements `ZetaUiPlugin` and
returns a composable, which the host draws inside a container Activity of its
own — because an `Activity` living in a plugin's DEX can never be started.
[Screens →](screens.md)

## Why can't a plugin ship an Activity?

Android resolves components from the manifest of an *installed APK*, frozen at
install time. The system has never heard of your plugin's Activity, so
`startActivity` has nothing to resolve. There is no flag for this; it is how
component registration works.

## Why no resources?

Merging a plugin's `resources.arsc` into a running app means reflection over
`AssetManager` internals, and a package format that carries a compiled resource
table. It was judged not worth it — especially since Compose needs no resources,
which makes screens work without any of that machinery.

Ship data in `assets/` and read it from the package.

## Can plugins talk to each other?

Not directly. Separate class loaders means a plugin cannot load another's
classes. They do share the host's UID, so they can communicate through files or
shared preferences under the host's storage — but nothing coordinates that, and
nothing prevents collisions.

## What happens if a plugin crashes?

The host catches it. Every `Throwable` around `execute` becomes a structured
failure carrying the exception type, message, first stack frames and duration.
The host stays up and the plugin can be run again immediately.

A [screen](screens.md) is contained by two separate mechanisms, because its code
is re-entered by the framework from several directions.

## Can a plugin run in the background?

Yes. Attach a schedule from the plugin card: nightly, every N minutes, chosen
weekdays, optionally only while charging or on Wi-Fi. Your code is identical
either way. The realistic limits — battery optimisation, 15-minute granularity —
are covered in [Scheduling](scheduling.md).

## How big is a plugin?

The reference network plugin, with Retrofit, OkHttp, Okio and Gson compiled in,
is about 1.5 MB. A calculator using only the framework and the host's Compose is
64 KB. A plugin with no dependencies is tens of kilobytes.

## Are plugins signed?

Not yet. `manifest.signature` is always `null` and the verifier reports it as a
warning on every import. The seam for signing exists — `PluginVerifier`,
`CompositePluginVerifier`, and the manifest block are all in place — but trust
today comes from provenance alone.

## Can I use Java instead of Kotlin?

The toolchain compiles Kotlin. Java sources in a plugin project are not part of
the generated build, so in practice: Kotlin. The contract itself is plain JVM
bytecode, so a Java implementation is possible in principle, and untested.

## Does it need Android Studio?

No. The CLI generates and drives the whole build. Android Studio is a convenient
way to get the SDK and an emulator, and nothing more.

## Can I use it in CI?

Yes. `zeta test` needs only Node and a JDK; `zeta build` also needs
`android.jar`. See [Testing](testing.md#in-ci).

## Why is the npm package called `zetaforge-cli`?

The plain `zetaforge` name on npm belongs to an unrelated project published in
2023. The command is still `zeta`.

## Is this production ready?

Depends what for. The mechanism is solid and tested: class loading, verification,
permissions, scheduling, error containment and screens all have coverage, and
the acceptance tests run the whole chain on a device.

What it is not is a platform for untrusted third-party code — there is no
isolation and no signing. Within the assumption that the plugin author and the
device owner trust each other, it does what it says.

## How do I contribute?

[Contributing →](contributing.md)

## Next

[Contributing →](contributing.md)
