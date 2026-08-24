# ZetaForge architecture

## 1. Modules and the direction of dependencies

```
                     plugin-api  (com.zetaforge.sdk)
                    /           \
                   /             \
            runtime               plugins/retrofit-demo
        (com.zetaforge.runtime)   (compiled separately, compileOnly on plugin-api)
                   |
                 host
          (com.zetaforge.app, UI only)
```

| Module | Type | Contains | Depends on |
|---|---|---|---|
| `plugin-api` | Android library | `ZetaPlugin`, `PluginResult`, `PluginState`, `ZetaLog`, `ZetaSdk.HOST_API_VERSION`, and `ui.ZetaUiPlugin` / `ui.ZetaUiHost` for plugins that are a screen | nothing (stdlib, coroutines *and Compose* are `compileOnly`) |
| `runtime` | Android library | manifest parsing, package reading, verification, installation, class loading, lifecycle, execution, logging | `plugin-api` |
| `host` | Android app | Compose UI + `HostViewModel`, plus `PluginScreenActivity`: the one container every plugin screen is drawn inside | `runtime` (and transitively `plugin-api`) |
| `plugins/retrofit-demo` | Android app (never installed) | reference plugin with external dependencies | `plugin-api` **compileOnly** |
| `plugins/files-demo` | Android app (never installed) | reference plugin needing a run-time permission | `plugin-api` **compileOnly** |
| `plugins/calculator` | Android app (never installed) | reference plugin that is a *screen*: a calculator, no permissions, no I/O | `plugin-api` **and Compose**, both **compileOnly** |
| `plugin-builder` | included Gradle build | `com.zetaforge.zeta-plugin` (packaging), `com.zetaforge.host-permissions` (manifest injection), `.zeta` writer, DEX reader | AGP API |

Rules enforced by the build:

* the plugin never sees `com.zetaforge.app.*` or `com.zetaforge.runtime.*` — it only
  compiles against `plugin-api`;
* the Host never references any concrete plugin: it only knows ids, manifests
  and the `ZetaPlugin` interface;
* `plugin-api`, Kotlin stdlib, coroutines **and Compose** are `compileOnly` on
  the plugin side, so exactly one copy of every boundary type exists at runtime.
  Compose joined that list when screens did, for the same reason as the rest: the
  Host builds the composition and the plugin adds to it.

## 2. The `.zeta` package

```
retrofit-demo.zeta                (ZIP)
├── manifest.json                 versioned metadata (formatVersion 1)
├── dex/classes.dex               real DEX produced by AGP/D8 (stored, uncompressed)
├── libs/                         reserved: native libraries, future artifacts
├── assets/                       optional plugin assets
└── metadata/build.json           build provenance
```

`manifest.json` fields: `formatVersion`, `pluginId`, `name`, `version`,
`description`, `author`, `homepage`, `license`, `entryPoint`, `minHostApi`,
`maxHostApi`, `minSdk`, `permissions[]`, `specialAccess[]`, `capabilities[]`,
`dependencies.bundled[]`, `dependencies.hostProvided[]`, `code.dex[]`
(path/size/sha256/dexVersion), `code.source[]`, `signature` (currently `null`),
`display`.

Format versions: **1** had plain-string permissions; **2** adds structured
permissions (`reason`, `optional`, `minSdk`, `maxSdk`), `specialAccess` and
bundled sources; **3** adds declared `settings`; **4** adds the `ui` block —
whether the plugin has a screen, which screen-contract version it was built
against, and whether the screen is all it is. Older packages still parse - the
runtime accepts every shape, which is the whole point of versioning the format.
The absence of `ui` is how every package built before screens existed says it
has none.

## 3. How the plugin is built

The plugin module is a normal `com.android.application` module. That is a
deliberate choice: it gives the real Android pipeline

```
Kotlin sources + external jars  ->  javac/kotlinc  ->  D8 (desugaring, min-api 26)  ->  classes.dex
```

The resulting APK is never installed. `buildZetaPlugin` opens it, extracts
`classes*.dex`, checks the DEX header, verifies the entry point class is really
defined inside, writes the manifest with per-DEX SHA-256 and seals the archive.

`compileOnly` is what keeps the shared contract out of that DEX. Verified on the
produced artifact: 548 classes are defined in `classes.dex` (OkHttp, Okio,
Retrofit and the plugin itself); `com.zetaforge.sdk.*`, `kotlin.*` and
`kotlinx.coroutines.*` appear only as *references*, never as definitions.

