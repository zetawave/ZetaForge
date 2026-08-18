# ZetaForge

**A dynamic plugin runtime for Android.**

ZetaForge is a Host app that loads and runs Kotlin code it was never compiled
against. A plugin is written in its own Gradle module, built separately, shipped
as a single `.zeta` file, imported by the user, and executed inside the Host
with the Host's `Context` — using ordinary Android and JVM APIs plus its own
external libraries.

This repository is a working proof of concept of exactly that chain:

```
EXTERNAL KOTLIN CODE -> SEPARATE BUILD -> SINGLE .ZETA FILE -> IMPORT INTO HOST
   -> DYNAMIC DEX LOADING -> SAME HOST CONTEXT -> NORMAL ANDROID/JVM APIs
   -> EXTERNAL LIBRARIES (Retrofit + OkHttp) -> EXECUTION
```

The demo plugin performs a real HTTPS request with Retrofit and OkHttp, and
**neither library is a dependency of the Host APK** — they live in the plugin's
own DEX. A Gradle task verifies that on the built APK.

---

## Table of contents

1. [What ZetaForge is](#1-what-zetaforge-is)
2. [Architecture](#2-architecture)
3. [Toolchain](#3-toolchain)
4. [Getting started](#4-getting-started)
5. [Building the Host](#5-building-the-host)
6. [Building a plugin and producing a .zeta](#6-building-a-plugin-and-producing-a-zeta)
7. [Importing and running a plugin](#7-importing-and-running-a-plugin)
8. [How dynamic loading works](#8-how-dynamic-loading-works)
9. [How plugin dependencies work](#9-how-plugin-dependencies-work)
9b. [Permissions](#9b-permissions)
10. [Proving Retrofit is not in the Host](#10-proving-retrofit-is-not-in-the-host)
11. [Writing your own plugin](#11-writing-your-own-plugin)
12. [Tests](#12-tests)
13. [Developer scripts](#13-developer-scripts)
14. [Dynamic Plugin Limitations](#14-dynamic-plugin-limitations)
15. [Security model](#15-security-model)
16. [Next steps](#16-next-steps)

---

## 1. What ZetaForge is

| Piece | Role |
|---|---|
| **Host** (`host/`) | The installed app. UI only: import, list, start, inspect, logs. |
| **SDK / plugin-api** (`plugin-api/`) | The versioned contract: `ZetaPlugin`, `PluginResult`, `PluginState`, `ZetaLog`. |
| **Runtime** (`runtime/`) | Import, validation, installation, class loading, lifecycle, execution, logging, error handling. |
| **Plugins** (`plugins/retrofit-demo/`, `plugins/files-demo/`) | Separately built Kotlin modules: one with external dependencies (Retrofit/OkHttp), one that needs a run-time permission (MediaStore). |
| **Builder** (`plugin-builder/`) | Gradle tooling that turns a built plugin into a `.zeta` archive. |

The Host has no idea what any plugin does. It knows `PluginPackage`,
`PluginManifest`, `PluginRuntime`, the plugin API and the lifecycle — nothing
else. There is no `if (pluginId == "retrofit-demo")` anywhere.

## 2. Architecture

```
                plugin-api
               /          \
           runtime         plugins/retrofit-demo   (compileOnly on plugin-api)
              |
            host
```

```
ZetaForge/
├── host/                  Android Host application (Compose UI)
├── plugin-api/            ZetaForge Plugin SDK / shared contract
├── runtime/               ZetaForge Plugin Runtime
├── plugins/
│   ├── retrofit-demo/     reference plugin: external libraries (template)
│   └── files-demo/        reference plugin: run-time permissions
├── plugin-builder/        included Gradle build: .zeta packaging tooling
├── docs/                  architecture notes
├── run.sh                 one-shot dev loop: build -> install -> import -> run
├── scripts/               doctor / build / install / run / logs / clean
├── gradle/                wrapper + version catalog
├── zetaforge.properties   single source of truth for names, packages, SDK levels
├── zetaforge.permissions  the permission ceiling, injected into the Host manifest
└── settings.gradle.kts
```

Full detail, including the class-loading rules and the package format, is in
[docs/architecture.md](docs/architecture.md).

Product identity lives in one file, `zetaforge.properties`:

```properties
zetaforge.host.package=com.zetaforge.app
zetaforge.sdk.package=com.zetaforge.sdk
zetaforge.runtime.package=com.zetaforge.runtime
zetaforge.hostApiVersion=1
zetaforge.compileSdk=35
zetaforge.minSdk=26
```

## 3. Toolchain

Chosen against what is actually installed on the development machine, not
against the newest releases available:

| Component | Version | Why |
|---|---|---|
| JDK | Temurin 21 (runs Gradle), language level 17 | AGP 8.9 supports 17–21; 17 is the safe bytecode target |
| Gradle | 8.13 | already in the local wrapper cache; required by AGP 8.9 |
| Android Gradle Plugin | 8.9.2 | stable, compatible with Gradle 8.13 and JDK 21 |
| Kotlin | 2.1.20 | matches AGP 8.9; ships the Compose compiler plugin |
| Compose BOM | 2025.04.01 | matches Kotlin 2.1.20 |
| compileSdk / targetSdk | 35 | `platforms/android-35` installed |
| minSdk | 26 | `DexClassLoader` + `InMemoryDexClassLoader` era; API 27+ gets `DelegateLastClassLoader` |
| Build tools | 35.0.0 (D8) | installed; D8 is invoked by AGP, never by hand |
| Retrofit / OkHttp | 2.11.0 / 4.12.0 | plugin-side only |

## 4. Getting started

**Android Studio is not required.** VS Code + a JDK + the Android SDK + the
Gradle wrapper + `adb` are enough for the entire daily workflow. Android Studio
is useful only for the SDK Manager, AVD Manager and occasional diagnostics.

```bash
git clone <this repo> && cd ZetaForge
./scripts/doctor          # verifies java, SDK, build-tools/D8, adb, devices
```

`local.properties` must point at your SDK (it is git-ignored):

```properties
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
```

If the SDK is missing pieces, install them from the CLI:

```bash
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

## 5. Building the Host

```bash
./gradlew :host:assembleDebug
adb install -r host/build/outputs/apk/debug/host-debug.apk
```

or:

```bash
./scripts/build-host
./scripts/install-host
```

`assembleDebug` is finalised by `verifyHostHasNoRetrofit` (see §10).

## 6. Building a plugin and producing a .zeta

```bash
./gradlew :plugins:retrofit-demo:buildZetaPlugin
```

Output:

```
plugins/retrofit-demo/build/zetaforge/retrofit-demo.zeta
plugins/retrofit-demo/build/zetaforge/retrofit-demo.zeta.sha256
```

The task prints exactly what it produced:

```
ZetaForge plugin packaged
  artifact         : .../retrofit-demo.zeta
  size             : 1022388 bytes
  sha256           : a995c0cb8b156bb820b35669e8a080fe7ccff8a22e1775a5c3d6ec13714cfbc8
  pluginId         : com.zetaforge.plugins.retrofitdemo
  entryPoint       : com.zetaforge.plugins.retrofitdemo.RetrofitDemoPlugin (found in classes.dex)
  dex files        : classes.dex (1021140 B, dex 038)
  retrofit2 in dex : true
  okhttp3 in dex   : true
```

Archive layout:

```
retrofit-demo.zeta
├── manifest.json
├── dex/classes.dex
├── source/…               the plugin's own .kt files, shown by VIEW CODE
├── libs/.keep
└── metadata/build.json
```

`manifest.json` (produced, not hand-written):

```json
{
  "formatVersion": 2,
  "pluginId": "com.zetaforge.plugins.retrofitdemo",
  "author": "ZetaForge Team <plugins@example.com>",
  "license": "Apache-2.0",
  "name": "Retrofit Demo",
  "version": "0.1.0",
  "entryPoint": "com.zetaforge.plugins.retrofitdemo.RetrofitDemoPlugin",
  "minHostApi": 1,
  "maxHostApi": 1,
  "minSdk": 26,
  "permissions": [
    { "name": "android.permission.INTERNET", "reason": "Sends the demo HTTPS request", "optional": false, "minSdk": 1 }
  ],
  "specialAccess": [],
  "capabilities": ["network.http"],
  "dependencies": {
    "bundled": ["com.squareup.okhttp3:okhttp:4.12.0", "com.squareup.okio:okio:3.6.0", "com.squareup.retrofit2:retrofit:2.11.0"],
    "hostProvided": ["com.zetaforge:plugin-api:1", "org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains.kotlinx:kotlinx-coroutines-core"]
  },
  "code": {
    "dex": [{ "path": "dex/classes.dex", "size": 1021376, "sha256": "…", "dexVersion": "038" }],
    "source": [{ "path": "source/src/main/kotlin/.../RetrofitDemoPlugin.kt", "language": "kotlin", "size": 6100 }]
  },
  "signature": null
}
```

The `.zeta` is the only file you move around. No separate `classes.dex`, no
`retrofit.jar`, no loose `manifest.json`.

## 7. Importing and running a plugin

```bash
./scripts/install-plugin     # adb push to /sdcard/Download
```

Then on the device:

1. open **ZetaForge**
2. tap **IMPORT PLUGIN**
3. pick `retrofit-demo.zeta`
4. the log shows: `Import started → Validation started → Manifest valid → DEX found → Checksum calculated → Installing → Installed`
5. tap **START**

Expected log:

```
13:41:02.118 INFO  Runtime     Runtime ready (Host API 1, device API 35)
13:41:09.552 INFO  Installer   Manifest valid: com.zetaforge.plugins.retrofitdemo v0.1.0
13:41:09.556 INFO  Installer   DEX found: dex/classes.dex
13:41:09.601 INFO  Installer   Checksum calculated: sha256=a995c0cb…
13:41:09.640 INFO  Installer   Installed in com.zetaforge.plugins.retrofitdemo
13:41:11.004 INFO  Runtime     Class loader: DELEGATE_LAST over code.jar (1021340 bytes)
13:41:11.180 INFO  Runtime     Entry point instantiated: …RetrofitDemoPlugin
13:41:11.181 INFO  Runtime     Plugin loaded
13:41:11.182 INFO  Runtime     START
13:41:11.190 INFO  RetrofitDemo Context: packageName=com.zetaforge.app
13:41:11.203 INFO  RetrofitDemo OkHttp 4.12.0 client built
13:41:11.219 INFO  RetrofitDemo Retrofit initialized (baseUrl=https://postman-echo.com/)
13:41:11.220 INFO  RetrofitDemo HTTP request started
13:41:12.004 INFO  RetrofitDemo HTTP 200 (784 ms, 312 chars)
13:41:12.006 INFO  RetrofitDemo SUCCESS
13:41:12.010 INFO  Runtime     SUCCESS - HTTP 200 in 784 ms (312 chars)
```

**DETAILS** shows the manifest, the verification checks, the last result payload
and two failure scenarios (unreachable host, deliberate exception) that
demonstrate the Host surviving a broken plugin.

You can also open a `.zeta` straight from a file manager: the Host registers an
`ACTION_VIEW` intent filter for `content://…/*.zeta`.

## 8. How dynamic loading works

1. The imported archive is copied to a **staging file in the app cache** — never
   executed from where the user picked it.
2. ZIP, manifest, DEX magic and per-DEX SHA-256 are validated; the SHA-256 of the
   whole archive is computed and recorded.
3. `BasicPluginVerifier` checks structure, manifest, entry point, Host API range,
   `minSdk`, checksum and permissions.
4. The archive is installed under `files/zetaforge/plugins/<pluginId>/`, and its
   DEX is repacked into `extracted/code.jar` (APK-shaped: `classes.dex`,
   `classes2.dex`, …), which is what `BaseDexClassLoader` expects.
5. A **per-plugin class loader** is created:
   * API 27+: `DelegateLastClassLoader` — bootclasspath, then the plugin's DEX,
     then the Host. The plugin's own libraries win over the Host's.
   * API 26: `DexClassLoader` (parent-first) as fallback.
6. `SharedContract.conflicts()` verifies that `ZetaPlugin`, `PluginResult` and
   `kotlin.coroutines.Continuation` resolve to the *same* class objects on both
   sides, failing loudly if a plugin ever bundled the contract.
7. The entry point is loaded by name, checked against `ZetaPlugin`, instantiated
   through its no-arg constructor, and `onLoad` is called.
8. `execute(context, input)` runs on `Dispatchers.IO`, never on the main thread.
   Every `Throwable` is caught and converted into a `PluginResult.Failure`.

## 9. How plugin dependencies work

The plugin module is a real Android module with its own build file:

```kotlin
dependencies {
    // Provided by the Host at runtime - must NOT enter the plugin DEX
    compileOnly(project(":plugin-api"))
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.kotlinx.coroutines.core)

    // The plugin's own dependencies - these DO enter the plugin DEX
    implementation(libs.retrofit) { exclude(group = "org.jetbrains.kotlin") }
    implementation(libs.okhttp)   { exclude(group = "org.jetbrains.kotlin") }
}
```

Two rules make the boundary work:

* **`compileOnly` for shared types.** The contract, the Kotlin stdlib and
  coroutines must exist as exactly one class object across the boundary,
  otherwise the Host cannot cast the instance it just created (and a
  `suspend fun` could not even be invoked: `Continuation` would differ).
* **`implementation` for everything else.** Retrofit, OkHttp and Okio are
  compiled into the plugin's DEX by AGP/D8 and are invisible to the Host.

Measured on the produced artifact: `classes.dex` defines **548 classes** —
OkHttp, Okio, Retrofit and the plugin itself. `com.zetaforge.sdk.*`, `kotlin.*`
and `kotlinx.coroutines.*` appear only as references, never as definitions.

## 9b. Permissions

### The one rule Android imposes

An app's permissions are frozen at install time from the manifest of the
installed APK. A permission that is not in the Host APK **can never be granted
at run time**: `requestPermissions()` returns denied without showing a dialog,
and even `adb shell pm grant` refuses it. Dynamically loaded code has no manifest
of its own as far as the OS is concerned.

ZetaForge therefore splits the problem in two:

| Question | Where it is answered |
|---|---|
| What *may* ever be asked? | `zetaforge.permissions` - the Host's ceiling, build time |
| What *is* asked, and when? | the plugin's own `.zeta` manifest, at every START |

**Declaring is not granting.** Everything in the ceiling stays ungranted until a
plugin declares it and the user accepts the dialog. Over-declaring costs a line
in the app's settings page, nothing else.

### Declaring the ceiling (once)

`zetaforge.permissions` is a plain list; the build injects it into the Host's
merged manifest, so no XML is ever edited by hand:

```
android.permission.READ_MEDIA_IMAGES
android.permission.READ_EXTERNAL_STORAGE maxSdkVersion=32
android.permission.CAMERA
...
```

It already covers files/media, camera, microphone, location, Bluetooth,
contacts, calendar, notifications, alarms and the special accesses. Adding one
means editing that file and running `./run.sh` - a normal app update.

### Declaring what a plugin needs

In the plugin's `build.gradle.kts`, with the reason the user will read:

```kotlin
permission("android.permission.READ_MEDIA_IMAGES") {
    reason = "Counts the images in your library to build the report"
    minSdk = 33
}
permission("android.permission.READ_EXTERNAL_STORAGE") {
    reason = "Counts the images in your library to build the report"
    maxSdk = 32
}
permission("android.permission.ACCESS_MEDIA_LOCATION") {
    reason = "Reads the location stored inside photos, when available"
    optional = true      // the plugin runs anyway if it is denied
}
specialAccess("allFilesAccess") { reason = "Writes the archive anywhere you choose" }
```

### What happens at START

```
execute()
  -> PermissionInspector: what does this plugin need, on THIS device, right now?
  -> not applicable on this API level?      ignore
  -> already granted?                       proceed
  -> not in the Host manifest?              PERMISSION_NOT_DECLARED_BY_HOST + how to fix
  -> requestable?    rationale dialog (plugin name + reason) -> system dialog
  -> denied for good? PERMISSION_PERMANENTLY_DENIED + "open settings"
  -> special access?  explanation -> the exact Settings screen -> re-check on return
  -> re-inspect, then run or fail with a structured result
```

Nothing is cached: permissions can be revoked from Settings, and Android
auto-revokes them for unused apps, so the check runs on **every** execution.

Three families are handled distinctly, because Android treats them differently:
install-time permissions (granted with the app), run-time/dangerous permissions
(dialog, revocable, "don't ask again"), and special access (all files access,
overlay, exact alarms, usage access, notification access, battery, install
packages, write settings) which is only reachable through a dedicated Settings
screen.

### What you see in the app

The plugin card lists the permissions the package declares before you ever run
it; `DETAILS` shows each one with its reason and live state (granted, will be
requested, denied permanently, not needed on this Android version); `VIEW CODE`
shows the plugin's own sources, shipped inside the `.zeta`, so a permission
request can be checked against the code that will use it.

## 10. Proving Retrofit is not in the Host

Automated, and wired into the Host build:

```bash
./gradlew :host:assembleDebug        # runs verifyHostHasNoRetrofit afterwards
```

```
ZetaForge Host APK verification
apk      : host/build/outputs/apk/debug/host-debug.apk
forbidden: Lretrofit2/, Lokhttp3/, Lokio/
result   : PASS - no plugin-only library found in the Host APK
```

The report is written to
`host/build/reports/zetaforge/host-apk-verification.txt`, and the build fails if
any of those markers ever appears in the Host DEX.

The instrumented test asserts the same thing from the other side: the Host class
loader cannot load `retrofit2.Retrofit`, while the plugin runs it happily.

## 11. Writing your own plugin

1. Create `plugins/<your-plugin>/` with a `com.android.application` module that
   also applies `com.zetaforge.zeta-plugin`.
2. Declare `compileOnly(project(":plugin-api"))`, `compileOnly(libs.kotlin.stdlib)`,
   `compileOnly(libs.kotlinx.coroutines.core)` and whatever `implementation`
   dependencies you need.
3. Implement the contract:

```kotlin
class MyPlugin : ZetaPlugin {
    override val id = "com.example.myplugin"
    override val name = "My Plugin"
    override val version = "0.1.0"

    override suspend fun execute(context: Context, input: Bundle): PluginResult {
        // normal Kotlin, normal Android Context, normal libraries
        val files = context.filesDir.listFiles()?.size ?: 0
        return PluginResult.Success("Found $files files")
    }
}
```

4. Describe it:

```kotlin
zetaPlugin {
    pluginId.set("com.example.myplugin")
    displayName.set("My Plugin")
    entryPoint.set("com.example.myplugin.MyPlugin")
    permissions.set(listOf("android.permission.INTERNET"))
}
```

5. `./gradlew :plugins:<your-plugin>:buildZetaPlugin`, then import the `.zeta`.

Add the module to `settings.gradle.kts`. Nothing in the Host changes.

## 12. Tests

| Layer | Command | Covers |
|---|---|---|
| Manifest + permissions rules (27 tests) | `./gradlew :runtime:test` | valid manifest, malformed JSON, missing/invalid entry point, invalid version, future format, bad API range |
| Package + verifier | same | valid `.zeta`, missing `classes.dex`, invalid ZIP, missing manifest, checksum mismatch, bad DEX magic, incompatible Host API, pinned-checksum mismatch |
| Builder | `./gradlew :plugins:retrofit-demo:buildZetaPlugin` | builds the plugin, produces the `.zeta`, fails if `manifest.json`, `classes.dex` or the entry point class are missing (the DEX is parsed, not trusted) |
| Integration (17 tests) | `./gradlew :host:connectedDebugAndroidTest` | the full chain on a device: import, DEX loading, Retrofit call, error containment, the permission gate (granted / denied / not declared by the Host) and both translations |

The integration test (`host/src/androidTest/.../ZetaForgeAcceptanceTest.kt`) is
the important one:

```
build plugin -> .zeta -> Host imports it -> runtime loads the DEX
   -> entry point instantiated -> Host Context passed
   -> Retrofit executes -> HTTP 200 -> SUCCESS
```

plus: repeated execution, a plugin that throws (Host survives and still works), a
network failure reported as `FAILED`, log-content assertions, and rejection of a
garbage package.

## 13. Developer scripts

### The fast loop: `run.sh`

By default it builds, installs and **launches** the app - nothing is imported or
executed, so you drive the UI yourself:

```bash
./run.sh                      # build plugin + Host, install, launch
./run.sh --import             # ... and import retrofit-demo.zeta
./run.sh --run                # ... and import it, then execute the plugin
./run.sh --scenario throw         # failure path: the plugin throws (implies --run)
./run.sh --scenario unreachable   # failure path: unreachable endpoint
./run.sh --logs               # follow the log stream at the end
./run.sh --host-only          # skip the plugin build (fast UI loop)
./run.sh --plugin-only        # skip the Host build (rebuild the .zeta only)
./run.sh --fresh              # wipe app data first
./run.sh --test               # also run unit + instrumented acceptance tests
./run.sh --clean              # gradle clean first
./run.sh -s emulator-5554     # target a specific device
./run.sh --help
```

The device is autodetected (a single attached device, or the running emulator);
with several devices connected it stops and asks for `-s`. The app is always
restarted, and the log printed at the end is filtered to that process.
`run.bat` is the Windows wrapper.

`--import` / `--run` go through two **debug-only** intents handled by
`MainActivity` (`com.zetaforge.app.action.IMPORT_FILE` / `.RUN_PLUGIN`), guarded
by `BuildConfig.DEBUG`: the archive is streamed into the app's own cache with
`run-as` and imported through the exact same runtime pipeline as a SAF pick. In
a release build those actions do nothing.

Typical output:

```
==> Device: emulator-5554 (sdk_gphone64_x86_64, API 34)
==> Building: :plugins:retrofit-demo:buildZetaPlugin :host:assembleDebug
[ ok ] com.zetaforge.app installed (17M)
==> Launching the app
==> Importing retrofit-demo.zeta (1000K)
==> Running com.zetaforge.plugins.retrofitdemo
==> ZetaForge log
    ... Class loader: DELEGATE_LAST over code.jar (415561 bytes)
    ... HTTP 200 (639 ms, 187 chars)
==> Result: SUCCESS - HTTP 200 in 639 ms (187 chars) (758 ms)
[ ok ] done in 20s
```

### Release build and signing

Create the signing key once (Git Bash):

```bash
./scripts/make-keystore                       # asks for the password
# or fully non-interactive:
./scripts/make-keystore --alias zetaforge --password '<your-password>' --cn 'Your Name'
```

It writes `keystore/zetaforge-release.jks` (PKCS12, RSA 4096, 30 years) and
`keystore.properties`; both are git-ignored, and the script verifies that before
finishing. Back the keystore up outside the repository: without it you can never
update an already published build.

```properties
# keystore.properties - read by host/build.gradle.kts, never committed
storeFile=keystore/zetaforge-release.jks
storePassword=...
keyAlias=zetaforge
keyPassword=...
```

Then:

```bash
./gradlew :host:assembleRelease
# -> host/build/outputs/apk/release/host-release.apk   (~1.9 MB, signed v2+v3)
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs -v     host/build/outputs/apk/release/host-release.apk
```

Without `keystore.properties` the release build still runs and produces an
unsigned APK, so CI and contributors are never blocked.

**R8 and the plugin ABI.** A plugin host cannot be shrunk like a normal app:
plugins declare the Kotlin stdlib and coroutines as `compileOnly` and expect the
Host to provide them, but R8 only sees what the *Host* uses. Minifying without
care produced a real failure (`ClassNotFoundException: kotlin.Unit` when running
the plugin from a release build), so [host/proguard-rules.pro](host/proguard-rules.pro)
keeps the whole shared runtime (`kotlin.**`, `kotlinx.coroutines.**`,
`com.zetaforge.sdk.**` and every `ZetaPlugin` implementation). Cost: ~0.5 MB of
APK. Verified: with those rules the minified release build imports and runs the
plugin exactly like the debug build.

### Single-purpose scripts

```bash
./scripts/doctor           # environment report: java, SDK, build-tools/D8, adb, devices
./scripts/build-host       # :host:assembleDebug (+ APK verification)
./scripts/install-host     # adb install -r
./scripts/build-plugin     # :plugins:retrofit-demo:buildZetaPlugin
./scripts/install-plugin   # adb push the .zeta to /sdcard/Download
./scripts/run-plugin       # connected acceptance test (build -> import -> run)
./scripts/logs             # adb logcat filtered on ZetaForge / RetrofitDemo
./scripts/make-keystore    # generate the release signing key + keystore.properties
./scripts/clean            # remove all build output
```

Each script also has a `.bat` wrapper that runs it through Git Bash.

## 14. Dynamic Plugin Limitations

What this PoC **demonstrably** does:

| Capability | Status |
|---|---|
| Dynamic DEX loading from a user-imported file | **works** — `DelegateLastClassLoader` over an APK-shaped `code.jar` |
| Kotlin plugin compiled entirely outside the Host | **works** |
| Plugin using the Host `Context` (`packageName`, `filesDir`, `contentResolver`) | **works**, asserted by the test |
| Plugin using its own JVM/Android libraries (Retrofit, OkHttp, Okio) | **works**, and they are absent from the Host APK |
| Real HTTPS request from plugin code | **works** |
| Background execution, structured results, structured logs | **works** |
| Error containment (plugin throws / network fails) | **works** — Host survives, plugin reports `FAILED` |
| Repeated execution, unload, uninstall, re-import | **works** |
| Manifest + package validation, SHA-256 checksums, Host API range | **works** |
| Run-time permissions requested per plugin, with reasons | **works** |
| Special access (all files, overlay, alarms, ...) routed to Settings | **works** |
| Reading the plugin's own source inside the app (`VIEW CODE`) | **works** - sources travel inside the `.zeta` |
| English + Italian UI | **works** |

What this PoC **does not** do (by design, in scope for later):

| Area | Reality today |
|---|---|
| **Permissions** | Implemented: plugins declare permissions with a reason, the runtime evaluates them on every run and requests what is missing (run-time dialogs *and* special-access Settings screens). The hard limit stays: a permission absent from the Host APK can never be granted, so `zetaforge.permissions` is the ceiling. Grants are process-wide, not per plugin. |
| **Android resources** | Plugins ship code only. `R`-based resources, layouts, drawables and string resources from the plugin APK are not merged into the Host and are not accessible. Use `assets/` in the package for data. |
| **Manifest components** | `Activity`, `Service`, `BroadcastReceiver` and `ContentProvider` declared by a plugin are ignored: the Host manifest is fixed at install time and Android cannot register components dynamically. |
| **AndroidX in plugins** | Plain JVM/Android libraries work. AndroidX libraries that rely on resources, `ContentProvider` initialisers (`androidx.startup`) or manifest merging will not work unmodified. |
| **Native libraries (`.so`)** | Not supported. `libs/` exists in the package format and the class loader already accepts a native library path, but nothing extracts or validates `.so` yet. |
| **Process isolation** | None. Plugin code runs in the Host process with the Host UID. This is a trust boundary, not a sandbox. |
| **Digital signature** | Packages are unsigned. `manifest.signature` is always `null` and `PluginVerifier` is the seam where `SignaturePluginVerifier` will plug in. |
| **WorkManager / scheduling** | Out of scope; `PluginState` models the lifecycle a scheduler will need. |
| **Hot reload, auto-update, marketplace** | Out of scope. |
| **Version conflicts** | `DelegateLastClassLoader` gives the plugin's own libraries priority, which is the mechanism that makes divergent versions possible. Exhaustive conflict handling (e.g. shared singletons, static state across loaders) is not solved. |

Not claimed: *"any Android code will work"*. Code that only needs classes,
Android framework APIs and JVM libraries works. Code that needs resources,
manifest components or native libraries does not, yet.

## 15. Security model

Dynamically loaded code runs **inside the Host process, with the Host's UID and
the Host's permissions**. It can do everything the Host can do. Therefore:

* installing a `.zeta` is as consequential as installing an app — only import
  packages you trust;
* the runtime never executes code from the user-selected location: the archive
  is copied into app-private storage first, and only that copy is loaded;
* every import computes and stores a SHA-256 of the archive, and the per-DEX
  hashes recorded in the manifest are re-checked;
* signature verification is **not** implemented yet; the extension point
  (`PluginVerifier` / `CompositePluginVerifier` / `manifest.signature`) is in
  place for it.

## 16. Next steps

1. `SignaturePluginVerifier` + a signing step in `buildZetaPlugin`.
2. Persisted logs and run history (`ZetaLogger.persister` seam).
3. Capability-based Host APIs (versioned, declared in the manifest) instead of a
   bare `Context`.
4. Scheduling and background execution driven by `PluginState`.
5. Optional isolated process for untrusted plugins.
6. Native library support via `libs/`.
7. Plugin assets and (later) a resource story.
