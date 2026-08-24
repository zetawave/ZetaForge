# Contributing to ZetaForge

Thanks for considering it. This document is what a contributor needs to know
before spending time on a change.

## Licensing — read this first

The repository is **not** uniformly open source, and contributions are accepted
under the licence of the directory you are changing:

| Directory | Licence |
|---|---|
| `zeta-cli/`, `zetaforge-doc/` | Apache-2.0 |
| `app/` | PolyForm Strict 1.0.0 — source-available, **not** OSI open source |

By opening a pull request you agree that your contribution is licensed under the
licence covering the files you touched. If that is a problem for you, please say
so in the issue before writing anything, rather than after.

See [LICENSES.md](LICENSES.md) for the reasoning behind the split.

## Before you write code

**Open an issue first** for anything that:

- changes behaviour a user can observe;
- adds a dependency, anywhere;
- touches the plugin contract (`app/plugin-api/`);
- changes the `.zeta` package format;
- adds to the host's permission superset.

A patch that is architecturally wrong takes longer to review than an idea that
is, and the contract in particular carries a compatibility promise to every
already published plugin.

**Bug reports need no permission.** The useful ones include:

- what you did, what happened, what you expected;
- the output of `zeta doctor`;
- the relevant part of `zeta logs`;
- the plugin's `zetaplugin.toml` if it is involved.

## Getting set up

```bash
git clone https://github.com/zetawave/ZetaForge.git
cd ZetaForge
npm install
npm run doctor
```

You need Node 18.17+, a JDK 17+, and the Android SDK (platform 35). `npm run
doctor` tells you which of those it cannot find and how to get it.

```bash
npm run build              # the contract jar, then the host APK
npm test                   # runtime unit tests and CLI tests
npm run test:device        # instrumented tests, needs a device or emulator
```

Full detail:
[Building from source](https://zetawave.github.io/ZetaForge/building-from-source/).

## What a good patch looks like

**It does one thing.** A change that fixes a bug and reformats a file is two
changes, and the second one hides the first.

**It is tested where testing is cheap.** Manifest parsing, package reading,
permission rules, the CLI's descriptor validation — all unit-testable, and a
change to any of them should come with a test. Anything needing a device is a
judgement call.

**It updates the documentation if it is user-visible.** A new setting type, a
new CLI flag or a contract change needs the corresponding page under
`zetaforge-doc/content/` updated in the same pull request.

**It reads like the code around it.** The codebase has a consistent voice:
comments explain *why* a decision was made, not what the line does, and they
appear where the reasoning would otherwise look arbitrary. Match that rather
than introducing a second style. There is no formatter to run; match the file
you are in.

**Its commit messages say what changed and why.** One line of subject, and a
body when the why is not obvious.

## Areas that need extra care

### The plugin contract — `app/plugin-api/`

Every change is a compatibility event for plugins already published.

- Adding a member **with a default implementation** is additive and fine.
- Changing a signature, or adding a member without a default, is breaking.
- Bumping `HOST_API_VERSION` means bumping the CLI's major version. The release
  script enforces that the two agree and will refuse to publish otherwise.

### The package format

Older packages must keep parsing. The format is versioned precisely so that is
possible: every new field has to be optional, with a sensible meaning when
absent.

### The permission superset — `zetaforge.permissions`

It is the ceiling for every plugin, and it is what a user sees when installing
the app. Adding to it is reasonable when a real plugin needs it; adding
speculatively is not.

### The shared boundary

The set of packages a plugin may not bundle — the SDK, Kotlin, coroutines,
Compose — is enforced in three places: the Gradle packaging task, the CLI, and
the runtime's load-time check. Changing it means changing all three, and
thinking about every package already published.

## Documentation

The site lives in `zetaforge-doc/` and is plain Markdown with a dependency-free
build:

```bash
cd zetaforge-doc
npm run dev        # build, serve on :4173, rebuild on save
```

Content is `content/*.md`; the sidebar is `content/nav.json`. Cross-references
are written as `[settings](settings.md)` so they work both on the site and when
browsing the files on GitHub. CI checks that every internal link resolves.

Every published page has an "Edit this page on GitHub" link, which is the
fastest route for a typo.

## Where to start

**Good first contributions**

- Documentation: anything unclear, out of date, or missing an example.
- CLI error messages — every one should be a sentence with a hint, and some are
  not there yet.
- A new project template for `zeta new`.
- Test coverage for the runtime's parsing and validation.

**Larger, worth discussing first**

- Signature verification (`SignaturePluginVerifier`) — the seam exists.
- Native library support via `libs/` in the package.
- Persisted logs and run history.
- An isolated-process mode for untrusted plugins.

## Pull requests

- Branch from `main`.
- Keep the diff focused; unrelated cleanups in their own PR.
- CI must be green: CLI tests, runtime tests, and the documentation link check.
- Describe what a reviewer should look at, not just what you changed.

## Code of conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). Disagree
about the work, not the person.

## Security

Do not report vulnerabilities in a public issue. See [SECURITY.md](SECURITY.md).
