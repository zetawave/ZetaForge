# Distributing a plugin

A `.zeta` is one self-contained file. There is no store, no account, no signing
authority, no review. You build it, and anyone with ZetaForge can import it.

```bash
zeta build
# dist/weather-1.0.0.zeta          the plugin
# dist/weather-1.0.0.zeta.sha256   its checksum
```

## Handing it to someone

Any way a file travels: a GitHub release, a link, a chat message, a USB cable.
On the phone, ZetaForge imports it through the system file picker.

A GitHub release is the usual choice, and worth doing properly:

* attach the `.zeta` **and** the `.sha256`;
* tag the release with the same version as `[plugin].version`;
* say in the notes which Host API it needs — `zeta inspect` prints it.

## Before you publish, look inside

```bash
zeta inspect dist/weather-1.0.0.zeta
```

Check, every time:

* the **version** is what you think it is, and has never been published before;
* the **permissions** are only what the plugin genuinely needs, and every reason
  reads like a sentence written for a user;
* the **bundled dependencies** are the ones you meant to ship;
* the **entry point** is right.

Your sources travel inside the package and the app can show them. That is a
feature — someone can read what they are about to run — but it also means a key
or a token left in the code is published with it.

## What a user is agreeing to

Say it plainly in your README, because it is true and they deserve to know:

> A plugin runs inside ZetaForge, with ZetaForge's permissions. It is not
> sandboxed. Importing one is as consequential as installing an app.

That is what makes the system useful and what makes trust the whole currency. If
you distribute plugins, publish the sources, tag your releases, and make it easy
to tell that the file someone downloaded is the one you built.

## Updating

Bump `[plugin].version`, rebuild, publish the new file. The user imports it and
the Host replaces the old one in place, keeping its settings and its state —
because both are keyed by `[plugin].id`, which must never change.

If you ever do need to break compatibility with your own stored state, do it in
the plugin: read the old format, convert it, write the new one. Changing the id
instead leaves the user with two plugins and no way to tell them apart.

## Checklist

* `zeta test` passes
* `zeta inspect` shows the right version, permissions and dependencies
* the version has never been published before
* no key, token or personal path in the sources — they ship with the package
* the README says what it does, what it needs, and who wrote it
