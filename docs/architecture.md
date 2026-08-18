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
| `plugin-api` | Android library | `ZetaPlugin`, `PluginResult`, `PluginState`, `ZetaLog`, `ZetaSdk.HOST_API_VERSION` | nothing (stdlib/coroutines are `compileOnly`) |
| `runtime` | Android library | manifest parsing, package reading, verification, installation, class loading, lifecycle, execution, logging | `plugin-api` |
| `host` | Android app | Compose UI + `HostViewModel` only | `runtime` (and transitively `plugin-api`) |
| `plugins/retrofit-demo` | Android app (never installed) | the demo plugin and its own dependencies | `plugin-api` **compileOnly** |
| `plugin-builder` | included Gradle build | `com.zetaforge.zeta-plugin` plugin, `buildZetaPlugin` task, `.zeta` writer, DEX reader | AGP API |

Rules enforced by the build:

* the plugin never sees `com.zetaforge.app.*` or `com.zetaforge.runtime.*` — it only
  compiles against `plugin-api`;
* the Host never references any concrete plugin: it only knows ids, manifests
  and the `ZetaPlugin` interface;
* `plugin-api`, Kotlin stdlib and coroutines are `compileOnly` on the plugin side,
  so exactly one copy of every boundary type exists at runtime.

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
`description`, `author`, `entryPoint`, `minHostApi`, `maxHostApi`, `minSdk`,
`permissions[]`, `capabilities[]`, `dependencies.bundled[]`,
`dependencies.hostProvided[]`, `code.dex[]` (path/size/sha256/dexVersion),
`signature` (currently `null`), `display`.

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

## 8. Extension points already in place

| Seam | Today | Next |
|---|---|---|
| `PluginVerifier` | `BasicPluginVerifier` | `SignaturePluginVerifier` chained via `CompositePluginVerifier` |
| `manifest.signature` | always `null` | certificate + signature over the DEX hashes |
| `ZetaLogger.persister` | in-memory ring buffer | file / Room persistence |
| `PluginState` | full lifecycle modelled | scheduler, background execution, updates |
| `capabilities[]` | recorded and displayed | capability-based Host API surface |
| `libs/` in the package | reserved, empty | native `.so` support |
