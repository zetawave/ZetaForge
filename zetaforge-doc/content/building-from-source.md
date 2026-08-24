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
adb install -r app/host/build/outputs/apk/debug/host-debug.apk
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

Changing a value there propagates to every module. The release script refuses to
publish if the npm major version and `hostApiVersion` disagree.

## Releasing

```bash
npm run release:dry        # everything except commit, tag and publish
npm run release:patch      # 4.0.0 → 4.0.1
npm run release:minor      # 4.0.0 → 4.1.0
npm run release:major      # 4.0.0 → 5.0.0 — a new Host API
```

The script refuses to start on a dirty tree, on the wrong branch, or without
working npm credentials — because the worst release is one that fails after the
tag has been pushed. It bumps every file carrying a version, builds, tests,
inspects the npm tarball, commits, tags, pushes, publishes and creates the
GitHub release with the APK attached.

## Next

[Licensing →](licensing.md)
