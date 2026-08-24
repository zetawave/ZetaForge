---
title: Versioning
description: Three numbers with different jobs — the Host API, the package format, and the screen contract — and how to version your own plugin.
---

# Versioning

## Three numbers, three meanings

| | What it describes | Today |
|---|---|---|
| **Host API version** | The contract the host implements | **4** |
| **Package format version** | The layout of the `.zeta` file | **4** |
| **Screen contract version** | `ui.uiApi`, for a plugin that has a screen | **1** |

::: note The rule worth remembering
**The major version of the `zetaforge-cli` npm package is the Host API version
it builds for.** `zetaforge-cli@4` builds plugins for Host API 4. Minor and
patch releases of the CLI never change the contract.
:::

```bash
npm install -g zetaforge-cli@4      # pinned to a contract
npx zetaforge-cli@4 build           # the same, without installing
```

This is also why the contract jar ships *inside* the npm package rather than
being resolved from a repository: the jar and the host that implements it come
out of the same build, so they cannot drift apart, and there is no second
version number for you to keep in sync.

## What a plugin declares

```toml
minHostApi = 4     # a hard requirement: an older host refuses to install it
maxHostApi = 4     # what you have tested: a newer host warns, but runs it
```

The asymmetry is deliberate.

`minHostApi` protects the user from a plugin that would call something their
host does not have. It is enforced: the host refuses to load the plugin and says
which version it needs.

`maxHostApi` is only your statement about what you verified. A newer host is
expected to keep working — treating it as a limit would make every plugin expire
the moment the app updated, which is the opposite of what versioning is for.

A package whose **format** version is newer than the host is refused outright,
with a message naming both numbers. That is what stops an old app from
half-reading a new package.

## Why screens are counted separately

The screen contract has its own number because it moves for a different reason:
it is **Compose's ABI**, not the SDK's.

A host that upgrades Compose can break an already compiled screen while every
other part of the contract is untouched. Without a separate number, that failure
arrives as a `NoSuchMethodError` on the first frame — one of the least
debuggable things this project can produce.

So a package with a screen records `ui.uiApi`, and a host implementing an older
screen contract refuses to open it with a sentence explaining why. Plugins
without a screen are unaffected.

## History

### Host API

| | |
|---|---|
| **1** | The base contract: `execute`, `PluginResult`, `ZetaLog` |
| **2** | `ZetaProgress`, and a foreground service that keeps a long run alive |
| **3** | Declared settings, the `settings()` hook, action buttons |
| **4** | Screens: `ZetaUiPlugin`, drawn by the host with the host's Compose |

Additions are backwards compatible. A plugin built against API 1 runs unchanged
on a host implementing API 4.

### Package format

| | |
|---|---|
| **1** | Permissions as plain strings |
| **2** | Structured permissions, special access, bundled sources |
| **3** | Declared settings |
| **4** | The `ui` block |

### Screen contract

| | |
|---|---|
| **1** | `Content(ZetaUiHost)`, Compose provided by the host |

## Versioning your own plugin

Ordinary semantic versioning, with one rule that matters more than usual.

::: danger Never reuse a version number
The host stores state per plugin id and users update in place. Two different
packages both calling themselves `1.2.0` is the one thing that makes a bug
impossible to diagnose afterwards.
:::

And changing `[plugin].id` does not release a new version — it creates a second,
unrelated plugin that installs alongside the first, with its own settings and
its own state.

### What counts as breaking, for a plugin

Your users are people with a phone, not developers with a compiler, so the usual
API-shaped definition does not quite apply. In practice:

**Major** — a saved setting changes meaning, or output moves somewhere else. The
user has to look at it again.

**Minor** — new settings with sensible defaults, new capability, better results
with no action needed.

**Patch** — fixes. Nothing the user has to know.

## When the host is older than the plugin

The user sees, on the card and in the failure:

```text
Plugin incompatible with this Host: requires API [5..5],
Host implements 4
```

The fix is to update the app: `zeta host install --force`, or the APK from the
releases page.

## When the host is newer than the plugin

It runs, and the host records that the combination is untested — `maxHostApi`
below the host's own version. That is a note in the details, not a warning in
the user's face, because it is the normal state of every plugin the moment the
app updates.

## Next

[Architecture →](architecture.md)
