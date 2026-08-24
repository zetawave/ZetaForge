---
title: Publishing a plugin
description: A .zeta is one self-contained file. How to release it, what to check first, and what to tell the people installing it.
---

# Publishing a plugin

There is no store, no account, no signing authority and no review. You build a
file, and anyone with ZetaForge can import it.

```bash
zeta build
# dist/weather-1.0.0.zeta          the plugin
# dist/weather-1.0.0.zeta.sha256   its checksum
```

## Before you publish, look inside

```bash
zeta inspect dist/weather-1.0.0.zeta
```

Check, every time:

* **the version** is what you think it is, and has never been published before;
* **the permissions** are only what the plugin genuinely needs, and every reason
  reads like a sentence written for a user;
* **the bundled dependencies** are the ones you meant to ship;
* **the entry point** is right.

::: danger Your sources are in the package
Every `.zeta` carries the Kotlin that produced it, and the app can display it.
That is a feature — someone can read what they are about to run — but it also
means an API key or a token left in the code is published with it.
:::

## Handing it to someone

Any way a file travels: a GitHub release, a link, a chat message, a USB cable.
On the phone, ZetaForge imports it through the system file picker, or by opening
the file from a file manager.

A GitHub release is the usual choice, and worth doing properly:

* attach the `.zeta` **and** the `.sha256`;
* tag the release with the same version as `[plugin].version`;
* say in the notes which Host API it needs — `zeta inspect` prints it.

## A release workflow

```yaml title=".github/workflows/release.yml"
name: release
on:
  push:
    tags: ["v*"]

jobs:
  publish:
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 20
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21

      - run: npm install -g zetaforge-cli@4
      - run: zeta test
      - run: zeta build

      - name: The tag and the descriptor must agree
        run: |
          VERSION=$(grep -m1 '^version' zetaplugin.toml | cut -d'"' -f2)
          [ "v$VERSION" = "${GITHUB_REF_NAME}" ] || {
            echo "Tag ${GITHUB_REF_NAME} does not match version $VERSION"; exit 1;
          }

      - uses: softprops/action-gh-release@v2
        with:
          files: |
            dist/*.zeta
            dist/*.zeta.sha256
          generate_release_notes: true
```

The version check is worth the four lines. A release tagged `v1.2.0` containing
a package that calls itself `1.1.0` is the kind of thing nobody notices for
months.

## Versioning

Ordinary semantic versioning, with one rule that matters more than usual:
**never reuse a version number.** The host stores state per plugin id and users
update in place; two different packages both calling themselves `1.2.0` is the
one thing that makes a bug impossible to diagnose afterwards.

Changing `[plugin].id` does not release a new version — it creates a second,
unrelated plugin that installs alongside the first, with its own settings and
its own state.

See [Versioning](versioning.md).

## What to write in your README

Four things, and they take a paragraph each at most.

**What it does**, in a sentence a non-developer understands.

**What it needs**: the permissions, and why. The user will see them anyway;
explaining them first reads very differently from letting them be a surprise.

**Which Host API it requires**, and therefore which version of the app.

**The trust statement.** Say it plainly, because it is true and they deserve to
know:

> A plugin runs inside ZetaForge, with ZetaForge's permissions. It is not
> sandboxed. Importing one is as consequential as installing an app.

## On trust

Packages are unsigned. There is no cryptographic identity attached to a `.zeta`,
so everything rests on provenance: where the file came from, and whether the
person who published it can be identified.

If you distribute plugins, make that as easy as possible for the people
installing them:

* **publish the sources** — they are in the package anyway, but a repository is
  where issues and history live;
* **tag every release**, so a version maps to a commit;
* **publish the checksum**, so a downloaded file can be compared with the one
  you built;
* **use the same account consistently**. Identity is the only signal available.

## Installing someone else's plugin

```bash
zeta install path/to/their-plugin.zeta
```

Or open the file on the phone. Either way, before you press START: read the
permissions on the card, and tap `VIEW CODE`. Both take ten seconds, and they
are the whole of the defence available to you. See
[the security model](security.md).

## Next

[Troubleshooting →](troubleshooting.md)
