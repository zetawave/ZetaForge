---
title: Building from source
description: Build the host app, the runtime, the contract and the CLI, and run every test suite.
---

# Building from source

This page is for working on ZetaForge itself. To write a *plugin*, you need none
of it — see [Installation](installation.md).

## The repository

```text
ZetaForge/
├── app/                  the Android side
│   ├── plugin-api/       the contract (com.zetaforge.sdk)
│   ├── runtime/          loading, verification, lifecycle, execution
│   ├── host/             the app: Compose UI and the screen container
│   ├── plugin-builder/   the Gradle plugin that produces .zeta
│   └── plugins/          reference plugins
├── zeta-cli/             the npm package
├── zetaforge-doc/        this documentation site
└── scripts/              build, release and end-to-end scripts
```

## Prerequisites

| | |
|---|---|
| Node.js | 18.17+ |
| JDK | 17+ (21 recommended) |
| Android SDK | platform 35, build-tools, platform-tools |

```bash
git clone https://github.com/zetawave/ZetaForge.git
cd ZetaForge
npm install
npm run doctor
```

`local.properties` in `app/` should point at your SDK, or `ANDROID_HOME` should
be set:

```properties title="app/local.properties"
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
```

## Building

```bash
npm run build                 # the contract jar, then the host APK
npm run build:contract        # just the contract
npm run build:host            # just the app (debug)
npm run build:host:release    # release, signed if keystore.properties exists
npm run build:plugins         # every reference plugin
```

Everything goes through `scripts/gradle.mjs`, so you never have to be in `app/`
or remember the wrapper's name on your platform. Directly, if you prefer:

```bash
node scripts/gradle.mjs :host:assembleDebug
```

### Installing what you built

```bash
adb install -r app/host/build/outputs/apk/debug/host-universal-debug.apk
```

`assembleDebug` is finalised by a verification task that fails the build if
Retrofit, OkHttp or Okio ever appear in the host APK — they belong to a plugin,
and their absence from the host is the proof that bundling works.

## Reference plugins

```bash
node scripts/gradle.mjs :plugins:retrofit-demo:buildZetaPlugin
node scripts/gradle.mjs :plugins:files-demo:buildZetaPlugin
node scripts/gradle.mjs :plugins:calculator:buildZetaPlugin
```

| | |
|---|---|
| `retrofit-demo` | External dependencies and a real HTTPS call |
| `files-demo` | The run-time permission path, end to end |
| `calculator` | A [screen](screens.md): Compose provided by the host |

Each produces `build/zetaforge/<name>.zeta`.

Anything you put in `app/plugins-local/` is picked up automatically and is
git-ignored, which is where personal plugins belong.

## Tests

```bash
npm test                   # CLI and runtime, in parallel
npm run test:cli           # the CLI
npm run test:unit          # runtime unit tests
npm run test:device        # instrumented tests, needs a device
npm run test:e2e           # the whole CLI chain against a device
```

| Layer | Covers |
|---|---|
| Runtime unit tests | Manifest parsing, package reading, verification, permission rules |
| CLI tests | Descriptor validation, packaging, reproducibility |
| Instrumented | The full chain on a device: import, DEX loading, a real HTTP call, error containment, the permission gate, both translations |

The instrumented test is the interesting one:

```text
build plugin → .zeta → the app imports it → the runtime loads the DEX
   → entry point instantiated → the host Context passed in
   → a real request made → a structured result returned
   → the plugin deliberately crashes → the host survives → it runs again
```

## The contract jar

```bash
npm run build:contract
```

Produces `app/build/zetaforge/sdk/zetaforge-api-<hostApi>.jar` — the single
artifact shared between host and plugins. It ships inside the npm package, which
is why the CLI and the app it targets cannot drift apart.

```bash
npm run sync:assets     # copy it and the Gradle wrapper into zeta-cli/assets
```

## Working on the CLI

```bash
npm run cli -- doctor
npm run cli -- new /tmp/demo
```

Runs `zeta-cli/bin/zeta.js` from the repository, so changes take effect without
installing anything. Or link it globally:

```bash
npm link --prefix zeta-cli
```

## Working on the documentation

```bash
cd zetaforge-doc
npm run dev
```

Builds the site and serves it at <http://localhost:4173/ZetaForge/>, rebuilding
on every save to `content/`, `theme/` or `lib/`. There are no dependencies to
install — the whole site builder is a few hundred lines of Node.

```bash
npm run build            # build into dist/ for GitHub Pages
npm run build:local      # build with base /, for serving the folder directly
```

## Identity and versions

Everything version-shaped lives in one file:

```properties title="app/zetaforge.properties"
zetaforge.hostApiVersion=4
zetaforge.uiApiVersion=1
zetaforge.manifestFormatVersion=4
zetaforge.compileSdk=35
zetaforge.minSdk=26
zetaforge.versionName=4.0.0
```

Changing a value there propagates to every module. `versionName` and
`versionCode` are written by the Host release script; you do not edit them by
hand. Both release scripts refuse to publish if their major and
`hostApiVersion` disagree.

## Releasing

The app and the CLI ship separately, so a fix to one is not a release of the
other. Each has its own command, its own tag and its own version:

```bash
npm run release:host:patch    # the Android app  -> tag host-v4.0.1
npm run release:cli:patch     # the zeta CLI     -> tag cli-v4.2.1, npm publish
npm run release:docs          # this site
```

`:minor` and `:major` exist for both, and `:dry` does everything except tagging
and publishing. Pass the bump as an argument instead if you prefer:
`npm run release:host -- --bump minor`.

The two versions drift apart on purpose. What they must always share is the
**major**, because the major *is* the Host API version: a plugin built by CLI
4.x runs on Host 4.x and on nothing else. Both scripts refuse to release a major
that does not equal `zetaforge.hostApiVersion`, which you raise by hand — and
only when the contract in `app/plugin-api` really changed.

Both refuse to start on a dirty tree, on the wrong branch, or without the
credentials they will need at the end, because the worst release is one that
fails after the tag has been pushed.

### What a Host release publishes

```
zetaforge-host-<v>-universal.apk     signed release build, every ABI
zetaforge-host-<v>-<abi>.apk         signed release build, one architecture
zetaforge-host-<v>-debug.apk         debuggable build, for the CLI
SHA256SUMS.txt
```

Take the universal one unless you know your device's architecture. The debug
build is published because `zeta install` hands packages to the app through
`run-as`, which Android allows only for a debuggable build — it is what
`zeta host install` downloads.

The APKs are built and uploaded from the releaser's machine rather than from CI,
because they are signed with a keystore that never leaves it.

## Next

[Licensing →](licensing.md)