Since screens exist that is no longer a claim, it is a build step.
`BuildZetaPluginTask` parses the produced DEX's `class_defs` table — not its
string table, which legitimately mentions the boundary constantly — and fails the
build if the plugin *defines* anything under `com.zetaforge.sdk`, `kotlin.`,
`kotlinx.coroutines.` or `androidx.compose.`. The message names the offending
classes and the fix, because the alternative a developer would otherwise see is
`ClassCastException: androidx.compose.runtime.Composer cannot be cast to
androidx.compose.runtime.Composer`.

## 4. Import and installation

```
user picks a file (SAF)
   -> copy to cache/zetaforge/import-<n>.zeta        (staging, untrusted)
   -> ZIP validation
   -> manifest.json parsing + semantic validation
   -> per-DEX magic + SHA-256 check
   -> SHA-256 of the whole archive
   -> BasicPluginVerifier (structure, manifest, entryPoint, hostApi, minSdk,
      checksum, permissions, signature-absent warning)
   -> files/zetaforge/plugins/<pluginId>/current.zeta
   -> files/zetaforge/plugins/<pluginId>/extracted/code.jar
   -> install.json record
```

Code is only ever executed from app-private storage. The user-selected location
is read once and never used again.

## 5. Class loading

`PluginClassLoaderFactory` creates one loader per plugin:

* **API 27+**: `DelegateLastClassLoader(code.jar, null, hostClassLoader)` —
  bootclasspath first, then the plugin's own DEX, then the Host. The plugin's
  bundled libraries win over the Host's, which is what allows
  `Host -> library X` and `Plugin -> library Y` to coexist.
* **API 26**: `DexClassLoader` (parent-first) as a fallback.

`code.jar` is an APK-shaped container (`classes.dex`, `classes2.dex`, …), the
layout `BaseDexClassLoader` is designed for, so multi-DEX plugins work with no
extra handling.

`SharedContract.conflicts()` runs right after the loader is created: if the
plugin ever bundled `ZetaPlugin`, `PluginResult` or `kotlin.coroutines.Continuation`,
loading fails with an explicit message instead of an opaque `ClassCastException`.

## 6. Execution and error containment

```
Runtime.execute(pluginId, Bundle)
  -> load (LOADING -> LOADED)      class loader, entry point, onLoad
  -> STARTING -> RUNNING           on Dispatchers.IO, never on the main thread
  -> plugin.execute(hostContext, input)
  -> SUCCESS | FAILED              every Throwable is caught and converted
```

A plugin exception becomes a `PluginResult.Failure` carrying exception type,
message, first stack frames, plugin id and duration. The Host process is never
affected; the acceptance test re-runs the plugin successfully right after a
deliberate crash.

## 7. Trust boundary (not a sandbox)

Dynamically loaded DEX runs **inside the Host process, with the Host UID and the
Host permissions**. Consequences:

* a plugin can do anything the Host can do — read `filesDir`, use the network,
  touch the `ContentResolver`;
* plugin permissions in the manifest are a *declaration*, not a grant: the
  runtime compares them with the Host's and logs mismatches;
* installing a `.zeta` is exactly as trusted an act as installing an app.

Real isolation would need a separate process (`android:process=":isolated"`) with
an IPC contract, which is out of scope for the PoC but is why `PluginVerifier`
and the `signature` block exist already.

## 7b. Screens

A plugin can also *be* a screen, which is the third shape after "a job you run"
and "a job that runs itself".

### Why the plugin supplies content and not a component

Android resolves components through the manifest of an installed APK, frozen at
install time. A plugin's `Activity` is therefore unstartable, exactly as a
plugin's permission is ungrantable (§9) - the same wall, in a different place.
So the Host declares one container Activity and the plugin implements

```kotlin
@Composable fun Content(host: ZetaUiHost)
```

Compose is the reason this costs nothing structurally: it needs no resources, so
the `.zeta` format, the installer and the class loader are all unchanged. The
package that draws a screen is the package that ran a batch job.

### Where the failures go

`Runtime.execute` contains failures with a single `try` around a single suspend
call. A screen cannot: its code is re-entered by the framework from four
directions, and any of them unwinds into `ViewRootImpl` and kills the process.

```
touch / key / measure / layout / draw   ──▶  PluginCrashGuard (FrameLayout)   ─┐
composition / recomposition / effects   ──▶  the plugin's own Recomposer,      ├─▶ error screen
                                             with its own exception handler   ─┘   + unload
```

