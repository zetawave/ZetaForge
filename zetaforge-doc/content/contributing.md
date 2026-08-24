---
title: Contributing
description: How to propose a change, what the project cares about in a patch, and where to start.
---

# Contributing

Contributions are welcome. This page is the short version; the repository's
[CONTRIBUTING.md](https://github.com/zetawave/ZetaForge/blob/main/CONTRIBUTING.md)
is the authoritative one.

::: warning Read the licence first
The repository is **not** uniformly open source. The CLI, the templates and the
plugin contract are Apache-2.0; the host app and runtime are under PolyForm
Strict, which is source-available but not an OSI-approved open source licence.

Contributions are accepted under the licence of the directory you are changing.
See [Licensing](licensing.md).
:::

## Before you write code

**Open an issue first** for anything that changes behaviour, adds a dependency,
or touches the plugin contract. A patch that is architecturally wrong is more
work to review than an idea that is, and the contract in particular carries a
compatibility promise to every already published plugin.

Bug reports are always welcome without asking. The useful ones include: what you
did, what happened, what you expected, `zeta doctor` output, and the relevant
part of `zeta logs`.

## Getting set up

```bash
git clone https://github.com/zetawave/ZetaForge.git
cd ZetaForge
npm install
npm run doctor
```

[Building from source](building-from-source.md) has the full detail, including
how to build the app and run the tests.

## What a good patch looks like

**One thing.** A patch that fixes a bug and reformats a file is two patches, and
the second one hides the first.

**Tested where testing is cheap.** Manifest parsing, package reading, permission
rules, the CLI's descriptor validation — all of that is unit-testable and should
come with a test. Anything needing a device is a judgement call.

**Documented if it is user-visible.** A new setting type, a new CLI flag or a
contract change needs the corresponding page in `zetaforge-doc/content/`
updated in the same patch.

**Written like the code around it.** The codebase has a consistent voice:
comments explain *why*, not *what*, and they are used where a decision would
otherwise look arbitrary. Match it rather than introducing a second style.

## Things that need care

**The plugin contract** (`app/plugin-api`). Every change is a compatibility
event. Adding a member with a default implementation is additive and fine;
changing a signature is not. Bumping `HOST_API_VERSION` means bumping the CLI's
major version, and the release script enforces that they agree.

**The package format.** Older packages must keep parsing. The format is
versioned precisely so this is possible, and every new field has to be optional
with a sensible absence.

**The permission superset** (`zetaforge.permissions`). It is the ceiling for
every plugin, and it is also what a user sees when they install the app. Adding
to it is reasonable when a real plugin needs it; adding speculatively is not.

**The shared boundary.** The set of packages a plugin may not bundle is enforced
in three places. Changing it means changing all three, and thinking about every
package already published.

## Running the tests

```bash
npm test                                    # everything
npm run test:cli                            # the CLI
npm run test:unit                           # runtime unit tests
npm run test:device                         # instrumented, needs a device
node scripts/gradle.mjs :runtime:test       # the same, directly
```

## Documentation

The site you are reading lives in `zetaforge-doc/` and is plain Markdown with a
dependency-free build:

```bash
cd zetaforge-doc
npm run dev        # build, serve on :4173, rebuild on save
```

Content is `content/*.md`; the sidebar is `content/nav.json`. Cross-references
are written as `[settings](settings.md)` so they work both on the site and when
reading the files on GitHub.

Every page has an "Edit this page on GitHub" link at the bottom, which is the
fastest route for a typo.

## Where to start

**Good first changes**

* documentation: anything unclear, out of date, or missing an example
* CLI error messages — every one should be a sentence with a hint
* a new project template
* test coverage for the runtime's parsing and validation

**Larger, and worth discussing first**

* signature verification (`SignaturePluginVerifier`); the seam exists
* native library support via `libs/`
* persisted logs and run history
* an isolated-process mode

## Code of conduct

The project follows the
[Contributor Covenant](https://github.com/zetawave/ZetaForge/blob/main/CODE_OF_CONDUCT.md).
Be decent; disagree about the work rather than the person.

## Next

[Building from source →](building-from-source.md)
