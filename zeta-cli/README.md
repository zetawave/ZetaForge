# zetaforge

Create, build and test **ZetaForge plugins** — real Kotlin, real libraries,
packaged as a single `.zeta` file that any ZetaForge install can load at run
time.

```bash
npx zetaforge-cli new weather
cd weather
npx zetaforge-cli dev
```

That is the whole loop: save a file, and a few seconds later the plugin is
running on your phone with its output in your terminal.

---

## What a plugin is

A plugin is an ordinary Kotlin class, compiled on its own, that the Host app has
never seen. At run time the Host loads the plugin's DEX, finds the class by
name, creates it and calls one function.

```kotlin
class WeatherPlugin : ZetaPlugin {
    override val id = "com.example.weather"
    override val name = "Weather"
    override val version = "1.0.0"

    override suspend fun execute(context: Context, input: Bundle): PluginResult {
        val city = input.getString("city") ?: "Rome"       // from the settings form
        val degrees = api.forecast(city).temperature        // your own Retrofit
        return PluginResult.Success("It is $degrees° in $city")
    }
}
```

No Android module, no `AndroidManifest.xml`, no resources, no Gradle file to
maintain. A project is one descriptor and your sources:

```
weather/
├── zetaplugin.toml        identity, permissions, settings, dependencies
├── src/                   Kotlin
├── test/                  JVM tests, no device needed
└── dist/weather-1.0.0.zeta
```

## Or a screen, if that is what it should be

A plugin does not have to be a job you start. It can be something the user
*opens* — a screen, written in Compose, drawn inside the Host:

```toml
[ui]
only = true        # this plugin is a screen and nothing else
```

```kotlin
class CalculatorPlugin : ZetaUiPlugin {
    override val id = "com.example.calculator"
    override val name = "Calculator"
    override val version = "1.0.0"

    @Composable
    override fun Content(host: ZetaUiHost) { /* your screen */ }
}
```

Still no Android module, no manifest and no resources: Compose needs none, which
is exactly why it is the way in. An `Activity` inside a plugin could never be
started — Android resolves components from an installed APK's manifest — so the
Host declares the container once and your plugin supplies the content.

Compose comes from the Host, like the Kotlin runtime does. Do not add it to
`[dependencies]`: `zeta build` refuses a package that carries its own copy, by
reading the class definitions in the DEX it just produced.

See [plugin-anatomy.md](docs/plugin-anatomy.md).

## Install

```bash
npm install -g zetaforge-cli     # or use npx, no install
zeta doctor                  # checks the machine and says what is missing
```

**Requirements**

| | |
|---|---|
| Node | 18.17+ |
| Java | JDK 17+ — `zeta doctor --install-jdk` fetches one if you have none |
| Android SDK | `platform-tools` for adb, and one SDK platform for `android.jar` |
| A device | a phone with USB debugging, or an emulator |

`zeta doctor` finds all of these on its own, including the JDK bundled with
Android Studio. When something is missing it prints the exact command to fix it.

The ZetaForge app itself is installed with `zeta host install`.

## The commands

| | |
|---|---|
| `zeta new <name>` | scaffold a project (`--template basic\|network`) |
| `zeta dev` | rebuild, install and run on every save |
| `zeta build` | produce and verify `dist/<name>-<version>.zeta` |
| `zeta test` | run the JVM tests, no device involved |
| `zeta install` | put the package on a device |
| `zeta run` | execute it and print the result |
| `zeta logs` | follow the log stream |
| `zeta inspect <file>` | show what is inside a `.zeta` |
| `zeta devices` | what adb can see |
| `zeta host install` | install or update the ZetaForge app |
| `zeta doctor` | check the machine |
| `zeta clean` | delete generated build state |

`zeta help <command>` for the options of any one of them.

## Settings, without writing a UI

Declare a parameter and the Host renders the form; the value arrives in the
`input` Bundle of `execute`. Adding one needs no change to the app.

```toml
[[setting]]
key         = "quality"
type        = "number"
label       = "Photo quality"
description = "82 is visually transparent; below 75 artefacts show."
min         = 50
max         = 100
default     = 82
```

Types: `switch`, `number`, `decimal`, `text`, `choice`, `multiChoice`, `folder`,
`action`. See [docs/settings.md](docs/settings.md).

## Permissions, with a reason the user reads

```toml
[[permission]]
name   = "android.permission.READ_MEDIA_IMAGES"
reason = "Reads your photos in order to copy them"
minSdk = 33
```

The Host re-checks every permission at START, shows your reason, requests what
is missing, and refuses to run if a mandatory one is denied. Special access —
all-files, exact alarms, battery — is handled too. See
[docs/permissions.md](docs/permissions.md).

## Your own libraries

```toml
[dependencies]
retrofit = "com.squareup.retrofit2:retrofit:2.11.0"
okhttp   = "com.squareup.okhttp3:okhttp:4.12.0"
```

They are compiled into the plugin's DEX. The Host does not have them and does
not need them — two plugins can even use different versions of the same library.

The one rule: the contract, the Kotlin standard library and coroutines come
**from the Host** and are never bundled. `zeta build` enforces it and fails with
an explanation rather than letting you ship a plugin that cannot load. See
[docs/dependencies.md](docs/dependencies.md).

## Documentation

| | |
|---|---|
| [Plugin anatomy](docs/plugin-anatomy.md) | the contract, the descriptor, the lifecycle |
| [Settings](docs/settings.md) | every field type, the run-time hook, actions |
| [Permissions](docs/permissions.md) | run-time, special access, and the Host superset |
| [Dependencies](docs/dependencies.md) | what is bundled, what is borrowed, and why |
| [Testing](docs/testing.md) | JVM tests, on-device runs, debugging |
| [CLI reference](docs/cli.md) | every command and option |
| [Versioning](docs/versioning.md) | Host API, package format, compatibility |
| [Distributing](docs/publishing.md) | shipping a `.zeta` to other people |
| [Troubleshooting](docs/troubleshooting.md) | when something does not work |

## Versioning

**The major version of this package is the Host API version it targets.**

```bash
npm install -g zetaforge-cli@4     # builds plugins for Host API 4
```

A plugin declares the API it needs; an older Host refuses it, a newer one warns
but runs it. See [docs/versioning.md](docs/versioning.md).

## A word on trust

A plugin runs **inside the Host process, with its permissions**. That is a trust
boundary, not a sandbox: importing a `.zeta` is as consequential as installing an
app. Ship your plugins from somewhere people can verify, and say so.

## License

**Apache-2.0** — see [LICENSE](LICENSE). The CLI, the plugin contract it ships,
and the templates are all Apache-2.0, so plugins you build with it are yours,
under whatever licence you choose, commercial or not.

The ZetaForge app itself is licensed separately: see the
[repository](https://github.com/zetawave/ZetaForge).