The plugin's composition deliberately does **not** share the window recomposer:
that one propagates, and propagating is what kills the Host. Nothing is repaired
in place — after an exception the slot table describes a tree that was never
finished — so the composition is torn down and the next open starts fresh.

### Where identity goes

The bar naming the plugin is a *separate composition in a separate view* above
the plugin's own. A screen runs with the Host's UID and permissions, so a
convincing fake of the Host's UI is the cheapest attack it has; the plugin owns
the subtitle and nothing else. It can still open a `Dialog` over the window,
which Android gives no way to prevent.

### Where the lifecycle goes

`ZetaUiSessions` is process-wide state, like `ZetaTaskCenter` and for the same
reason: the Activity is created by Android and cannot be handed a reference to
anything. A screen being open makes two previously free operations expensive:

* `unload` is refused — the composition holds objects of classes that loader owns;
* `uninstall` and re-import ask the screen to close and wait for it, bounded, so
  a stuck screen cannot hang an uninstall for ever.

### Two contracts, two version numbers

`ZetaSdk.UI_API_VERSION` is counted separately from `HOST_API_VERSION` because
the screen ABI is Compose's ABI: a Host that upgrades Compose can break an
already compiled screen without touching the SDK. The manifest records
`ui.uiApi`; a Host implementing an older screen contract refuses with a sentence
instead of a `NoSuchMethodError` on the first frame.

## 8. Extension points already in place

| Seam | Today | Next |
|---|---|---|
| `PluginVerifier` | `BasicPluginVerifier` | `SignaturePluginVerifier` chained via `CompositePluginVerifier` |
| `manifest.signature` | always `null` | certificate + signature over the DEX hashes |
| `ZetaLogger.persister` | in-memory ring buffer | file / Room persistence |
| `PluginState` | full lifecycle modelled | scheduler, background execution, updates |
| `ZetaUiHost` | screen, settings, permissions, messages | richer Host services for screens, and a durable place to keep screen state |
| `capabilities[]` | recorded and displayed; `ui` is added automatically when a screen is declared | capability-based Host API surface |
| `libs/` in the package | reserved, empty | native `.so` support |


## 9. Permissions

### The constraint

Android freezes an app's permissions at install time from the APK manifest.
Dynamically loaded code has no manifest of its own, so a plugin can only ever
use permissions the *Host* declares. This is not a design choice; there is no
API to add permissions to an installed app.

### The split

```
zetaforge.permissions      (build time)   the ceiling: what may EVER be asked
        |
        v  injected into the merged manifest by com.zetaforge.host-permissions
Host APK <uses-permission …>
        |
        v  compared, per run, against
plugin .zeta manifest      (run time)     what THIS plugin asks, and why
```

Declaring is not granting: nothing in the ceiling is held until a plugin asks
and the user accepts.

### The pipeline, on every execution

```
PermissionInspector      reads the Host manifest, the current grants, the API level
  -> PermissionRules     pure logic: GRANTED | REQUESTABLE | PERMANENTLY_DENIED
                         | NOT_DECLARED_BY_HOST | NOT_APPLICABLE  (+ specialAccess)
  -> PermissionPlan      what blocks, what is optional, what can be asked
  -> PermissionGateway   (Host, Activity-backed) rationale -> system dialog
                         or explanation -> the exact Settings screen
  -> re-inspect          the user may have changed anything meanwhile
  -> Allowed | Blocked(errorCode)
```

`PermissionRules` is deliberately pure so the whole decision table is unit
tested without a device; `PermissionInspector` is the thin platform layer, and
`ActivityPermissionGateway` is the only piece that needs an Activity.

Error codes surfaced to the UI: `PERMISSION_DENIED`,
`PERMISSION_PERMANENTLY_DENIED` (offers Settings), `SPECIAL_ACCESS_REQUIRED`,
`PERMISSION_NOT_DECLARED_BY_HOST` (a build-time problem, with the fix spelled
out).

### Limits

Grants are process-wide: a permission granted for one plugin is held by the
process, so every plugin sees it. Per-plugin enforcement would need a separate
process or a separate installed APK per plugin.

## 10. Sources inside the package

`buildZetaPlugin` copies the plugin's own `.kt`/`.java` files into `source/` and
lists them in `code.source[]`. The Host reads them straight from the installed
archive (`PluginSourceReader`) and shows them in the code viewer, so the user can
read what a plugin does before granting it anything.

This is transparency, not proof: it shows the sources shipped *with* the DEX, not
that the DEX was compiled from them. Reproducible builds plus signatures would be
needed for that, and both are future work.
