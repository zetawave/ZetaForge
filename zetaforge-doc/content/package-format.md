---
title: The .zeta format
description: The layout of a plugin package, every field of manifest.json, and the guarantees the format provides.
---

# The `.zeta` format

A plugin ships as one file: a ZIP with a fixed layout and a versioned manifest.

```text
weather-1.0.0.zeta
├── manifest.json          versioned metadata
├── dex/
│   └── classes.dex        real DEX, stored uncompressed
├── source/                the Kotlin that produced it
├── assets/                optional data files
├── libs/                  reserved for native libraries
└── metadata/build.json    build provenance
```

Nothing about it is exotic: `unzip` opens it, and so does any archive tool.

## Why the pieces are what they are

**`dex/` is stored, not deflated.** The host can then map it directly rather
than inflating it into memory first. Multi-DEX works: `classes.dex`,
`classes2.dex` and so on are all extracted into an APK-shaped container, which
is the layout `BaseDexClassLoader` is designed for.

**`source/` travels with the code.** The app has a `VIEW CODE` button. Given
that there is no sandbox between a plugin and the host, being able to read what
you are about to run is not a nicety.

**`manifest.json` is versioned.** A package whose `formatVersion` is newer than
the host understands is refused outright, with a message naming both numbers, so
an old app never half-reads a new package.

## manifest.json

```json
{
  "formatVersion": 4,
  "pluginId": "com.example.weather",
  "name": "Weather",
  "version": "1.0.0",
  "description": "Fetches a forecast.",
  "author": "Jane Doe",
  "homepage": "https://example.com/weather",
  "license": "Apache-2.0",
  "entryPoint": "com.example.weather.WeatherPlugin",
  "minHostApi": 4,
  "maxHostApi": 4,
  "minSdk": 26,
  "permissions": [
    {
      "name": "android.permission.INTERNET",
      "reason": "Downloads the forecast",
      "optional": false,
      "minSdk": 1
    }
  ],
  "specialAccess": [],
  "capabilities": ["ui"],
  "settings": [
    { "type": "text", "key": "city", "label": "City", "default": "Rome" }
  ],
  "ui": { "enabled": true, "uiApi": 1, "only": true },
  "dependencies": {
    "bundled": ["com.squareup.retrofit2:retrofit:2.11.0"],
    "hostProvided": [
      "com.zetaforge:plugin-api:4",
      "org.jetbrains.kotlin:kotlin-stdlib",
      "org.jetbrains.kotlinx:kotlinx-coroutines-core"
    ]
  },
  "code": {
    "dex": [
      {
        "path": "dex/classes.dex",
        "size": 1438208,
        "sha256": "66c36da01bbaf…",
        "dexVersion": "039"
      }
    ],
    "source": [
      { "path": "source/src/…/WeatherPlugin.kt", "displayName": "…", "language": "kotlin", "size": 2317 }
    ]
  },
  "signature": null,
  "display": { "category": "utility", "icon": "" }
}
```

### Fields

| | |
|---|---|
| `formatVersion` | Layout of this file. Refused if newer than the host. |
| `pluginId` | Reverse-DNS. The installation directory and the key of all stored state. |
| `entryPoint` | Class implementing `ZetaPlugin`; verified to exist in the DEX at build time. |
| `minHostApi` / `maxHostApi` | Hard floor; tested ceiling. See [Versioning](versioning.md). |
| `minSdk` | Refused on an older Android. |
| `permissions[]` | Structured since format 2: `name`, `reason`, `optional`, `minSdk`, `maxSdk`. |
| `specialAccess[]` | `id`, `reason`, `optional`. |
| `capabilities[]` | Named capabilities; `ui` is added automatically for a screen. |
| `settings[]` | The form the host renders. |
| `ui` | Present only for a screen: `enabled`, `uiApi`, `only`, `label`. |
| `dependencies.bundled` | What is inside the DEX. Documentation, not something the host acts on. |
| `dependencies.hostProvided` | What the plugin expects to find. Also documentation. |
| `code.dex[]` | Per-file `path`, `size`, `sha256`, `dexVersion`. |
| `code.source[]` | What `VIEW CODE` shows. |
| `signature` | Always `null` today. Reserved. |

## Format history

| | |
|---|---|
| **1** | Permissions as plain strings. |
| **2** | Structured permissions (`reason`, `optional`, `minSdk`, `maxSdk`), `specialAccess`, bundled sources. |
| **3** | Declared `settings`. |
| **4** | The `ui` block. |

Older packages still parse. The runtime accepts every shape, which is the whole
point of versioning the format — and the absence of `ui` is exactly how a
package built before screens existed says it has none.

## What the host checks on import

```text
user picks a file (SAF)
  → copied into cache/zetaforge/import-<n>.zeta        staging, untrusted
  → ZIP validation
  → manifest.json parsed and semantically validated
  → per-DEX magic bytes and SHA-256
  → SHA-256 of the whole archive
  → BasicPluginVerifier
       structure · manifest · entryPoint · hostApi · minSdk
       checksum · permissions · signature-absent warning
  → files/zetaforge/plugins/<pluginId>/current.zeta
  → files/zetaforge/plugins/<pluginId>/extracted/code.jar
  → install.json record
```

Code is only ever executed from app-private storage. The file the user picked is
read once and never used again.

Re-running the verification later, against what is on disk, is what the app's
`DETAILS → verify` does:

```text
[ok]   structure: 1 DEX file(s): dex/classes.dex
[ok]   manifest: formatVersion=4, pluginId=com.example.weather, version=1.0.0
[ok]   entryPoint: com.example.weather.WeatherPlugin
[ok]   hostApi: host API 4, plugin needs at least 4
[ok]   minSdk: plugin minSdk=26, device API=34
[ok]   checksum: sha256=66c36da01bbaf…
[warn] signature: Package is unsigned. Trust comes from the file source only.
```

## Signing

::: warning Packages are unsigned today
`manifest.signature` is always `null`, and the verifier reports it as a warning
on every import. Trust in a `.zeta` currently comes entirely from where you got
it.
:::

The seam exists: `PluginVerifier` is an interface, `BasicPluginVerifier` is one
implementation, and `CompositePluginVerifier` is how a signature verifier will
be chained in without touching anything else. The manifest block is already
parsed, so signed packages will stay readable by older hosts.

## Reproducibility

Archive entry timestamps are zeroed and entries are written in a fixed order, so
two builds of the same sources produce the same bytes — apart from
`metadata/build.json`, which records when and with what the package was built.

`<name>.zeta.sha256` is written next to every package.

## Reading one

```bash
zeta inspect dist/weather-1.0.0.zeta
zeta inspect dist/weather-1.0.0.zeta --classes
```

Or by hand, since it is only a ZIP:

```bash
unzip -p weather-1.0.0.zeta manifest.json | jq .
unzip -l weather-1.0.0.zeta
```

## Next

[Versioning →](versioning.md)
