# Versioning

## Three numbers, three meanings

| | what it describes | today |
|---|---|---|
| **Host API version** | the contract the Host implements | 4 |
| **Package format version** | the layout of the `.zeta` file | 4 |
| **Screen contract version** | `ui.uiApi`, for a plugin that has a screen | 1 |

**The major version of the `zetaforge` npm package is the Host API version it
targets.** `zetaforge@4` builds plugins for Host API 4. Minor and patch releases
of the CLI never change the contract.

```bash
npm install -g zetaforge@4      # pinned to a contract
npx zetaforge@4 build           # same, without installing
```

This is why the contract jar ships inside the npm package rather than being
resolved from a repository: the jar and the Host that implements it come out of
the same build, so they cannot drift apart, and there is no second version
number for you to keep in sync.

## What a plugin declares

```toml
minHostApi = 4     # a hard requirement: an older Host refuses to install it
maxHostApi = 4     # what you have tested: a newer Host warns, but runs it
```

The asymmetry is deliberate. `minHostApi` protects the user from a plugin that
would call something their Host does not have. `maxHostApi` is only your
statement about what you verified — a newer Host is expected to keep working,
and treating it as a limit would make every plugin expire.

A package whose **format** version is newer than the Host is refused outright,
with a message naming both numbers. That is what stops an old app from
half-reading a new package.

## History

**Host API**

| | |
|---|---|
| 1 | the base contract: `execute`, `PluginResult`, `ZetaLog` |
| 2 | `ZetaProgress` and the foreground service |
| 3 | declared settings, the `settings()` hook, actions |
| 4 | screens: `ZetaUiPlugin`, drawn by the Host with the Host's Compose |

**Package format**

| | |
|---|---|
| 1 | permissions as plain strings |
| 2 | structured permissions, special access, bundled sources |
| 3 | declared settings |
| 4 | the `ui` block: whether the plugin has a screen, and which contract it uses |

**Screen contract**

| | |
|---|---|
| 1 | `Content(ZetaUiHost)`, Compose provided by the Host |

The screen contract is counted apart from the Host API because it moves for a
different reason: it is Compose's ABI, not the SDK's. A Host that upgrades
Compose can break an already compiled screen while the rest of the contract is
untouched — so a package records the version it was built against, and a Host
implementing an older one refuses to open the screen with a message rather than
failing on the first frame.

## Versioning your own plugin

Ordinary semantic versioning, with one rule that matters more than usual:

**Never reuse a version number.** The Host stores state per plugin id and users
update in place. Two different packages both calling themselves `1.2.0` is the
one thing that makes a bug impossible to diagnose afterwards.

And changing `[plugin].id` does not release a new version — it creates a second,
unrelated plugin that installs alongside the first, with its own settings and
its own state.
