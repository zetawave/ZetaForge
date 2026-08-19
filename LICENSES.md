# Licensing

ZetaForge is split deliberately: **the part you build against is permissive, the
part that runs on the device is not.**

| | Licence | Why |
|---|---|---|
| **[zeta-cli/](zeta-cli/)** — the CLI, the plugin contract, the templates | [Apache-2.0](zeta-cli/LICENSE) | Anyone should be able to write, build and sell a plugin without asking permission or reading a licence. A toolchain nobody may use commercially is a toolchain nobody adopts. |
| **[app/](app/)** — the Host app, the runtime, the plugin builder | [PolyForm Strict 1.0.0](LICENSE) | Use it, read it, learn from it — but it is not free to resell or to ship inside someone else's product. |

## What this means in practice

**Writing a plugin.** Everything you touch is Apache-2.0: `zeta`, the templates
it generates, and `zetaforge-api-*.jar`, the contract your code compiles against
and which ships inside the npm package. Your plugin is yours — licence it,
sell it, keep it closed. Nothing propagates.

**Using the app.** Install it and run plugins, freely.

**Embedding the runtime in your own product**, or shipping a modified ZetaForge,
is not granted by PolyForm Strict. If that is what you want, a commercial
licence is the intended route — open an issue.

## Why not one licence for everything

Apache-2.0 everywhere would let anyone ship the runtime as their own. PolyForm
Strict everywhere would mean a company could not use the CLI to build an
internal plugin, which is exactly the use the CLI exists for.

The split follows the boundary that already exists in the code: the contract is
the line between "what you build against" and "what runs it", and it is the
natural line for licensing too.

## Third-party

The CLI depends on `picocolors` (ISC) and `smol-toml` (MIT), and ships the
Gradle wrapper (Apache-2.0). It downloads, but does not redistribute, Gradle,
the Kotlin compiler, `d8` from R8 (BSD-3-Clause) and whatever dependencies a
plugin declares. Android SDK components — `android.jar`, `adb` — are used from
your own installation and never redistributed.
