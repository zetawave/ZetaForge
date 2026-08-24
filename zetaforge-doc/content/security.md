---
title: Security model
description: What ZetaForge protects against, what it does not, and how to decide whether to trust a plugin.
---

# Security model

This page is written to be read before you install anything, and it does not
soften the parts that are uncomfortable.

## The single most important fact

::: danger A plugin runs with the host's identity
Dynamically loaded code runs **inside the host process, with the host's UID and
the host's permissions**. It can read the host's private files, use its network
access, touch its `ContentResolver`, and call any Android API the host is
allowed to call.

There is no sandbox between a plugin and the app running it.
:::

Installing a `.zeta` is as consequential as installing an APK. That is the
correct mental model, and every other statement on this page follows from it.

## Why it is built this way

The alternative is a separate process with an IPC contract — which is real
isolation, and would mean every capability a plugin has must be designed,
proxied and versioned by hand. What a plugin could do would then be whatever
someone had already thought to expose.

ZetaForge chose the other trade: a plugin is as capable as an app, because it
*is* running as one. What you get is that everything you know about Android
applies unchanged. What you pay is that trust has to come from somewhere other
than the runtime.

The seams for isolation exist and are named — `PluginVerifier`,
`manifest.signature`, and a `:isolated` process — but they are not implemented.

## What the runtime does protect

These are real and worth knowing.

**Code never runs from where the user picked it.** The archive is copied into
app-private storage first, and only that copy is ever loaded. A file that
changes on shared storage after import cannot change what runs.

**Every package is verified on import.** Structure, manifest validity, entry
point existence, Host API range, `minSdk`, per-DEX magic bytes, per-DEX SHA-256
and the SHA-256 of the whole archive. The result is stored, and can be re-run
later against what is on disk from the app's details screen.

**A plugin cannot exceed the host's permissions.** Not by policy — by Android.
Permissions are frozen into an app's manifest at build time, so a permission the
host does not declare can never be granted, whatever the plugin's manifest
claims. The declaration in a plugin's manifest is a *request*, not a grant.

**Permissions are re-evaluated on every run.** Never cached. A permission
revoked between two runs blocks the second one.

**A plugin that throws cannot take the host down.** Every `Throwable` around
`execute` is caught and converted into a structured failure. A
[screen](screens.md) is contained by two separate mechanisms for the same
reason.

**A plugin cannot silently repaint the host's UI.** The bar identifying a plugin
screen is composed in a different view, above the plugin's, out of its reach.

**The source travels with the code.** Every package carries the Kotlin that
produced it, and the app shows it. This is the main practical defence available
to a careful user, and it is why it exists.

## What it does not protect against

Stated plainly, because a security page that lists only the good parts is worse
than none.

| | |
|---|---|
| **A malicious plugin** | It has the host's permissions. It can read the host's files, exfiltrate over the network, and access anything the user has granted. Nothing at run time stops it. |
| **Tampering after publication** | Packages are unsigned. `manifest.signature` is always `null` and the verifier reports it as a warning on every import. A `.zeta` from an untrusted mirror has no cryptographic identity. |
| **A plugin reading another plugin's data** | Separate class loaders isolate *classes*, not files. Everything is under the same UID. |
| **Deceptive UI** | A plugin screen can open a dialog covering the window. It cannot alter the identity bar, but it can draw something that looks official inside its own area. |
| **Resource exhaustion** | No quotas on CPU, memory, disk or network. A plugin in a tight loop is a plugin in a tight loop. |
| **Secrets at rest** | A `secret = true` setting is masked in the form, not encrypted. Anything that reaches the host's private storage reaches it. |

## For someone installing a plugin

**Where did it come from?** This is most of the answer. There is no signature to
check, so provenance is what you have. A package from a repository you trust,
downloaded over HTTPS, is a different proposition from one attached to a forum
post.

**Read the source.** Tap `VIEW CODE`. It ships inside the package, so what you
read is what was compiled — not a copy on a website that may differ.

**Read the permissions, before pressing START.** The card lists them, and
`DETAILS` shows each with the author's reason. A calculator asking for
`allFilesAccess` is telling you something.

**Look at the dependencies.** `zeta inspect` on the file, or `DETAILS` in the
app, lists what was compiled in. A tiny utility carrying a networking stack is
worth a second look.

**Uninstall removes everything.** The plugin's directory, its settings, its
schedule. Nothing survives it.

## For someone writing a plugin

**Ask for the least you can.** Mark what you can as `optional`, use
`minSdk`/`maxSdk` so you never request something the platform ignores, and
remember the permission list is the first thing a careful user reads.

**Do not log secrets.** The log console is visible in the app and over adb. The
temptation is strongest exactly when debugging authentication.

**Do not put credentials in the package.** It is a ZIP; anyone can open it. Use
a `secret` setting the user fills in, and understand its limits.

**Write the reasons for the person reading them.** "Reads your photos in order
to copy them", not "required for MediaStore query". A vague reason reads as
something to hide.

## For someone operating a device

The host declares a broad permission superset — it is the ceiling for what any
plugin may use, so it looks alarming, and it is honest that it does. Grant at
the OS level only what you actually want plugins to be able to reach.

Scheduled plugins ask for battery-optimisation exemption. That is a real
capability: it lets the app run when the system would rather it did not. Grant
it for a backup you rely on; do not grant it by reflex.

## Reporting a vulnerability

Please do not open a public issue. See
[SECURITY.md](https://github.com/zetawave/ZetaForge/blob/main/SECURITY.md) in
the repository for how to report privately and what to expect.

## Next

[Limitations →](limitations.md)
