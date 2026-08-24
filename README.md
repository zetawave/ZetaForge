# ZetaForge

An Android app that loads plugins written in **real Kotlin**, compiled
separately, shipped as a single `.zeta` file, with their own libraries — and a
CLI that makes writing one take a minute.

```bash
npx zetaforge new weather
cd weather
npx zetaforge dev
```

A plugin is not a script and not a configuration file. It is a Kotlin class that
receives the Host's `Context`, uses Retrofit or MediaCodec or anything else it
bundles, and runs inside an app that never knew it existed.

## This repository

| | |
|---|---|
| **[app/](app/)** | the Android side: the Host, the runtime, the SDK contract, the plugin builder |
| **[zeta-cli/](zeta-cli/)** | the `zetaforge` npm package: scaffold, build, test, run |
| **[scripts/](scripts/)** | build, release and end-to-end scripts for this repository |

The two halves meet at one artifact: `zetaforge-api-<n>.jar`, the contract.
`app/plugin-api` produces it and the npm package ships it, so the CLI and the
app it targets are the same version by construction — no Maven publishing, and
nothing to keep in sync by hand.

## Writing a plugin

Everything you need is **[zeta-cli/README.md](zeta-cli/README.md)** and
[zeta-cli/docs/](zeta-cli/docs/): the contract, settings, permissions,
dependencies, testing, distribution. None of this repository is required.

## Working on ZetaForge itself

```bash
npm install                # workspace tooling
npm run doctor             # check the machine

npm run build              # contract + Host (debug)
npm run build:plugins      # the bundled plugins
npm run test               # runtime unit tests + CLI tests
npm run test:e2e           # scaffold, build, install and run, on a real device
npm run test:device        # instrumented tests on a device

npm run release:dry        # everything except publishing
npm run release:patch      # 3.0.0 -> 3.0.1, published to npm and GitHub
```

Gradle is still there and works directly from `app/`; the npm scripts are a thin
layer so both halves have one entry point.

### Releasing

`npm run release:<patch|minor|major>` does the whole thing: checks the tree and
your credentials, bumps the version everywhere it appears, builds the contract
and the Host, stages the CLI assets, runs the tests, packs and inspects the npm
tarball, then commits, tags, pushes, publishes to npm and creates the GitHub
release with the APK attached.

It refuses to start on a dirty tree, off `main`, or without npm and `gh`
authenticated — a release that fails *after* the tag is pushed is far worse than
one that never starts.

**The major version is the Host API version.** `zetaforge@3` builds plugins for
Host API 3, and the release script rejects a version whose major does not match
`zetaforge.hostApiVersion` in [app/zetaforge.properties](app/zetaforge.properties).

### Where the big files go

npm carries only what is small — the CLI, the contract jar (48 KB) and the
Gradle wrapper: 137 KB in total. The Host APK is 18 MB and goes to GitHub
Releases, where `zeta host install` fetches it on demand and caches it under
`~/.zetaforge/`.

## How it works

The Host extracts the plugin's DEX, loads it with a `DelegateLastClassLoader` so
the plugin's own libraries win over the app's, finds the entry point class by
name, instantiates it and calls `execute`.

Four things must be *shared* rather than duplicated — the contract, the Kotlin
standard library, coroutines, and Compose for a plugin that has a screen —
because otherwise the Host could not even cast the plugin to `ZetaPlugin`.
Everything else the plugin brings with it. The build parses the DEX it produced
and refuses to package anything that breaks that rule, or that names an entry
point which does not exist.

A plugin can also *be* a screen rather than a job: it implements `ZetaUiPlugin`
and supplies a composable, which the Host draws inside a container Activity of
its own. An `Activity` shipped in a plugin could never be started — Android
resolves components from an installed APK's manifest — and Compose needs no
resources, so the package format does not change at all.
[app/plugins/calculator/](app/plugins/calculator/) is the reference.

The full account is in [app/README.md](app/README.md) and
[app/docs/architecture.md](app/docs/architecture.md).

## The trust boundary

A plugin runs **inside the Host process, with the Host's UID and permissions**.
It is a trust boundary, not a sandbox: importing a `.zeta` is as consequential
as installing an app, and the app says so.

## Licensing

Two licences, on purpose — see [LICENSES.md](LICENSES.md):

| | |
|---|---|
| [zeta-cli/](zeta-cli/) — CLI, contract, templates | **Apache-2.0** — build and sell plugins freely |
| [app/](app/) — Host, runtime, builder | **PolyForm Strict 1.0.0** — not for resale or redistribution |

The line between them is the contract: what you build against is permissive,
what runs it is not.
