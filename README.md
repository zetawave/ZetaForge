<div align="center">

<img src="zetaforge-doc/theme/logo.svg" width="72" height="72" alt="">

# ZetaForge

**An Android app that runs code it was never compiled against.**

Write an ordinary Kotlin class, compile it on your machine, ship it as a single
`.zeta` file. The app loads it at run time — with its own libraries, its own
permissions, and its own screens.

[**Documentation**](https://zetawave.github.io/ZetaForge/) ·
[Quick start](https://zetawave.github.io/ZetaForge/quick-start/) ·
[Architecture](https://zetawave.github.io/ZetaForge/architecture/) ·
[Security model](https://zetawave.github.io/ZetaForge/security/)

[![CI](https://github.com/zetawave/ZetaForge/actions/workflows/ci.yml/badge.svg)](https://github.com/zetawave/ZetaForge/actions/workflows/ci.yml)
[![npm](https://img.shields.io/npm/v/zetaforge-cli?label=zetaforge-cli)](https://www.npmjs.com/package/zetaforge-cli)
[![Docs](https://github.com/zetawave/ZetaForge/actions/workflows/docs.yml/badge.svg)](https://zetawave.github.io/ZetaForge/)

</div>

---

```bash
npm install -g zetaforge-cli
zeta doctor
zeta new weather && cd weather
zeta dev
```

```kotlin
class WeatherPlugin : ZetaPlugin {
    override val id = "com.example.weather"
    override val name = "Weather"
    override val version = "1.0.0"

    override suspend fun execute(context: Context, input: Bundle): PluginResult {
        val city = input.getString("city") ?: "Rome"    // from the settings form
        val degrees = api.forecast(city).temperature     // your own Retrofit
        return PluginResult.Success("It is $degrees° in $city")
    }
}
```

That is a complete plugin. It carries its own Retrofit — a copy the app does not
contain and has never seen — and it runs inside the app with the app's
permissions.

## Why it is not another scripting plugin system

**Real Kotlin, real toolchain.** kotlinc and D8, producing the same DEX bytecode
Android runs everywhere else. No interpreter, no transpilation, no language
subset.

**Its own libraries.** Retrofit, OkHttp, anything on Maven Central, compiled
into the plugin's own DEX. Two plugins can even use different versions of the
same library, because each gets its own class loader.

**Its own permissions, with its own reasons.** Declared by the plugin, requested
at the moment they are needed, re-checked on every run.

**Its own screen.** A plugin can implement `ZetaUiPlugin` and return a
composable, which the app draws in a container Activity of its own — a mini app
inside the app.

**Its own source, in the box.** Every `.zeta` carries the Kotlin that produced
it, and the app can show it before you run it.

## The trust boundary

> A plugin runs **inside the host process, with the host's UID and permissions**.
> There is no sandbox. Importing a `.zeta` is as consequential as installing an
> app.

That is what makes the system useful, and it is why the app says so plainly.
[Security model →](https://zetawave.github.io/ZetaForge/security/)

## Documentation

Everything is at **<https://zetawave.github.io/ZetaForge/>**.

| | |
|---|---|
| [Installation](https://zetawave.github.io/ZetaForge/installation/) | The CLI, the app, and a device |
| [Quick start](https://zetawave.github.io/ZetaForge/quick-start/) | A plugin running in five minutes |
| [Plugin anatomy](https://zetawave.github.io/ZetaForge/plugin-anatomy/) | The contract, and what you can do with it |
| [Settings](https://zetawave.github.io/ZetaForge/settings/) · [Permissions](https://zetawave.github.io/ZetaForge/permissions/) · [Dependencies](https://zetawave.github.io/ZetaForge/dependencies/) | Declaring what a plugin needs |
| [Scheduling](https://zetawave.github.io/ZetaForge/scheduling/) · [Screens](https://zetawave.github.io/ZetaForge/screens/) | Running on its own; having an interface |
| [Architecture](https://zetawave.github.io/ZetaForge/architecture/) · [Class loading](https://zetawave.github.io/ZetaForge/class-loading/) | How it actually works |
| [Limitations](https://zetawave.github.io/ZetaForge/limitations/) | What it does not do — worth reading first |

## This repository

| | |
|---|---|
| **[app/](app/)** | The Android side: the host app, the runtime, the SDK contract, the plugin builder |
| **[zeta-cli/](zeta-cli/)** | The `zetaforge-cli` npm package: scaffold, build, test, run |
| **[zetaforge-doc/](zetaforge-doc/)** | The documentation site — static HTML, no dependencies |
| **[scripts/](scripts/)** | Build, release and end-to-end scripts |

The two halves meet at one artifact: `zetaforge-api-<n>.jar`, the contract.
`app/plugin-api` produces it and the npm package ships it, so the CLI and the
app it targets are the same version by construction.

## Working on ZetaForge itself

```bash
git clone https://github.com/zetawave/ZetaForge.git
cd ZetaForge
npm install
npm run doctor

npm run build              # contract + host (debug)
npm test                   # runtime unit tests + CLI tests
npm run test:device        # instrumented tests, needs a device
```

Full detail:
[Building from source](https://zetawave.github.io/ZetaForge/building-from-source/).

## Contributing

Issues and pull requests are welcome. Please read
[CONTRIBUTING.md](CONTRIBUTING.md) first — particularly the note about which
licence applies to which directory.

For anything that changes behaviour, adds a dependency or touches the plugin
contract, open an issue before writing code.

## Licensing

Two licences, on purpose — see [LICENSES.md](LICENSES.md):

| | |
|---|---|
| [zeta-cli/](zeta-cli/) — CLI, contract, templates | **Apache-2.0** — build and sell plugins freely |
| [app/](app/) — host, runtime, builder | **PolyForm Strict 1.0.0** — source-available; not for resale or redistribution |

The line between them is the contract: what you build against is permissive,
what runs it is not.

> **Note** — PolyForm Strict is a source-available licence, not an OSI-approved
> open source one. The repository is public and readable in full; that is not
> the same thing, and it is stated here rather than implied.

## Security

Found a vulnerability? Please do not open a public issue — see
[SECURITY.md](SECURITY.md).
