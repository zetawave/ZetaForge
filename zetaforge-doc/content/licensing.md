---
title: Licensing
description: The toolchain is Apache-2.0; the app and runtime are source-available under PolyForm Strict. What that means for you.
---

# Licensing

ZetaForge is split deliberately: **the part you build against is permissive, the
part that runs on the device is not.**

| | Licence | |
|---|---|---|
| **`zeta-cli/`** — the CLI, the plugin contract, the templates | [Apache-2.0](https://github.com/zetawave/ZetaForge/blob/main/zeta-cli/LICENSE) | Anyone should be able to write, build and sell a plugin without asking permission or reading a licence. |
| **`app/`** — the host app, the runtime, the plugin builder | [PolyForm Strict 1.0.0](https://github.com/zetawave/ZetaForge/blob/main/LICENSE) | Use it, read it, learn from it — but it is not free to resell or to ship inside someone else's product. |

::: warning "Open source" is not accurate for the whole repository
The CLI and the contract are open source under any definition. The app and
runtime are **source-available**: PolyForm Strict is not an OSI-approved licence,
and it does not grant the right to redistribute or to build a product on top.

The repository is public and readable in full. That is not the same thing, and
these docs do not pretend otherwise.
:::

## What this means in practice

### Writing a plugin

Everything you touch is Apache-2.0: `zeta`, the templates it generates, and
`zetaforge-api-*.jar` — the contract your code compiles against, which ships
inside the npm package.

**Your plugin is yours.** Licence it how you like, sell it, keep it closed.
Nothing propagates from the contract into your code.

### Using the app

Install it and run plugins, freely. Personal use, company use, any use.

### Distributing plugins

Unrestricted. A `.zeta` is your artifact, built with an Apache-2.0 toolchain
against an Apache-2.0 contract. ZetaForge has no claim on it.

### Embedding the runtime, or shipping a modified ZetaForge

Not granted by PolyForm Strict. If that is what you want, a commercial licence is
the intended route — [open an issue](https://github.com/zetawave/ZetaForge/issues).

## Why not one licence for everything

Apache-2.0 everywhere would let anyone ship the runtime as their own.

PolyForm Strict everywhere would mean a company could not use the CLI to build an
internal plugin, which is exactly the use the CLI exists for.

The split follows a boundary that already exists in the code: the contract is the
line between *what you build against* and *what runs it*, and it is the natural
line for licensing too.

## Contributing

Contributions are accepted under the licence of the directory you are changing —
Apache-2.0 for `zeta-cli/` and the documentation, PolyForm Strict for `app/`. See
[Contributing](contributing.md).

## Third-party

The CLI depends on `picocolors` (ISC) and `smol-toml` (MIT), and ships the Gradle
wrapper (Apache-2.0).

It downloads, but does not redistribute: Gradle, the Kotlin compiler, `d8` from
R8 (BSD-3-Clause), and whatever dependencies a plugin declares.

Android SDK components — `android.jar`, `adb` — are used from your own
installation and never redistributed.

The documentation site has no third-party dependencies at all.

## The short version

* **Write plugins, sell plugins, keep them closed** — nothing is asked of you.
* **Use the app** — freely.
* **Ship the runtime inside your own product** — talk to us first.
